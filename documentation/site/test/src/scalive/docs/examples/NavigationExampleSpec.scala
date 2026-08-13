package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.docs.{DocumentationApplication, SiteLiveViewHarness}

object NavigationExampleSpec extends ZIOSpecDefault:
  private def state(harness: SiteLiveViewHarness[?, ?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  override def spec = suite("NavigationExampleSpec")(
    test("builds real documentation search locations from typed parameters") {
      assertTrue(
        DocumentationApplication.SearchRouteBuilder.location(None).href == "/search",
        DocumentationApplication.SearchRouteBuilder.location(Some("LiveView")).href ==
          "/search?q=LiveView"
      )
    },
    test("selects a query and exposes push and replace navigation to the same location") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new NavigationExample)
          _       <- harness.click("[data-navigation-preset=streams]")
          current <- state(harness)
        yield assertTrue(
          current.select("[data-navigation-query]").text() == "streams",
          current.select("[data-navigation-destination]").text() == "/search?q=streams",
          current.select("a[data-push-navigation][href='/search?q=streams']").size() == 1,
          current.select("[data-navigation-preset][aria-pressed=true]").text().contains("Streams"),
          current.select("[data-navigation-preset][aria-pressed=true]").size() == 1
        )
      }
    },
    test("renders replace navigation from the same typed location") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(new NavigationExample)
          _       <- harness.click("[data-navigation-preset='typed forms']")
          current <- state(harness)
        yield assertTrue(
          current
            .select("a[data-replace-navigation][href='/search?q=typed+forms']")
            .attr("data-phx-link-state") == "replace"
        )
      }
    },
    test("resets selection and isolates instances") {
      ZIO.scoped {
        for
          first  <- SiteLiveViewHarness.join(new NavigationExample)
          second <- SiteLiveViewHarness.join(new NavigationExample)
          _      <- first.click("[data-navigation-preset=streams]")
          _      <- first.sendServer(NavigationExample.Msg.Reset)
          firstState  <- state(first)
          secondState <- state(second)
        yield assertTrue(
          firstState.select("[data-navigation-query]").text() == "LiveView",
          secondState.select("[data-navigation-query]").text() == "LiveView"
        )
      }
    },
    test("recreates initial navigation state after remounting") {
      for
        _ <- ZIO.scoped {
               for
                 harness <- SiteLiveViewHarness.join(new NavigationExample)
                 _       <- harness.click("[data-navigation-preset=streams]")
                 _       <- harness.leave
               yield ()
             }
        remounted <- ZIO.scoped {
                       for
                         harness <- SiteLiveViewHarness.join(new NavigationExample)
                         current <- state(harness)
                       yield current.select("[data-navigation-query]").text()
                     }
      yield assertTrue(remounted == "LiveView")
    }
  )
end NavigationExampleSpec
