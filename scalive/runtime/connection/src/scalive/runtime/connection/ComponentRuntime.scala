package scalive.runtime.connection

import zio.*
import zio.json.JsonDecoder

import scalive.*
import scalive.render.StreamRequirement
import scalive.runtime.contracts.ComponentInstanceId
import scalive.runtime.contracts.Epoch
import scalive.runtime.contracts.LifecycleId
import scalive.runtime.kernel.*
import scalive.runtime.resources.*

/** Connection-owned adapter from component callbacks to transactional root turn drafts. */
final private[connection] class ConnectedComponentEnvironment[RootMsg, RootModel] private (
  metadata: RootConnectionMetadata,
  lifecycle: LifecycleId,
  ownerEpoch: Epoch,
  closedInstances: Ref[Set[ComponentInstanceId]],
  discardedInstances: Ref[Set[ComponentInstanceId]])
    extends ComponentEnvironment[RootMsg, RootState[RootMsg, RootModel]]:

  private type Draft = TurnDraft[RootMsg, RootState[RootMsg, RootModel]]

  override def flash(draft: Draft): Map[FlashKind, String] = draft.model.flash

  def mount[P, M, A](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    draft: Draft
  ): Task[ComponentCallbackResult[A, RootMsg, RootState[RootMsg, RootModel]]] =
    for
      turn <- ComponentTurn.make(
                draft,
                ComponentHookRegistry.fromStatic(component.hooks),
                lifecycle,
                id,
                ownerEpoch,
                StreamStore.empty
              )
      model  <- ZIO.suspend(component.mount(props, ComponentMountContextImpl(metadata, turn)))
      result <- turn.result(model)
    yield result

  def update[P, M, A](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    state: ComponentEnvironmentState,
    draft: Draft
  ): Task[ComponentCallbackResult[A, RootMsg, RootState[RootMsg, RootModel]]] =
    for
      turn <- ComponentTurn.make(
                draft,
                registry[P, M, A](state),
                lifecycle,
                id,
                ownerEpoch,
                streamStore(state)
              )
      next <-
        ZIO.suspend(component.update(props, model, ComponentUpdateContextImpl(metadata, turn)))
      result <- turn.result(next)
    yield result

  def message[P, M, A, O](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    value: M,
    emit: O => Task[Unit],
    state: ComponentEnvironmentState,
    draft: Draft
  ): Task[ComponentCallbackResult[A, RootMsg, RootState[RootMsg, RootModel]]] =
    for
      turn <- ComponentTurn.make(
                draft,
                registry[P, M, A](state),
                lifecycle,
                id,
                ownerEpoch,
                streamStore(state)
              )
      context = ComponentMessageContextImpl[P, M, A, O](metadata, component, emit, turn)
      hooks  <- turn.hookRegistry[P, M, A]
      hooked <- runEventHooks(hooks.event, props, model, value, context)
      next   <- hooked match
                case LiveHookResult.Halt(current)     => ZIO.succeed(current)
                case LiveHookResult.Continue(current) =>
                  ZIO.suspend(component.handleMessage(props, current, context)(value))
      result <- turn.result(next)
    yield result

  def async[P, M, A, O](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    event: LiveAsyncEvent[M],
    emit: O => Task[Unit],
    state: ComponentEnvironmentState,
    draft: Draft
  ): Task[ComponentCallbackResult[A, RootMsg, RootState[RootMsg, RootModel]]] =
    for
      turn <- ComponentTurn.make(
                draft,
                registry[P, M, A](state),
                lifecycle,
                id,
                ownerEpoch,
                streamStore(state)
              )
      context = ComponentMessageContextImpl[P, M, A, O](metadata, component, emit, turn)
      hooks  <- turn.hookRegistry[P, M, A]
      hooked <- runAsyncHooks(hooks.async, props, model, event, context)
      next   <- hooked match
                case LiveHookResult.Halt(current)     => ZIO.succeed(current)
                case LiveHookResult.Continue(current) =>
                  event.result match
                    case LiveAsyncResult.Succeeded(message) =>
                      ZIO.suspend(component.handleMessage(props, current, context)(message))
                    case _ => ZIO.succeed(current)
      result <- turn.result(next)
    yield result

  override def managedAsync[P, M, A, O](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    event: LiveAsyncEvent[M],
    message: M,
    emit: O => Task[Unit],
    state: ComponentEnvironmentState,
    draft: Draft
  ): Task[ComponentCallbackResult[A, RootMsg, RootState[RootMsg, RootModel]]] =
    for
      turn <- ComponentTurn.make(
                draft,
                registry[P, M, A](state),
                lifecycle,
                id,
                ownerEpoch,
                streamStore(state)
              )
      context = ComponentMessageContextImpl[P, M, A, O](metadata, component, emit, turn)
      hooks  <- turn.hookRegistry[P, M, A]
      hooked <- runAsyncHooks(hooks.async, props, model, event, context)
      next   <- hooked match
                case LiveHookResult.Halt(current)     => ZIO.succeed(current)
                case LiveHookResult.Continue(current) =>
                  ZIO.suspend(component.handleMessage(props, current, context)(message))
      result <- turn.result(next)
    yield result

  def browserEvent[P, M, A, O](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    command: SessionCommand.ComponentClientEvent,
    emit: O => Task[Unit],
    state: ComponentEnvironmentState,
    draft: Draft
  ): Task[Option[ComponentCallbackResult[A, RootMsg, RootState[RootMsg, RootModel]]]] =
    (command.eventName, command.rawJson) match
      case (Some(name), Some(raw)) =>
        for
          turn <- ComponentTurn.make(
                    draft,
                    registry[P, M, A](state),
                    lifecycle,
                    id,
                    ownerEpoch,
                    streamStore(state)
                  )
          hooks <- turn.hookRegistry[P, M, A]
          matching = hooks.browser.filter(_.name == name)
          result <-
            if matching.isEmpty then ZIO.none
            else
              val context = ComponentMessageContextImpl[P, M, A, O](metadata, component, emit, turn)
              runBrowserHooks(matching, props, model, raw, context)
                .flatMap(turn.result).map(Some(_))
        yield result
      case _ => ZIO.none

  def afterRender[P, M, A](
    id: ComponentInstanceId,
    component: LiveComponent[P, M, A],
    props: P,
    model: A,
    state: ComponentEnvironmentState,
    draft: Draft
  ): Task[ComponentAfterRenderResult[RootMsg, RootState[RootMsg, RootModel]]] =
    for
      turn <- ComponentTurn.make(
                draft,
                registry[P, M, A](state),
                lifecycle,
                id,
                ownerEpoch,
                streamStore(state)
              )
      context = ComponentAfterRenderContextImpl[P, M, A](metadata, turn)
      hooks  <- turn.hookRegistry[P, M, A]
      _      <- ZIO.foreachDiscard(hooks.afterRender)(_.invoke(props, model, context))
      result <- turn.afterRenderResult
    yield result

  override def validateStreams[M](
    _id: ComponentInstanceId,
    state: ComponentEnvironmentState,
    requirements: Vector[StreamRequirement[M]]
  ): Task[Unit] = ZIO.attempt(streamStore(state).validate(requirements))

  def discard(id: ComponentInstanceId, state: ComponentEnvironmentState): UIO[Unit] =
    discardedInstances.update(_ + id)

  def close(id: ComponentInstanceId, state: ComponentEnvironmentState): UIO[Unit] =
    closedInstances.update(_ + id)

  def wasClosed(id: ComponentInstanceId): UIO[Boolean]    = closedInstances.get.map(_.contains(id))
  def wasDiscarded(id: ComponentInstanceId): UIO[Boolean] =
    discardedInstances.get.map(_.contains(id))

  private def registry[P, M, A](state: ComponentEnvironmentState): ComponentHookRegistry[P, M, A] =
    state.value.asInstanceOf[ComponentState[P, M, A]].hooks

  private def streamStore(state: ComponentEnvironmentState): StreamStore =
    state.value.asInstanceOf[ComponentState[Any, Any, Any]].streams

  private def runEventHooks[P, M, A](
    hooks: Vector[ComponentHookRegistry.Event[P, M, A]],
    props: P,
    initial: A,
    message: M,
    context: ComponentMessageContext[P, M, A]
  ): LiveIO[LiveHookResult[A]] =
    hooks.foldLeft[LiveIO[LiveHookResult[A]]](ZIO.succeed(LiveHookResult.cont(initial))) {
      (effect, hook) =>
        effect.flatMap {
          case halted @ LiveHookResult.Halt(_) => ZIO.succeed(halted)
          case LiveHookResult.Continue(model)  => hook.invoke(props, model, message, context)
        }
    }

  private def runAsyncHooks[P, M, A](
    hooks: Vector[ComponentHookRegistry.Async[P, M, A]],
    props: P,
    initial: A,
    event: LiveAsyncEvent[M],
    context: ComponentMessageContext[P, M, A]
  ): LiveIO[LiveHookResult[A]] =
    hooks.foldLeft[LiveIO[LiveHookResult[A]]](ZIO.succeed(LiveHookResult.cont(initial))) {
      (effect, hook) =>
        effect.flatMap {
          case halted @ LiveHookResult.Halt(_) => ZIO.succeed(halted)
          case LiveHookResult.Continue(model)  => hook.invoke(props, model, event, context)
        }
    }

  private def runBrowserHooks[P, M, A](
    hooks: Vector[ComponentHookRegistry.Browser[P, M, A]],
    props: P,
    committed: A,
    raw: String,
    context: ComponentMessageContext[P, M, A]
  ): LiveIO[A] =
    hooks
      .foldLeft[LiveIO[Either[Unit, A]]](ZIO.succeed(Right(committed))) { (effect, hook) =>
        effect.flatMap {
          case malformed @ Left(_) => ZIO.succeed(malformed)
          case Right(model)        =>
            hook.invoke(props, model, raw, context) match
              case Right(next) => next.map(Right(_))
              case Left(_)     =>
                ZIO.logWarning("component browser event payload was malformed").as(Left(()))
        }
      }.map(_.fold(_ => committed, identity))
