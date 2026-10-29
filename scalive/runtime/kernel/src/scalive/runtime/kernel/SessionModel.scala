package scalive.runtime.kernel

import java.time.Duration
import java.time.Instant

import zio.Cause
import zio.IO
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
import scalive.FlashKey
import scalive.FlashValue
import scalive.LifecycleTurnKind
import scalive.LiveAsyncEvent
import scalive.LiveAsyncResult
import scalive.LiveComponent
import scalive.LiveComponentInstance
import scalive.LiveComponentOutputInstance
import scalive.LiveConnectedTurnFailure
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
  flash: Map[FlashKey, FlashValue] = Map.empty)

final private[scalive] case class NavigationOutput(
  id: NavigationId,
  destination: URL,
  kind: NavigationKind,
  flash: Map[FlashKey, FlashValue])

final private[scalive] case class ClientEffect(name: String, payload: Json)

final private[scalive] case class SessionEffects(
  pageTitle: Option[String] = None,
  clientEvents: Vector[ClientEffect] = Vector.empty)

sealed private[scalive] trait ResourceOperation

private[scalive] object ResourceOperation:
  final case class StartAsync[E, A, Msg](
    owner: OwnerId,
    key: AsyncKey[A],
    task: IO[E, A],
    toMessage: LiveAsyncResult[E, A] => Msg)
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
  result: LiveAsyncResult[Any, Any],
  message: Any)

sealed private[kernel] trait ManagedResource:
  def token: ResourceToken

private[kernel] object ManagedResource:
  sealed trait Running extends ManagedResource:
    def prepared: PreparedResource

  final case class Async(
    token: ResourceToken,
    prepared: PreparedResource,
    mapResult: LiveAsyncResult[Any, Any] => Any)
      extends Running

  final case class SubscriptionDefinition(
    delivery: SubscriptionDelivery,
    stream: ZStream[Any, Nothing, Any])

  sealed trait Subscription extends ManagedResource:
    def definition: SubscriptionDefinition
    def ended: Ref[Boolean]

  final case class RunningSubscription(
    token: ResourceToken,
    prepared: PreparedResource,
    definition: SubscriptionDefinition,
    ended: Ref[Boolean])
      extends Running
      with Subscription

  final case class SuspendedSubscription(
    token: ResourceToken,
    definition: SubscriptionDefinition,
    ended: Ref[Boolean])
      extends Subscription
end ManagedResource

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
  private[kernel] case ManagedAsync(
    epoch: Epoch,
    token: ResourceToken,
    result: LiveAsyncResult[Any, Any]) extends SessionCommand[Nothing]
  private[kernel] case ManagedSubscription(
    epoch: Epoch,
    token: ResourceToken,
    message: Any) extends SessionCommand[Nothing]
  private[kernel] case ManagedSubscriptionEnded(epoch: Epoch, token: ResourceToken)
      extends SessionCommand[Nothing]

  /** Test seam: delivers `message` to one component as if one of its bindings had sent it.
    * Production component messages arrive as [[ComponentClientEvent]]; this command lets tests
    * drive a component turn without rendering a binding, and it is observed the same way.
    */
  private[scalive] case ComponentMessage(
    epoch: Epoch,
    component: ComponentInstanceId,
    message: Any) extends SessionCommand[Nothing]
  private[scalive] case Upload(
    epoch: Epoch,
    command: CommandId,
    mutation: UploadMutation[?],
    publish: Boolean = true)                       extends SessionCommand[Nothing]
  case ParamsPatch(epoch: Epoch, destination: URL) extends SessionCommand[Nothing]

  def expectedEpoch: Epoch = this match
    case ClientEvent(epoch, _, _, _)             => epoch
    case ComponentClientEvent(epoch, _, _, _, _) => epoch
    case Message(epoch, _)                       => epoch
    case ManagedAsync(epoch, _, _)               => epoch
    case ManagedSubscription(epoch, _, _)        => epoch
    case ManagedSubscriptionEnded(epoch, _)      => epoch
    case ComponentMessage(epoch, _, _)           => epoch
    case Upload(epoch, _, _, _)                  => epoch
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
  uploadCompletion: UIO[Unit] = ZIO.unit,
  reply: Option[Json] = None)

