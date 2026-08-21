package scalive.runtime.kernel

import java.time.Duration
import java.time.Instant

import zio.Promise
import zio.Ref
import zio.Task
import zio.UIO
import zio.ZIO
import zio.http.URL
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.AsyncKey
import scalive.BindingPayload
import scalive.ComponentRef
import scalive.FlashKind
import scalive.LiveAsyncEvent
import scalive.LiveAsyncResult
import scalive.LiveComponent
import scalive.LiveComponentInstance
import scalive.LiveComponentOutputInstance
import scalive.LiveEvent
import scalive.SubscriptionDelivery
import scalive.SubscriptionKey
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*

final private[scalive] case class SessionConfig private (
  mailboxCapacity: Int,
  continuationCapacity: Int,
  navigationDeferredCapacity: Int,
  navigationTimeout: Duration,
  navigationRedirectLimit: Int)

private[scalive] object SessionConfig:
  def make(
    mailboxCapacity: Int,
    continuationCapacity: Int
  ): Either[SessionConfig.Error, SessionConfig] =
    make(
      mailboxCapacity,
      continuationCapacity,
      continuationCapacity,
      Duration.ofSeconds(5),
      20
    )

  def make(
    mailboxCapacity: Int,
    continuationCapacity: Int,
    navigationDeferredCapacity: Int,
    navigationTimeout: Duration,
    navigationRedirectLimit: Int
  ): Either[SessionConfig.Error, SessionConfig] =
    if mailboxCapacity <= 0 then Left(Error.InvalidMailboxCapacity(mailboxCapacity))
    else if continuationCapacity <= 0 then
      Left(Error.InvalidContinuationCapacity(continuationCapacity))
    else if navigationDeferredCapacity <= 0 then
      Left(Error.InvalidNavigationDeferredCapacity(navigationDeferredCapacity))
    else if navigationTimeout.isZero || navigationTimeout.isNegative then
      Left(Error.NonPositiveNavigationTimeout)
    else if navigationRedirectLimit <= 0 then
      Left(Error.InvalidNavigationRedirectLimit(navigationRedirectLimit))
    else
      Right(
        SessionConfig(
          mailboxCapacity,
          continuationCapacity,
          navigationDeferredCapacity,
          navigationTimeout,
          navigationRedirectLimit
        )
      )

  enum Error:
    case InvalidMailboxCapacity(capacity: Int)
    case InvalidContinuationCapacity(capacity: Int)
    case InvalidNavigationDeferredCapacity(capacity: Int)
    case NonPositiveNavigationTimeout
    case InvalidNavigationRedirectLimit(limit: Int)
end SessionConfig

enum NavigationKind:
  case PushPatch
  case ReplacePatch
  case PushNavigate
  case ReplaceNavigate
  case Redirect

  def isPatch: Boolean = this match
    case PushPatch | ReplacePatch => true
    case _                        => false

final private[scalive] case class NavigationRequest(
  destination: URL,
  kind: NavigationKind,
  flash: Map[FlashKind, String] = Map.empty)

final private[scalive] case class NavigationOutput(
  id: NavigationId,
  destination: URL,
  kind: NavigationKind,
  flash: Map[FlashKind, String])

final private[scalive] case class ClientEffect(name: String, payload: Json)

final private[scalive] case class SessionEffects(
  pageTitle: Option[String] = None,
  clientEvents: Vector[ClientEffect] = Vector.empty)

sealed private[scalive] trait ResourceOperation

private[scalive] object ResourceOperation:
  final case class StartAsync[A, Msg](
    owner: OwnerId,
    key: AsyncKey[A],
    task: Task[A],
    toMessage: LiveAsyncResult[A] => Msg)
      extends ResourceOperation

  final case class CancelAsync(
    owner: OwnerId,
    key: AsyncKey[Any],
    reason: Option[String])
      extends ResourceOperation

  final case class StartSubscription[Msg](
    owner: OwnerId,
    key: SubscriptionKey,
    delivery: SubscriptionDelivery,
    stream: ZStream[Any, Nothing, Msg],
    replace: Boolean)
      extends ResourceOperation

  final case class CancelSubscription(owner: OwnerId, key: SubscriptionKey)
      extends ResourceOperation

  final case class Complete(token: ResourceToken) extends ResourceOperation

