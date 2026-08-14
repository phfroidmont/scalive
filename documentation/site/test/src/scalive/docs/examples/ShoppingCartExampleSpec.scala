package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.docs.SiteLiveViewHarness

object ShoppingCartExampleSpec extends ZIOSpecDefault:
  private def cartState(harness: SiteLiveViewHarness[?, ?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  override def spec = suite("ShoppingCartExampleSpec")(
    test("adds, removes, totals, and clears through connected bindings") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new ShoppingCartExample)
          initial <- cartState(harness)
          _       <- harness.click("[data-product=coffee]")
          _       <- harness.click("[data-product=coffee]")
          _       <- harness.click("[data-product=notebook]")
          added   <- cartState(harness)
          _       <- harness.click("[data-remove-product=coffee]")
          reduced <- cartState(harness)
          _       <- harness.click("[data-remove-product=coffee]")
          removed <- cartState(harness)
          _       <- harness.clickButton("Clear")
          cleared <- cartState(harness)
        yield assertTrue(
          initial.select("[data-cart-empty]").text() == "Add a product to begin.",
          initial.select("button[data-cart-clear][disabled]").size() == 1,
          added.select("[data-cart-item-count]").text() == "3 items",
          added.select("[data-cart-line=coffee] [data-cart-quantity]").text() == "2",
          added.select("[data-cart-line=coffee] [data-cart-subtotal]").text() == "$25.98",
          added.select("[data-cart-line=notebook] [data-cart-subtotal]").text() == "$8.50",
          added.select("[data-cart-total]").text() == "$34.48",
          reduced.select("[data-cart-line=coffee] [data-cart-quantity]").text() == "1",
          reduced.select("[data-cart-total]").text() == "$21.49",
          removed.select("[data-cart-line=coffee]").isEmpty,
          removed.select("[data-cart-total]").text() == "$8.50",
          cleared.select("[data-cart-empty]").text() == "Add a product to begin.",
          cleared.select("button[data-cart-clear][disabled]").size() == 1
        )
      }
    }
  )
end ShoppingCartExampleSpec
