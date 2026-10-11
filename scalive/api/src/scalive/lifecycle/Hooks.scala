package scalive

import zio.Task
import zio.http.URL
import zio.json.JsonDecoder
import zio.json.ast.Json

/** What a typed lifecycle hook decides: go on with a model, or stop processing.
  *
  * Event, params, info, and async hooks return it. A failed hook effect is not a halt: it aborts
  * the lifecycle. See [continue, halt, and
  * reply](https://scalive.dev/guides/lifecycle-hooks#continue-halt-reply).
  */
enum LiveHookResult[+Model]:
  /** Processing goes on: the next hook of the stage, then the normal callback, receives `model`.
    *
    * @param model
    *   The model passed to the next hook or callback.
    */
  case Continue(model: Model)

  /** Processing stops: later hooks and the normal callback are skipped, and `model` is rendered.
    *
    * @param model
    *   The model to commit and render.
    */
  case Halt(model: Model)

/** Constructors for [[LiveHookResult]] values. */
object LiveHookResult:
  /** Returns a result that passes `model` to the next hook and then to the normal callback. */
  def cont[Model](model: Model): LiveHookResult[Model] = Continue(model)

  /** Returns a result that skips later hooks and the normal callback, and renders `model`. */
  def halt[Model](model: Model): LiveHookResult[Model] = Halt(model)

/** What a raw event hook decides: go on with a model, or stop with an optional reply.
  *
  * Like [[LiveHookResult]], except that a halt can also send JSON back to the browser as the
  * event's reply.
  */
enum LiveEventHookResult[+Model]:
  /** Processing goes on: the next raw hook, then the rest of event handling, receives `model`.
    *
    * @param model
    *   The model passed to the next hook or to the rest of event handling.
    */
  case Continue(model: Model)

  /** Processing stops: later hooks and the rest of event handling are skipped, and `model` is
    * rendered.
    *
    * @param model
    *   The model to commit and render.
    * @param reply
    *   The JSON sent back to the browser as the event's reply, or `None` for no reply.
    */
  case Halt(model: Model, reply: Option[Json] = None)

/** Constructors for [[LiveEventHookResult]] values. */
object LiveEventHookResult:
  /** Returns a result that passes `model` to the next raw hook and then to the rest of event
    * handling.
    */
  def cont[Model](model: Model): LiveEventHookResult[Model] = Continue(model)

  /** Returns a result that stops event handling and renders `model`, with no reply. */
  def halt[Model](model: Model): LiveEventHookResult[Model] = Halt(model)

  /** Returns a result that stops event handling, renders `model`, and replies with `value`. */
  def haltReply[Model](model: Model, value: Json): LiveEventHookResult[Model] =
    Halt(model, Some(value))

