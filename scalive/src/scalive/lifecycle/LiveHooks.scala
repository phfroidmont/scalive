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

enum LiveHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model)

object LiveHookResult:
  def cont[Model](model: Model): LiveHookResult[Model] =
    LiveHookResult.Continue(model)

  def halt[Model](model: Model): LiveHookResult[Model] =
    LiveHookResult.Halt(model)

enum LiveEventHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[Json] = None)

object LiveEventHookResult:
  def cont[Model](model: Model): LiveEventHookResult[Model] =
    LiveEventHookResult.Continue(model)

  def halt[Model](model: Model): LiveEventHookResult[Model] =
    LiveEventHookResult.Halt(model, None)

  def haltReply[Model](model: Model, value: Json): LiveEventHookResult[Model] =
    LiveEventHookResult.Halt(model, Some(value))

final case class LiveHooks[Msg, Model] private[scalive] (
  private[scalive] val rawEventHooks: Vector[LiveHooks.RawEvent[Msg, Model]],
  private[scalive] val eventHooks: Vector[LiveHooks.Event[Msg, Model]],
  private[scalive] val paramsHooks: Vector[LiveHooks.Params[Msg, Model]],
  private[scalive] val infoHooks: Vector[LiveHooks.Info[Msg, Model]],
  private[scalive] val asyncHooks: Vector[LiveHooks.Async[Msg, Model]],
  private[scalive] val afterRenderHooks: Vector[LiveHooks.AfterRender[Msg, Model]]):

  def rawEvent(
    id: String
  )(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(rawEventHooks = rawEventHooks :+ LiveHooks.RawEvent(id, hook))

  def onRawEvent(
    id: String
  )(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    rawEvent(id)(hook)

  def event(
    id: String
  )(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    copy(eventHooks = eventHooks :+ LiveHooks.Event(id, hook))

  def onEvent(
    id: String
  )(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    event(id)(hook)

  def params(
    id: String
  )(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(paramsHooks = paramsHooks :+ LiveHooks.Params(id, hook))

  def onParams(
    id: String
  )(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    params(id)(hook)

  def info(
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(infoHooks = infoHooks :+ LiveHooks.Info(id, hook))

  def onInfo(
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    info(id)(hook)

  def async(
    id: String
  )(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    copy(asyncHooks = asyncHooks :+ LiveHooks.Async(id, hook))

  def onAsync(
    id: String
  )(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    async(id)(hook)

  def afterRender(
    id: String
  )(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Model]
  ): LiveHooks[Msg, Model] =
    copy(afterRenderHooks = afterRenderHooks :+ LiveHooks.AfterRender(id, hook))
end LiveHooks

object LiveHooks:
  final private[scalive] case class RawEvent[Msg, Model](
    id: String,
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]])

  final private[scalive] case class Event[Msg, Model](
    id: String,
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ])

  final private[scalive] case class Params[Msg, Model](
    id: String,
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]])

  final private[scalive] case class Info[Msg, Model](
    id: String,
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]])

  final private[scalive] case class Async[Msg, Model](
    id: String,
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ])

  final private[scalive] case class AfterRender[Msg, Model](
    id: String,
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Model])

  def empty[Msg, Model]: LiveHooks[Msg, Model] =
    LiveHooks(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)

  def onEvent[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    empty[Msg, Model].onEvent(id)(hook)

  def afterRender[Msg, Model](
    id: String
  )(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Model]
  ): LiveHooks[Msg, Model] =
    empty[Msg, Model].afterRender(id)(hook)
end LiveHooks