private[scalive] enum ClientEventInterception[+Msg, Model]:
  case Continue(draft: TurnDraft[Msg, Model])
  case Halt(draft: TurnDraft[Msg, Model])
  // The event is a declared browser event of the root, already decoded into `message`.
  case Dispatch(draft: TurnDraft[Msg, Model], message: Msg)

/** Typed lifecycle operations interpreted by the protocol-neutral session owner.
  *
  * Connected mount contexts and complete lifecycle hooks adapt to this boundary in later
  * milestones.
  */
final private[scalive] case class SessionLogic[Msg, Model](
  bootstrap: Task[TurnDraft[Msg, Model]],
  handle: (Model, Msg) => Task[TurnDraft[Msg, Model]],
  guardConnectedTurn: Task[Either[LiveConnectedTurnFailure, Unit]] = ZIO.succeed(Right(())),
  handleParams: (Model, URL) => Task[TurnDraft[Msg, Model]] = (model: Model, url: URL) =>
    ZIO.succeed(TurnDraft(model, url = Some(url))),
  interceptClientEvent: (
    Model,
    LiveEvent,
    Option[ComponentInstanceId]
  ) => Task[ClientEventInterception[Msg, Model]] = (
    model: Model,
    _: LiveEvent,
    _: Option[ComponentInstanceId]
  ) => ZIO.succeed(ClientEventInterception.Continue(TurnDraft(model))),
  handleEvent: Option[
    (TurnDraft[Msg, Model], Msg, LiveEvent) => Task[TurnDraft[Msg, Model]]
  ] = None,
  handleInfo: Option[(Model, Msg) => Task[TurnDraft[Msg, Model]]] = None,
  handleAsync: Option[(Model, LiveAsyncEvent[Msg]) => Task[TurnDraft[Msg, Model]]] = None,
  handleUpload: Option[
    (Model, CommandId, UploadMutation[?]) => Task[TurnDraft[Msg, Model]]
  ] = None,
  guardUpload: (Model, UploadMutation[?]) => Boolean = (_: Model, mutation: UploadMutation[?]) =>
    mutation.isGuarded,
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
  // Receives whether the page renders each component after the turn.
  reconcileUploads: (
    TurnDraft[Msg, Model],
    ComponentInstanceId => Boolean
  ) => Task[TurnDraft[Msg, Model]] = (
    draft: TurnDraft[Msg, Model],
    _: ComponentInstanceId => Boolean
  ) => ZIO.succeed(draft),
  retireUploads: UploadRetirementPlan => UIO[Unit] = (plan: UploadRetirementPlan) =>
    ZIO.foreachDiscard(plan.instructions) {
      case UploadRetirementInstruction.Cleanup(operation) =>
        operation.run.catchAllCause(ZIO.logWarningCause("Upload cleanup failed", _))
      case UploadRetirementInstruction.Hosted(_, _) => ZIO.unit
    },
  closeUploads: Model => UIO[Unit] = (_: Model) => ZIO.unit,
  closeLifecycle: UIO[Unit] = ZIO.unit,
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
  def program: RenderProgram[(Props, Model, RenderState), Message]
  def parent: Option[ComponentInstanceId]
  def children: Vector[ComponentInstanceId]
  def environmentState: ComponentEnvironmentState
  def mapOutput(output: Output): Option[OwnerMsg]

  /** The requirement that last declared this component: one of those of its parent's render, or of
    * the root's.
    */
  def declaration: ComponentRequirement[?]

  /** Whether this component renders its committed render again when its runtime-owned state is
    * `state`.
    */
  def rendersAgain(state: RenderState): Boolean

  /** Whether `requirement` declares this component with the props it was last declared with. */
  def declaredAs(requirement: ComponentRequirement[?]): Boolean =
    (requirement.definition.asInstanceOf[AnyRef] eq definition.asInstanceOf[AnyRef]) &&
      requirement.applicationId == key.applicationId && requirement.props == inputProps

  /** This component as `requirement` declares it again in the render of `parent`: sending its
    * output where `requirement` sends it.
    */
  def declaredBy(
    requirement: ComponentRequirement[?],
    parent: Option[ComponentInstanceId]
  ): MountedComponent[OwnerMsg]

  /** Resolves the declaration of this component to its committed render. */
  def resolution: ComponentResolution =
    val requirement = declaration
    requirement.resolve(
      ref.asInstanceOf[ComponentRef[requirement.Message]],
      ref.asInstanceOf[AnyRef],
      render.asInstanceOf[CommittedRender[requirement.Message]]
    )

  /** This component, also resolving for a while the bindings `previous`, the same instance as last
    * committed, had and this render dropped.
    */
  def retiringBindingsOf(
    previous: MountedComponent[OwnerMsg],
    now: Instant
  ): MountedComponent[OwnerMsg]
end MountedComponent

final private[kernel] case class MountedComponentValue[OwnerMsg, Props0, Message0, Model0, Output0](
  id: ComponentInstanceId,
  key: ComponentKey,
  definition: LiveComponent[Props0, Message0, Model0],
  inputProps: Props0,
  props: Props0,
  model: Model0,
  ref: ComponentRef[Message0],
  render: CommittedRender[Message0],
  program: RenderProgram[(Props0, Model0, RenderState), Message0],
  parent: Option[ComponentInstanceId],
  children: Vector[ComponentInstanceId],
  environmentState: ComponentEnvironmentState,
  outputMapper: Option[Output0 => OwnerMsg],
  declaration: ComponentRequirement[?])
    extends MountedComponent[OwnerMsg]:
  type Props   = Props0
  type Message = Message0
  type Model   = Model0
  type Output  = Output0
  def mapOutput(output: Output0): Option[OwnerMsg] = outputMapper.map(_(output))

  def rendersAgain(state: RenderState): Boolean =
    program.rendersAgain((props, model, state), render)

  def declaredBy(
    requirement: ComponentRequirement[?],
    parent: Option[ComponentInstanceId]
  ): MountedComponent[OwnerMsg] =
    if (requirement eq declaration) && parent == this.parent then this
    else
      copy(
        parent = parent,
        outputMapper = requirement.outputMapper.map(_.asInstanceOf[Output0 => OwnerMsg]),
        declaration = requirement
      )

  def retiringBindingsOf(
    previous: MountedComponent[OwnerMsg],
    now: Instant
  ): MountedComponent[OwnerMsg] =
    // One instance keeps its definition, so its renders share the message type.
    copy(render = render.retiring(previous.render.asInstanceOf[CommittedRender[Message0]], now))
end MountedComponentValue

/** The components of a page, by id, including those it no longer renders but the browser has not
  * destroyed yet. A commit replaces the components its turn rendered or kept and adds the new ones;
  * only a destruction removes one, so a turn costs what it changed, not what the page holds.
  */
final private[scalive] case class ComponentForest[OwnerMsg] private (
  roots: Vector[ComponentInstanceId],
  private val entries: Map[ComponentInstanceId, MountedComponent[OwnerMsg]],
  private val refs: Map[AnyRef, ComponentInstanceId],
  private val keys: Map[ComponentKey, ComponentInstanceId],
  // The components whose render read something signals do not track, so render on every turn.
  private[kernel] val untracked: Set[ComponentInstanceId]):
  def get(id: ComponentInstanceId): Option[MountedComponent[OwnerMsg]] = entries.get(id)
  def values: Vector[MountedComponent[OwnerMsg]]                       =
    def descendants(ids: Vector[ComponentInstanceId]): Vector[MountedComponent[OwnerMsg]] =
      ids.flatMap(id =>
        entries.get(id).toVector.flatMap(value => value +: descendants(value.children))
      )
    descendants(roots)
  private[kernel] def contains(id: ComponentInstanceId): Boolean         = entries.contains(id)
  private[kernel] lazy val allValues: Vector[MountedComponent[OwnerMsg]] =
    entries.values.toVector.sortBy(_.id.value)
  private[kernel] def byKey(key: ComponentKey): Option[MountedComponent[OwnerMsg]] =
    keys.get(key).flatMap(entries.get)

  /** Whether the page renders component `id`: its parent does, and declares it. */
  private[kernel] def isLive(id: ComponentInstanceId): Boolean =
    ComponentForest.isLive(id, roots, entries.get(_).map(_.parent), entries.get(_).map(_.children))

  private[kernel] def isDormant(id: ComponentInstanceId): Boolean =
    entries.contains(id) && !isLive(id)

  /** This forest after a turn that renders `roots` and commits `components`. */
  private[kernel] def committing(
    roots: Vector[ComponentInstanceId],
    components: Iterable[MountedComponent[OwnerMsg]]
  ): ComponentForest[OwnerMsg] =
    var nextEntries   = entries
    var nextRefs      = refs
    var nextKeys      = keys
    var nextUntracked = untracked
    components.foreach { component =>
      entries.get(component.id) match
        case Some(current) if current eq component => ()
        case Some(_)                               =>
          nextEntries = nextEntries.updated(component.id, component)
        case None =>
          nextEntries = nextEntries.updated(component.id, component)
          nextRefs = nextRefs.updated(component.ref.asInstanceOf[AnyRef], component.id)
          nextKeys = nextKeys.updated(component.key, component.id)
      nextUntracked =
        if component.render.readsUntracked then nextUntracked + component.id
        else nextUntracked - component.id
    }
    ComponentForest(roots, nextEntries, nextRefs, nextKeys, nextUntracked)

  private[kernel] def updated(value: MountedComponent[OwnerMsg]): ComponentForest[OwnerMsg] =
    committing(roots, Vector(value))
  private[kernel] def removeDormant(
    tokens: Set[AnyRef]
  ): (ComponentForest[OwnerMsg], Vector[MountedComponent[OwnerMsg]]) =
    val activeIds  = values.iterator.map(_.id).toSet
    var removedIds = tokens.flatMap(refs.get).filterNot(activeIds.contains)
    var changed    = true
    while changed do
      val next = removedIds ++ entries.valuesIterator.collect {
        case component if component.parent.exists(removedIds.contains) => component.id
      }
      changed = next.size != removedIds.size
      removedIds = next
    val removed  = removedIds.toVector.flatMap(entries.get).sortBy(_.id.value)
    val retained = allValues.filterNot(component => removedIds.contains(component.id))
    ComponentForest(roots, retained) -> removed
end ComponentForest

private[scalive] object ComponentForest:
  val empty: ComponentForest[Nothing] =
    ComponentForest(Vector.empty, Map.empty, Map.empty, Map.empty, Set.empty)

  /** Whether `id` is reachable from `roots`, given the parent and children of each component. */
  @scala.annotation.tailrec
  private[kernel] def isLive(
    id: ComponentInstanceId,
    roots: Vector[ComponentInstanceId],
    parentOf: ComponentInstanceId => Option[Option[ComponentInstanceId]],
    childrenOf: ComponentInstanceId => Option[Vector[ComponentInstanceId]]
  ): Boolean =
    parentOf(id) match
      case None               => false
      case Some(None)         => roots.contains(id)
      case Some(Some(parent)) =>
        childrenOf(parent).exists(_.contains(id)) && isLive(parent, roots, parentOf, childrenOf)
  private[kernel] def apply[OwnerMsg](
    roots: Vector[ComponentInstanceId],
    values: Vector[MountedComponent[OwnerMsg]]
  ): ComponentForest[OwnerMsg] =
    ComponentForest.empty.asInstanceOf[ComponentForest[OwnerMsg]].committing(roots, values)

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
  /** The runtime-owned state that one render of component `id` reads: flash, uploads, and streams.
    */
  def renderState(
    id: ComponentInstanceId,
    draft: TurnDraft[RootMsg, RootModel],
    state: ComponentEnvironmentState
  ): RenderState =
    Option(draft.model).fold(RenderState.empty)(_ => RenderState.empty)

  /** Whether each component whose environment state is unchanged reads the same state from
    * `renderState` under root model `next` as under `previous`. A turn renders such a component
    * only when it has another reason to; override it with `renderState`.
    */
  def sameRenderState(previous: RootModel, next: RootModel): Boolean = true

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
  // The tree the browser last received. A commit that publishes nothing keeps it, so the next
  // published turn sends that commit's changes too.
  published: EvaluatedTree,
  components: ComponentForest[Msg],
  // The root model whose runtime-owned render state every live component last rendered or was
  // kept with, if they all did with the same.
  componentRenderBasis: Option[Model],
  resources: PreparedResources,
  managedResources: ResourceIndex[ManagedResource],
  auxiliaryScope: CandidateScope,
  revision: TurnRevision)

