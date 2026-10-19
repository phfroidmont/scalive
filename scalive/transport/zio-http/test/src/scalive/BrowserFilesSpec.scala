package scalive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

import zio.*
import zio.http.{Header, Method, Request, Response, Routes, Status, URL}
import zio.http.codec.PathCodec
import zio.test.{Live as _, live as _, *}

object BrowserFilesSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    "browser-files-test-signing-secret-0000000000",
    Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(throw _, identity)

  private object Page extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext)  = ZIO.unit
    def view(model: Signal[Unit]) = div("page")

  private final class RootLayout extends LiveRootLayout[Any, Any]:
    def view[Msg](
      content: HtmlElement[Msg],
      pageTitle: Option[String],
      context: LiveRootLayoutContext[Any, Any]
    ) =
      htmlRootTag(headTag(scriptTag(src := "/app.js", defer := true)), bodyTag(content))

  private val scriptTags = "<script[^>]*>".r
  private val sourceOf   = "src=\"([^\"]+)\"".r

  private def get(routes: Routes[Any, Nothing], path: String, method: Method = Method.GET) =
    ZIO.fromEither(URL.decode(path)).orDie.flatMap(url =>
      ZIO.scoped(routes.runZIO(Request(method = method, url = url)))
    )

  private def page(routes: Routes[Any, Nothing]): UIO[String] =
    get(routes, "/").flatMap(_.body.asString.orDie)

  private def sources(html: String): Vector[String] =
    scriptTags.findAllIn(html).flatMap(sourceOf.findFirstMatchIn(_).map(_.group(1))).toVector

  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def body(response: Response): UIO[String] = response.body.asString.orDie

  override def spec = suite("BrowserFilesSpec")(
    test("the packaged client loads before the layout's scripts and connects after them") {
      val application = Live.router.withRootLayout(RootLayout())(live -> Page)
      for
        routes <- ZioHttp.routes(application, config)
        html   <- page(routes)
        scripts = sources(html)
        responses <- ZIO.foreach(scripts.filterNot(_ == "/app.js"))(get(routes, _))
        bodies    <- ZIO.foreach(responses)(body)
        head      <- get(routes, scripts.head, Method.HEAD)
      yield assertTrue(
        scripts.size == 5,
        scripts(0).matches("/_scalive/assets/[0-9a-f]{64}/navigation-guard\\.js"),
        scripts(1).matches("/_scalive/live-view/[0-9a-f]{64}/phoenix\\.min\\.js"),
        scripts(2).matches("/_scalive/live-view/[0-9a-f]{64}/phoenix_live_view\\.min\\.js"),
        scripts(3) == "/app.js",
        scripts(4).matches("/_scalive/assets/[0-9a-f]{64}/connect\\.js"),
        html.indexOf("name=\"csrf-token\"") < html.indexOf("connect.js"),
        html.contains("data-socket-path=\"/live\""),
        scriptTags.findAllIn(html).count(_.contains("phx-track-static")) == 4,
        scriptTags.findAllIn(html).count(_.contains(" defer")) == 5,
        responses.forall(_.status == Status.Ok),
        responses.forall(
          _.header(Header.CacheControl).contains(StaticAssetCache.default.immutable)
        ),
        bodies(0).contains("[data-scalive-navigation-guard]"),
        sha256(bodies(1)) == "02470e33af505595906535b87432092c19e1053a0029c7257986837781891e41",
        sha256(bodies(2)) == "b3644c575949ce39f4cebf2d53f01eee659ae2ac4111075f4a7ece1002fecadc",
        bodies(3).contains("window.liveSocketOptions"),
        head.status == Status.Ok
      )
    },
    test("the connect script uses the router's socket path") {
      val application = Live.router.withSocketPath(PathCodec.empty / "socket")(live -> Page)
      for
        routes <- ZioHttp.routes(application, config)
        html   <- page(routes)
      yield assertTrue(html.contains("data-socket-path=\"/socket\""))
    },
    test("the router's client path moves every browser file") {
      val application = Live.router
        .withClientPath(PathCodec.empty / "app" / "_scalive")
        .withRootLayout(RootLayout())(live -> Page)
      for
        routes    <- ZioHttp.routes(application, config)
        html      <- page(routes)
        scripts    = sources(html).filterNot(_ == "/app.js")
        responses <- ZIO.foreach(scripts)(get(routes, _))
        moved     <- get(routes, scripts.head.stripPrefix("/app"))
      yield assertTrue(
        scripts.size == 4,
        scripts.forall(_.startsWith("/app/_scalive/")),
        scripts(0).matches("/app/_scalive/assets/[0-9a-f]{64}/navigation-guard\\.js"),
        scripts(1).matches("/app/_scalive/live-view/[0-9a-f]{64}/phoenix\\.min\\.js"),
        responses.forall(_.status == Status.Ok),
        moved.status == Status.NotFound
      )
    },
    test("only a route that matches a browser file fails assembly") {
      val shadowing = live / "_scalive" / PathCodec.string("folder") / PathCodec.string("hash") /
        PathCodec.string("file")
      val outside = live / "_scalive" / "about"
      for
        custom <- ZioHttp
                    .routes(Live.router(shadowing -> Page), config, client = LiveClient.Custom)
                    .flip
        moved <- ZioHttp
                   .routes(
                     Live.router.withClientPath(PathCodec.empty / "files")(shadowing -> Page),
                     config
                   ).either
        unrelated <- ZioHttp.routes(Live.router(outside -> Page), config).either
      yield assertTrue(
        custom.getMessage.startsWith("route path overlaps Scalive's browser files below /_scalive:"),
        moved.isRight,
        unrelated.isRight
      )
    },
    test("a custom client gets only the navigation guard") {
      val application = Live.router.withRootLayout(RootLayout())(live -> Page)
      for
        routes  <- ZioHttp.routes(application, config, client = LiveClient.Custom)
        html    <- page(routes)
        scripts = sources(html)
        phoenix <- get(routes, "/_scalive/live-view/phoenix.min.js")
      yield assertTrue(
        scripts.size == 2,
        scripts(0).matches("/_scalive/assets/[0-9a-f]{64}/navigation-guard\\.js"),
        scripts(1) == "/app.js",
        phoenix.status == Status.NotFound
      )
    },
    test("packages Nix-generated upstream provenance and license notices") {
      val classLoader = getClass.getClassLoader

      assertTrue(
        classLoader.getResource("META-INF/scalive/live-view-client/PROVENANCE.md") != null,
        classLoader.getResource(
          "META-INF/licenses/scalive/live-view-client/phoenix-1.8.15-LICENSE.md"
        ) != null,
        classLoader.getResource(
          "META-INF/licenses/scalive/live-view-client/phoenix-live-view-1.2.12-LICENSE.md"
        ) != null
      )
    }
  )
end BrowserFilesSpec