final private[kernel] case class ManagedAsyncCompletion(
  name: String,
  result: LiveAsyncResult[Any],
  message: Any)

private[kernel] enum ManagedResourceKind:
  case Async(mapResult: LiveAsyncResult[Any] => Any)
  case Subscription(delivery: SubscriptionDelivery)

final private[kernel] case class ManagedResource(
  token: ResourceToken,
  prepared: PreparedResource,
  kind: ManagedResourceKind)

enum SessionCommand[+Msg]:
  case ClientEvent(
    epoch: Epoch,
    binding: BindingId,
    payload: BindingPayload,
    event: Option[LiveEvent] = None) extends SessionCommand[Nothing]
  case ComponentClientEvent(
    epoch: Epoch,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    event: Option[LiveEvent] = None)            extends SessionCommand[Nothing]
  case Message[Msg](epoch: Epoch, message: Msg) extends SessionCommand[Msg]
  case AsyncCompletion[Msg](epoch: Epoch, event: LiveAsyncEvent[Msg]) extends SessionCommand[Msg]
  private[kernel] case ManagedAsync(
    epoch: Epoch,
    token: ResourceToken,
    result: LiveAsyncResult[Any]) extends SessionCommand[Nothing]
  private[kernel] case ManagedSubscription(
    epoch: Epoch,
    token: ResourceToken,
    message: Any) extends SessionCommand[Nothing]
  private[kernel] case ManagedSubscriptionEnded(epoch: Epoch, token: ResourceToken)
      extends SessionCommand[Nothing]
  private[scalive] case ComponentMessage(
    epoch: Epoch,
    component: ComponentInstanceId,
    message: Any) extends SessionCommand[Nothing]
  private[scalive] case ComponentUpdate(epoch: Epoch, component: ComponentInstanceId)
      extends SessionCommand[Nothing]
  private[scalive] case ComponentAsyncCompletion(
    epoch: Epoch,
    component: ComponentInstanceId,
    event: LiveAsyncEvent[Any]) extends SessionCommand[Nothing]
  private[scalive] case Upload(
    epoch: Epoch,
    command: CommandId,
    mutation: UploadMutation[?])                   extends SessionCommand[Nothing]
  case ParamsPatch(epoch: Epoch, destination: URL) extends SessionCommand[Nothing]

  def expectedEpoch: Epoch = this match
    case ClientEvent(epoch, _, _, _)             => epoch
    case ComponentClientEvent(epoch, _, _, _, _) => epoch
    case Message(epoch, _)                       => epoch
    case AsyncCompletion(epoch, _)               => epoch
    case ManagedAsync(epoch, _, _)               => epoch
    case ManagedSubscription(epoch, _, _)        => epoch
    case ManagedSubscriptionEnded(epoch, _)      => epoch
    case ComponentMessage(epoch, _, _)           => epoch
    case ComponentUpdate(epoch, _)               => epoch
    case ComponentAsyncCompletion(epoch, _, _)   => epoch
    case Upload(epoch, _, _)                     => epoch
    case ParamsPatch(epoch, _)                   => epoch
end SessionCommand

final private[scalive] case class TurnDraft[+Msg, Model](
  model: Model,
  continuations: Vector[Msg] = Vector.empty,
  url: Option[URL] = None,
  navigation: Option[NavigationRequest] = None,
  effects: SessionEffects = SessionEffects(),
  componentUpdates: Vector[ComponentUpdateRequest] = Vector.empty,
  resourceOperations: Vector[ResourceOperation] = Vector.empty,
  uploadCommit: UploadRetirementPlan = UploadRetirementPlan.empty,
  uploadRollback: UploadRetirementPlan = UploadRetirementPlan.empty,
  reply: Option[Json] = None)

private[scalive] enum ClientEventInterception[+Msg, Model]:
  case Continue(draft: TurnDraft[Msg, Model])
  case Halt(draft: TurnDraft[Msg, Model])

/** Typed lifecycle operations interpreted by the protocol-neutral session owner.
  *
  * Connected mount contexts and complete lifecycle hooks adapt to this boundary in later
  * milestones.
  */
