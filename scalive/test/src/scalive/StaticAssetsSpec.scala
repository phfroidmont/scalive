package scalive

import zio.*
import zio.http.*
import zio.test.*

object StaticAssetsSpec extends ZIOSpecDefault:

  private def loadAssets(
    mountPath: Path = Path.empty / "assets",
    serveOriginals: Boolean = true
  ): Task[StaticAssets] =
    StaticAssets.load(
      StaticAssetConfig.classpath(
        "public",
        Seq("app.js"),
        mountPath = mountPath,
        serveOriginals = serveOriginals
      )
    )

  private def runRequest(routes: Routes[Any, Nothing], path: String) =
    ZIO.fromEither(URL.decode(path)).orDie.flatMap(url => routes.runZIO(Request.get(url)))

  override def spec = suite("StaticAssetsSpec")(
    test("builds digested URLs and serves them from the configured mount path") {
      for
        assets <- loadAssets()
        url = assets.path("app.js")
        res  <- ZIO.scoped(runRequest(assets.routes, url))
        body <- res.body.asString
      yield assertTrue(
        url.startsWith("/assets/app-"),
        url.endsWith(".js"),
        res.status == Status.Ok,
        res.header(Header.CacheControl).contains(StaticAssetCache.default.digested),
        res.header(Header.ETag).contains(Header.ETag.Strong(assets.entry("app.js").digest)),
        body.contains("test-asset")
      )
    },
    test("rejects paths with an unknown digest") {
      for
        assets <- loadAssets()
        entry = assets.entry("app.js")
        wrong = assets.path("app.js").replace(entry.digest, "0" * entry.digest.length)
        res <- ZIO.scoped(runRequest(assets.routes, wrong))
      yield assertTrue(res.status == Status.NotFound)
    },
    test("serves original paths with non-immutable cache headers when enabled") {
      for
        assets <- loadAssets()
        res  <- ZIO.scoped(runRequest(assets.routes, "/assets/app.js"))
        body <- res.body.asString
      yield assertTrue(
        res.status == Status.Ok,
        res.header(Header.CacheControl).contains(StaticAssetCache.default.original),
        body.contains("test-asset")
      )
    },
    test("does not serve original paths when disabled") {
      for
        assets <- loadAssets(serveOriginals = false)
        res    <- ZIO.scoped(runRequest(assets.routes, "/assets/app.js"))
      yield assertTrue(res.status == Status.NotFound)
    },
    test("renders tracked script and stylesheet tags") {
      for assets <- StaticAssets.load(
                      StaticAssetConfig.classpath(
                        "public",
                        Seq("app.js"),
                        mountPath = Path.empty / "static"
                      )
                    )
      yield
        val html = HtmlBuilder.build(
          div(
            assets.trackedScript("app.js", defer := true),
            assets.trackedStylesheet("app.js")
          )
        )
        assertTrue(
          html.contains("phx-track-static"),
          html.contains("src=\"/static/app-"),
          html.contains("href=\"/static/app-")
        )
    },
    test("ignores query strings when serving static assets") {
      for
        assets <- loadAssets()
        res    <- ZIO.scoped(runRequest(assets.routes, s"${assets.path("app.js")}?vsn=d"))
      yield assertTrue(res.status == Status.Ok)
    }
  )
end StaticAssetsSpec
