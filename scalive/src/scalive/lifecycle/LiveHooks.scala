package scalive

import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json

/** The client event envelope visible to lifecycle hooks.
  *
  * Raw event hooks receive this value instead of a typed message. Root raw hooks run before
  * rendered binding resolution; component raw hooks run after component targeting and before typed
  * component handling. Typed event hooks receive the same envelope alongside the resolved message.
  * This is distinct from [[BrowserToServerEvent]], whose payload is decoded directly from
  * [[value]].
  *
  * @param kind
  *   the client event kind, such as `click`, `form`, or `hook`
  * @param bindingId
  *   the rendered binding identifier or browser event name used to route the event
  * @param value
  *   the unmodified JSON value sent by the client
  * @param params
  *   the event parameters normalized as strings; form fields and event metadata are included, as
  *   are component and upload protocol values when present
  * @param cid
  *   the target component id, or `None` when the event targets the root LiveView
  * @param meta
  *   optional client event metadata in its original JSON representation
  */
final case class LiveEvent(
  kind: String,
  bindingId: String,
  value: Json,
  params: Map[String, String],
  cid: Option[Int],
  meta: Option[Json])

/** Runtime conversion support for [[LiveEvent]]. */
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

/** Controls whether a non-event lifecycle hook continues to the next stage.
  *
  * Hooks of one kind run in registration order. Each `Continue` model is passed to the next hook
  * and eventually to the lifecycle callback. `Halt` stops the remaining hooks and skips that
  * callback; its model becomes the lifecycle result and is rendered unless navigation was
  * requested.
  *
  * @tparam Model
  *   the state owned by the hooked LiveView or component
  */
enum LiveHookResult[+Model]:
  /** Continues with `model`, threading it through the remaining hooks.
    *
    * @param model
    *   the model supplied to the next hook or lifecycle callback
    */
  case Continue(model: Model)

  /** Stops this hook stage and uses `model` without invoking the lifecycle callback.
    *
    * @param model
    *   the model returned as the result of this lifecycle stage
    */
  case Halt(model: Model)

/** Constructors for [[LiveHookResult]]. */
object LiveHookResult:
  /** Creates a result that continues lifecycle processing with `model`.
    *
    * @param model
    *   the model supplied to the next hook or lifecycle callback
    */
  def cont[Model](model: Model): LiveHookResult[Model] =
    LiveHookResult.Continue(model)

  /** Creates a result that halts lifecycle processing with `model`.
    *
    * @param model
    *   the model returned as the result of this lifecycle stage
    */
  def halt[Model](model: Model): LiveHookResult[Model] =
    LiveHookResult.Halt(model)

/** Controls event hook processing and the optional browser reply.
  *
  * Raw and typed event hooks run in registration order and thread every `Continue` model forward. A
  * root raw continuation proceeds to binding resolution; a component raw continuation proceeds
  * within the targeted component. For a resolved message, typed event hooks then run before the
  * LiveView or component message handler. `Halt` consumes the event, stops the remaining event
  * lifecycle, and retains and renders its model. A reply, when present, is returned in the event
  * acknowledgement together with any resulting render diff.
  *
  * @tparam Model
  *   the state owned by the hooked LiveView or component
  */
enum LiveEventHookResult[+Model]:
  /** Continues event processing with `model`.
    *
    * @param model
    *   the model supplied to the next hook or event lifecycle stage
    */
  case Continue(model: Model)

  /** Consumes the event and optionally replies to the browser.
    *
    * @param model
    *   the model retained and rendered after the event is consumed
    * @param reply
    *   a JSON value for the event acknowledgement, or `None` for the normal empty or diff reply
    */
  case Halt(model: Model, reply: Option[Json] = None)

/** Constructors for [[LiveEventHookResult]]. */
object LiveEventHookResult:
  /** Creates an event result that continues processing with `model`.
    *
    * @param model
    *   the model supplied to the next hook or event lifecycle stage
    */
  def cont[Model](model: Model): LiveEventHookResult[Model] =
    LiveEventHookResult.Continue(model)

  /** Creates an event result that consumes the event without a custom reply.
    *
    * @param model
    *   the model retained and rendered after the event is consumed
    */
  def halt[Model](model: Model): LiveEventHookResult[Model] =
    LiveEventHookResult.Halt(model, None)

  /** Creates an event result that consumes the event and replies with `value`.
    *
    * The event acknowledgement also carries a render diff when `model` changes the rendered tree.
    *
    * @param model
    *   the model retained and rendered after the event is consumed
    * @param value
    *   the JSON value returned to the browser
    */
  def haltReply[Model](model: Model, value: Json): LiveEventHookResult[Model] =
    LiveEventHookResult.Halt(model, Some(value))

/** An immutable definition of static lifecycle hooks for a [[LiveView]].
  *
  * Fluent calls append hooks, so hooks of the same kind run in declaration order. Continued models
  * are threaded through that order. Static hooks are installed before each disconnected and
  * connected mount and cannot be detached. Hooks attached through [[RootHooks]] are dynamic
  * additions to the current lifecycle: they run after static hooks in attachment order and can be
  * detached by id. In a connected lifecycle, these additions are local to that socket.
  *
  * @tparam Msg
  *   the messages accepted by the LiveView
  * @tparam Model
  *   the state owned by the LiveView
  */