final case class ComponentLiveHooks[Props, Msg, Model] private[scalive] (
  private[scalive] val rawEventHooks: Vector[ComponentLiveHooks.RawEvent[Props, Msg, Model]],
  private[scalive] val eventHooks: Vector[ComponentLiveHooks.Event[Props, Msg, Model]],
  private[scalive] val asyncHooks: Vector[ComponentLiveHooks.Async[Props, Msg, Model]],
  private[scalive] val afterRenderHooks: Vector[
    ComponentLiveHooks.AfterRender[Props, Msg, Model]
  ]):

  def rawEvent(
    id: String
  )(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(rawEventHooks = rawEventHooks :+ ComponentLiveHooks.RawEvent(id, hook))

  def event(
    id: String
  )(
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(eventHooks = eventHooks :+ ComponentLiveHooks.Event(id, hook))

  def async(
    id: String
  )(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(asyncHooks = asyncHooks :+ ComponentLiveHooks.Async(id, hook))

  def afterRender(
    id: String
  )(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Model]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(afterRenderHooks = afterRenderHooks :+ ComponentLiveHooks.AfterRender(id, hook))
end ComponentLiveHooks

object ComponentLiveHooks:
  final private[scalive] case class RawEvent[Props, Msg, Model](
    id: String,
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ])

  final private[scalive] case class Event[Props, Msg, Model](
    id: String,
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ])

  final private[scalive] case class Async[Props, Msg, Model](
    id: String,
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ])

  final private[scalive] case class AfterRender[Props, Msg, Model](
    id: String,
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Model])

  def empty[Props, Msg, Model]: ComponentLiveHooks[Props, Msg, Model] =
    ComponentLiveHooks(Vector.empty, Vector.empty, Vector.empty, Vector.empty)

private[scalive] trait LiveHookRuntime:
  def attachRawEvent[Msg, Model](
    id: String
  )(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): Task[Unit]

  def attachComponentRawEvent[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): Task[Unit]

  def detachRawEvent(id: String): Task[Unit]

  def attachEvent[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): Task[Unit]

  def attachComponentEvent[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): Task[Unit]

  def detachEvent(id: String): Task[Unit]

  def attachParams[Msg, Model](
    id: String
  )(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): Task[Unit]

  def detachParams(id: String): Task[Unit]

  def attachInfo[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): Task[Unit]

  def detachInfo(id: String): Task[Unit]

  def attachAsync[Msg, Model](
    id: String
  )(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): Task[Unit]

  def attachComponentAsync[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): Task[Unit]

  def detachAsync(id: String): Task[Unit]

  def attachAfterRender[Msg, Model](
    id: String
  )(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Model]
  ): Task[Unit]

  def attachComponentAfterRender[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Model]
  ): Task[Unit]

  def detachAfterRender(id: String): Task[Unit]

  private[scalive] def runRawEvent[Msg, Model](
    model: Model,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]]

  private[scalive] def runComponentRawEvent[Props, Msg, Model](
    props: Props,
    model: Model,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]]

  private[scalive] def runEvent[Msg, Model](
    model: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]]

  private[scalive] def runComponentEvent[Props, Msg, Model](
    props: Props,
    model: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]]

  private[scalive] def runParams[Msg, Model](
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
    event: LiveAsyncEvent[Msg],
    ctx: LiveContext
  ): Task[LiveHookResult[Model]]

  private[scalive] def runComponentAsync[Props, Msg, Model](
    props: Props,
    model: Model,
    event: LiveAsyncEvent[Msg],
    ctx: LiveContext
  ): Task[LiveHookResult[Model]]

  private[scalive] def runAfterRender[Msg, Model](
    model: Model,
    ctx: LiveContext
  ): Task[Model]

  private[scalive] def runComponentAfterRender[Props, Msg, Model](
    props: Props,
    model: Model,
    ctx: LiveContext
  ): Task[Model]
end LiveHookRuntime

