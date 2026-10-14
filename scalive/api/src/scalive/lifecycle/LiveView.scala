package scalive

import zio.http.URL
import zio.{Task, ZIO}

/** Defines a server-rendered page with typed messages and model state.
  *
  * Scalive mounts a LiveView once for the disconnected HTTP render that produces the first HTML,
  * and again when the browser connects. Each mount builds its own model. Browser bindings, async
  * results, and subscription values then arrive as `Msg` values in [[handleMessage]], which returns
  * the next model, and [[view]] follows the model as it changes.
  *
  * Register a LiveView with a route, as in `live / "counter" -> CounterLiveView()`. Extend
  * [[LiveView.Routed LiveView.Routed]] instead when it reads path or query parameters. Start with
  * the [quick start](https://scalive.dev/learn/quick-start).
  *
  * @tparam Msg
  *   messages accepted by this view
  * @tparam Model
  *   immutable state owned by one lifecycle
  */
trait LiveView[Msg, Model]:
  /** The capabilities passed to [[mount]]. */
  type MountContext = scalive.MountContext[Msg, Model]

  /** The capabilities passed to [[handleMessage]]. */
  type MessageContext = scalive.MessageContext[Msg, Model]

  /** The capabilities passed to after-render hooks. */
  type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

  /** Static lifecycle hooks shared by every instance of this view. Defaults to none.
    *
    * Prefer [[handleMessage]]; use hooks for behavior that must intercept or observe events,
    * messages, or renders before or after it. See the [lifecycle hooks
    * guide](https://scalive.dev/guides/lifecycle-hooks).
    */
  def hooks: LiveHooks[Msg, Model] = LiveHooks.empty

  /** Returns the document title for `model`, or `None` to use the root layout's default.
    *
    * The root layout renders it with `liveTitle` in the first response, and Scalive sends later
    * changes to the browser. Only the root LiveView of a page sets the title.
    */
  def pageTitle(model: Model): Option[String] = None

  /** Builds the initial model.
    *
    * Scalive calls it for the disconnected HTTP render and again for each connected mount,
    * including after a reconnect, so it must not rely on an earlier mount. Match `ctx.connection`
    * and start subscriptions or async work only in its `Connection.Connected` branch. If the effect
    * fails, the HTTP request answers with status 500 and a connected join fails.
    *
    * See [two independent mounts](https://scalive.dev/learn/lifecycle-and-connection-behavior).
    */
  def mount(ctx: MountContext): Task[Model]

  /** Handles a message against the current immutable model.
    *
    * The returned function keeps message dispatch typed while sharing the model and connected
    * capabilities captured for this lifecycle turn. Scalive commits the returned model, and renders
    * it, only after the effect and the render succeed. A failed effect crashes the connected
    * lifecycle, so recover failures the user can act on into the model.
    *
    * @param model
    *   current model
    * @param ctx
    *   capabilities available while handling the message
    * @return
    *   a handler for one message
    */
  def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model]

  /** Describes the HTML for every model this lifecycle will have.
    *
    * Scalive calls `view` once per lifecycle and keeps the result. `model` is a [[Signal]] that
    * follows each committed model, so derive values with `model.map(...)` and render conditional
    * content with `when`, `option`, or `choose` rather than reading the model directly. See
    * [rendering](https://scalive.dev/learn/rendering-and-dom-updates).
    */
  def view(model: Signal[Model]): HtmlElement[Msg]
end LiveView

/** Variants of [[LiveView]]: eventless and routed views. */
object LiveView:
  /** A LiveView that renders no bindings that send it messages.
    *
    * It needs no message type or [[LiveView.handleMessage]]. Extend [[LiveView]] instead as soon as
    * the view handles a click, form, async result, or subscription.
    */
  trait Eventless[Model] extends LiveView[Nothing, Model]:
    /** Returns a handler that is never called, because an eventless view gets no messages. */
    final def handleMessage(model: Model, ctx: MessageContext): Nothing => Task[Model] =
      _ => ZIO.succeed(model)

  /** A LiveView whose route decodes parameters from the path and query.
    *
    * Register it on a route that declares its parameters with `query`, `queryOptional`, or
    * `params`. Scalive passes the decoded `Params` to [[mount]], and to [[handleParams]] after each
    * live patch. A routed definition is deliberately not an unrouted [[LiveView]]. See the [routes
    * guide](https://scalive.dev/guides/routes-and-navigation#mount-and-react-to-parameters).
    */
  trait Routed[Msg, Model, Params]:
    /** The capabilities passed to [[mount]]. */
    type MountContext = scalive.MountContext[Msg, Model]

    /** The capabilities passed to [[handleMessage]]. */
    type MessageContext = scalive.RoutedMessageContext[Msg, Model, Params]

    /** The capabilities passed to [[handleParams]]. */
    type ParamsContext = scalive.RoutedParamsContext[Msg, Model, Params]

    /** The capabilities passed to after-render hooks. */
    type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

    /** Static lifecycle hooks shared by every instance of this view. Defaults to none. */
    def hooks: LiveHooks[Msg, Model] = LiveHooks.empty

    /** Returns the document title for `model`, or `None` to use the root layout's default. */
    def pageTitle(model: Model): Option[String] = None

    /** Mounts this routed view from decoded route parameters and lifecycle capabilities.
      *
      * Disconnected HTTP rendering and connected websocket admission invoke this method
      * independently. After mount, Scalive also calls [[handleParams]] with the same parameters.
      */
    def mount(params: Params, ctx: MountContext): Task[Model]

    /** Handles a message against the current immutable model, like [[LiveView.handleMessage]]. */
    def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model]

    /** Updates the model for parameters decoded from the current URL.
      *
      * Scalive calls it after mount and after each live patch to this route, such as a
      * `link.pushPatch` or `ctx.nav.pushPatch`. Derive parameter-dependent state here so mounts,
      * patches, and the browser's Back button agree. Defaults to keeping the model.
      */
    def handleParams(
      model: Model,
      params: Params,
      url: URL,
      ctx: ParamsContext
    ): Task[Model] = ZIO.succeed(model)

    /** Handles a live patch whose URL does not decode into `Params`.
      *
      * Defaults to failing with `error`, which crashes the lifecycle. Override it to keep the model
      * or patch to a valid URL instead.
      */
    def handleParamsDecodeError(
      model: Model,
      error: LiveParamsCodec.DecodeError,
      url: URL,
      ctx: ParamsContext
    ): Task[Model] = ZIO.fail(error)

    /** Describes the HTML for every model this lifecycle will have, like [[LiveView.view]]. */
    def view(model: Signal[Model]): HtmlElement[Msg]
  end Routed

  /** Variants of [[LiveView.Routed]]. */
  object Routed:
    /** A routed LiveView that renders no bindings that send it messages. */
    trait Eventless[Model, Params] extends Routed[Nothing, Model, Params]:
      /** Returns a handler that is never called, because an eventless view gets no messages. */
      final def handleMessage(model: Model, ctx: MessageContext): Nothing => Task[Model] =
        _ => ZIO.succeed(model)
end LiveView