final case class LiveHooks[Msg, Model] private[scalive] (
  private[scalive] val rawEventHooks: Vector[LiveHooks.RawEvent[Msg, Model]],
  private[scalive] val eventHooks: Vector[LiveHooks.Event[Msg, Model]],
  private[scalive] val paramsHooks: Vector[LiveHooks.Params[Msg, Model]],
  private[scalive] val infoHooks: Vector[LiveHooks.Info[Msg, Model]],
  private[scalive] val asyncHooks: Vector[LiveHooks.Async[Msg, Model]],
  private[scalive] val afterRenderHooks: Vector[LiveHooks.AfterRender[Msg, Model]]):

  /** Appends a hook for raw client events.
    *
    * Raw hooks run before rendered binding lookup and message decoding. Root raw hooks can
    * therefore observe both root- and component-targeted envelopes; inspect [[LiveEvent.cid]] when
    * targeting matters. Return [[LiveEventHookResult.Continue]] to pass the resulting model to
    * later raw hooks and normal event routing, or `Halt` to consume the event before either root or
    * component handlers run.
    *
    * @param hook
    *   the hook to append
    */
  def onRawEvent(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(rawEventHooks = rawEventHooks :+ LiveHooks.RawEvent(hook))

  /** Appends a decoding hook for a named root browser event.
    *
    * This is a typed convenience over [[onRawEvent]], not a rendered-binding message hook. It only
    * matches an event whose [[LiveEvent.bindingId]] equals `browserEvent.value` and whose
    * [[LiveEvent.cid]] is empty. A matching value is decoded as `A` with its
    * [[zio.json.JsonDecoder]], passed to `handler`, and consumed with the returned model. A
    * malformed matching value is logged and also consumed with the model unchanged. Other events
    * continue normally.
    *
    * @param browserEvent
    *   the typed browser event name to match
    * @param handler
    *   the handler for a successfully decoded payload
    * @tparam A
    *   the decoded browser payload type
    */
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

  /** Appends a hook for root events resolved to a typed `Msg`.
    *
    * These hooks run after raw hooks and after the rendered binding has decoded its message, but
    * before [[LiveView.handleMessage]]. They only receive messages targeted at the root LiveView;
    * component-targeted messages use [[ComponentLiveHooks.onEvent]]. Returning `Halt` consumes the
    * event and skips the message handler.
    *
    * @param hook
    *   the hook to append
    */
  def onEvent(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    copy(eventHooks = eventHooks :+ LiveHooks.Event(hook))

  /** Appends a hook that runs before routed parameter handling.
    *
    * Parameter hooks run after the current URL has decoded successfully and before
    * [[LiveView.Routed.handleParams]]. This occurs after routed mount for the initial disconnected
    * and connected lifecycles, and after each connected live patch. Returning `Halt` skips
    * `handleParams`; decode failures do not invoke this hook.
    *
    * @param hook
    *   the hook to append; it receives the complete current URL
    */
  def onParams(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(paramsHooks = paramsHooks :+ LiveHooks.Params(hook))

  /** Appends a hook for a server message emitted by a subscription.
    *
    * Info hooks run before [[LiveView.handleMessage]]. Returning `Halt` retains and renders the
    * hook model without invoking the message handler. Async task completions use [[onAsync]]
    * instead.
    *
    * @param hook
    *   the hook to append
    */
  def onInfo(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] =
    copy(infoHooks = infoHooks :+ LiveHooks.Info(hook))

  /** Appends a hook for completion of an async task owned by the root LiveView.
    *
    * Async hooks receive the task key and its success, failure, or cancellation in a
    * [[LiveAsyncEvent]]. They run before the completion message is passed to
    * [[LiveView.handleMessage]]. Returning `Halt` skips that message handler. If mapping the task
    * result to `Msg` fails, the hook still observes a failed event, but no mapped message exists to
    * handle.
    *
    * @param hook
    *   the hook to append
    */
  def onAsync(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    copy(asyncHooks = asyncHooks :+ LiveHooks.Async(hook))

  /** Appends a hook that runs after each completed root render.
    *
    * This includes initial disconnected and connected renders and subsequent connected renders.
    * After-render hooks run in registration order with the same rendered model; they do not thread
    * a model and cannot halt or change the tree that was just rendered. Their deliberately limited
    * [[AfterRenderContext]] supports client effects and dynamic hook management, but not navigation
    * or the ordinary message-phase capabilities. Client events pushed here are included with the
    * current connected render.
    *
    * @param hook
    *   the side-effecting hook to append
    */
  def afterRender(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
  ): LiveHooks[Msg, Model] =
    copy(afterRenderHooks = afterRenderHooks :+ LiveHooks.AfterRender(hook))
end LiveHooks

/** Entry points for building static root [[LiveHooks]]. */
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

  /** Returns a hook definition containing no lifecycle hooks. */
  def empty[Msg, Model]: LiveHooks[Msg, Model] =
    LiveHooks(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)

  /** Creates a hook definition containing one typed root event hook.
    *
    * This is equivalent to `LiveHooks.empty[Msg, Model].onEvent(hook)`.
    *
    * @param hook
    *   the initial event hook
    */
  def onEvent[Msg, Model](
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] =
    empty[Msg, Model].onEvent(hook)

  /** Creates a hook definition containing one root after-render hook.
    *
    * This is equivalent to `LiveHooks.empty[Msg, Model].afterRender(hook)`.
    *
    * @param hook
    *   the initial after-render hook
    */
  def afterRender[Msg, Model](
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
  ): LiveHooks[Msg, Model] =
    empty[Msg, Model].afterRender(hook)
end LiveHooks

/** An immutable definition of static lifecycle hooks for a [[LiveComponent]].
  *
  * Each component instance installs these hooks when it is first rendered and retains their runtime
  * state while the instance remains mounted. Fluent calls append hooks, so hooks of the same kind
  * run in declaration order and thread continued models forward. Hooks attached through
  * [[ComponentHooks]] are dynamic additions for that instance; they run after static hooks in
  * attachment order and can be detached by id.
  *
  * @tparam Props
  *   the component properties supplied by its parent
  * @tparam Msg
  *   the messages accepted by the component
  * @tparam Model
  *   the state owned by the component instance
  */
final case class ComponentLiveHooks[Props, Msg, Model] private[scalive] (
  private[scalive] val rawEventHooks: Vector[ComponentLiveHooks.RawEvent[Props, Msg, Model]],
  private[scalive] val eventHooks: Vector[ComponentLiveHooks.Event[Props, Msg, Model]],
  private[scalive] val asyncHooks: Vector[ComponentLiveHooks.Async[Props, Msg, Model]],
  private[scalive] val afterRenderHooks: Vector[
    ComponentLiveHooks.AfterRender[Props, Msg, Model]
  ]):

  /** Appends a hook for raw events routed to this component instance.
    *
    * Component raw hooks run after root raw hooks and after the event has been routed to a
    * component instance. For rendered bindings, routing may already have decoded the message, but
    * the raw hook still runs before that message is exposed to typed hooks or
    * [[LiveComponent.handleMessage]]. It also handles component-targeted browser events that have
    * no rendered binding. Return `Continue` to proceed to later hooks and normal component event
    * handling, or `Halt` to consume the event. The hook receives the component's current props.
    *
    * @param hook
    *   the hook to append
    */
  def onRawEvent(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(rawEventHooks = rawEventHooks :+ ComponentLiveHooks.RawEvent(hook))

  /** Appends a decoding hook for a named browser event routed to this component instance.
    *
    * This is a typed convenience over [[onRawEvent]], not a rendered-binding message hook. A
    * matching [[LiveEvent.bindingId]] is decoded as `A` with its [[zio.json.JsonDecoder]], passed
    * to `handler`, and consumed with the returned model. A malformed matching value is logged and
    * also consumed with the model unchanged. Other events continue normally. Unlike the root
    * helper, component targeting has already been resolved before this hook runs.
    *
    * @param browserEvent
    *   the typed browser event name to match
    * @param handler
    *   the handler for a successfully decoded payload
    * @tparam A
    *   the decoded browser payload type
    */
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

  /** Appends a hook for component events resolved to a typed `Msg`.
    *
    * These hooks run after component raw hooks and rendered binding decoding, but before
    * [[LiveComponent.handleMessage]]. Returning `Halt` consumes the event and skips the component
    * message handler. The hook receives the component's current props.
    *
    * @param hook
    *   the hook to append
    */
  def onEvent(
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(eventHooks = eventHooks :+ ComponentLiveHooks.Event(hook))

  /** Appends a hook for completion of an async task owned by this component instance.
    *
    * Async hooks receive the current props and a [[LiveAsyncEvent]], and run before the mapped
    * completion message is passed to [[LiveComponent.handleMessage]]. Returning `Halt` skips that
    * message handler. If mapping the task result to `Msg` fails, the hook still observes a failed
    * event, but no mapped message exists to handle.
    *
    * @param hook
    *   the hook to append
    */
  def onAsync(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(asyncHooks = asyncHooks :+ ComponentLiveHooks.Async(hook))

  /** Appends a hook that runs after each completed render of this component instance.
    *
    * Component after-render hooks run in registration order with the same props and rendered model.
    * They do not thread a model and cannot halt or change the tree that was just rendered. Their
    * [[ComponentAfterRenderContext]] only exposes lifecycle metadata and dynamic hook management;
    * it does not expose navigation, client effects, or ordinary component message capabilities.
    *
    * @param hook
    *   the side-effecting hook to append
    */
  def afterRender(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit]
  ): ComponentLiveHooks[Props, Msg, Model] =
    copy(afterRenderHooks = afterRenderHooks :+ ComponentLiveHooks.AfterRender(hook))
end ComponentLiveHooks

/** Entry points for building static component [[ComponentLiveHooks]]. */
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

  /** Returns a component hook definition containing no lifecycle hooks. */
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
