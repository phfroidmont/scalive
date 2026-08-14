package scalive.docs.examples

import zio.*
import zio.test.*

import scalive.docs.SiteLiveViewHarness

object CounterExampleSpec extends ZIOSpecDefault:
  private val Count = "[role=status] strong"

  override def spec = suite("CounterExampleSpec")(
    test("increments, decrements, and resets through connected bindings") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new CounterExample)
          initial <- harness.text(Count)
          _       <- harness.clickButton("Increase")
          _       <- harness.clickButton("Increase")
          increased <- harness.text(Count)
          _         <- harness.clickButton("Decrease")
          decreased <- harness.text(Count)
          _         <- harness.clickButton("Reset")
          reset     <- harness.text(Count)
        yield assertTrue(
          initial == "0",
          increased == "2",
          decreased == "1",
          reset == "0"
        )
      }
    }
  )
end CounterExampleSpec
