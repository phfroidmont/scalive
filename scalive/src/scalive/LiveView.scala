package scalive

import zio.ZIO
import zio.http.URL

/** Defines a server-rendered view with typed messages and model state.
  *
  * A LiveView is mounted independently for the disconnected HTTP render and the connected socket
  * lifecycle. It renders its model as an [[HtmlElement]] whose event bindings can emit `Msg` values
  * back to [[handleMessage]].
  *
  * @tparam Msg
  *   the messages this LiveView can receive
  * @tparam Model
  *   the state owned by this LiveView
  */
trait LiveView[Msg, Model]:
  /** Context available while mounting this LiveView. */
  type MountContext = scalive.MountContext[Msg, Model]

  /** Context available while handling a message for this LiveView. */
  type MessageContext = scalive.MessageContext[Msg, Model]

  /** Context available to this LiveView's after-render hooks. */
  type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

  /** Returns the static lifecycle hooks installed for this LiveView.
    *
    * Hooks are installed before mounting each disconnected or connected lifecycle. Override this
    * method to attach hooks with [[LiveHooks]]; the default contains no hooks.
    */
  def hooks: LiveHooks[Msg, Model] = LiveHooks.empty

  /** Returns the document title derived from the current model.
    *
    * The root LiveView supplies this value to the root layout during disconnected rendering and
    * updates the browser title after connected renders. `None`, empty titles, and whitespace-only
    * titles use the root layout's fallback. Nested LiveViews do not own the document title.
    *
    * @param model
    *   the model being rendered
    */
  def pageTitle(model: Model): Option[String] = None

  /** Creates the initial model for this LiveView.
    *
    * This method runs once for the disconnected HTTP render and again whenever a socket joins or
    * rejoins. Use `ctx.connected` to distinguish those phases.
    *
    * @param ctx
    *   the mount-phase capabilities and connection metadata
    * @return
    *   an effect producing the initial model
    */
  def mount(ctx: MountContext): LiveIO[Model]

  /** Returns the handler for a message received by the connected LiveView.
    *
    * Messages may originate from rendered event bindings, async operations, or subscriptions. The
    * successful result becomes the next model and is rendered unless the lifecycle requests
    * navigation.
    *
    * @param model
    *   the current model
    * @param ctx
    *   the message-phase capabilities and connection metadata
    * @return
    *   an effectful handler that produces the next model
    */
  def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model]

  /** Constructs this LiveView's signal-backed view graph from its read-only model signal.
    *
    * The runtime invokes this method once for each disconnected request and connected socket graph
    * lifetime. The `Msg` type of the returned tree restricts its server event bindings to messages
    * accepted by this LiveView.
    *
    * @param model
    *   the read-only signal containing the current model
    */
  def view(model: Signal[Model]): HtmlElement[Msg]
end LiveView

/** Variants of [[LiveView]] for eventless views and views with typed route parameters. */
object LiveView:
  /** Defines a LiveView that cannot receive server messages.
    *
    * The message type is fixed to `Nothing`, which supplies the unreachable message handler and
    * prevents server event bindings from being rendered. Implementations only need to define
    * [[LiveView.mount mount]] and [[LiveView.view view]].
    *
    * @tparam Model
    *   the state owned by this LiveView
    */
  trait Eventless[Model] extends LiveView[Nothing, Model]:
    /** Returns the unreachable message handler for this eventless LiveView.
      *
      * This implementation is final because no value of `Nothing` can be delivered. Application
      * code does not need to call or override it.
      */
    final def handleMessage(model: Model, ctx: MessageContext): Nothing => LiveIO[Model] =
      _ => ZIO.succeed(model)

  /** Defines a LiveView mounted from typed route parameters.
    *
    * A routed LiveView must be attached to a parameterized Live route. The route decodes its path
    * and query parameters before [[mount]], then runs [[handleParams]] after mounting and after
    * each connected live patch.
    *
    * @tparam Msg
    *   the messages this LiveView can receive
    * @tparam Model
    *   the state owned by this LiveView
    * @tparam Params
    *   the route's decoded parameter type
    */
  trait Routed[Msg, Model, Params] extends LiveView[Msg, Model]:
    /** Context available while handling route parameters for this LiveView. */
    type ParamsContext = scalive.ParamsContext[Msg, Model]

    /** Creates the initial model for this routed LiveView.
      *
      * This inherited overload is a safeguard against mounting a routed LiveView through a route
      * without a parameter decoder. Parameterized routes invoke `mount(params, ctx)` instead.
      * Calling this overload produces a defect.
      *
      * @param ctx
      *   the mount-phase context
      * @return
      *   an effect that terminates because typed parameters are unavailable
      */
    final def mount(ctx: MountContext): LiveIO[Model] =
      ZIO.dieMessage("Routed LiveViews must be mounted through a parameterized Live route")

    /** Creates the initial model for this routed LiveView.
      *
      * The route decodes `params` before invoking this method. Like an ordinary LiveView mount, it
      * runs independently for the disconnected HTTP render and each connected socket join. Initial
      * decode failures occur before this method because no model exists yet.
      *
      * @param params
      *   the parameters decoded from the initial URL
      * @param ctx
      *   the mount-phase capabilities and connection metadata
      * @return
      *   an effect producing the initial model
      */
    def mount(params: Params, ctx: MountContext): LiveIO[Model]

    /** Handles successfully decoded parameters for the current URL.
      *
      * This method runs after the parameterized mount and whenever a connected live patch changes
      * the URL. Parameter hooks run first. The default implementation leaves the model unchanged.
      *
      * @param model
      *   the current model
      * @param params
      *   the parameters decoded from `url`
      * @param url
      *   the complete current URL
      * @param ctx
      *   the parameter-phase capabilities and connection metadata
      * @return
      *   an effect producing the next model
      */
    def handleParams(
      model: Model,
      params: Params,
      url: URL,
      ctx: ParamsContext
    ): LiveIO[Model] =
      ZIO.succeed(model)

    /** Recovers a route parameter decoding failure when a model is available.
      *
      * This method runs when a URL change cannot be decoded or when [[handleParams]] fails with a
      * [[LiveParamsCodec.DecodeError]]. Initial decode failures occur before mount and cannot be
      * recovered here. The default implementation re-fails with `error`.
      *
      * @param model
      *   the current model
      * @param error
      *   the parameter decoding failure
      * @param url
      *   the URL that could not be handled
      * @param ctx
      *   the parameter-phase capabilities and connection metadata
      * @return
      *   an effect producing a recovered model
      */
    def handleParamsDecodeError(
      model: Model,
      error: LiveParamsCodec.DecodeError,
      url: URL,
      ctx: ParamsContext
    ): LiveIO[Model] =
      ZIO.fail(error)
  end Routed

  /** Variants of [[LiveView.Routed]] for routed views without server messages. */
  object Routed:
    /** Defines a routed LiveView that cannot receive server messages.
      *
      * This combines typed route parameters with the `Nothing` message type from
      * [[LiveView.Eventless]]. Implementations define the parameterized mount and view methods, and
      * may override [[LiveView.Routed.handleParams handleParams]].
      *
      * @tparam Model
      *   the state owned by this LiveView
      * @tparam Params
      *   the route's decoded parameter type
      */
    trait Eventless[Model, Params]
        extends LiveView.Eventless[Model],
          LiveView.Routed[Nothing, Model, Params]
end LiveView