private[scalive] object LiveHookRuntime:
  object Disabled extends LiveHookRuntime:
    private def unavailable[A]: Task[A] =
      ZIO.fail(new IllegalStateException("lifecycle hooks are not available in this context"))

    def attachRawEvent[Msg, Model](
      id: String
    )(
      hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
    ): Task[Unit] =
      unavailable

    def attachComponentRawEvent[Props, Msg, Model](
      id: String
    )(
      hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): Task[Unit] =
      unavailable

    def detachRawEvent(id: String): Task[Unit] = ZIO.unit

    def attachEvent[Msg, Model](
      id: String
    )(
      hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): Task[Unit] =
      unavailable

    def attachComponentEvent[Props, Msg, Model](
      id: String
    )(
      hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): Task[Unit] =
      unavailable

    def detachEvent(id: String): Task[Unit] = ZIO.unit

    def attachParams[Msg, Model](
      id: String
    )(
      hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): Task[Unit] =
      unavailable

    def detachParams(id: String): Task[Unit] = ZIO.unit

    def attachInfo[Msg, Model](
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): Task[Unit] =
      unavailable

    def detachInfo(id: String): Task[Unit] = ZIO.unit

    def attachAsync[Msg, Model](
      id: String
    )(
      hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
        LiveHookResult[Model]
      ]
    ): Task[Unit] =
      unavailable

    def attachComponentAsync[Props, Msg, Model](
      id: String
    )(
      hook: (
        Props,
        Model,
        LiveAsyncEvent[Msg],
        ComponentMessageContext[Props, Msg, Model]
      ) => LiveIO[
        LiveHookResult[Model]
      ]
    ): Task[Unit] =
      unavailable

    def detachAsync(id: String): Task[Unit] = ZIO.unit

    def attachAfterRender[Msg, Model](
      id: String
    )(
      hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Model]
    ): Task[Unit] =
      unavailable

    def attachComponentAfterRender[Props, Msg, Model](
      id: String
    )(
      hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Model]
    ): Task[Unit] =
      unavailable

    def detachAfterRender(id: String): Task[Unit] = ZIO.unit

    private[scalive] def runRawEvent[Msg, Model](
      model: Model,
      event: LiveEvent,
      ctx: LiveContext
    ): Task[LiveEventHookResult[Model]] =
      ZIO.succeed(LiveEventHookResult.Continue(model))

    private[scalive] def runComponentRawEvent[Props, Msg, Model](
      props: Props,
      model: Model,
      event: LiveEvent,
      ctx: LiveContext
    ): Task[LiveEventHookResult[Model]] =
      ZIO.succeed(LiveEventHookResult.Continue(model))

    private[scalive] def runEvent[Msg, Model](
      model: Model,
      message: Msg,
      event: LiveEvent,
      ctx: LiveContext
    ): Task[LiveEventHookResult[Model]] =
      ZIO.succeed(LiveEventHookResult.Continue(model))

    private[scalive] def runComponentEvent[Props, Msg, Model](
      props: Props,
      model: Model,
      message: Msg,
      event: LiveEvent,
      ctx: LiveContext
    ): Task[LiveEventHookResult[Model]] =
      ZIO.succeed(LiveEventHookResult.Continue(model))

    private[scalive] def runParams[Msg, Model](
      model: Model,
      url: URL,
      ctx: LiveContext
    ): Task[LiveHookResult[Model]] =
      ZIO.succeed(LiveHookResult.Continue(model))

    private[scalive] def runInfo[Msg, Model](
      model: Model,
      message: Msg,
      ctx: LiveContext
    ): Task[LiveHookResult[Model]] =
      ZIO.succeed(LiveHookResult.Continue(model))

    private[scalive] def runAsync[Msg, Model](
      model: Model,
      event: LiveAsyncEvent[Msg],
      ctx: LiveContext
    ): Task[LiveHookResult[Model]] =
      ZIO.succeed(LiveHookResult.Continue(model))

    private[scalive] def runComponentAsync[Props, Msg, Model](
      props: Props,
      model: Model,
      event: LiveAsyncEvent[Msg],
      ctx: LiveContext
    ): Task[LiveHookResult[Model]] =
      ZIO.succeed(LiveHookResult.Continue(model))

    private[scalive] def runAfterRender[Msg, Model](model: Model, ctx: LiveContext): Task[Model] =
      ZIO.succeed(model)

    private[scalive] def runComponentAfterRender[Props, Msg, Model](
      props: Props,
      model: Model,
      ctx: LiveContext
    ): Task[Model] =
      ZIO.succeed(model)
  end Disabled
end LiveHookRuntime
