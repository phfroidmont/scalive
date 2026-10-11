package routing

import zio.test.*

object LocationsSpec extends ZIOSpecDefault:
  def spec = suite("Route locations")(
    test("builds the documented hrefs") {
      assertTrue(
        Locations.destination.href == "/search?q=LiveView",
        Locations.heading.href == "/search?q=LiveView#results"
      )
    },
    test("maps raw query values into domain parameters and back") {
      val params = TypedRoutes.SearchParams(TypedRoutes.SearchTerm.from(" LiveView "))

      assertTrue(TypedRoutes.search.location(params).href == "/search?q=LiveView")
    }
  )