enum SessionTermination:
  case Disconnect(reason: Option[String])

final private[scalive] case class SessionOutput(
  command: Option[CommandId],
  delta: RenderDelta,
  navigation: Option[NavigationOutput] = None,
  effects: SessionEffects = SessionEffects(),
  reply: Option[Json] = None,
  termination: Option[SessionTermination] = None)

final private[scalive] case class DeferredSessionCommand[Msg, Model](
  command: CommandId,
  input: SessionCommand[Msg],
  response: Promise[SessionRejection, TurnResult])

/** A patch, or a navigation that keeps the lifecycle, waiting for the browser's acknowledgement.
  *
  * `candidate` is the whole turn that requested it, model and resource operations included. It
  * commits on acknowledgement, before `handleParams` runs for `destination`.
  */
final private[scalive] case class PendingNavigation[Msg, Model](
  id: NavigationId,
  source: URL,
  destination: URL,
  kind: NavigationKind,
  committed: Committed[Msg, Model],
  flash: Map[FlashKey, FlashValue],
  deadline: Instant,
  redirectCount: Int,
  deferred: Vector[DeferredSessionCommand[Msg, Model]],
  candidate: TurnCandidate[Msg, Model],
  componentDestructions: Vector[Object] = Vector.empty)

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
  startedAtNanos: Long,
  observedKind: LifecycleTurnKind,
  observedCommand: Option[CommandId],
  draft: TurnDraft[Msg, Model],
  render: RenderCandidate[Msg],
  components: ComponentForestCandidate[Msg],
  componentRenderBasis: Option[Model],
  outputs: Vector[ComponentOutput[Msg]],
  topology: PreparedNestedTopology,
  resources: PreparedResources,
  managedResources: ResourceIndex[ManagedResource],
  managedActivations: Vector[ManagedResource.Running],
  managedRetirements: Vector[ManagedResource.Running],
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
  case Mount
  case ConnectedTurnGuard
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

