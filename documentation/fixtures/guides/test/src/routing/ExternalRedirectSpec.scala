package routing

import zio.*
import zio.test.*

import scalive.testing.{ConnectedAction, ConnectedRender}

object ExternalRedirectSpec extends ZIOSpecDefault:
  private object StripeBilling extends Billing:
    def checkoutUrl(plan: String) = ZIO.succeed(s"https://checkout.stripe.com/c/pay/$plan")

  def spec = suite("External redirect")(
    test("a message handler redirects to a checkout outside the application") {
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(CheckoutLiveView(StripeBilling))
          action    <- connected.clickButton("Subscribe")
        yield assertTrue(
          action match
            case ConnectedAction.Redirect(to) => to.encode == "https://checkout.stripe.com/c/pay/yearly"
            case _                            => false
        )
      }
    }
  )
end ExternalRedirectSpec
