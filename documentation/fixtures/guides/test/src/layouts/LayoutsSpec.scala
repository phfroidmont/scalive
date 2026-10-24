package layouts

import java.time.Duration

import zio.*
import zio.http.{Request, URL}
import zio.test.*

import scalive.*
import scalive.testing.*

object LayoutsSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(throw _, identity)

  private def render(path: String) =
    for
      assets <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.css")))
      routes <- ZioHttp.routes(Application(assets), config)
      page   <- DisconnectedRender.run(routes, Request.get(URL.decode(path).toOption.get))
    yield RenderedHtml.parse(page.html)

  def spec = suite("Layouts")(
    test("nests the route layout inside the application layout and the root layout") {
      for html <- render("/projects/scalive")
      yield assertTrue(
        html.selectOne("html > head > title").map(_.text) ==
          Right("Open issues · Project scalive · Scalive"),
        html.selectOne("header p").map(_.text) == Right("Project scalive"),
        html.selectOne("header nav a[aria-current=page]").map(_.text) == Right("Projects"),
        html.selectOne("main section h1").map(_.text) == Right("Project scalive"),
        html.text.contains("Open issues for scalive.")
      )
    },
    test("shows the page title in the header when the page provides no heading") {
      for html <- render("/projects")
      yield assertTrue(
        html.selectOne("html > head > title").map(_.text) == Right("All projects · Scalive"),
        html.selectOne("header p").map(_.text) == Right("All projects")
      )
    },
    test("marks the current page in the navigation") {
      for html <- render("/")
      yield assertTrue(
        html.selectOne("header p").map(_.text) == Right("Projects"),
        html.selectOne("header nav a[aria-current=page]").map(_.text) == Right("Home")
      )
    }
  )
end LayoutsSpec