sealed abstract class SessionFailure(message: String, cause: Throwable = null)
    extends Exception(message, cause)

object SessionFailure:
  /** A stage failed with `cause`, which keeps application failures and their traces for logging. */
  final case class StageFailed(stage: SessionStage, cause: Cause[Any])
      extends SessionFailure(
        s"session stage $stage failed: ${summary(cause)}",
        firstThrowable(cause)
      )

  object StageFailed:
    /** A stage failed because Scalive rejected its result, as described by `details`. */
    def apply(stage: SessionStage, details: String): StageFailed =
      StageFailed(stage, Cause.fail(details))

  /** A commit tail failed with `cause`, which also holds any failures of its rollback. */
  final case class CommitDefect(cause: Cause[Any])
      extends SessionFailure(
        s"session commit tail defected: ${summary(cause)}",
        firstThrowable(cause)
      )

  final case class Interrupted() extends SessionFailure("session initialization was interrupted")
  final case class NavigationTimedOut(id: NavigationId, destination: URL)
      extends SessionFailure(
        s"navigation ${id.value} to ${destination.encode} timed out"
      )
  final case class NavigationRedirectOverflow(limit: Int)
      extends SessionFailure(s"navigation redirect limit $limit exceeded")
  final case class NavigationDeferredOverflow(capacity: Int)
      extends SessionFailure(s"navigation deferred-command capacity $capacity exceeded")

  private def summary(cause: Cause[Any]): String =
    (cause.failures ++ cause.defects).headOption.fold("interrupted")(_.toString)

  private def firstThrowable(cause: Cause[Any]): Throwable =
    cause.failures
      .collectFirst { case error: Throwable => error }
      .orElse(cause.defects.headOption).orNull