final private[scalive] case class SessionLogic[Msg, Model](
  bootstrap: Task[TurnDraft[Msg, Model]],
  handle: (Model, Msg) => Task[TurnDraft[Msg, Model]],
  handleParams: (Model, URL) => Task[TurnDraft[Msg, Model]] = (model: Model, url: URL) =>
    ZIO.succeed(TurnDraft(model, url = Some(url))),
  interceptClientEvent: (Model, LiveEvent) => Task[ClientEventInterception[Msg, Model]] = (
    model: Model,
    _: LiveEvent
  ) => ZIO.succeed(ClientEventInterception.Continue(TurnDraft(model))),
  handleEvent: Option[
    (TurnDraft[Msg, Model], Msg, LiveEvent) => Task[TurnDraft[Msg, Model]]
  ] = None,
  handleInfo: Option[(Model, Msg) => Task[TurnDraft[Msg, Model]]] = None,
  handleAsync: Option[(Model, LiveAsyncEvent[Msg]) => Task[TurnDraft[Msg, Model]]] = None,
  handleManagedAsync: Option[
    (Model, LiveAsyncEvent[Msg], Msg) => Task[TurnDraft[Msg, Model]]
  ] = None,
  handleUpload: Option[
    (Model, CommandId, UploadMutation[?]) => Task[TurnDraft[Msg, Model]]
  ] = None,
  prepare: (TurnDraft[Msg, Model], PreparedResourceRegistry) => Task[Unit] = (
    _: TurnDraft[Msg, Model],
    _: PreparedResourceRegistry
  ) => ZIO.unit,
  afterRender: TurnDraft[Msg, Model] => Task[TurnDraft[Msg, Model]] =
    (draft: TurnDraft[Msg, Model]) => ZIO.succeed(draft),
  validateStreams: (Model, Vector[StreamRequirement[Msg]]) => Task[Unit] = (
    _: Model,
    _: Vector[StreamRequirement[Msg]]
  ) => ZIO.unit,
  reconcileUploads: (
    TurnDraft[Msg, Model],
    Set[ComponentInstanceId]
  ) => Task[TurnDraft[Msg, Model]] = (draft: TurnDraft[Msg, Model], _: Set[ComponentInstanceId]) =>
    ZIO.succeed(draft),
  retireUploads: UploadRetirementPlan => UIO[Unit] = (plan: UploadRetirementPlan) =>
    ZIO.foreachDiscard(plan.instructions) {
      case UploadRetirementInstruction.Cleanup(operation) =>
        operation.run.catchAllCause(_ => ZIO.logWarning("upload cleanup failed"))
      case UploadRetirementInstruction.Hosted(_, _) => ZIO.unit
    },
  closeUploads: Model => UIO[Unit] = (_: Model) => ZIO.unit,
  terminateOnNavigate: Boolean = true)

/** Reference-identity wrapper: component definitions are not value keys. */
final private[kernel] class ComponentDefinitionIdentity private (val value: AnyRef):
  override def equals(other: Any): Boolean = other match
    case identity: ComponentDefinitionIdentity => value eq identity.value
    case _                                     => false
  override def hashCode(): Int = System.identityHashCode(value)

private[kernel] object ComponentDefinitionIdentity:
  def apply(value: AnyRef): ComponentDefinitionIdentity = new ComponentDefinitionIdentity(value)

final private[kernel] case class ComponentKey(
  definition: ComponentDefinitionIdentity,
  applicationId: String)

/** The only erased boundary around heterogeneously typed component instances. */
sealed private[scalive] trait MountedComponent[OwnerMsg]:
  type Props
  type Message
  type Model
  type Output

  def id: ComponentInstanceId
  def key: ComponentKey
  def definition: LiveComponent[Props, Message, Model]
  def inputProps: Props
  def props: Props
  def model: Model
  def ref: ComponentRef[Message]
  def render: CommittedRender[Message]
  def program: RenderProgram[(Props, Model, Map[FlashKind, String]), Message]
  def parent: Option[ComponentInstanceId]
  def children: Vector[ComponentInstanceId]
  def environmentState: ComponentEnvironmentState
  def mapOutput(output: Output): Option[OwnerMsg]

