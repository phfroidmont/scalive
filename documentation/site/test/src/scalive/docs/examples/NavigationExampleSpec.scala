package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.docs.DocumentationApplication
import scalive.testing.{ConnectedRender, ConnectedView}

object NavigationExampleSpec extends ZIOSpecDefault:
  private def state(harness: ConnectedView[?]) =
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
          harness <- ConnectedRender.join(new NavigationExample)
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
          harness <- ConnectedRender.join(new NavigationExample)
          _       <- harness.click("[data-navigation-preset='typed forms']")
          current <- state(harness)
        yield assertTrue(
          current
            .select("a[data-replace-navigation][href='/search?q=typed+forms']")
            .attr("data-phx-link-state") == "replace"
        )
      }
    },
    test("resets the selected destination") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new NavigationExample)
          _       <- harness.click("[data-navigation-preset=streams]")
          _       <- harness.send(NavigationExample.Msg.Reset)
          current <- state(harness)
        yield assertTrue(current.select("[data-navigation-query]").text() == "LiveView")
      }
    }
  )
end NavigationExampleSpec
