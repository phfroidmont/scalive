package scalive

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.*
import zio.test.*

object StaticAssetsSpec extends ZIOSpecDefault:
  private def loadAssets(serveOriginals: Boolean = false): Task[StaticAssets] =
    StaticAssets.load(
      StaticAssetConfig.classpath(
        "public",
        Seq("test-asset.js"),
        mountPath = Path.empty / "assets",
        serveOriginals = serveOriginals
      )
    )

  private def loadGraphAssets: Task[StaticAssets] =
    StaticAssets.load(
      StaticAssetConfig.classpath(
        "public",
        Seq(
          "graph/app.js",
          "graph/chunk.js",
          "graph/styles/app.css",
          "graph/fonts/test.woff2",
          "graph/worker.js"
        ),
        mountPath = Path.empty / "assets"
      )
    )

  private def loadDeploymentAssets(manifest: String = "deployment-assets.json") =
    StaticAssets.load(
      StaticAssetConfig.deploymentClasspath(
        "public",
        manifest,
        mountPath = Path.empty / "assets"
      )
    )

  private def request(routes: Routes[Any, Nothing], path: String, method: Method = Method.GET) =
    ZIO.fromEither(URL.decode(path)).orDie.flatMap(url =>
      ZIO.scoped(routes.runZIO(Request(method = method, url = url)))
    )

  private def temporaryDirectory: ZIO[Scope, Throwable, java.nio.file.Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("scalive-static-assets-")))(
      path =>
        ZIO
          .attemptBlocking {
            val stream = Files.walk(path)
            try
              stream.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
            finally stream.close()
          }.orDie
    )

  override def spec = suite("StaticAssetsSpec")(
    test("serves an immutable asset-set version and optional revalidating originals") {
      for
        assets   <- loadAssets(serveOriginals = true)
        versioned <- request(assets.routes, assets.path("test-asset.js"))
        original <- request(assets.routes, "/assets/test-asset.js")
        body      <- versioned.body.asString
      yield assertTrue(
        assets.path("test-asset.js").matches("/assets/[0-9a-f]{64}/test-asset\\.js"),
        versioned.status == Status.Ok,
        versioned.header(Header.CacheControl).contains(StaticAssetCache.default.immutable),
        original.status == Status.Ok,
        original.header(Header.CacheControl).contains(StaticAssetCache.default.revalidating),
        body.contains("scaliveTestAsset")
      )
    },
    test("rejects unknown versions and disables original paths by default") {
      for
        assets <- loadAssets()
        path = assets.path("test-asset.js")
        version = path.stripPrefix("/assets/").takeWhile(_ != '/')
        unknown  <- request(assets.routes, path.replace(version, "0" * version.length))
        original <- request(assets.routes, "/assets/test-asset.js")
      yield assertTrue(unknown.status == Status.NotFound, original.status == Status.NotFound)
    },
    test("HEAD and query-string requests retain asset metadata") {
      for
        assets <- loadAssets()
        path = assets.path("test-asset.js")
        queried <- request(assets.routes, s"$path?vsn=d")
        encodedQuery <- request(assets.routes, s"$path%3Fvsn=d")
        encodedFragment <- request(assets.routes, s"$path%23fragment")
        head    <- request(assets.routes, path, Method.HEAD)
        body    <- head.body.asString
      yield assertTrue(
        queried.status == Status.Ok,
        encodedQuery.status == Status.NotFound,
        encodedFragment.status == Status.NotFound,
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
    },
    test("encodes final path segments without changing asset lookup names") {
      for
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath(
                      "public",
                      Seq("percent%2Fasset.js"),
                      mountPath = Path.empty / "assets"
                    )
                  )
        path = assets.path("percent%2Fasset.js")
        response <- request(assets.routes, path)
      yield assertTrue(
        path.matches("/assets/[0-9a-f]{64}/percent%252Fasset\\.js"),
        response.status == Status.Ok
      )
    },
    test("encodes custom mount path segments") {
      for
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath(
                      "public",
                      Seq("test-asset.js"),
                      mountPath = Path.empty / "assets%2Fv1"
                    )
                  )
        path = assets.path("test-asset.js")
        response <- request(assets.routes, path)
      yield assertTrue(
        path.matches("/assets%252Fv1/[0-9a-f]{64}/test-asset\\.js"),
        response.status == Status.Ok
      )
    },
    test("keeps relative asset graph paths inside one immutable version namespace") {
      for
        assets <- loadGraphAssets
        appPath = assets.path("graph/app.js")
        prefix = appPath.stripSuffix("graph/app.js")
        app    <- request(assets.routes, appPath)
        chunk  <- request(assets.routes, s"${prefix}graph/chunk.js")
        style  <- request(assets.routes, s"${prefix}graph/styles/app.css")
        font   <- request(assets.routes, s"${prefix}graph/fonts/test.woff2")
        worker <- request(assets.routes, s"${prefix}graph/worker.js")
        body   <- app.body.asString
      yield assertTrue(
        body.contains("./chunk.js"),
        body.contains("./worker.js"),
        List(app, chunk, style, font, worker).forall(_.status == Status.Ok),
        List(app, chunk, style, font, worker).forall(
          _.header(Header.CacheControl).contains(StaticAssetCache.default.immutable)
        )
      )
    },
    test("serves deployment-manifest files at their final paths without renaming") {
      for
        assets <- loadDeploymentAssets()
        app     <- request(assets.routes, assets.path("app.js"))
        chunk   <- request(assets.routes, assets.path("built/chunk-c3d4.js"))
        worker  <- request(assets.routes, assets.path("built/worker-e5f6.js"))
        font    <- request(assets.routes, assets.path("built/font-f7g8.woff2"))
        stable  <- request(assets.routes, assets.path("robots.txt"))
        manifest <- request(assets.routes, "/assets/deployment-assets.json")
        entry = assets.entry("app.js")
      yield assertTrue(
        assets.path("app.js") == "/assets/built/app-a1b2.js",
        entry.logicalPath == "app.js",
        entry.sourcePath == "built/app-a1b2.js",
        entry.servedPath == "built/app-a1b2.js",
        List(app, chunk, worker, font, stable).forall(_.status == Status.Ok),
        List(app, chunk, worker, font).forall(
          _.header(Header.CacheControl).contains(StaticAssetCache.default.immutable)
        ),
        stable.header(Header.CacheControl).contains(StaticAssetCache.default.revalidating),
        manifest.status == Status.NotFound
      )
    },
    test("loads a deployment manifest from a filesystem directory") {
      ZIO.scoped {
        for
          root <- temporaryDirectory
          _    <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("built")))
          _    <- ZIO.attemptBlocking(Files.writeString(root.resolve("built/app-final.js"), "ready"))
          _ <- ZIO.attemptBlocking(
                 Files.writeString(
                   root.resolve("assets-manifest.json"),
                   """{"version":1,"assets":{"app.js":{"file":"built/app-final.js","cache":"immutable"}}}"""
                 )
               )
          assets <- StaticAssets.load(
                      StaticAssetConfig.deploymentDirectory(
                        root,
                        mountPath = Path.empty / "assets"
                      )
                    )
          response <- request(assets.routes, assets.path("app.js"))
          body     <- response.body.asString
        yield assertTrue(
          assets.path("app.js") == "/assets/built/app-final.js",
          response.status == Status.Ok,
          body == "ready"
        )
      }
    },
    test("rejects invalid deployment manifests at startup") {
      for
        missingFile <- loadDeploymentAssets("deployment-missing-file.json").exit
        invalidPath <- loadDeploymentAssets("deployment-invalid-path.json").exit
        invalidCache <- loadDeploymentAssets("deployment-invalid-cache.json").exit
        conflicting <- loadDeploymentAssets("deployment-conflicting-cache.json").exit
        duplicate <- loadDeploymentAssets("deployment-duplicate-logical.json").exit
        unsupported <- loadDeploymentAssets("deployment-unsupported-version.json").exit
      yield assertTrue(
        missingFile.isFailure,
        invalidPath.isFailure,
        invalidCache.isFailure,
        conflicting.isFailure,
        duplicate.isFailure,
        unsupported.isFailure
      )
    },
    test("revalidates changed originals but rejects changed immutable versions") {
      ZIO.scoped {
        for
          root <- temporaryDirectory
          file = root.resolve("mutable.txt")
          _ <- ZIO.attemptBlocking(Files.writeString(file, "first"))
          assets <- StaticAssets.load(
                      StaticAssetConfig.directory(
                        root,
                        mountPath = Path.empty / "assets",
                        serveOriginals = true,
                        assets = Some(Seq("mutable.txt"))
                      )
                    )
          originalBefore <- request(assets.routes, "/assets/mutable.txt")
          etagBefore = originalBefore.header(Header.ETag)
          _             <- ZIO.attemptBlocking(Files.writeString(file, "second"))
          immutable     <- request(assets.routes, assets.path("mutable.txt"))
          originalAfter <- request(assets.routes, "/assets/mutable.txt")
          body          <- originalAfter.body.asString
        yield assertTrue(
          immutable.status == Status.NotFound,
          originalAfter.status == Status.Ok,
          originalAfter.header(Header.CacheControl).contains(StaticAssetCache.default.revalidating),
          originalAfter.header(Header.ETag) != etagBefore,
          body == "second"
        )
      }
    },
    test("rejects directory symlinks that escape the configured root") {
      ZIO.scoped {
        for
          workspace <- temporaryDirectory
          root      = workspace.resolve("public")
          secret    = workspace.resolve("secret.txt")
          _        <- ZIO.attemptBlocking(Files.createDirectory(root))
          _        <- ZIO.attemptBlocking(Files.writeString(secret, "secret"))
          _        <- ZIO.attemptBlocking(Files.createSymbolicLink(root.resolve("leak.txt"), secret))
          attempted <- StaticAssets
                         .load(
                           StaticAssetConfig.directory(
                             root,
                             assets = Some(Seq("leak.txt"))
                           )
                         ).exit
        yield assertTrue(attempted.isFailure)
      }
    }
  )
end StaticAssetsSpec
