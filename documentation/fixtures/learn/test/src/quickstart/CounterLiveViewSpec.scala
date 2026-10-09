// docs:start learn-counter-test
package quickstart

import zio.*
import zio.test.*

import scalive.testing.*

object CounterLiveViewSpec extends ZIOSpecDefault:
  def spec = suite("CounterLiveView")(
    test("increases the count, then resets it") {
      ZIO.scoped {
        for
          view      <- ConnectedRender.join(CounterLiveView())
          initial   <- view.text("output")
          _         <- view.clickButton("Increase")
          _         <- view.clickButton("Increase")
          increased <- view.text("output")
          _         <- view.clickButton("Reset")
          reset     <- view.text("output")
        yield assertTrue(initial == "0", increased == "2", reset == "0")
      }
    }
  )
// docs:end learn-counter-test
