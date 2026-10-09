package quickstart

import zio.*
import zio.test.*

import scalive.testing.*

object CartLiveViewSpec extends ZIOSpecDefault:
  def spec = suite("CartLiveView")(
    test("switches between the empty message and the rows") {
      ZIO.scoped {
        for
          view  <- ConnectedRender.join(CartLiveView())
          empty <- view.html
          _     <- view.clickButton("Add Coffee beans")
          _     <- view.clickButton("Add Coffee beans")
          added <- view.html
          _     <- view.clickButton("Remove one")
          _     <- view.clickButton("Remove one")
          again <- view.html
        yield assertTrue(
          empty.contains("Your cart is empty."),
          !added.contains("Your cart is empty."),
          added.contains("Added Coffee beans."),
          added.contains("$25.98"),
          again.contains("Your cart is empty."),
          !again.contains("Added Coffee beans.")
        )
      }
    },
    test("shows the free-shipping note from the threshold") {
      ZIO.scoped {
        for
          view   <- ConnectedRender.join(CartLiveView())
          _      <- view.clickButton("Add Coffee beans")
          _      <- view.clickButton("Add Coffee beans")
          below  <- view.html
          _      <- view.clickButton("Add Notebook")
          above  <- view.html
          _      <- view.clickButton("Clear")
          clear  <- view.html
        yield assertTrue(
          !below.contains("Your order ships free."),
          above.contains("Your order ships free."),
          !clear.contains("Your order ships free.")
        )
      }
    }
  )
