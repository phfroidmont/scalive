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
