package scalive

import zio.*
import zio.http.*
import zio.test.*

import scalive.render.{HtmlRenderer, RenderProgram}

object NavigationGuardAssetsSpec extends ZIOSpecDefault:
  private def request(routes: Routes[Any, Nothing], path: String) =
    ZIO.fromEither(URL.decode(path)).orDie.flatMap(url =>
      ZIO.scoped(routes.runZIO(Request.get(url)))
    )

  override def spec = suite("NavigationGuardAssetsSpec")(
    test("loads and serves the fixed runtime from its dedicated immutable path") {
      for
        assets <- NavigationGuardAssets.load()
        program <- ZIO.fromEither(
                     RenderProgram.compile[Unit, Nothing](_ => assets.script)
                   )
        rendered <- program.evaluate(()).map(candidate => HtmlRenderer.render(candidate.tree))
        source = "src=\"([^\"]+)\"".r.findFirstMatchIn(rendered).map(_.group(1)).get
        response <- request(assets.routes, source)
        body     <- response.body.asString
      yield assertTrue(
        source.matches("/_scalive/assets/[0-9a-f]{64}/navigation-guard\\.js"),
        response.status == Status.Ok,
        response.header(Header.CacheControl).contains(StaticAssetCache.default.immutable),
        body.contains("[data-scalive-navigation-guard]"),
        rendered.contains(" phx-track-static"),
        rendered.contains(" defer")
      )
    }
  )
end NavigationGuardAssetsSpec
