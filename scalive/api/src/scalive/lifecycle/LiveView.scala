package scalive

import zio.ZIO
import zio.http.URL

trait LiveView[Msg, Model]:
  type MountContext       = scalive.MountContext[Msg, Model]
  type MessageContext     = scalive.MessageContext[Msg, Model]
  type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

  def hooks: LiveHooks[Msg, Model]            = LiveHooks.empty
  def pageTitle(model: Model): Option[String] = None
  def mount(ctx: MountContext): LiveIO[Model]
  def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model]
  def view(model: Signal[Model]): HtmlElement[Msg]

object LiveView:
  trait Eventless[Model] extends LiveView[Nothing, Model]:
    final def handleMessage(model: Model, ctx: MessageContext): Nothing => LiveIO[Model] =
      _ => ZIO.succeed(model)

  /** A routed definition is deliberately not an unrouted [[LiveView]]. */
  trait Routed[Msg, Model, Params]:
    type MountContext       = scalive.MountContext[Msg, Model]
    type MessageContext     = scalive.MessageContext[Msg, Model]
    type ParamsContext      = scalive.ParamsContext[Msg, Model]
    type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

    def hooks: LiveHooks[Msg, Model]            = LiveHooks.empty
    def pageTitle(model: Model): Option[String] = None
    def mount(params: Params, ctx: MountContext): LiveIO[Model]
    def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model]
    def handleParams(
      model: Model,
      params: Params,
      url: URL,
      ctx: ParamsContext
    ): LiveIO[Model] = ZIO.succeed(model)
    def handleParamsDecodeError(
      model: Model,
      error: LiveParamsCodec.DecodeError,
      url: URL,
      ctx: ParamsContext
    ): LiveIO[Model] = ZIO.fail(error)
    def view(model: Signal[Model]): HtmlElement[Msg]

  object Routed:
    trait Eventless[Model, Params] extends Routed[Nothing, Model, Params]:
      final def handleMessage(model: Model, ctx: MessageContext): Nothing => LiveIO[Model] =
        _ => ZIO.succeed(model)
end LiveView
