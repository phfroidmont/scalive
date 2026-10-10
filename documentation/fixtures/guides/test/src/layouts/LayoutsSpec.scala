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
  ).fold(error => throw IllegalArgumentException(error.toString), identity)

  private def render(path: String) =
    for
      clientAssets <- LiveViewClientAssets.load()
      assets       <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.js")))
      routes        = ZioHttp.routes(Application(clientAssets, assets), config)
      page         <- DisconnectedRender.run(routes, Request.get(URL.decode(path).toOption.get))
    yield RenderedHtml.parse(page.html)

  def spec = suite("Layouts")(
    test("nests the route layout inside the application layout and the root layout") {
      for html <- render("/projects/scalive")
      yield assertTrue(
        html.selectOne("html > head > title").map(_.text) == Right("Scalive quick start"),
        html.selectOne("header nav a[aria-current=page]").map(_.text) == Right("Projects"),
        html.selectOne("main section h1").map(_.text) == Right("Project scalive"),
        html.text.contains("Open issues for scalive.")
      )
    },
    test("marks the current page in the navigation") {
      for html <- render("/")
      yield assertTrue(
        html.selectOne("header nav a[aria-current=page]").map(_.text) == Right("Home")
      )
    }
  )
end LayoutsSpec
