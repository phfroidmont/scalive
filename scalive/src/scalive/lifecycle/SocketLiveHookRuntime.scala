package scalive

import zio.*
import zio.http.URL

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
