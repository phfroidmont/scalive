package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.docs.SiteLiveViewHarness

object LifecycleExampleSpec extends ZIOSpecDefault:
  private def document(harness: SiteLiveViewHarness[?, ?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  override def spec = suite("LifecycleExampleSpec")(
    test("shows connected mount state and handles flash, title, and reset messages") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new LifecycleExample)
          initial <- document(harness)
          _       <- harness.clickButton("Show notification")
          flashed <- document(harness)
          _       <- harness.clickButton("Request attention")
          titled  <- document(harness)
          _       <- harness.clickButton("Reset example")
          reset   <- document(harness)
        yield assertTrue(
          initial.select("[data-mount-phase]").text() == "Connected socket mount",
          initial.select("[data-lifecycle-title]").text() == "Lifecycle example",
          flashed.select("[data-lifecycle-flash]").text() == "Your notification is ready.",
          titled.select("[data-lifecycle-title]").text() == "Attention needed",
          reset.select("[data-lifecycle-title]").text() == "Lifecycle example",
          reset.select("[data-lifecycle-flash]").isEmpty
        )
      }
    },
    test("clears one keyed flash message") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new LifecycleExample)
          _       <- harness.clickButton("Show notification")
          _       <- harness.clickButton("Clear notification")
          cleared <- document(harness)
        yield assertTrue(cleared.select("[data-lifecycle-flash]").isEmpty)
      }
    },
    test("handles an explicit server reset") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new LifecycleExample)
          _       <- harness.clickButton("Show notification")
          _       <- harness.clickButton("Request attention")
          _       <- harness.sendServer(LifecycleExample.Msg.Reset)
          reset   <- document(harness)
        yield assertTrue(
          reset.select("[data-lifecycle-title]").text() == "Lifecycle example",
          reset.select("[data-lifecycle-flash]").isEmpty
        )
      }
    },
    test("isolates state between instances") {
      ZIO.scoped {
        for
          first  <- SiteLiveViewHarness.join(new LifecycleExample)
          second <- SiteLiveViewHarness.join(new LifecycleExample)
          _      <- first.clickButton("Request attention")
          firstDocument  <- document(first)
          secondDocument <- document(second)
        yield assertTrue(
          firstDocument.select("[data-lifecycle-title]").text() == "Attention needed",
          secondDocument.select("[data-lifecycle-title]").text() == "Lifecycle example"
        )
      }
    },
    test("starts from defaults after leaving and remounting") {
      for
        _ <- ZIO.scoped {
               for
                 harness <- SiteLiveViewHarness.join(new LifecycleExample)
                 _       <- harness.clickButton("Request attention")
                 _       <- harness.leave
               yield ()
             }
        remounted <- ZIO.scoped {
                       for
                         harness <- SiteLiveViewHarness.join(new LifecycleExample)
                         state   <- document(harness)
                       yield state.select("[data-lifecycle-title]").text()
                     }
      yield assertTrue(remounted == "Lifecycle example")
    }
  )
end LifecycleExampleSpec
