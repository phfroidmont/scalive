package scalive

import zio.*
import zio.http.*
import zio.test.*

object StaticAssetsSpec extends ZIOSpecDefault:
  private def loadAssets(serveOriginals: Boolean = true): Task[StaticAssets] =
    StaticAssets.load(
      StaticAssetConfig.classpath(
        "public",
        Seq("test-asset.js"),
        mountPath = Path.empty / "assets",
        serveOriginals = serveOriginals
      )
    )

  private def request(routes: Routes[Any, Nothing], path: String, method: Method = Method.GET) =
    ZIO.fromEither(URL.decode(path)).orDie.flatMap(url =>
      ZIO.scoped(routes.runZIO(Request(method = method, url = url)))
    )

  override def spec = suite("StaticAssetsSpec")(
    test("serves digested and original assets with distinct cache policies") {
      for
        assets   <- loadAssets()
        digested <- request(assets.routes, assets.path("test-asset.js"))
        original <- request(assets.routes, "/assets/test-asset.js")
        body     <- digested.body.asString
      yield assertTrue(
        assets.path("test-asset.js").startsWith("/assets/test-asset-"),
        digested.status == Status.Ok,
        digested.header(Header.CacheControl).contains(StaticAssetCache.default.digested),
        original.status == Status.Ok,
        original.header(Header.CacheControl).contains(StaticAssetCache.default.original),
        body.contains("scaliveTestAsset")
      )
    },
    test("rejects unknown digests and optionally disables original paths") {
      for
        assets <- loadAssets(serveOriginals = false)
        entry = assets.entry("test-asset.js")
        unknown <- request(
                     assets.routes,
                     assets.path("test-asset.js").replace(entry.digest, "0" * entry.digest.length)
                   )
        original <- request(assets.routes, "/assets/test-asset.js")
      yield assertTrue(unknown.status == Status.NotFound, original.status == Status.NotFound)
    },
    test("HEAD and query-string requests retain asset metadata") {
      for
        assets <- loadAssets()
        path = assets.path("test-asset.js")
        queried <- request(assets.routes, s"$path?vsn=d")
        head    <- request(assets.routes, path, Method.HEAD)
        body    <- head.body.asString
      yield assertTrue(
        queried.status == Status.Ok,
        head.status == Status.Ok,
        head.header(Header.ETag).contains(Header.ETag.Strong(assets.entry("test-asset.js").digest)),
        body.isEmpty
      )
    },
    test("normalizes lookups and rejects traversal") {
      for assets <- loadAssets()
      yield assertTrue(
        assets.pathOption("/test-asset.js?x=1").contains(assets.path("test-asset.js")),
        assets.pathOption("../test-asset.js").isEmpty,
        assets.pathOption("missing.js").isEmpty
      )
    }
  )
end StaticAssetsSpec
