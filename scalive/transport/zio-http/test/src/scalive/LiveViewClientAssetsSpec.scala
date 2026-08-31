package scalive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.*
import zio.test.*

import scalive.render.{HtmlRenderer, RenderProgram}

object LiveViewClientAssetsSpec extends ZIOSpecDefault:
  private val sourcePattern = "src=\"([^\"]+)\"".r

  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def request(
    routes: Routes[Any, Nothing],
    path: String,
    method: Method = Method.GET
  ) =
    ZIO.fromEither(URL.decode(path)).orDie.flatMap(url =>
      ZIO.scoped(routes.runZIO(Request(method = method, url = url)))
    )

  override def spec = suite("LiveViewClientAssetsSpec")(
    test("loads and serves the supported clients in explicit script order") {
      for
        assets <- LiveViewClientAssets.load()
        program <- ZIO.fromEither(
                     RenderProgram.compile[Unit, Nothing](_ =>
                       div(assets.phoenixScript, assets.liveViewScript)
                     )
                   )
        rendered <- program.evaluate(()).map(candidate => HtmlRenderer.render(candidate.tree))
        sources = sourcePattern.findAllMatchIn(rendered).map(_.group(1)).toList
        responses <- ZIO.foreach(sources)(request(assets.routes, _))
        bodies    <- ZIO.foreach(responses)(_.body.asString)
        head      <- request(assets.routes, sources.head, Method.HEAD)
        headBody  <- head.body.asString
      yield assertTrue(
        sources.size == 2,
        sources.head.matches(
          "/_scalive/live-view/[0-9a-f]{64}/phoenix\\.min\\.js"
        ),
        sources(1).matches(
          "/_scalive/live-view/[0-9a-f]{64}/phoenix_live_view\\.min\\.js"
        ),
        responses.forall(_.status == Status.Ok),
        responses.forall(
          _.header(Header.CacheControl).contains(StaticAssetCache.default.immutable)
        ),
        bodies.head.startsWith("var Phoenix="),
        bodies(1).startsWith("var LiveView="),
        sha256(bodies.head) ==
          "7f96de34f92e9d8bab93552210a435ec1bdb049fa54793eba876ab5153e1c233",
        sha256(bodies(1)) ==
          "0a18a51060d0dc19842068191400b2e0f3f75c853af0a04a879e29b91ec0a629",
        rendered.indexOf(sources.head) < rendered.indexOf(sources(1)),
        rendered.split("phx-track-static", -1).length == 3,
        rendered.split(" defer", -1).length == 3,
        head.status == Status.Ok,
        headBody.isEmpty,
        LiveViewClientAssets.defaultMountPath != NavigationGuardAssets.defaultMountPath
      )
    },
    test("uses a custom mount path for both client scripts") {
      for
        assets <- LiveViewClientAssets.load(Path.empty / "custom" / "live-view")
        program <- ZIO.fromEither(
                     RenderProgram.compile[Unit, Nothing](_ =>
                       div(assets.phoenixScript, assets.liveViewScript)
                     )
                   )
        rendered <- program.evaluate(()).map(candidate => HtmlRenderer.render(candidate.tree))
        sources = sourcePattern.findAllMatchIn(rendered).map(_.group(1)).toList
        responses <- ZIO.foreach(sources)(request(assets.routes, _))
      yield assertTrue(
        sources.head.matches("/custom/live-view/[0-9a-f]{64}/phoenix\\.min\\.js"),
        sources(1).matches(
          "/custom/live-view/[0-9a-f]{64}/phoenix_live_view\\.min\\.js"
        ),
        responses.size == 2,
        responses.forall(_.status == Status.Ok)
      )
    },
    test("loads and serves both clients with an explicitly supplied classloader") {
      val resourceReads = new AtomicInteger
      val classLoader = new ClassLoader(LiveViewClientAssetsSpec.getClass.getClassLoader):
        override def getResourceAsStream(name: String) =
          val _ = resourceReads.incrementAndGet()
          super.getResourceAsStream(name)

      for
        assets <- LiveViewClientAssets.load(classLoader = classLoader)
        program <- ZIO.fromEither(
                     RenderProgram.compile[Unit, Nothing](_ =>
                       div(assets.phoenixScript, assets.liveViewScript)
                     )
                   )
        rendered <- program.evaluate(()).map(candidate => HtmlRenderer.render(candidate.tree))
        sources = sourcePattern.findAllMatchIn(rendered).map(_.group(1)).toList
        responses <- ZIO.foreach(sources)(request(assets.routes, _))
        bodies    <- ZIO.foreach(responses)(_.body.asString)
      yield assertTrue(
        responses.size == 2,
        responses.forall(_.status == Status.Ok),
        bodies.head.startsWith("var Phoenix="),
        bodies(1).startsWith("var LiveView="),
        resourceReads.get() >= 2
      )
    },
    test("packages Nix-generated upstream provenance and license notices") {
      val classLoader = getClass.getClassLoader

      assertTrue(
        classLoader.getResource("META-INF/scalive/live-view-client/PROVENANCE.md") != null,
        classLoader.getResource(
          "META-INF/licenses/scalive/live-view-client/phoenix-1.8.9-LICENSE.md"
        ) != null,
        classLoader.getResource(
          "META-INF/licenses/scalive/live-view-client/phoenix-live-view-1.2.10-LICENSE.md"
        ) != null
      )
    }
  )
end LiveViewClientAssetsSpec
