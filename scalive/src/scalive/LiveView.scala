package scalive

import zio.ZIO
import zio.http.URL

trait LiveView[Msg, Model]:
  type MountContext       = scalive.MountContext[Msg, Model]
  type MessageContext     = scalive.MessageContext[Msg, Model]
  type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

  def hooks: LiveHooks[Msg, Model] = LiveHooks.empty

  def mount(ctx: MountContext): LiveIO[Model]

  def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model]

  def render(model: Model): HtmlElement[Msg]

trait RoutedLiveView[Msg, Model, Params] extends LiveView[Msg, Model]:
  type ParamsContext = scalive.ParamsContext[Msg, Model]

  def handleParams(
    model: Model,
    params: Params,
    url: URL,
    ctx: ParamsContext
  ): LiveIO[Model] =
    ZIO.succeed(model)

  def handleParamsDecodeError(
    model: Model,
    error: LiveParamsCodec.DecodeError,
    url: URL,
    ctx: ParamsContext
  ): LiveIO[Model] =
    zio.ZIO.fail(error)
