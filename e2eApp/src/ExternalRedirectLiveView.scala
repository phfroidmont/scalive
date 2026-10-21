import zio.*

import scalive.*

/** Redirects the browser to another origin after an action, as a payment checkout does. */
class ExternalRedirectLiveView extends LiveView[ExternalRedirectLiveView.Msg, Unit]:
  import ExternalRedirectLiveView.*

  def mount(ctx: MountContext) = ZIO.unit

  def handleMessage(model: Unit, ctx: MessageContext) =
    case Msg.Checkout =>
      ZIO
        .fromEither(ExternalUrl.parse("https://checkout.example.test/pay?session=a1"))
        .flatMap(ctx.nav.redirect)

  override def view(model: Signal[Unit]) =
    button(on.click(Msg.Checkout), "Checkout")

object ExternalRedirectLiveView:
  enum Msg:
    case Checkout
