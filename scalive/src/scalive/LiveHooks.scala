package scalive

import zio.*
import zio.http.URL
import zio.json.ast.Json

final case class LiveEvent(
  kind: String,
  bindingId: String,
  value: Json,
  params: Map[String, String],
  cid: Option[Int],
  meta: Option[Json])

object LiveEvent:
  private[scalive] def fromPayload(event: WebSocketMessage.Payload.Event): LiveEvent =
    LiveEvent(
      kind = event.`type`,
      bindingId = event.event,
      value = event.value,
      params = event.params,
      cid = event.cid,
      meta = event.meta
    )

final case class LiveAsyncEvent(name: String)

enum LiveHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model)

object LiveHookResult:
  def cont[Model](model: Model): LiveHookResult[Model] =
    LiveHookResult.Continue(model)

  def halt[Model](model: Model): LiveHookResult[Model] =
    LiveHookResult.Halt(model)

enum LiveEventResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[Json] = None)

object LiveEventResult:
  def cont[Model](model: Model): LiveEventResult[Model] =
    LiveEventResult.Continue(model)

  def halt[Model](model: Model): LiveEventResult[Model] =
    LiveEventResult.Halt(model, None)

  def haltReply[Model](model: Model, value: Json): LiveEventResult[Model] =
    LiveEventResult.Halt(model, Some(value))

trait LiveHookRuntime:
  def attachEvent[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveEvent) => LiveIO[LiveView.UpdateContext, LiveEventResult[Model]]
  ): Task[Unit]

  def detachEvent(id: String): Task[Unit]

  def attachParams[Model](
    id: String
  )(
    hook: (Model, URL) => LiveIO[LiveView.ParamsContext, LiveHookResult[Model]]
  ): Task[Unit]

  def detachParams(id: String): Task[Unit]

  def attachInfo[Msg, Model](
    id: String
  )(
    hook: (Model, Msg) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
  ): Task[Unit]

  def detachInfo(id: String): Task[Unit]

  def attachAsync[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveAsyncEvent) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
  ): Task[Unit]

  def detachAsync(id: String): Task[Unit]

  def attachAfterRender[Model](
    id: String
  )(
    hook: Model => LiveIO[LiveView.UpdateContext, Model]
  ): Task[Unit]

  def detachAfterRender(id: String): Task[Unit]

  private[scalive] def runEvent[Msg, Model](
    model: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventResult[Model]]

  private[scalive] def runParams[Model](
    model: Model,
    url: URL,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]]

  private[scalive] def runInfo[Msg, Model](
    model: Model,
    message: Msg,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]]

  private[scalive] def runAsync[Msg, Model](
    model: Model,
    message: Msg,
    event: LiveAsyncEvent,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]]

  private[scalive] def runAfterRender[Model](
    model: Model,
    ctx: LiveContext
  ): Task[Model]
end LiveHookRuntime

object LiveHookRuntime:
  object Disabled extends LiveHookRuntime:
    private def unavailable[A]: Task[A] =
      ZIO.fail(new IllegalStateException("lifecycle hooks are not available in this context"))

    def attachEvent[Msg, Model](
      id: String
    )(
      hook: (Model, Msg, LiveEvent) => LiveIO[LiveView.UpdateContext, LiveEventResult[Model]]
    ): Task[Unit] =
      val _ = (id, hook)
      unavailable

    def detachEvent(id: String): Task[Unit] =
      val _ = id
      ZIO.unit

    def attachParams[Model](
      id: String
    )(
      hook: (Model, URL) => LiveIO[LiveView.ParamsContext, LiveHookResult[Model]]
    ): Task[Unit] =
      val _ = (id, hook)
      unavailable

    def detachParams(id: String): Task[Unit] =
      val _ = id
      ZIO.unit

    def attachInfo[Msg, Model](
      id: String
    )(
      hook: (Model, Msg) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
    ): Task[Unit] =
      val _ = (id, hook)
      unavailable

    def detachInfo(id: String): Task[Unit] =
      val _ = id
      ZIO.unit

    def attachAsync[Msg, Model](
      id: String
    )(
      hook: (Model, Msg, LiveAsyncEvent) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
    ): Task[Unit] =
      val _ = (id, hook)
      unavailable

    def detachAsync(id: String): Task[Unit] =
      val _ = id
      ZIO.unit

    def attachAfterRender[Model](
      id: String
    )(
      hook: Model => LiveIO[LiveView.UpdateContext, Model]
    ): Task[Unit] =
      val _ = (id, hook)
      unavailable

    def detachAfterRender(id: String): Task[Unit] =
      val _ = id
      ZIO.unit

    private[scalive] def runEvent[Msg, Model](
      model: Model,
      message: Msg,
      event: LiveEvent,
      ctx: LiveContext
    ): Task[LiveEventResult[Model]] =
      val _ = (message, event, ctx)
      ZIO.succeed(LiveEventResult.Continue(model))

    private[scalive] def runParams[Model](
      model: Model,
      url: URL,
      ctx: LiveContext
    ): Task[LiveHookResult[Model]] =
      val _ = (url, ctx)
      ZIO.succeed(LiveHookResult.Continue(model))

    private[scalive] def runInfo[Msg, Model](
      model: Model,
      message: Msg,
      ctx: LiveContext
    ): Task[LiveHookResult[Model]] =
      val _ = (message, ctx)
      ZIO.succeed(LiveHookResult.Continue(model))

    private[scalive] def runAsync[Msg, Model](
      model: Model,
      message: Msg,
      event: LiveAsyncEvent,
      ctx: LiveContext
    ): Task[LiveHookResult[Model]] =
      val _ = (message, event, ctx)
      ZIO.succeed(LiveHookResult.Continue(model))

    private[scalive] def runAfterRender[Model](model: Model, ctx: LiveContext): Task[Model] =
      val _ = ctx
      ZIO.succeed(model)
  end Disabled