end ConnectedComponentEnvironment

private[connection] object ConnectedComponentEnvironment:
  def make[RootMsg, RootModel](
    metadata: RootConnectionMetadata,
    lifecycle: LifecycleId,
    ownerEpoch: Epoch = Epoch.initial
  ): UIO[ConnectedComponentEnvironment[RootMsg, RootModel]] =
    for
      closed    <- Ref.make(Set.empty[ComponentInstanceId])
      discarded <- Ref.make(Set.empty[ComponentInstanceId])
    yield new ConnectedComponentEnvironment(metadata, lifecycle, ownerEpoch, closed, discarded)

final private case class ComponentState[P, M, A](
  hooks: ComponentHookRegistry[P, M, A],
  streams: StreamStore)

private trait ComponentHookJournal:
  def updateHooks[P, M, A](
    f: ComponentHookRegistry[P, M, A] => ComponentHookRegistry[P, M, A]
  ): UIO[Unit]

final private class ComponentTurn[RootMsg, RootModel] private (
  initial: TurnDraft[RootMsg, RootState[RootMsg, RootModel]],
  val root: RootTurnJournal,
  componentHooks: Ref[ComponentHookRegistry[Any, Any, Any]])
    extends ComponentHookJournal:

  def hookRegistry[P, M, A]: UIO[ComponentHookRegistry[P, M, A]] =
    componentHooks.get.map(_.asInstanceOf[ComponentHookRegistry[P, M, A]])

  def updateHooks[P, M, A](
    f: ComponentHookRegistry[P, M, A] => ComponentHookRegistry[P, M, A]
  ): UIO[Unit] = componentHooks.update(current =>
    f(current.asInstanceOf[ComponentHookRegistry[P, M, A]])
      .asInstanceOf[ComponentHookRegistry[Any, Any, Any]]
  )

  def currentUrl = initial.model.url

  def result[A](model: A): UIO[ComponentCallbackResult[A, RootMsg, RootState[RootMsg, RootModel]]] =
    snapshot.map { case (draft, state) => ComponentCallbackResult(model, draft, state) }

  def afterRenderResult: UIO[ComponentAfterRenderResult[RootMsg, RootState[RootMsg, RootModel]]] =
    snapshot.map { case (draft, state) => ComponentAfterRenderResult(draft, state) }

  private def snapshot
    : UIO[(TurnDraft[RootMsg, RootState[RootMsg, RootModel]], ComponentEnvironmentState)] =
    for
      navigation  <- root.navigationWithFlash
      flash       <- root.flash.get
      events      <- root.clientEvents.get
      updates     <- root.componentUpdates.get
      operations  <- root.resourceOperationSnapshot
      streams     <- root.streamSnapshot
      uploadState <- root.uploadSnapshot
      hooks       <- componentHooks.get
      (uploads, uploadCommit, uploadRollback) = uploadState
      state = ComponentEnvironmentState(ComponentState(hooks, streams))
      draft = initial.copy(
                model = initial.model.copy(flash = flash, uploads = uploads),
                navigation = navigation,
                effects = initial.effects.copy(clientEvents = events),
                componentUpdates = updates,
                resourceOperations = operations,
                uploadCommit = uploadCommit,
                uploadRollback = uploadRollback
              )
    yield draft -> state