end SessionFailure

enum SessionRejection:
  case MailboxSaturated(capacity: Int)
  case InvalidEpoch(expected: Epoch, actual: Epoch)
  case UnknownBinding(binding: BindingId)
  case UnknownComponent(component: ComponentInstanceId)
  case UnknownComponentTarget
  case StaleComponent(component: ComponentInstanceId)
  case StaleResource(token: ResourceToken)
  case UploadUnavailable
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

/** The components of a page after a turn: `previous`, the committed forest, with the components the
  * turn staged, `components`, in place of theirs. The turn leaves the others as committed.
  */
final private[kernel] case class ComponentForestCandidate[OwnerMsg](
  roots: Vector[ComponentInstanceId],
  components: Vector[StagedComponent[OwnerMsg]],
  dormantAction: Option[DormantAction[OwnerMsg]],
  previous: ComponentForest[OwnerMsg]):
  private lazy val byId = components.iterator.map(component => component.id -> component).toMap

  def staged(id: ComponentInstanceId): Option[StagedComponent[OwnerMsg]] = byId.get(id)

  /** Whether the page renders component `id` after the turn. */
  def isLive(id: ComponentInstanceId): Boolean =
    ComponentForest.isLive(
      id,
      roots,
      id => byId.get(id).map(_.parent).orElse(previous.get(id).map(_.parent)),
      id => byId.get(id).map(_.children).orElse(previous.get(id).map(_.children))
    )