end LiveHookRuntime

final private[scalive] case class LiveHookRuntimeState(
  eventHooks: Vector[StoredEventHook],
  paramsHooks: Vector[StoredParamsHook],
  infoHooks: Vector[StoredInfoHook],
  asyncHooks: Vector[StoredAsyncHook],
  afterRenderHooks: Vector[StoredAfterRenderHook])

private[scalive] object LiveHookRuntimeState:
  val empty: LiveHookRuntimeState =
    LiveHookRuntimeState(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)

final private[scalive] case class StoredEventHook(
  id: String,
  run: (Any, Any, LiveEvent) => RIO[LiveView.UpdateContext, LiveEventResult[Any]])

final private[scalive] case class StoredParamsHook(
  id: String,
  run: (Any, URL) => RIO[LiveView.ParamsContext, LiveHookResult[Any]])

final private[scalive] case class StoredInfoHook(
  id: String,
  run: (Any, Any) => RIO[LiveView.UpdateContext, LiveHookResult[Any]])

final private[scalive] case class StoredAsyncHook(
  id: String,
  run: (Any, Any, LiveAsyncEvent) => RIO[LiveView.UpdateContext, LiveHookResult[Any]])

final private[scalive] case class StoredAfterRenderHook(
  id: String,
  run: Any => RIO[LiveView.UpdateContext, Any])