final private[kernel] case class MountedComponentValue[OwnerMsg, Props0, Message0, Model0, Output0](
  id: ComponentInstanceId,
  key: ComponentKey,
  definition: LiveComponent[Props0, Message0, Model0],
  inputProps: Props0,
  props: Props0,
  model: Model0,
  ref: ComponentRef[Message0],
  render: CommittedRender[Message0],
  program: RenderProgram[(Props0, Model0, Map[FlashKind, String]), Message0],
  parent: Option[ComponentInstanceId],
  children: Vector[ComponentInstanceId],
  environmentState: ComponentEnvironmentState,
  outputMapper: Option[Output0 => OwnerMsg])
    extends MountedComponent[OwnerMsg]:
  type Props   = Props0
  type Message = Message0
  type Model   = Model0
  type Output  = Output0
  def mapOutput(output: Output0): Option[OwnerMsg] = outputMapper.map(_(output))

final private[scalive] case class ComponentForest[OwnerMsg] private (
  roots: Vector[ComponentInstanceId],
  private val entries: Map[ComponentInstanceId, MountedComponent[OwnerMsg]],
  private val refs: Map[AnyRef, ComponentInstanceId]):
  def get(id: ComponentInstanceId): Option[MountedComponent[OwnerMsg]]  = entries.get(id)
  def byRef(ref: ComponentRef[Any]): Option[MountedComponent[OwnerMsg]] =
    entries.get(refs.getOrElse(ref.asInstanceOf[AnyRef], ComponentInstanceId(0L)))
  def values: Vector[MountedComponent[OwnerMsg]] =
    def descendants(ids: Vector[ComponentInstanceId]): Vector[MountedComponent[OwnerMsg]] =
      ids.flatMap(id =>
        entries.get(id).toVector.flatMap(value => value +: descendants(value.children))
      )
    descendants(roots)
  private[kernel] def byKey: Map[ComponentKey, MountedComponent[OwnerMsg]] =
    entries.valuesIterator.map(component => component.key -> component).toMap

private[scalive] object ComponentForest:
  val empty: ComponentForest[Nothing] = ComponentForest(Vector.empty, Map.empty, Map.empty)
  private[kernel] def apply[OwnerMsg](
    roots: Vector[ComponentInstanceId],
    values: Vector[MountedComponent[OwnerMsg]]
  ): ComponentForest[OwnerMsg] =
    ComponentForest(
      roots,
      values.iterator.map(value => value.id -> value).toMap,
      values.iterator.map(value => value.ref.asInstanceOf[AnyRef] -> value.id).toMap
    )

/** Opaque candidate-owned connection context and dynamic-hook state. */
opaque type ComponentEnvironmentState = AnyRef

private[scalive] object ComponentEnvironmentState:
  def apply(value: AnyRef): ComponentEnvironmentState            = value
  extension (state: ComponentEnvironmentState) def value: AnyRef = state

final private[scalive] case class ComponentCallbackResult[A, RootMsg, RootModel](
  model: A,
  draft: TurnDraft[RootMsg, RootModel],
  state: ComponentEnvironmentState)

final private[scalive] case class ComponentAfterRenderResult[RootMsg, RootModel](
  draft: TurnDraft[RootMsg, RootModel],
  state: ComponentEnvironmentState)

/** Typed, turn-local send-update journal entry. */
sealed trait ComponentUpdateRequest:
  type Props
  type Message
  type Model
  def definition: LiveComponent[Props, Message, Model]
  def applicationId: String
  def props: Props

private[scalive] object ComponentUpdateRequest:
  final private case class Value[P, M, A](
    definition: LiveComponent[P, M, A],
    applicationId: String,
    props: P)
      extends ComponentUpdateRequest:
    type Props   = P
    type Message = M
    type Model   = A

  def apply[P, M, A](
    definition: LiveComponent[P, M, A],
    applicationId: String,
    props: P
  ): ComponentUpdateRequest = Value(definition, applicationId, props)

  def apply[P, M, A](
    instance: LiveComponentInstance[P, M, A],
    props: P
  ): ComponentUpdateRequest = Value(instance.component, instance.id, props)

  def apply[P, M, A, O](
    instance: LiveComponentOutputInstance[P, M, A, O],
    props: P
  ): ComponentUpdateRequest = Value(instance.component, instance.id, props)