/** A component the page no longer renders, but the browser has not destroyed yet, after it handled
  * the turn's action. It stays dormant: only its model and environment state change.
  */
final private[kernel] case class DormantAction[OwnerMsg](
  component: MountedComponent[OwnerMsg],
  discard: UIO[Unit])

private[kernel] enum ComponentOutput[+RootMsg]:
  case Root(message: RootMsg, componentType: String, componentId: String)
  case Parent(
    component: ComponentInstanceId,
    message: Any,
    componentType: String,
    componentId: String)

/** A component of the turn's forest, either rendered by the turn or kept as committed. */
sealed private[kernel] trait StagedComponent[OwnerMsg]:
  def id: ComponentInstanceId
  def key: ComponentKey
  def parent: Option[ComponentInstanceId]
  def children: Vector[ComponentInstanceId]
  def environmentState: ComponentEnvironmentState
  def appliedUpdate: Option[ComponentUpdateRequest]

  /** The requirement the turn staged this component for. */
  def declaration: ComponentRequirement[?]

  /** The components the render of this component declares. */
  def declares: Vector[ComponentRequirement[?]]

  /** Whether `requirement` declares this component with the props it was staged for. */
  def matches(requirement: ComponentRequirement[?]): Boolean

  /** This component, staged for `requirement`, which declares it as it was staged. */
  def redeclared(requirement: ComponentRequirement[?]): StagedComponent[OwnerMsg]

  /** Resolves the declaration of this component to its staged render. */
  def resolution: ComponentResolution

  /** The component to commit, still resolving for a while the bindings its last render dropped. */
  def committed(now: Instant): MountedComponent[OwnerMsg]

  /** Releases what the turn staged for this component, when the turn is not committed. */
  def discard: UIO[Unit]

  /** Releases what the turn staged for this component, when its commit failed. */
  def abortCommitted: UIO[Unit]
end StagedComponent

