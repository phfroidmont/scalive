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
        yield assertTrue(initial == "0", increased == "2", decreased == "1", reset == "0")
      }
    },
    test("handles an explicit server reset") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new CounterExample)
          _       <- harness.clickButton("Increase")
          _       <- harness.sendServer(CounterExample.Msg.Reset)
          reset   <- harness.text(Count)
        yield assertTrue(reset == "0")
      }
    },
    test("isolates state between instances") {
      ZIO.scoped {
        for
          first  <- SiteLiveViewHarness.join(new CounterExample)
          second <- SiteLiveViewHarness.join(new CounterExample)
          _      <- first.clickButton("Increase")
          one    <- first.text(Count)
          zero   <- second.text(Count)
        yield assertTrue(one == "1", zero == "0")
      }
    },
    test("starts from zero after leaving and remounting") {
      for
        _ <- ZIO.scoped {
               for
                 harness <- SiteLiveViewHarness.join(new CounterExample)
                 _       <- harness.clickButton("Increase")
                 _       <- harness.leave
               yield ()
             }
        remounted <- ZIO.scoped {
                       for
                         harness <- SiteLiveViewHarness.join(new CounterExample)
                         value   <- harness.text(Count)
                       yield value
                     }
      yield assertTrue(remounted == "0")
    }
  )
end CounterExampleSpec
