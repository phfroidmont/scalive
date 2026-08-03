package scalive

import zio.*
import zio.http.URL
import zio.json.*
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

  def onRawEvent(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(rawEventHooks = rawEventHooks :+ LiveHooks.RawEvent(hook))

  def onBrowserEvent[A: JsonDecoder](
    browserEvent: BrowserToServerEvent[A]
  )(
    handler: (Model, A, MessageContext[Msg, Model]) => LiveIO[Model]
  ): LiveHooks[Msg, Model] =
    onRawEvent { (model, event, ctx) =>
      if event.bindingId != browserEvent.value || event.cid.nonEmpty then
        ZIO.succeed(LiveEventHookResult.cont(model))
      else
        event.value.as[A] match
          case Right(payload) =>
            handler(model, payload, ctx).map(LiveEventHookResult.halt(_))
          case Left(error) =>
            ZIO
              .logWarning(
                s"Ignoring malformed browser event '${browserEvent.value}': $error"
              ).as(LiveEventHookResult.halt(model))
    }

  def onEvent(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    copy(eventHooks = eventHooks :+ LiveHooks.Event(hook))

  def onParams(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(paramsHooks = paramsHooks :+ LiveHooks.Params(hook))

  def onInfo(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(infoHooks = infoHooks :+ LiveHooks.Info(hook))

  def onAsync(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    copy(asyncHooks = asyncHooks :+ LiveHooks.Async(hook))

  def afterRender(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
  ): LiveHooks[Msg, Model] =
    copy(afterRenderHooks = afterRenderHooks :+ LiveHooks.AfterRender(hook))
end LiveHooks

object LiveHooks:
  final private[scalive] case class RawEvent[Msg, Model](
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]])

  final private[scalive] case class Event[Msg, Model](
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ])

  final private[scalive] case class Params[Msg, Model](
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]])

  final private[scalive] case class Info[Msg, Model](
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]])

  final private[scalive] case class Async[Msg, Model](
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ])

  final private[scalive] case class AfterRender[Msg, Model](
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit])

  def empty[Msg, Model]: LiveHooks[Msg, Model] =
    LiveHooks(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)

  def onEvent[Msg, Model](
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    empty[Msg, Model].onEvent(hook)

  def afterRender[Msg, Model](
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
  ): LiveHooks[Msg, Model] =
    empty[Msg, Model].afterRender(hook)
end LiveHooks

final case class ComponentLiveHooks[Props, Msg, Model] private[scalive] (
  private[scalive] val rawEventHooks: Vector[ComponentLiveHooks.RawEvent[Props, Msg, Model]],
  private[scalive] val eventHooks: Vector[ComponentLiveHooks.Event[Props, Msg, Model]],
  private[scalive] val asyncHooks: Vector[ComponentLiveHooks.Async[Props, Msg, Model]],
  private[scalive] val afterRenderHooks: Vector[
    ComponentLiveHooks.AfterRender[Props, Msg, Model]
  ]):

  def onRawEvent(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(rawEventHooks = rawEventHooks :+ ComponentLiveHooks.RawEvent(hook))

  def onBrowserEvent[A: JsonDecoder](
    browserEvent: BrowserToServerEvent[A]
  )(
    handler: (Props, Model, A, ComponentMessageContext[Props, Msg, Model]) => LiveIO[Model]
  ): ComponentLiveHooks[Props, Msg, Model] =
    onRawEvent { (props, model, event, ctx) =>
      if event.bindingId != browserEvent.value then ZIO.succeed(LiveEventHookResult.cont(model))
      else
        event.value.as[A] match
          case Right(payload) =>
            handler(props, model, payload, ctx).map(LiveEventHookResult.halt(_))
          case Left(error) =>
            ZIO
              .logWarning(
                s"Ignoring malformed browser event '${browserEvent.value}': $error"
              ).as(LiveEventHookResult.halt(model))
    }

  def onEvent(
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(eventHooks = eventHooks :+ ComponentLiveHooks.Event(hook))

  def onAsync(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(asyncHooks = asyncHooks :+ ComponentLiveHooks.Async(hook))

  def afterRender(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(afterRenderHooks = afterRenderHooks :+ ComponentLiveHooks.AfterRender(hook))
end ComponentLiveHooks

object ComponentLiveHooks:
  final private[scalive] case class RawEvent[Props, Msg, Model](
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ])

  final private[scalive] case class Event[Props, Msg, Model](
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ])

  final private[scalive] case class Async[Props, Msg, Model](
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ])

  final private[scalive] case class AfterRender[Props, Msg, Model](
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit])

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
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
  ): Task[Unit]

  def attachComponentAfterRender[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit]
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
  ): Task[Unit]

  private[scalive] def runComponentAfterRender[Props, Msg, Model](
    props: Props,
    model: Model,
    ctx: LiveContext
  ): Task[Unit]
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
      hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
    ): Task[Unit] =
      unavailable

    def attachComponentAfterRender[Props, Msg, Model](
      id: String
    )(
      hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit]
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

    private[scalive] def runAfterRender[Msg, Model](model: Model, ctx: LiveContext): Task[Unit] =
      ZIO.unit

    private[scalive] def runComponentAfterRender[Props, Msg, Model](
      props: Props,
      model: Model,
      ctx: LiveContext
    ): Task[Unit] =
      ZIO.unit
  end Disabled
end LiveHookRuntime
