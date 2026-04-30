package scalive

import zio.*
import zio.http.URL

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
