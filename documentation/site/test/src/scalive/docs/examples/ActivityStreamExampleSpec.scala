package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.docs.SiteLiveViewHarness

object ActivityStreamExampleSpec extends ZIOSpecDefault:
  private def state(harness: SiteLiveViewHarness[?, ?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  override def spec = suite("ActivityStreamExampleSpec")(
    test("keeps durable history while emitting bounded stream inserts") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new ActivityStreamExample)
          _       <- harness.clickButton("Insert activity")
          _       <- harness.clickButton("Insert activity")
          _       <- harness.clickButton("Insert activity")
          current <- state(harness)
        yield assertTrue(
          current.select("[data-activity-count]").text() == "7",
          // renderedHtml is the latest server patch; Phoenix retains prior rows in the browser.
          current.select("[data-activity-row]").size() == 1,
          current.select("#activity-7").size() == 1
        )
      }
    },
    test("deletes an activity from durable and rendered state") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new ActivityStreamExample)
          _       <- harness.click("#activity-2 [data-delete-activity]")
          current <- state(harness)
        yield assertTrue(
          current.select("[data-activity-count]").text() == "3",
          current.select("#activity-2").isEmpty
        )
      }
    },
    test("restores durable and rendered state through the explicit reset") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new ActivityStreamExample)
          _       <- harness.click("#activity-1 [data-delete-activity]")
          _       <- harness.sendServer(ActivityStreamExample.Msg.Reset)
          current <- state(harness)
        yield assertTrue(
          current.select("[data-activity-count]").text() == "4",
          current.select("[data-activity-row]").size() == 4,
          current.select("#activity-1").size() == 1,
          current.select("#activity-4").size() == 1,
          current.select("#activity-5").isEmpty
        )
      }
    }
  )
end ActivityStreamExampleSpec