end ComponentTurn

private object ComponentTurn:
  def make[RootMsg, RootModel, P, M, A](
    draft: TurnDraft[RootMsg, RootState[RootMsg, RootModel]],
    hooks: ComponentHookRegistry[P, M, A],
    lifecycle: LifecycleId,
    id: ComponentInstanceId,
    ownerEpoch: Epoch = Epoch.initial,
    initialStreams: StreamStore = StreamStore.empty
  ): Task[ComponentTurn[RootMsg, RootModel]] =
    for
      root <- RootTurnJournal.make(
                OwnerId.Component(lifecycle, id),
                draft.model.hooks,
                draft.model.flash,
                draft.effects.clientEvents,
                draft.componentUpdates,
                draft.navigation,
                draft.resourceOperations,
                initialStreams,
                ownerEpoch = ownerEpoch,
                initialUploads = draft.model.uploads,
                initialUploadCommit = draft.uploadCommit,
                initialUploadRollback = draft.uploadRollback
              )
      componentHooks <- Ref.make(hooks.asInstanceOf[ComponentHookRegistry[Any, Any, Any]])
    yield ComponentTurn(draft, root, componentHooks)

final private case class ComponentHookRegistry[P, M, A](
  staticBrowser: Vector[ComponentHookRegistry.Browser[P, M, A]],
  staticEvent: Vector[ComponentHookRegistry.Event[P, M, A]],
  staticAsync: Vector[ComponentHookRegistry.Async[P, M, A]],
  staticAfterRender: Vector[ComponentHookRegistry.AfterRender[P, M, A]],
  dynamicBrowser: Vector[(String, ComponentHookRegistry.Browser[P, M, A])] = Vector.empty,
  dynamicEvent: Vector[(String, ComponentHookRegistry.Event[P, M, A])] = Vector.empty,
  dynamicAsync: Vector[(String, ComponentHookRegistry.Async[P, M, A])] = Vector.empty,
  dynamicAfterRender: Vector[(String, ComponentHookRegistry.AfterRender[P, M, A])] = Vector.empty):
  def browser     = staticBrowser ++ dynamicBrowser.map(_._2)
  def event       = staticEvent ++ dynamicEvent.map(_._2)
  def async       = staticAsync ++ dynamicAsync.map(_._2)
  def afterRender = staticAfterRender ++ dynamicAfterRender.map(_._2)

