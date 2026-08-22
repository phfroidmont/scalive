package scalive

import zio.http.URL
import zio.{Task, ZIO}

/** Defines a server-rendered view with typed messages and model state.
  *
  * A LiveView is mounted independently for the disconnected HTTP render and each connected socket
  * lifecycle. Its immutable model is projected through a signal-backed [[HtmlElement]], while
  * browser bindings and managed work deliver values to [[handleMessage]].
  *
  * @tparam Msg
  *   messages accepted by this view
  * @tparam Model
  *   immutable state owned by one lifecycle
  */
trait LiveView[Msg, Model]:
  type MountContext       = scalive.MountContext[Msg, Model]
  type MessageContext     = scalive.MessageContext[Msg, Model]
  type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

  def hooks: LiveHooks[Msg, Model]            = LiveHooks.empty
  def pageTitle(model: Model): Option[String] = None
  def mount(ctx: MountContext): Task[Model]

  /** Handles a message against the current immutable model.
    *
    * The returned function keeps message dispatch typed while sharing the model and connected
    * capabilities captured for this lifecycle turn.
    *
    * @param model
    *   current model
    * @param ctx
    *   capabilities available while handling the message
    * @return
    *   a handler for one message
    */
  def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model]
  def view(model: Signal[Model]): HtmlElement[Msg]

object LiveView:
  trait Eventless[Model] extends LiveView[Nothing, Model]:
    final def handleMessage(model: Model, ctx: MessageContext): Nothing => Task[Model] =
      _ => ZIO.succeed(model)

  /** A routed definition is deliberately not an unrouted [[LiveView]]. */
  trait Routed[Msg, Model, Params]:
    type MountContext       = scalive.MountContext[Msg, Model]
    type MessageContext     = scalive.MessageContext[Msg, Model]
    type ParamsContext      = scalive.ParamsContext[Msg, Model]
    type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

    def hooks: LiveHooks[Msg, Model]            = LiveHooks.empty
    def pageTitle(model: Model): Option[String] = None

    /** Mounts this routed view from decoded route parameters and lifecycle capabilities.
      *
      * Disconnected HTTP rendering and connected websocket admission invoke this method
      * independently.
      */
    def mount(params: Params, ctx: MountContext): Task[Model]
    def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model]
    def handleParams(
      model: Model,
      params: Params,
      url: URL,
      ctx: ParamsContext
    ): Task[Model] = ZIO.succeed(model)
    def handleParamsDecodeError(
      model: Model,
      error: LiveParamsCodec.DecodeError,
      url: URL,
      ctx: ParamsContext
    ): Task[Model] = ZIO.fail(error)
    def view(model: Signal[Model]): HtmlElement[Msg]

  object Routed:
    trait Eventless[Model, Params] extends Routed[Nothing, Model, Params]:
      final def handleMessage(model: Model, ctx: MessageContext): Nothing => Task[Model] =
        _ => ZIO.succeed(model)
end LiveView