final private[scalive] class SocketLiveHookRuntime(ref: Ref[LiveHookRuntimeState])
    extends LiveHookRuntime:
  def attachEvent[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveEvent) => LiveIO[LiveView.UpdateContext, LiveEventResult[Model]]
  ): Task[Unit] =
    val stored = StoredEventHook(
      id,
      (model, message, event) =>
        LiveIO
          .toZIO(hook(model.asInstanceOf[Model], message.asInstanceOf[Msg], event))
          .map(_.asInstanceOf[LiveEventResult[Any]])
    )
    ref
      .modify { state =>
        if state.eventHooks.exists(_.id == id) then Left(duplicateError(id, "event")) -> state
        else Right(()) -> state.copy(eventHooks = state.eventHooks :+ stored)
      }.flatMap(ZIO.fromEither(_))

  def detachEvent(id: String): Task[Unit] =
    ref.update(state => state.copy(eventHooks = state.eventHooks.filterNot(_.id == id)))

  def attachParams[Model](
    id: String
  )(
    hook: (Model, URL) => LiveIO[LiveView.ParamsContext, LiveHookResult[Model]]
  ): Task[Unit] =
    val stored = StoredParamsHook(
      id,
      (model, url) =>
        LiveIO
          .toZIO(hook(model.asInstanceOf[Model], url))
          .map(_.asInstanceOf[LiveHookResult[Any]])
    )
    ref
      .modify { state =>
        if state.paramsHooks.exists(_.id == id) then Left(duplicateError(id, "params")) -> state
        else Right(()) -> state.copy(paramsHooks = state.paramsHooks :+ stored)
      }.flatMap(ZIO.fromEither(_))

  def detachParams(id: String): Task[Unit] =
    ref.update(state => state.copy(paramsHooks = state.paramsHooks.filterNot(_.id == id)))

  def attachInfo[Msg, Model](
    id: String
  )(
    hook: (Model, Msg) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
  ): Task[Unit] =
    val stored = StoredInfoHook(
      id,
      (model, message) =>
        LiveIO
          .toZIO(hook(model.asInstanceOf[Model], message.asInstanceOf[Msg]))
          .map(_.asInstanceOf[LiveHookResult[Any]])
    )
    ref
      .modify { state =>
        if state.infoHooks.exists(_.id == id) then Left(duplicateError(id, "info")) -> state
        else Right(()) -> state.copy(infoHooks = state.infoHooks :+ stored)
      }.flatMap(ZIO.fromEither(_))

  def detachInfo(id: String): Task[Unit] =
    ref.update(state => state.copy(infoHooks = state.infoHooks.filterNot(_.id == id)))

  def attachAsync[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveAsyncEvent) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
  ): Task[Unit] =
    val stored = StoredAsyncHook(
      id,
      (model, message, event) =>
        LiveIO
          .toZIO(hook(model.asInstanceOf[Model], message.asInstanceOf[Msg], event))
          .map(_.asInstanceOf[LiveHookResult[Any]])
    )
    ref
      .modify { state =>
        if state.asyncHooks.exists(_.id == id) then Left(duplicateError(id, "async")) -> state
        else Right(()) -> state.copy(asyncHooks = state.asyncHooks :+ stored)
      }.flatMap(ZIO.fromEither(_))

  def detachAsync(id: String): Task[Unit] =
    ref.update(state => state.copy(asyncHooks = state.asyncHooks.filterNot(_.id == id)))

  def attachAfterRender[Model](
    id: String
  )(
    hook: Model => LiveIO[LiveView.UpdateContext, Model]
  ): Task[Unit] =
    val stored = StoredAfterRenderHook(
      id,
      model => LiveIO.toZIO(hook(model.asInstanceOf[Model])).map(_.asInstanceOf[Any])
    )
    ref
      .modify { state =>
        if state.afterRenderHooks.exists(_.id == id) then
          Left(duplicateError(id, "afterRender")) -> state
        else Right(()) -> state.copy(afterRenderHooks = state.afterRenderHooks :+ stored)
      }.flatMap(ZIO.fromEither(_))

  def detachAfterRender(id: String): Task[Unit] =
    ref.update(state => state.copy(afterRenderHooks = state.afterRenderHooks.filterNot(_.id == id)))

  private[scalive] def runEvent[Msg, Model](
    model: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventResult[Model]] =
    ref.get.flatMap(state => reduceEventHooks(state.eventHooks, model, message, event, ctx))

  private[scalive] def runParams[Model](
    model: Model,
    url: URL,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    ref.get.flatMap(state => reduceHooks(state.paramsHooks, model)(_.run(_, url), ctx))

  private[scalive] def runInfo[Msg, Model](
    model: Model,
    message: Msg,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    ref.get.flatMap(state => reduceHooks(state.infoHooks, model)(_.run(_, message), ctx))

  private[scalive] def runAsync[Msg, Model](
    model: Model,
    message: Msg,
    event: LiveAsyncEvent,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    ref.get.flatMap(state => reduceAsyncHooks(state.asyncHooks, model, message, event, ctx))

  private[scalive] def runAfterRender[Model](model: Model, ctx: LiveContext): Task[Model] =
    ref.get.flatMap { state =>
      state.afterRenderHooks
        .foldLeft(ZIO.succeed(model.asInstanceOf[Any]): Task[Any]) { case (current, hook) =>
          current.flatMap(value => hook.run(value).provide(ZLayer.succeed(ctx)))
        }.map(_.asInstanceOf[Model])
    }

  private def reduceEventHooks[Msg, Model](
    hooks: Vector[StoredEventHook],
    initialModel: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventResult[Model]] =
    hooks
      .foldLeft(
        ZIO.succeed(
          LiveEventResult.Continue(initialModel).asInstanceOf[LiveEventResult[Any]]
        ): Task[LiveEventResult[Any]]
      ) { case (current, hook) =>
        current.flatMap {
          case LiveEventResult.Continue(model) =>
            hook.run(model, message, event).provide(ZLayer.succeed(ctx))
          case halt @ LiveEventResult.Halt(_, _) => ZIO.succeed(halt)
        }
      }.map(_.asInstanceOf[LiveEventResult[Model]])

  private def reduceAsyncHooks[Msg, Model](
    hooks: Vector[StoredAsyncHook],
    initialModel: Model,
    message: Msg,
    event: LiveAsyncEvent,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    hooks
      .foldLeft(
        ZIO.succeed(LiveHookResult.Continue(initialModel).asInstanceOf[LiveHookResult[Any]]): Task[
          LiveHookResult[Any]
        ]
      ) { case (current, hook) =>
        current.flatMap {
          case LiveHookResult.Continue(model) =>
            hook.run(model, message, event).provide(ZLayer.succeed(ctx))
          case halt @ LiveHookResult.Halt(_) => ZIO.succeed(halt)
        }
      }.map(_.asInstanceOf[LiveHookResult[Model]])

  private def reduceHooks[Model, Hook](
    hooks: Vector[Hook],
    initialModel: Model
  )(
    run: (Hook, Any) => RIO[LiveView.UpdateContext, LiveHookResult[Any]],
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    hooks
      .foldLeft(
        ZIO.succeed(LiveHookResult.Continue(initialModel).asInstanceOf[LiveHookResult[Any]]): Task[
          LiveHookResult[Any]
        ]
      ) { case (current, hook) =>
        current.flatMap {
          case LiveHookResult.Continue(model) => run(hook, model).provide(ZLayer.succeed(ctx))
          case halt @ LiveHookResult.Halt(_)  => ZIO.succeed(halt)
        }
      }.map(_.asInstanceOf[LiveHookResult[Model]])

  private def duplicateError(id: String, stage: String): IllegalArgumentException =
    new IllegalArgumentException(s"$stage hook '$id' is already attached")
end SocketLiveHookRuntime

final private[scalive] class ComponentLiveHookRuntime(ref: Ref[LiveHookRuntimeState])
    extends LiveHookRuntime:
  private val delegate = new SocketLiveHookRuntime(ref)

  def attachEvent[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveEvent) => LiveIO[LiveView.UpdateContext, LiveEventResult[Model]]
  ): Task[Unit] =
    delegate.attachEvent(id)(hook)

  def detachEvent(id: String): Task[Unit] =
    delegate.detachEvent(id)

  def attachParams[Model](
    id: String
  )(
    hook: (Model, URL) => LiveIO[LiveView.ParamsContext, LiveHookResult[Model]]
  ): Task[Unit] =
    val _ = (id, hook)
    unsupported("params")

  def detachParams(id: String): Task[Unit] =
    val _ = id
    unsupported("params")

  def attachInfo[Msg, Model](
    id: String
  )(
    hook: (Model, Msg) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
  ): Task[Unit] =
    val _ = (id, hook)
    unsupported("info")

  def detachInfo(id: String): Task[Unit] =
    val _ = id
    unsupported("info")

  def attachAsync[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveAsyncEvent) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
  ): Task[Unit] =
    val _ = (id, hook)
    unsupported("async")

  def detachAsync(id: String): Task[Unit] =
    val _ = id
    unsupported("async")

  def attachAfterRender[Model](
    id: String
  )(
    hook: Model => LiveIO[LiveView.UpdateContext, Model]
  ): Task[Unit] =
    delegate.attachAfterRender(id)(hook)

  def detachAfterRender(id: String): Task[Unit] =
    delegate.detachAfterRender(id)

  private[scalive] def runEvent[Msg, Model](
    model: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventResult[Model]] =
    delegate.runEvent(model, message, event, ctx)

  private[scalive] def runParams[Model](
    model: Model,
    url: URL,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    val _ = (url, ctx)
    ZIO.succeed(LiveHookResult.Continue(model))

  private[scalive] def runInfo[Msg, Model](
    model: Model,
    message: Msg,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    val _ = (message, ctx)
    ZIO.succeed(LiveHookResult.Continue(model))

  private[scalive] def runAsync[Msg, Model](
    model: Model,
    message: Msg,
    event: LiveAsyncEvent,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    val _ = (message, event, ctx)
    ZIO.succeed(LiveHookResult.Continue(model))

  private[scalive] def runAfterRender[Model](model: Model, ctx: LiveContext): Task[Model] =
    delegate.runAfterRender(model, ctx)

  private def unsupported[A](stage: String): Task[A] =
    ZIO.fail(
      new IllegalArgumentException(
        s"$stage hooks are not supported on LiveComponents"
      )
    )
end ComponentLiveHookRuntime
