package scalive

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.*
import zio.test.*

object StaticAssetsSpec extends ZIOSpecDefault:
  private def loadAssets(serveOriginals: Boolean = false): Task[StaticAssets] =
    StaticAssets.tree(
      StaticAssetRoot.classpath("public"),
      only = Some(Seq("test-asset.js")),
      mountPath = Path.empty / "assets",
      serveOriginals = serveOriginals
    )

  private def loadGraphAssets: Task[StaticAssets] =
    StaticAssets.tree(
      StaticAssetRoot.classpath("public"),
      only = Some(
        Seq(
          "graph/app.js",
          "graph/chunk.js",
          "graph/styles/app.css",
          "graph/fonts/test.woff2",
          "graph/worker.js"
        )
      ),
      mountPath = Path.empty / "assets"
    )

  private def loadDeploymentAssets(manifest: String = "deployment-assets.json") =
    StaticAssets.manifest(
      StaticAssetRoot.classpath("public"),
      manifest,
      mountPath = Path.empty / "assets"
    )

  private def request(
    routes: Routes[Any, Nothing],
    path: String,
    method: Method = Method.GET,
    headers: Headers = Headers.empty
  ) =
    ZIO.fromEither(URL.decode(path)).orDie.flatMap(url =>
      ZIO.scoped(routes.runZIO(Request(method = method, url = url, headers = headers)))
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

  private def classLoader(classpath: java.nio.file.Path): ZIO[Scope, Throwable, ClassLoader] =
    ZIO.fromAutoCloseable(
      ZIO.attemptBlocking(new java.net.URLClassLoader(Array(classpath.toUri.toURL), null))
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
    test("answers a request for the copy the browser holds with Not Modified") {
      def ifNoneMatch(value: String) = Headers(Header.Custom("If-None-Match", value))
      for
        assets <- loadAssets(serveOriginals = true)
        digest = assets.entry("test-asset.js").digest
        held      <- request(assets.routes, "/assets/test-asset.js", headers = ifNoneMatch(s"\"$digest\""))
        weak      <- request(assets.routes, "/assets/test-asset.js", headers = ifNoneMatch(s"W/\"$digest\""))
        listed    <- request(assets.routes, "/assets/test-asset.js", headers = ifNoneMatch(s"\"other\", \"$digest\""))
        any       <- request(assets.routes, "/assets/test-asset.js", headers = ifNoneMatch("*"))
        stale     <- request(assets.routes, "/assets/test-asset.js", headers = ifNoneMatch("\"other\""))
        heldBody  <- held.body.asString
        staleBody <- stale.body.asString
      yield assertTrue(
        held.status == Status.NotModified,
        heldBody.isEmpty,
        held.header(Header.ETag).contains(Header.ETag.Strong(digest)),
        held.header(Header.CacheControl).contains(StaticAssetCache.default.revalidating),
        weak.status == Status.NotModified,
        listed.status == Status.NotModified,
        any.status == Status.NotModified,
        stale.status == Status.Ok,
        staleBody.contains("scaliveTestAsset")
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
        assets <- StaticAssets.tree(
                    StaticAssetRoot.classpath("public"),
                    only = Some(Seq("percent%2Fasset.js")),
                    mountPath = Path.empty / "assets"
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
        assets <- StaticAssets.tree(
                    StaticAssetRoot.classpath("public"),
                    only = Some(Seq("test-asset.js")),
                    mountPath = Path.empty / "assets%2Fv1"
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
          assets <- StaticAssets.manifest(
                      StaticAssetRoot.directory(root),
                      mountPath = Path.empty / "assets"
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
    test("serves the bytes loaded at startup after their file changes") {
      ZIO.scoped {
        for
          root <- temporaryDirectory
          file = root.resolve("mutable.txt")
          _ <- ZIO.attemptBlocking(Files.writeString(file, "first"))
          assets <- StaticAssets.tree(
                      StaticAssetRoot.directory(root),
                      only = Some(Seq("mutable.txt")),
                      mountPath = Path.empty / "assets",
                      serveOriginals = true
                    )
          _             <- ZIO.attemptBlocking(Files.writeString(file, "second"))
          immutable     <- request(assets.routes, assets.path("mutable.txt"))
          original      <- request(assets.routes, "/assets/mutable.txt")
          immutableBody <- immutable.body.asString
          originalBody  <- original.body.asString
        yield assertTrue(
          immutable.status == Status.Ok,
          immutableBody == "first",
          original.status == Status.Ok,
          original.header(Header.CacheControl).contains(StaticAssetCache.default.revalidating),
          original.header(Header.ETag).contains(Header.ETag.Strong(assets.entry("mutable.txt").digest)),
          originalBody == "first"
        )
      }
    },
    test("serves a precompressed copy next to an asset to a browser that accepts it") {
      def accepting(value: String) = Headers(Header.Custom("Accept-Encoding", value))
      def encoding(response: Response) = response.rawHeader("Content-Encoding")
      def vary(response: Response)     = response.rawHeader("Vary")
      ZIO.scoped {
        for
          root <- temporaryDirectory
          _    <- ZIO.attemptBlocking(Files.writeString(root.resolve("app.js"), "plain"))
          _    <- ZIO.attemptBlocking(Files.writeString(root.resolve("app.js.br"), "brotli"))
          _    <- ZIO.attemptBlocking(Files.writeString(root.resolve("app.js.gz"), "gzip"))
          _    <- ZIO.attemptBlocking(Files.writeString(root.resolve("alone.js"), "alone"))
          assets <- StaticAssets.tree(
                      StaticAssetRoot.directory(root),
                      only = Some(Seq("app.js", "alone.js")),
                      mountPath = Path.empty / "assets"
                    )
          path = assets.path("app.js")
          brotli    <- request(assets.routes, path, headers = accepting("gzip, deflate, br"))
          gzip      <- request(assets.routes, path, headers = accepting("gzip"))
          refused   <- request(assets.routes, path, headers = accepting("br;q=0, gzip;q=0.5"))
          identity  <- request(assets.routes, path)
          alone     <- request(assets.routes, assets.path("alone.js"), headers = accepting("br"))
          brotliTag  = brotli.header(Header.ETag).map(_.renderedValue).getOrElse("")
          held      <- request(
                         assets.routes,
                         path,
                         headers = accepting("br") ++ Headers(Header.Custom("If-None-Match", brotliTag))
                       )
          otherCopy <- request(
                         assets.routes,
                         path,
                         headers = Headers(Header.Custom("If-None-Match", brotliTag))
                       )
          head          <- request(assets.routes, path, Method.HEAD, accepting("br"))
          brotliBody    <- brotli.body.asString
          gzipBody      <- gzip.body.asString
          refusedBody   <- refused.body.asString
          identityBody  <- identity.body.asString
          aloneBody     <- alone.body.asString
          headBody      <- head.body.asString
        yield assertTrue(
          brotliBody == "brotli",
          encoding(brotli).contains("br"),
          gzipBody == "gzip",
          encoding(gzip).contains("gzip"),
          refusedBody == "gzip",
          identityBody == "plain",
          encoding(identity).isEmpty,
          List(brotli, gzip, identity).forall(vary(_).contains("Accept-Encoding")),
          brotli.header(Header.ETag) != identity.header(Header.ETag),
          brotli.header(Header.ETag) != gzip.header(Header.ETag),
          held.status == Status.NotModified,
          otherCopy.status == Status.Ok,
          head.status == Status.Ok,
          encoding(head).contains("br"),
          headBody.isEmpty,
          aloneBody == "alone",
          encoding(alone).isEmpty,
          vary(alone).isEmpty
        )
      }
    },
    test("applies the configured cache headers to a tree and to a manifest") {
      val cache = StaticAssetCache(
        immutable = Header.CacheControl.MaxAge(60),
        revalidating = Header.CacheControl.NoStore
      )
      for
        tree <- StaticAssets.tree(
                  StaticAssetRoot.classpath("public"),
                  only = Some(Seq("test-asset.js")),
                  serveOriginals = true,
                  cache = cache
                )
        manifest <- StaticAssets.manifest(
                      StaticAssetRoot.classpath("public"),
                      "deployment-assets.json",
                      cache = cache
                    )
        versioned <- request(tree.routes, tree.path("test-asset.js"))
        original  <- request(tree.routes, "/static/test-asset.js")
        hashed    <- request(manifest.routes, manifest.path("app.js"))
        stable    <- request(manifest.routes, manifest.path("robots.txt"))
      yield assertTrue(
        versioned.header(Header.CacheControl).contains(cache.immutable),
        original.header(Header.CacheControl).contains(cache.revalidating),
        hashed.header(Header.CacheControl).contains(cache.immutable),
        stable.header(Header.CacheControl).contains(cache.revalidating)
      )
    },
    test("reads a classpath root through the class loader it names") {
      ZIO.scoped {
        for
          directory <- temporaryDirectory
          _         <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("site")))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/app.js"), "loaded"))
          loader    <- ZIO.fromAutoCloseable(
                         ZIO.attemptBlocking(
                           new java.net.URLClassLoader(Array(directory.toUri.toURL), null)
                         )
                       )
          assets   <- StaticAssets.tree(
                        StaticAssetRoot.classpath("site", loader),
                        only = Some(Seq("app.js"))
                      )
          response <- request(assets.routes, assets.path("app.js"))
          body     <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body == "loaded")
      }
    },
    test("discovers every regular file of a directory tree without a restriction list") {
      ZIO.scoped {
        for
          root     <- temporaryDirectory
          _        <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("fonts")))
          _        <- ZIO.attemptBlocking(Files.writeString(root.resolve("app.js"), "app"))
          _        <- ZIO.attemptBlocking(Files.writeString(root.resolve("fonts/a.woff2"), "font"))
          assets   <- StaticAssets.tree(StaticAssetRoot.directory(root))
          app      <- request(assets.routes, assets.path("app.js"))
          font     <- request(assets.routes, assets.path("fonts/a.woff2"))
        yield assertTrue(app.status == Status.Ok, font.status == Status.Ok)
      }
    },
    test("discovers every file of a classpath tree in a directory on the class loader") {
      ZIO.scoped {
        for
          directory <- temporaryDirectory
          _         <- ZIO.attemptBlocking(Files.createDirectories(directory.resolve("site/fonts")))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/app.js"), "app"))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/fonts/a.woff2"), "font"))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("outside.txt"), "outside"))
          loader    <- classLoader(directory)
          assets    <- StaticAssets.tree(StaticAssetRoot.classpath("site", loader))
          app       <- request(assets.routes, assets.path("app.js"))
          font      <- request(assets.routes, assets.path("fonts/a.woff2"))
          appBody   <- app.body.asString
          fontBody  <- font.body.asString
        yield assertTrue(
          app.status == Status.Ok,
          appBody == "app",
          font.status == Status.Ok,
          fontBody == "font",
          assets.pathOption("outside.txt").isEmpty,
          assets.pathOption("../outside.txt").isEmpty
        )
      }
    },
    test("discovers every file of a classpath tree inside a JAR") {
      ZIO.scoped {
        for
          directory <- temporaryDirectory
          jar        = directory.resolve("site.jar")
          _         <- ZIO.attemptBlocking {
                         val output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))
                         try
                           def add(name: String, content: Option[String]): Unit =
                             output.putNextEntry(new java.util.jar.JarEntry(name))
                             content.foreach(text => output.write(text.getBytes("UTF-8")))
                             output.closeEntry()
                           add("site/", None)
                           add("site/app.js", Some("app"))
                           add("site/fonts/", None)
                           add("site/fonts/a.woff2", Some("font"))
                           add("sitemap.txt", Some("outside"))
                         finally output.close()
                       }
          loader    <- classLoader(jar)
          assets    <- StaticAssets.tree(StaticAssetRoot.classpath("site", loader))
          app       <- request(assets.routes, assets.path("app.js"))
          font      <- request(assets.routes, assets.path("fonts/a.woff2"))
          appBody   <- app.body.asString
          fontBody  <- font.body.asString
        yield assertTrue(
          app.status == Status.Ok,
          appBody == "app",
          font.status == Status.Ok,
          fontBody == "font",
          assets.pathOption("fonts").isEmpty,
          assets.pathOption("sitemap.txt").isEmpty
        )
      }
    },
    test("fails loading a classpath tree that cannot be listed and says to pass a restriction list") {
      val remote = java.net.URI.create("http://assets.invalid/site").toURL
      val unlistable = new ClassLoader(null):
        override def getResources(name: String): java.util.Enumeration[java.net.URL] =
          java.util.Collections.enumeration(java.util.List.of(remote))
      for attempted <- StaticAssets.tree(StaticAssetRoot.classpath("site", unlistable)).either
      yield assertTrue(
        attempted.left.exists(_.getMessage.contains("`only`")),
        attempted.left.exists(_.getMessage.contains("http://assets.invalid/site"))
      )
    },
    test("fails loading a classpath tree whose prefix the class loader cannot find") {
      ZIO.scoped {
        for
          directory <- temporaryDirectory
          loader    <- classLoader(directory)
          attempted <- StaticAssets.tree(StaticAssetRoot.classpath("site", loader)).either
        yield assertTrue(
          attempted.left.exists(_.getMessage.contains("'site'")),
          attempted.left.exists(_.getMessage.contains("`only`"))
        )
      }
    },
    test("a restriction list limits a tree to the listed files") {
      ZIO.scoped {
        for
          directory <- temporaryDirectory
          _         <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("site")))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/app.js"), "app"))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/private.txt"), "private"))
          loader    <- classLoader(directory)
          roots      = List(
                         StaticAssetRoot.classpath("site", loader),
                         StaticAssetRoot.directory(directory.resolve("site"))
                       )
          results   <- ZIO.foreach(roots) { root =>
                         for
                           assets  <- StaticAssets.tree(root, only = Some(Seq("app.js")), serveOriginals = true)
                           listed  <- request(assets.routes, "/static/app.js")
                           omitted <- request(assets.routes, "/static/private.txt")
                         yield assertTrue(
                           listed.status == Status.Ok,
                           omitted.status == Status.NotFound,
                           assets.pathOption("private.txt").isEmpty
                         )
                       }
        yield results.reduce(_ && _)
      }
    },
    test("a discovered tree serves a precompressed copy as an encoding, not as an asset of its own") {
      ZIO.scoped {
        for
          directory <- temporaryDirectory
          _         <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("site")))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/app.js"), "plain"))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/app.js.br"), "brotli"))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/app.js.gz"), "gzip"))
          _         <- ZIO.attemptBlocking(Files.writeString(directory.resolve("site/archive.tar.gz"), "archive"))
          loader    <- classLoader(directory)
          roots      = List(
                         StaticAssetRoot.classpath("site", loader),
                         StaticAssetRoot.directory(directory.resolve("site"))
                       )
          results   <- ZIO.foreach(roots) { root =>
                         for
                           assets  <- StaticAssets.tree(root, serveOriginals = true)
                           encoded <- request(
                                        assets.routes,
                                        assets.path("app.js"),
                                        headers = Headers(Header.Custom("Accept-Encoding", "br"))
                                      )
                           body    <- encoded.body.asString
                           brotli  <- request(assets.routes, "/static/app.js.br")
                           gzip    <- request(assets.routes, "/static/app.js.gz")
                           archive <- request(assets.routes, assets.path("archive.tar.gz"))
                         yield assertTrue(
                           body == "brotli",
                           encoded.rawHeader("Content-Encoding").contains("br"),
                           assets.pathOption("app.js.br").isEmpty,
                           assets.pathOption("app.js.gz").isEmpty,
                           brotli.status == Status.NotFound,
                           gzip.status == Status.NotFound,
                           archive.status == Status.Ok
                         )
                       }
        yield results.reduce(_ && _)
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
                         .tree(StaticAssetRoot.directory(root), only = Some(Seq("leak.txt")))
                         .exit
        yield assertTrue(attempted.isFailure)
      }
    },
    test("a discovered classpath tree does not publish a symlink to a file outside it") {
      ZIO.scoped {
        for
          workspace <- temporaryDirectory
          classes    = workspace.resolve("classes")
          secret     = workspace.resolve("secret.txt")
          _         <- ZIO.attemptBlocking(Files.createDirectories(classes.resolve("site")))
          _         <- ZIO.attemptBlocking(Files.writeString(classes.resolve("site/app.js"), "app"))
          _         <- ZIO.attemptBlocking(Files.writeString(secret, "secret"))
          _         <- ZIO.attemptBlocking(
                         Files.createSymbolicLink(classes.resolve("site/leak.txt"), secret)
                       )
          loader    <- classLoader(classes)
          assets    <- StaticAssets.tree(StaticAssetRoot.classpath("site", loader))
          app       <- request(assets.routes, assets.path("app.js"))
        yield assertTrue(app.status == Status.Ok, assets.pathOption("leak.txt").isEmpty)
      }
    }
  )
end StaticAssetsSpec