/** Connection-owned lifecycle contexts are adapted at this protocol-neutral boundary. */
trait ComponentEnvironment[RootMsg, RootModel]:
  def flash(draft: TurnDraft[RootMsg, RootModel]): Map[FlashKind, String] =
    Option(draft.model).fold(Map.empty[FlashKind, String])(_ => Map.empty)
  def mount[P, M, A](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    draft: TurnDraft[RootMsg, RootModel]
  ): Task[ComponentCallbackResult[A, RootMsg, RootModel]]
  def update[P, M, A](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    state: ComponentEnvironmentState,
    draft: TurnDraft[RootMsg, RootModel]
  ): Task[ComponentCallbackResult[A, RootMsg, RootModel]]
  def message[P, M, A, O](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    value: M,
    emit: O => Task[Unit],
    state: ComponentEnvironmentState,
    draft: TurnDraft[RootMsg, RootModel]
  ): Task[ComponentCallbackResult[A, RootMsg, RootModel]]
  def async[P, M, A, O](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    event: LiveAsyncEvent[M],
    emit: O => Task[Unit],
    state: ComponentEnvironmentState,
    draft: TurnDraft[RootMsg, RootModel]
  ): Task[ComponentCallbackResult[A, RootMsg, RootModel]]
  def managedAsync[P, M, A, O](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    event: LiveAsyncEvent[M],
    _message: M,
    emit: O => Task[Unit],
    state: ComponentEnvironmentState,
    draft: TurnDraft[RootMsg, RootModel]
  ): Task[ComponentCallbackResult[A, RootMsg, RootModel]] =
    async(id, component, props, model, event, emit, state, draft)
  def browserEvent[P, M, A, O](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    command: SessionCommand.ComponentClientEvent,
    message: Task[M],
    emit: O => Task[Unit],
    state: ComponentEnvironmentState,
    draft: TurnDraft[RootMsg, RootModel]
  ): Task[ComponentCallbackResult[A, RootMsg, RootModel]]
  def afterRender[P, M, A](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    state: ComponentEnvironmentState,
    draft: TurnDraft[RootMsg, RootModel]
  ): Task[ComponentAfterRenderResult[RootMsg, RootModel]]
  def validateStreams[M](
    _id: ComponentInstanceId,
    _state: ComponentEnvironmentState,
    _requirements: Vector[StreamRequirement[M]]
  ): Task[Unit] = ZIO.unit
  def discard(id: ComponentInstanceId, state: ComponentEnvironmentState): UIO[Unit]
  def close(id: ComponentInstanceId, state: ComponentEnvironmentState): UIO[Unit]
end ComponentEnvironment

private[scalive] object ComponentEnvironment:
  def unavailable[RootMsg, RootModel]: ComponentEnvironment[RootMsg, RootModel] =
    new ComponentEnvironment:
      private def missing[A]: Task[A] =
        ZIO.fail(IllegalStateException("component lifecycle context environment was not installed"))
      def mount[P, M, A](
        id: ComponentInstanceId,
        component: LiveComponent[P, M, A],
        props: P,
        draft: TurnDraft[RootMsg, RootModel]
      ) =
        missing[ComponentCallbackResult[A, RootMsg, RootModel]]
      def update[P, M, A](
        id: ComponentInstanceId,
        component: LiveComponent[P, M, A],
        props: P,
        model: A,
        state: ComponentEnvironmentState,
        draft: TurnDraft[RootMsg, RootModel]
      ) = missing[ComponentCallbackResult[A, RootMsg, RootModel]]
      def message[P, M, A, O](
        id: ComponentInstanceId,
        component: LiveComponent[P, M, A],
        props: P,
        model: A,
        value: M,
        emit: O => Task[Unit],
        state: ComponentEnvironmentState,
        draft: TurnDraft[RootMsg, RootModel]
      ) = missing[ComponentCallbackResult[A, RootMsg, RootModel]]
      def async[P, M, A, O](
        id: ComponentInstanceId,
        component: LiveComponent[P, M, A],
        props: P,
        model: A,
        event: LiveAsyncEvent[M],
        emit: O => Task[Unit],
        state: ComponentEnvironmentState,
        draft: TurnDraft[RootMsg, RootModel]
      ) = missing[ComponentCallbackResult[A, RootMsg, RootModel]]
      def browserEvent[P, M, A, O](
        id: ComponentInstanceId,
        component: LiveComponent[P, M, A],
        props: P,
        model: A,
        command: SessionCommand.ComponentClientEvent,
        message: Task[M],
        emit: O => Task[Unit],
        state: ComponentEnvironmentState,
        draft: TurnDraft[RootMsg, RootModel]
      ) = missing[ComponentCallbackResult[A, RootMsg, RootModel]]
      def afterRender[P, M, A](
        id: ComponentInstanceId,
        component: LiveComponent[P, M, A],
        props: P,
        model: A,
        state: ComponentEnvironmentState,
        draft: TurnDraft[RootMsg, RootModel]
      ) = missing[ComponentAfterRenderResult[RootMsg, RootModel]]
      def discard(id: ComponentInstanceId, state: ComponentEnvironmentState) = ZIO.unit
      def close(id: ComponentInstanceId, state: ComponentEnvironmentState)   = ZIO.unit