private object ComponentHookRegistry:
  trait Browser[P, M, A]:
    def name: String
    def invoke(props: P, model: A, raw: String, context: ComponentMessageContext[P, M, A])
      : Either[String, LiveIO[A]]
  trait Event[P, M, A]:
    def invoke(props: P, model: A, message: M, context: ComponentMessageContext[P, M, A])
      : LiveIO[LiveHookResult[A]]
  trait Async[P, M, A]:
    def invoke(
      props: P,
      model: A,
      event: LiveAsyncEvent[M],
      context: ComponentMessageContext[P, M, A]
    ): LiveIO[LiveHookResult[A]]
  trait AfterRender[P, M, A]:
    def invoke(props: P, model: A, context: ComponentAfterRenderContext[P, M, A]): LiveIO[Unit]

  def browserHook[P, M, A, B](
    event: BrowserToServerEvent[B],
    decoder: JsonDecoder[B],
    handler: (P, A, B, ComponentMessageContext[P, M, A]) => LiveIO[A]
  ): Browser[P, M, A] = new Browser[P, M, A]:
    val name = event.value
    def invoke(props: P, model: A, raw: String, context: ComponentMessageContext[P, M, A]) =
      decoder.decodeJson(raw).map(value => handler(props, model, value, context))

  def fromStatic[P, M, A](hooks: ComponentLiveHooks[P, M, A]): ComponentHookRegistry[P, M, A] =
    hooks match
      case _: ComponentLiveHooks.Empty[P, M, A]              => empty
      case hook: ComponentLiveHooks.BrowserEvent[P, M, A, ?] =>
        val previous = fromStatic(hook.previous)
        previous.copy(staticBrowser =
          previous.staticBrowser :+ browserHook(hook.event, hook.decoder, hook.handler)
        )
      case hook: ComponentLiveHooks.Event[P, M, A] =>
        val previous = fromStatic(hook.previous)
        previous.copy(staticEvent =
          previous.staticEvent :+ new Event[P, M, A]:
            def invoke(props: P, model: A, message: M, context: ComponentMessageContext[P, M, A]) =
              hook.hook(props, model, message, context)
        )
      case hook: ComponentLiveHooks.Async[P, M, A] =>
        val previous = fromStatic(hook.previous)
        previous.copy(staticAsync =
          previous.staticAsync :+ new Async[P, M, A]:
            def invoke(
              props: P,
              model: A,
              event: LiveAsyncEvent[M],
              context: ComponentMessageContext[P, M, A]
            ) = hook.hook(props, model, event, context)
        )
      case hook: ComponentLiveHooks.AfterRender[P, M, A] =>
        val previous = fromStatic(hook.previous)
        previous.copy(staticAfterRender =
          previous.staticAfterRender :+ new AfterRender[P, M, A]:
            def invoke(props: P, model: A, context: ComponentAfterRenderContext[P, M, A]) =
              hook.hook(props, model, context)
        )

  def empty[P, M, A]: ComponentHookRegistry[P, M, A] =
    ComponentHookRegistry[P, M, A](
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

  def replace[B](entries: Vector[(String, B)], id: String, value: B): Vector[(String, B)] =
    val index = entries.indexWhere(_._1 == id)
    if index < 0 then entries :+ (id -> value) else entries.updated(index, id -> value)

  def detach[B](entries: Vector[(String, B)], id: String): Vector[(String, B)] =
    entries.filterNot(_._1 == id)
end ComponentHookRegistry

final private class JournaledComponentHooks[P, M, A](turn: ComponentHookJournal)
    extends ComponentHooks[P, M, A]:
  val browserEvent = new ComponentBrowserEventHooks[P, M, A]:
    def attach[B: JsonDecoder](
      id: String,
      event: BrowserToServerEvent[B]
    )(
      hook: (P, A, B, ComponentMessageContext[P, M, A]) => LiveIO[A]
    ) = turn.updateHooks[P, M, A](registry =>
      registry.copy(dynamicBrowser =
        ComponentHookRegistry.replace(
          registry.dynamicBrowser,
          id,
          ComponentHookRegistry.browserHook(event, summon[JsonDecoder[B]], hook)
        )
      )
    )
    def detach(id: String) = turn.updateHooks[P, M, A](registry =>
      registry.copy(dynamicBrowser = ComponentHookRegistry.detach(registry.dynamicBrowser, id))
    )
  val event = new ComponentEventHooks[P, M, A]:
    def attach(
      id: String
    )(
      hook: (P, A, M, ComponentMessageContext[P, M, A]) => LiveIO[LiveHookResult[A]]
    ) = turn.updateHooks[P, M, A](registry =>
      registry.copy(dynamicEvent =
        ComponentHookRegistry.replace(
          registry.dynamicEvent,
          id,
          new ComponentHookRegistry.Event:
            def invoke(props: P, model: A, message: M, context: ComponentMessageContext[P, M, A]) =
              hook(props, model, message, context)
        )
      )
    )
    def detach(id: String) = turn.updateHooks[P, M, A](registry =>
      registry.copy(dynamicEvent = ComponentHookRegistry.detach(registry.dynamicEvent, id))
    )
  val async = new ComponentAsyncHooks[P, M, A]:
    def attach(
      id: String
    )(
      hook: (P, A, LiveAsyncEvent[M], ComponentMessageContext[P, M, A]) => LiveIO[LiveHookResult[A]]
    ) = turn.updateHooks[P, M, A](registry =>
      registry.copy(dynamicAsync =
        ComponentHookRegistry.replace(
          registry.dynamicAsync,
          id,
          new ComponentHookRegistry.Async:
            def invoke(
              props: P,
              model: A,
              event: LiveAsyncEvent[M],
              context: ComponentMessageContext[P, M, A]
            ) = hook(props, model, event, context)
        )
      )
    )
    def detach(id: String) = turn.updateHooks[P, M, A](registry =>
      registry.copy(dynamicAsync = ComponentHookRegistry.detach(registry.dynamicAsync, id))
    )
  val afterRender = new ComponentAfterRenderHooks[P, M, A]:
    def attach(id: String)(hook: (P, A, ComponentAfterRenderContext[P, M, A]) => LiveIO[Unit]) =
      turn.updateHooks[P, M, A](registry =>
        registry.copy(dynamicAfterRender =
          ComponentHookRegistry.replace(
            registry.dynamicAfterRender,
            id,
            new ComponentHookRegistry.AfterRender:
              def invoke(props: P, model: A, context: ComponentAfterRenderContext[P, M, A]) =
                hook(props, model, context)
          )
        )
      )
    def detach(id: String) = turn.updateHooks[P, M, A](registry =>
      registry.copy(
        dynamicAfterRender = ComponentHookRegistry.detach(registry.dynamicAfterRender, id)
      )
    )
end JournaledComponentHooks

final private class ComponentConnectedImpl[M](
  metadata: RootConnectionMetadata,
  turn: ComponentTurn[?, ?])
    extends ComponentConnected[M]:
  val staticChanged = metadata.staticChanged
  val connectParams = metadata.connectParams
  val async         = JournaledAsync[M](turn.root)
  val client        = JournaledClient(turn.root)

final private case class ComponentMountContextImpl[P, M, A](
  metadata: RootConnectionMetadata,
  turn: ComponentTurn[?, ?])
    extends ComponentMountContext[P, M, A]:
  val connection = Connection.Connected(ComponentConnectedImpl[M](metadata, turn))
  val flash      = JournaledFlash(turn.root)
  val uploads    = JournaledUploads(turn.root)
  val streams    = JournaledStreams(turn.root.streams)
  val hooks      = JournaledComponentHooks[P, M, A](turn)

final private case class ComponentUpdateContextImpl[P, M, A](
  metadata: RootConnectionMetadata,
  turn: ComponentTurn[?, ?])
    extends ComponentUpdateContext[P, M, A]:
  val connection = Connection.Connected(ComponentConnectedImpl[M](metadata, turn))
  val flash      = JournaledFlash(turn.root)
  val uploads    = JournaledUploads(turn.root)
  val streams    = JournaledStreams(turn.root.streams)
  val hooks      = JournaledComponentHooks[P, M, A](turn)

final private case class ComponentMessageContextImpl[P, M, A, O](
  metadata: RootConnectionMetadata,
  component: LiveComponent[P, M, A],
  output: O => Task[Unit],
  turn: ComponentTurn[?, ?])
    extends ComponentMessageContext[P, M, A]:
  val staticChanged = metadata.staticChanged
  val connectParams = metadata.connectParams
  val nav           = RootNavigation(turn.currentUrl, turn.root, allowPatch = true)
  val flash         = JournaledFlash(turn.root)
  val uploads       = JournaledUploads(turn.root)
  val streams       = JournaledStreams(turn.root.streams)
  val async         = JournaledAsync[M](turn.root)
  val client        = JournaledClient(turn.root)
  val components    = JournaledComponentUpdates(turn.root)
  val hooks         = JournaledComponentHooks[P, M, A](turn)

  private[scalive] def emit[B](channel: ComponentOutputChannel[B], value: B): LiveIO[Unit] =
    component match
      case withOutput: LiveComponent.WithOutput[P, M, A, ?]
          if withOutput.outputChannel eq channel =>
        output(value.asInstanceOf[O])
      case _ => ZIO.fail(IllegalArgumentException("unrelated component output channel"))

final private case class ComponentAfterRenderContextImpl[P, M, A](
  metadata: RootConnectionMetadata,
  turn: ComponentTurn[?, ?])
    extends ComponentAfterRenderContext[P, M, A]:
  val connection = Connection.Connected(new ConnectedMetadata:
    val staticChanged = metadata.staticChanged
    val connectParams = metadata.connectParams)
  val hooks = JournaledComponentHooks[P, M, A](turn)