/** Immutable declarations installed when a root lifecycle starts. */
sealed trait LiveHooks[Msg, Model]:
  /** Adds a hook that runs on each client event before binding lookup. */
  final def onRawEvent(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => Task[LiveEventHookResult[Model]]
  ): LiveHooks[Msg, Model] = LiveHooks.RawEvent(this, hook)

  /** Adds a handler for the browser event `event` pushed to the view. */
  final def onBrowserEvent[A: JsonDecoder](
    event: BrowserToServerEvent[A]
  )(
    handler: (Model, A, MessageContext[Msg, Model]) => Task[Model]
  ): LiveHooks[Msg, Model] =
    LiveHooks.BrowserEvent(this, event, handler, summon[JsonDecoder[A]])

  /** Adds a hook that runs when a binding sends the view a message, before `handleMessage`. */
  final def onEvent(
    hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = LiveHooks.Event(this, hook)

  /** Adds a hook that runs with the new URL before `handleParams`.
    *
    * Every LiveView runs it after mount and each time its URL changes; a nested LiveView sees the
    * URL of its page. A [[LiveView.Routed]] view skips it when the URL does not decode into its
    * parameters.
    */
  final def onParams(
    hook: (Model, URL, ParamsContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = LiveHooks.Params(this, hook)

  /** Adds a hook that runs before `handleMessage` for each message that does not come from a
    * binding, such as a subscription value.
    */
  final def onInfo(
    hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = LiveHooks.Info(this, hook)

  /** Adds a hook that runs when a task the view started completes, before its message reaches
    * `handleMessage`.
    */
  final def onAsync(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => Task[
      LiveHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] = LiveHooks.Async(this, hook)

  /** Adds a hook that runs after each render of the view and its components. */
  final def afterRender(
    hook: (Model, AfterRenderContext[Msg, Model]) => Task[Unit]
  ): LiveHooks[Msg, Model] = LiveHooks.AfterRender(this, hook)
end LiveHooks

/** Constructors for [[LiveHooks]] declarations. */
object LiveHooks:
  final private[scalive] case class Empty[Msg, Model]() extends LiveHooks[Msg, Model]
  final private[scalive] case class RawEvent[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => Task[LiveEventHookResult[Model]])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class BrowserEvent[Msg, Model, A](
    previous: LiveHooks[Msg, Model],
    event: BrowserToServerEvent[A],
    handler: (Model, A, MessageContext[Msg, Model]) => Task[Model],
    decoder: JsonDecoder[A])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class Event[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class Params[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, URL, ParamsContext[Msg, Model]) => Task[LiveHookResult[Model]])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class Info[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class Async[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => Task[
      LiveHookResult[Model]
    ])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class AfterRender[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, AfterRenderContext[Msg, Model]) => Task[Unit])
      extends LiveHooks[Msg, Model]

  /** Returns declarations with no hooks, to chain stage calls such as `onEvent` onto. */
  def empty[Msg, Model]: LiveHooks[Msg, Model] = Empty()

  /** Adds a hook that runs on each client event before binding lookup.
    *
    * This form starts the declarations with this hook, like `empty.onRawEvent`.
    */
  def onRawEvent[Msg, Model](
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => Task[LiveEventHookResult[Model]]
  ): LiveHooks[Msg, Model] = empty.onRawEvent(hook)

  /** Adds a handler for the browser event `event` pushed to the view.
    *
    * This form starts the declarations with this hook, like `empty.onBrowserEvent`.
    */
  def onBrowserEvent[Msg, Model, A: JsonDecoder](
    event: BrowserToServerEvent[A]
  )(
    handler: (Model, A, MessageContext[Msg, Model]) => Task[Model]
  ): LiveHooks[Msg, Model] = empty.onBrowserEvent(event)(handler)

  /** Adds a hook that runs when a binding sends the view a message, before `handleMessage`.
    *
    * This form starts the declarations with this hook, like `empty.onEvent`.
    */
  def onEvent[Msg, Model](
    hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = empty.onEvent(hook)

  /** Adds a hook that runs with the new URL before `handleParams`.
    *
    * This form starts the declarations with this hook, like `empty.onParams`. Every LiveView runs
    * it after mount and each time its URL changes.
    */
  def onParams[Msg, Model](
    hook: (Model, URL, ParamsContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = empty.onParams(hook)

  /** Adds a hook that runs before `handleMessage` for each message that does not come from a
    * binding, such as a subscription value.
    *
    * This form starts the declarations with this hook, like `empty.onInfo`.
    */
  def onInfo[Msg, Model](
    hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = empty.onInfo(hook)

  /** Adds a hook that runs when a task the view started completes, before its message reaches
    * `handleMessage`.
    *
    * This form starts the declarations with this hook, like `empty.onAsync`.
    */
  def onAsync[Msg, Model](
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => Task[
      LiveHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] = empty.onAsync(hook)

  /** Adds a hook that runs after each render of the view and its components.
    *
    * This form starts the declarations with this hook, like `empty.afterRender`.
    */
  def afterRender[Msg, Model](
    hook: (Model, AfterRenderContext[Msg, Model]) => Task[Unit]
  ): LiveHooks[Msg, Model] = empty.afterRender(hook)
end LiveHooks

/** Immutable declarations installed when a component instance starts. */
sealed trait ComponentLiveHooks[Props, Msg, Model]:
  /** Adds a hook that runs on each client event routed to the component. */
  final def onRawEvent(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveEventHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.RawEvent(this, hook)

  /** Adds a handler for the browser event `event` pushed to the component. */
  final def onBrowserEvent[A: JsonDecoder](
    event: BrowserToServerEvent[A]
  )(
    handler: (Props, Model, A, ComponentMessageContext[Props, Msg, Model]) => Task[Model]
  ): ComponentLiveHooks[Props, Msg, Model] =
    ComponentLiveHooks.BrowserEvent(this, event, handler, summon[JsonDecoder[A]])

  /** Adds a hook that runs for each message sent to the component, before `handleMessage`. */
  final def onEvent(
    hook: (Props, Model, Msg, ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.Event(this, hook)

  /** Adds a hook that runs when a task the component started completes, before its message reaches
    * `handleMessage`.
    */
  final def onAsync(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.Async(this, hook)

  /** Adds a hook that runs after each render of the component. */
  final def afterRender(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => Task[Unit]
  ): ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.AfterRender(this, hook)
end ComponentLiveHooks

/** Constructors for [[ComponentLiveHooks]] declarations. */
object ComponentLiveHooks:
  final private[scalive] case class Empty[Props, Msg, Model]()
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class RawEvent[Props, Msg, Model](
    previous: ComponentLiveHooks[Props, Msg, Model],
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveEventHookResult[Model]
    ])
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class BrowserEvent[Props, Msg, Model, A](
    previous: ComponentLiveHooks[Props, Msg, Model],
    event: BrowserToServerEvent[A],
    handler: (Props, Model, A, ComponentMessageContext[Props, Msg, Model]) => Task[Model],
    decoder: JsonDecoder[A])
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class Event[Props, Msg, Model](
    previous: ComponentLiveHooks[Props, Msg, Model],
    hook: (Props, Model, Msg, ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveHookResult[Model]
    ])
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class Async[Props, Msg, Model](
    previous: ComponentLiveHooks[Props, Msg, Model],
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveHookResult[Model]
    ])
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class AfterRender[Props, Msg, Model](
    previous: ComponentLiveHooks[Props, Msg, Model],
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => Task[Unit])
      extends ComponentLiveHooks[Props, Msg, Model]

  /** Returns declarations with no hooks, to chain stage calls such as `onEvent` onto. */
  def empty[Props, Msg, Model]: ComponentLiveHooks[Props, Msg, Model] = Empty()

  /** Adds a hook that runs on each client event routed to the component.
    *
    * This form starts the declarations with this hook, like `empty.onRawEvent`.
    */
  def onRawEvent[Props, Msg, Model](
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveEventHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] = empty.onRawEvent(hook)

  /** Adds a handler for the browser event `event` pushed to the component.
    *
    * This form starts the declarations with this hook, like `empty.onBrowserEvent`.
    */
  def onBrowserEvent[Props, Msg, Model, A: JsonDecoder](
    event: BrowserToServerEvent[A]
  )(
    handler: (Props, Model, A, ComponentMessageContext[Props, Msg, Model]) => Task[Model]
  ): ComponentLiveHooks[Props, Msg, Model] = empty.onBrowserEvent(event)(handler)

  /** Adds a hook that runs for each message sent to the component, before `handleMessage`.
    *
    * This form starts the declarations with this hook, like `empty.onEvent`.
    */
  def onEvent[Props, Msg, Model](
    hook: (Props, Model, Msg, ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] = empty.onEvent(hook)

  /** Adds a hook that runs when a task the component started completes, before its message reaches
    * `handleMessage`.
    *
    * This form starts the declarations with this hook, like `empty.onAsync`.
    */
  def onAsync[Props, Msg, Model](
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] = empty.onAsync(hook)

  /** Adds a hook that runs after each render of the component.
    *
    * This form starts the declarations with this hook, like `empty.afterRender`.
    */
  def afterRender[Props, Msg, Model](
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => Task[Unit]
  ): ComponentLiveHooks[Props, Msg, Model] = empty.afterRender(hook)
end ComponentLiveHooks

/** The dynamic hooks of one LiveView lifecycle, with one registry per stage.
  *
  * Reach it as `ctx.hooks` in mount, message, params, and after-render contexts. In each stage,
  * dynamic hooks run after the view's static `hooks`, in attachment order, and stay until detached
  * or until the lifecycle ends. A change applies from the next dispatch of its stage. The
  * disconnected render has its own registry, which the connected lifecycle does not inherit. See
  * [dynamic hooks](https://scalive.dev/guides/lifecycle-hooks#dynamic-hooks).
  */
trait RootHooks[Msg, Model]:
  /** Attaches and detaches raw event hooks, which see each client event before binding lookup. */
  def rawEvent: RootRawEventHooks[Msg, Model]

  /** Attaches and detaches handlers for named events pushed from browser JavaScript. */
  def browserEvent: RootBrowserEventHooks[Msg, Model]

  /** Attaches and detaches hooks that run when a binding sends the view a message. */
  def event: RootEventHooks[Msg, Model]

  /** Attaches and detaches hooks that run when a routed view receives new URL parameters. */
  def params: RootParamsHooks[Msg, Model]

  /** Attaches and detaches hooks that run for messages that do not come from a binding. */
  def info: RootInfoHooks[Msg, Model]

  /** Attaches and detaches hooks that run when a task the view started completes. */
  def async: RootAsyncHooks[Msg, Model]

  /** Attaches and detaches hooks that run after each render. */
  def afterRender: RootAfterRenderHooks[Msg, Model]

/** The dynamic raw event hooks of one LiveView lifecycle. */
trait RootRawEventHooks[Msg, Model]:
  /** Attaches `hook` under `id`, to run on each client event before binding lookup.
    *
    * The hook receives the event envelope as a [[LiveEvent]], including events targeted at a
    * component. A halt stops all processing of the event, by the view and by its components.
    * Attaching an `id` that is already attached replaces that hook in place.
    */
  def attach(
    id: String
  )(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => Task[LiveEventHookResult[Model]]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic named browser event handlers of one LiveView lifecycle. */
trait RootBrowserEventHooks[Msg, Model]:
  /** Attaches `hook` under `id`, to handle the browser event `event` pushed to the view.
    *
    * The hook runs after the raw event hooks continue and receives the payload decoded as `A`. It
    * consumes the event, so bindings and `handleMessage` never see it. A payload that does not
    * decode is logged and consumed without changing the model. Events targeted at a component are
    * ignored. Attaching an `id` that is already attached replaces that handler in place.
    */
  def attach[A: JsonDecoder](
    id: String,
    event: BrowserToServerEvent[A]
  )(
    hook: (Model, A, MessageContext[Msg, Model]) => Task[Model]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic event hooks of one LiveView lifecycle. */
trait RootEventHooks[Msg, Model]:
  /** Attaches `hook` under `id`, to run when a binding sends the view a message, before
    * `handleMessage`.
    *
    * A halt skips later hooks and `handleMessage`. Attaching an `id` that is already attached
    * replaces that hook in place.
    */
  def attach(
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic params hooks of one LiveView lifecycle. */
trait RootParamsHooks[Msg, Model]:
  /** Attaches `hook` under `id`, to run with the new URL before `handleParams`.
    *
    * Every LiveView runs these hooks after mount and each time its URL changes; a nested LiveView
    * sees the URL of its page. A [[LiveView.Routed]] view skips them when the URL does not decode
    * into its parameters. A halt skips later hooks and `handleParams`. Attaching an `id` that is
    * already attached replaces that hook in place.
    */
  def attach(
    id: String
  )(
    hook: (Model, URL, ParamsContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic info hooks of one LiveView lifecycle. */
trait RootInfoHooks[Msg, Model]:
  /** Attaches `hook` under `id`, to run before `handleMessage` for each message that does not come
    * from a binding, such as a subscription value.
    *
    * A halt skips later hooks and `handleMessage`. Attaching an `id` that is already attached
    * replaces that hook in place.
    */
  def attach(
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic async hooks of one LiveView lifecycle. */
trait RootAsyncHooks[Msg, Model]:
  /** Attaches `hook` under `id`, to run when a task the view started completes, before its message
    * reaches `handleMessage`.
    *
    * The hook receives a [[LiveAsyncEvent]] with the task's key, outcome, and message. A halt skips
    * later hooks and `handleMessage`. Attaching an `id` that is already attached replaces that hook
    * in place.
    */
  def attach(
    id: String
  )(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic after-render hooks of one LiveView lifecycle. */
trait RootAfterRenderHooks[Msg, Model]:
  /** Attaches `hook` under `id`, to run after each render of the view and its components.
    *
    * Every hook receives the rendered model; it cannot replace the model or halt, and a failure
    * aborts the render. In a connected lifecycle it runs before the changes reach the browser.
    * Attaching an `id` that is already attached replaces that hook in place.
    */
  def attach(
    id: String
  )(
    hook: (Model, AfterRenderContext[Msg, Model]) => Task[Unit]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic hooks of one LiveComponent instance, with one registry per stage.
  *
  * Reach it as `ctx.hooks` in the component's mount, update, message, and after-render contexts. In
  * each stage, dynamic hooks run after the component's static `hooks`, in attachment order, and
  * stay until detached or until the instance is removed. A change applies from the next dispatch of
  * its stage. Hooks also receive the instance's current props. See [dynamic
  * hooks](https://scalive.dev/guides/lifecycle-hooks#dynamic-hooks).
  */
trait ComponentHooks[Props, Msg, Model]:
  /** Attaches and detaches raw event hooks, which see each event routed to this instance. */
  def rawEvent: ComponentRawEventHooks[Props, Msg, Model]

  /** Attaches and detaches handlers for named events pushed to this instance from JavaScript. */
  def browserEvent: ComponentBrowserEventHooks[Props, Msg, Model]

  /** Attaches and detaches hooks that run for each message sent to this instance. */
  def event: ComponentEventHooks[Props, Msg, Model]

  /** Attaches and detaches hooks that run when a task this instance started completes. */
  def async: ComponentAsyncHooks[Props, Msg, Model]

  /** Attaches and detaches hooks that run after each render of this instance. */
  def afterRender: ComponentAfterRenderHooks[Props, Msg, Model]

/** The dynamic raw event hooks of one LiveComponent instance. */
trait ComponentRawEventHooks[Props, Msg, Model]:
  /** Attaches `hook` under `id`, to run on each client event routed to this instance.
    *
    * The hook runs after the LiveView's raw event hooks continue, and before named browser event
    * handlers and event hooks. A halt stops the instance's processing of the event. Attaching an
    * `id` that is already attached replaces that hook in place.
    */
  def attach(
    id: String
  )(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveEventHookResult[Model]
    ]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic named browser event handlers of one LiveComponent instance. */
trait ComponentBrowserEventHooks[Props, Msg, Model]:
  /** Attaches `hook` under `id`, to handle the browser event `event` pushed to this instance.
    *
    * The hook runs after the raw event hooks continue and receives the payload decoded as `A`. It
    * consumes the event, so event hooks and `handleMessage` never see it. A payload that does not
    * decode is logged and consumed without changing the model. Attaching an `id` that is already
    * attached replaces that handler in place.
    */
  def attach[A: JsonDecoder](
    id: String,
    event: BrowserToServerEvent[A]
  )(
    hook: (Props, Model, A, ComponentMessageContext[Props, Msg, Model]) => Task[Model]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic event hooks of one LiveComponent instance. */
trait ComponentEventHooks[Props, Msg, Model]:
  /** Attaches `hook` under `id`, to run for each message sent to this instance, before
    * `handleMessage`.
    *
    * Messages from bindings and from the instance's subscriptions both pass through it. A halt
    * skips later hooks and `handleMessage`. Attaching an `id` that is already attached replaces
    * that hook in place.
    */
  def attach(
    id: String
  )(
    hook: (Props, Model, Msg, ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveHookResult[Model]
    ]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic async hooks of one LiveComponent instance. */
trait ComponentAsyncHooks[Props, Msg, Model]:
  /** Attaches `hook` under `id`, to run when a task this instance started completes, before its
    * message reaches `handleMessage`.
    *
    * The hook receives a [[LiveAsyncEvent]] with the task's key, outcome, and message. A halt skips
    * later hooks and `handleMessage`. Attaching an `id` that is already attached replaces that hook
    * in place.
    */
  def attach(
    id: String
  )(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => Task[
      LiveHookResult[Model]
    ]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]

/** The dynamic after-render hooks of one LiveComponent instance. */
trait ComponentAfterRenderHooks[Props, Msg, Model]:
  /** Attaches `hook` under `id`, to run after each render of this instance.
    *
    * Every hook receives the current props and the rendered model; it cannot replace the model or
    * halt, and a failure aborts the render. Attaching an `id` that is already attached replaces
    * that hook in place.
    */
  def attach(
    id: String
  )(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => Task[Unit]
  ): Task[Unit]

  /** Detaches the dynamic hook attached under `id`, if any. Static hooks cannot be detached. */
  def detach(id: String): Task[Unit]
