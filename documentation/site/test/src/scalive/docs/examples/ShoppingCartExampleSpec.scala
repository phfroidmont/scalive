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
    },
    test("handles an explicit server reset") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new ShoppingCartExample)
          _       <- harness.click("[data-product=sticker]")
          _       <- harness.sendServer(ShoppingCartExample.Msg.Clear)
          state   <- cartState(harness)
        yield assertTrue(
          state.select("[data-cart-line]").isEmpty,
          state.select("[data-cart-item-count]").text() == "0 items"
        )
      }
    },
    test("isolates state between instances") {
      ZIO.scoped {
        for
          first  <- SiteLiveViewHarness.join(new ShoppingCartExample)
          second <- SiteLiveViewHarness.join(new ShoppingCartExample)
          _      <- first.click("[data-product=coffee]")
          firstState  <- cartState(first)
          secondState <- cartState(second)
        yield assertTrue(
          firstState.select("[data-cart-item-count]").text() == "1 item",
          secondState.select("[data-cart-item-count]").text() == "0 items"
        )
      }
    },
    test("starts empty after leaving and remounting") {
      for
        _ <- ZIO.scoped {
               for
                 harness <- SiteLiveViewHarness.join(new ShoppingCartExample)
                 _       <- harness.click("[data-product=notebook]")
                 _       <- harness.leave
               yield ()
             }
        remounted <- ZIO.scoped {
                       for
                         harness <- SiteLiveViewHarness.join(new ShoppingCartExample)
                         state   <- cartState(harness)
                       yield state.select("[data-cart-item-count]").text()
                     }
      yield assertTrue(remounted == "0 items")
    }
  )
end ShoppingCartExampleSpec