end ComponentEnvironment

final private[scalive] case class Committed[Msg, Model](
  model: Model,
  url: URL,
  render: CommittedRender[Msg],
  components: ComponentForest[Msg],
  resources: PreparedResources,
  managedResources: ResourceIndex[ManagedResource],
  auxiliaryScope: CandidateScope,
  revision: TurnRevision)

final private[scalive] case class SessionOutput(
  command: Option[CommandId],
  delta: RenderDelta,
  navigation: Option[NavigationOutput] = None,
  effects: SessionEffects = SessionEffects(),
  reply: Option[Json] = None)

final private[scalive] case class DeferredSessionCommand[Msg, Model](
  command: CommandId,
  input: SessionCommand[Msg],
  response: Promise[SessionRejection, TurnResult])

final private[scalive] case class PendingNavigation[Msg, Model](
  id: NavigationId,
  source: URL,
  destination: URL,
  kind: NavigationKind,
  committed: Committed[Msg, Model],
  stagedModel: Model,
  flash: Map[FlashKind, String],
  deadline: Instant,
  redirectCount: Int,
  deferred: Vector[DeferredSessionCommand[Msg, Model]],
  componentCandidate: Option[TurnCandidate[Msg, Model]] = None)

enum SessionState[Msg, Model]:
  case Bootstrapping(epoch: Epoch)
  case Active(epoch: Epoch, committed: Committed[Msg, Model])
  case Navigating(epoch: Epoch, pending: PendingNavigation[Msg, Model])
  case Redirected(epoch: Epoch, navigation: NavigationOutput)
  case Closing(epoch: Epoch, committed: Option[Committed[Msg, Model]])
  case Crashed(epoch: Epoch, failure: SessionFailure)
  case Closed(epoch: Epoch)

final private[scalive] case class TurnCandidate[Msg, Model](
  id: TurnId,
  revision: TurnRevision,
  draft: TurnDraft[Msg, Model],
  render: RenderCandidate[Msg],
  components: ComponentForestCandidate[Msg],
  outputs: Vector[ComponentOutput[Msg]],
  topology: PreparedNestedTopology,
  resources: PreparedResources,
  managedResources: ResourceIndex[ManagedResource],
  managedActivations: Vector[ManagedResource],
  managedRetirements: Vector[ManagedResource],
  managedContinuations: Vector[ManagedAsyncContinuation],
  delta: RenderDelta,
  reservation: OutboundReservation[SessionOutput],
  auxiliaryScope: CandidateScope,
  uploadRollback: UploadRetirementPlan,
  uploadRollbackClaim: Ref[Boolean])

final private[kernel] case class ManagedAsyncContinuation(
  owner: OwnerId,
  completion: ManagedAsyncCompletion)

enum SessionStage:
  case BootstrapHandler
  case Handler
  case ResourcePreparation
  case TopologyPreparation
  case Render
  case OutputReservation
  case AfterRender
  case Validation
  case Identity
  case Retirement
  case ComponentMount
  case ComponentUpdate
  case ComponentMessage
  case ComponentAsync
  case ComponentAfterRender

sealed abstract class SessionFailure(message: String) extends Exception(message)