private[kernel] object StagedComponent:
  /** A component the turn rendered: it is new, it ran a callback, its input changed, or one of its
    * descendants did.
    *
    * @param release
    *   discards the environment state the turn staged and, for a new component, closes its program
    */
  final case class Rendered[OwnerMsg, P, M, A, O](
    id: ComponentInstanceId,
    key: ComponentKey,
    definition: LiveComponent[P, M, A],
    inputProps: P,
    props: P,
    model: A,
    ref: ComponentRef[M],
    program: RenderProgram[(P, A, RenderState), M],
    candidate: RenderCandidate[M],
    parent: Option[ComponentInstanceId],
    children: Vector[ComponentInstanceId],
    environmentState: ComponentEnvironmentState,
    appliedUpdate: Option[ComponentUpdateRequest],
    outputMapper: Option[O => OwnerMsg],
    previous: Option[MountedComponent[OwnerMsg]],
    declaration: ComponentRequirement[?],
    release: UIO[Unit])
      extends StagedComponent[OwnerMsg]:
    def declares: Vector[ComponentRequirement[?]] = candidate.componentRequirements

    def matches(requirement: ComponentRequirement[?]): Boolean =
      (requirement.definition.asInstanceOf[AnyRef] eq definition.asInstanceOf[AnyRef]) &&
        requirement.applicationId == key.applicationId && requirement.props == inputProps

    def redeclared(requirement: ComponentRequirement[?]): StagedComponent[OwnerMsg] =
      copy(
        outputMapper = requirement.outputMapper.map(_.asInstanceOf[O => OwnerMsg]),
        declaration = requirement
      )

    def resolution: ComponentResolution =
      val requirement = declaration
      requirement.resolve(
        ref.asInstanceOf[ComponentRef[requirement.Message]],
        ref.asInstanceOf[AnyRef],
        candidate.asInstanceOf[RenderCandidate[requirement.Message]]
      )

    def resolvingNested(
      resolutions: Vector[NestedResolution]
    ): Either[RenderError, Rendered[OwnerMsg, P, M, A, O]] =
      candidate.resolveNested(resolutions).map(next => copy(candidate = next))

    def resolvingComponents(
      resolutions: Vector[ComponentResolution]
    ): Either[RenderError, Rendered[OwnerMsg, P, M, A, O]] =
      candidate
        .resolveComponents(resolutions)
        .map(next => if next eq candidate then this else copy(candidate = next))

    def committed(now: Instant): MountedComponent[OwnerMsg] =
      val value = MountedComponentValue[OwnerMsg, P, M, A, O](
        id,
        key,
        definition,
        inputProps,
        props,
        model,
        ref,
        candidate.commit,
        program,
        parent,
        children,
        environmentState,
        outputMapper,
        declaration
      )
      previous.fold(value)(value.retiringBindingsOf(_, now))

    def discard: UIO[Unit] =
      candidate.discard *> ZIO.when(candidate.stagedScope.isClosed)(release).unit

    def abortCommitted: UIO[Unit] = candidate.stagedScope.closeFromOwner *> release
  end Rendered

  /** A component the turn leaves as committed, as it does each of its descendants: none of them ran
    * a callback, and each would render its committed render again.
    */
  final case class Kept[OwnerMsg](component: MountedComponent[OwnerMsg])
      extends StagedComponent[OwnerMsg]:
    def id: ComponentInstanceId                          = component.id
    def key: ComponentKey                                = component.key
    def parent: Option[ComponentInstanceId]              = component.parent
    def children: Vector[ComponentInstanceId]            = component.children
    def environmentState: ComponentEnvironmentState      = component.environmentState
    def appliedUpdate: Option[ComponentUpdateRequest]    = None
    def declaration: ComponentRequirement[?]             = component.declaration
    def declares: Vector[ComponentRequirement[?]]        = component.render.componentRequirements
    def matches(requirement: ComponentRequirement[?])    = component.declaredAs(requirement)
    def redeclared(requirement: ComponentRequirement[?]) =
      Kept(component.declaredBy(requirement, component.parent))
    def resolution: ComponentResolution                     = component.resolution
    def committed(now: Instant): MountedComponent[OwnerMsg] = component
    // The committed state owns everything a kept component holds.
    def discard: UIO[Unit]        = ZIO.unit
    def abortCommitted: UIO[Unit] = ZIO.unit
end StagedComponent
