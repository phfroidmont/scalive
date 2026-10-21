package routing

import zio.*

import scalive.*

trait Billing:
  def checkoutUrl(plan: String): Task[String]

// docs:start routing-external-redirect
enum CheckoutMsg:
  case Checkout(plan: String)

final class CheckoutLiveView(billing: Billing) extends LiveView[CheckoutMsg, Unit]:
  def mount(ctx: MountContext) = ZIO.unit

  def handleMessage(model: Unit, ctx: MessageContext) =
    case CheckoutMsg.Checkout(plan) =>
      for
        text     <- billing.checkoutUrl(plan)
        checkout <- ZIO.fromEither(ExternalUrl.parse(text))
        _        <- ctx.nav.redirect(checkout)
      yield model

  def view(model: Signal[Unit]): HtmlElement[CheckoutMsg] =
    button(on.click(CheckoutMsg.Checkout("yearly")), "Subscribe")
// docs:end routing-external-redirect