object SessionFailure:
  final case class StageFailed(stage: SessionStage, details: String)
      extends SessionFailure(s"session stage $stage failed: $details")

  final case class CommitDefect(details: String)
      extends SessionFailure(s"session commit tail defected: $details")

  final case class Interrupted() extends SessionFailure("session initialization was interrupted")
  final case class NavigationTimedOut(id: NavigationId, destination: URL)
      extends SessionFailure(
        s"navigation ${id.value} to ${destination.encode} timed out"
      )
  final case class NavigationRedirectOverflow(limit: Int)
      extends SessionFailure(s"navigation redirect limit $limit exceeded")
  final case class NavigationDeferredOverflow(capacity: Int)
      extends SessionFailure(s"navigation deferred-command capacity $capacity exceeded")

enum SessionRejection:
  case MailboxSaturated(capacity: Int)
  case InvalidEpoch(expected: Epoch, actual: Epoch)
  case UnknownBinding(binding: BindingId)
  case UnknownComponent(component: ComponentInstanceId)
  case UnknownComponentTarget
  case StaleComponent(component: ComponentInstanceId)
  case StaleResource(token: ResourceToken)
  case UploadUnavailable
  case AmbiguousComponent(applicationId: Option[String])
  case BindingFailed(binding: BindingId, error: Throwable)
  case UnexpectedPatch
  case MismatchedPatch(expected: URL, actual: URL)
  case IdentityUnavailable(error: RuntimeIdentityError)
  case SessionFailed(failure: SessionFailure)
  case Terminal(state: String)

final private[scalive] case class TurnResult(
  command: CommandId,
  turn: TurnId,
  revision: TurnRevision,
  delta: RenderDelta)

final private[kernel] case class ComponentForestCandidate[OwnerMsg](
  roots: Vector[ComponentInstanceId],
  components: Vector[StagedComponent[OwnerMsg]],
  resolutions: Vector[ComponentResolution])

private[kernel] enum ComponentOutput[+RootMsg]:
  case Root(message: RootMsg, componentType: String, componentId: String)
  case Parent(
    component: ComponentInstanceId,
    message: Any,
    componentType: String,
    componentId: String)

private[kernel] trait StagedComponent[OwnerMsg]:
  def id: ComponentInstanceId
  def key: ComponentKey
  def parent: Option[ComponentInstanceId]
  def children: Vector[ComponentInstanceId]
  def previous: Option[MountedComponent[OwnerMsg]]
  def candidateScope: CandidateScope
  def environmentState: ComponentEnvironmentState
  def appliedUpdate: Option[ComponentUpdateRequest]
  def matches(requirement: ComponentRequirement[?]): Boolean
  def resolutionFor(requirement: ComponentRequirement[?]): ComponentResolution
  protected def resolutionForCandidate(
    requirement: ComponentRequirement[?],
    candidate: RenderCandidate[Any]
  ): ComponentResolution
  def renderCandidate: RenderCandidate[Any]
  def withRenderCandidate(candidate: RenderCandidate[Any]): StagedComponent[OwnerMsg] =
    val original = this
    new StagedComponent[OwnerMsg]:
      def id                                                  = original.id
      def key                                                 = original.key
      def parent                                              = original.parent
      def children                                            = original.children
      def previous                                            = original.previous
      def candidateScope                                      = candidate.stagedScope
      def environmentState                                    = original.environmentState
      def appliedUpdate                                       = original.appliedUpdate
      def matches(requirement: ComponentRequirement[?])       = original.matches(requirement)
      def resolutionFor(requirement: ComponentRequirement[?]) =
        original.resolutionForCandidate(requirement, candidate)
      protected def resolutionForCandidate(
        requirement: ComponentRequirement[?],
        next: RenderCandidate[Any]
      ) = original.resolutionForCandidate(requirement, next)
      def renderCandidate                                      = candidate
      protected def commitValueFor(next: RenderCandidate[Any]) =
        original.commitValueFor(next)
      def commitValue    = original.commitValueFor(candidate)
      def discard        = original.discard
      def abortCommitted = original.abortCommitted
  protected def commitValueFor(candidate: RenderCandidate[Any]): MountedComponent[OwnerMsg]
  def commitValue: MountedComponent[OwnerMsg]
  def discard: UIO[Unit]
  def abortCommitted: UIO[Unit]
end StagedComponent
