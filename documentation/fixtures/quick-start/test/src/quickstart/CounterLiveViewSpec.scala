// docs:start quick-start-connected-test
package quickstart

import zio.*
import zio.test.*

import scalive.testing.*

object CounterLiveViewSpec extends ZIOSpecDefault:
  def spec = suite("CounterLiveView")(
    test("increases the count when Increase is clicked") {
      ZIO.scoped {
        for
          view   <- ConnectedRender.join(CounterLiveView())
          before <- view.text("output")
          _      <- view.clickButton("Increase")
          after  <- view.text("output")
        yield assertTrue(before == "0", after == "1")
      }
    }
  )
// docs:end quick-start-connected-test
