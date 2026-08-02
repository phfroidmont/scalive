package scalive.examples.navigation

import zio.http.*
import zio.test.*

import scalive.*
import scalive.examples.ExamplesRoutes
import scalive.testing.DisconnectedRender

object SearchParamsSpec extends ZIOSpecDefault:

  def spec = suite("SearchParamsSpec")(
    test("normalizes permissive raw search parameters") {
      val blank       = SearchParams.fromRaw(RawSearchParams(Some("   "), Some("0")))
      val malformed   = SearchParams.fromRaw(RawSearchParams(Some(" streams "), Some("many")))
      val validSecond = SearchParams.fromRaw(RawSearchParams(Some(" streams "), Some(" 2 ")))

      assertTrue(
        blank == SearchParams.Empty,
        malformed.query.map(_.value).contains("streams"),
        malformed.page == SearchPage.First,
        validSecond.query.map(_.value).contains("streams"),
        validSecond.page.value == 2
      )
    },
    test("encodes canonical search locations") {
      val first  = SearchParams.fromRaw(RawSearchParams(Some(" streams "), Some("1")))
      val second = SearchParams.fromRaw(RawSearchParams(Some(" streams "), Some("2")))

      assertTrue(
        ExamplesRoutes.search.location(SearchParams.Empty).href == "/navigation/search",
        ExamplesRoutes.search.location(first).href == "/navigation/search?query=streams",
        ExamplesRoutes.search.location(second).href ==
          "/navigation/search?query=streams&page=2"
      )
    },
    test("replaces a noncanonical incoming URL") {
      val routes  = scalive.Live.router(ExamplesRoutes.search -> SearchLiveView())
      val request = Request.get(
        URL.decode("/navigation/search?query=%20streams%20&page=invalid").fold(throw _, identity)
      )

      for page <- DisconnectedRender.run(routes, request)
      yield assertTrue(
        page.response.status.isRedirection,
        page.response.rawHeader("location").contains("/navigation/search?query=streams")
      )
    }
  )
end SearchParamsSpec
