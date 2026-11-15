package scalive

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path as NioPath}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.json.*
import zio.json.ast.Json

/** Where the bytes of an asset tree or of a deployment manifest's files are: below a classpath
  * prefix or in a filesystem directory.
  *
  * A root says nothing about versioning. Pass it to [[StaticAssets.tree]] or
  * [[StaticAssets.manifest]] to choose the asset model.
  */
sealed trait StaticAssetRoot

/** Constructors for [[StaticAssetRoot]], one for each place assets can be stored. */
object StaticAssetRoot:
  /** Files packaged with the application below a classpath prefix, such as `public`.
    *
    * @param prefix
    *   The classpath prefix below which the files are stored.
    * @param classLoader
    *   The class loader that reads the resources. Defaults to the calling thread's context class
    *   loader.
    */
  def classpath(
    prefix: String,
    classLoader: ClassLoader = Thread.currentThread().getContextClassLoader
  ): StaticAssetRoot =
    Classpath(prefix, classLoader)

  /** Files below a filesystem directory. Symlinks cannot escape it. */
  def directory(path: NioPath): StaticAssetRoot =
    Directory(path)

  final private[scalive] case class Classpath(prefix: String, classLoader: ClassLoader)
      extends StaticAssetRoot

  final private[scalive] case class Directory(path: NioPath) extends StaticAssetRoot

/** Cache-control headers for immutable canonical paths and mutable revalidating paths.
  *
  * @param immutable
  *   header used by ordinary versioned paths and immutable deployment entries
  * @param revalidating
  *   header used by enabled originals and revalidating deployment entries
  */
final case class StaticAssetCache(
  immutable: Header.CacheControl,
  revalidating: Header.CacheControl)

/** The default [[StaticAssetCache]]. */
object StaticAssetCache:
  /** The default headers: `public, max-age=31536000, immutable` for immutable paths and `no-cache`
    * for revalidating paths.
    */
  val default: StaticAssetCache = StaticAssetCache(
    immutable = Header.CacheControl.Multiple(
      NonEmptyChunk(
        Header.CacheControl.Public,
        Header.CacheControl.MaxAge(31536000),
        Header.CacheControl.Immutable
      )
    ),
    revalidating = Header.CacheControl.NoCache
  )

/** The cache behavior for a served asset path. */
enum StaticAssetCachePolicy derives CanEqual:
  /** The path is pinned to its startup digest and may be cached without revalidation. */
  case Immutable

  /** The path serves current bytes with a current ETag and must revalidate. */
  case Revalidate

/** Metadata validated for one logical asset when its asset description is loaded.
  *
  * @param logicalPath
  *   name accepted by [[StaticAssets.path]], [[StaticAssets.pathOption]], and
  *   [[StaticAssets.entry]]
  * @param sourcePath
  *   relative classpath resource or filesystem path read when serving the asset
  * @param servedPath
  *   canonical relative URL below the configured mount path
  * @param digest
  *   SHA-256 digest of the bytes read at load time
  * @param size
  *   byte count observed at load time
  * @param mediaType
  *   response media type inferred from the source path
  * @param cachePolicy
  *   policy for the canonical served path; it does not describe an optional ordinary original
  */
final case class StaticAssetEntry(
  logicalPath: String,
  sourcePath: String,
  servedPath: String,
  digest: String,
  size: Long,
  mediaType: MediaType,
  cachePolicy: StaticAssetCachePolicy)

/** Bytes an asset is served as, with the strong ETag that identifies them. */
final private[scalive] case class StaticAssetBytes(bytes: Chunk[Byte], etag: String)

/** The bytes of an asset as loaded, and the precompressed copies found next to it by content
  * coding, the preferred first.
  */
final private[scalive] case class StaticAssetContent(
  loaded: StaticAssetBytes,
  encoded: Vector[(String, StaticAssetBytes)])

final private[scalive] case class ServedStaticAsset(
  entry: StaticAssetEntry,
  cachePolicy: StaticAssetCachePolicy,
  content: StaticAssetContent)

final private[scalive] case class StaticAssetManifest(
  entries: Map[String, StaticAssetEntry],
  servedEntries: Map[String, ServedStaticAsset],
  originalEntries: Map[String, ServedStaticAsset]):

  def get(path: String): Option[StaticAssetEntry] =
    StaticAssets.normalizeRelativePath(path).toOption.flatMap(entries.get)

  def apply(path: String): StaticAssetEntry =
    get(path).getOrElse(throw new IllegalArgumentException(s"Static asset not found: $path"))

  def served(path: String, includeOriginals: Boolean): Option[ServedStaticAsset] =
    StaticAssets.normalizeServedPath(path).toOption.flatMap { normalized =>
      servedEntries
        .get(normalized)
        .orElse(originalEntries.get(normalized).filter(_ => includeOriginals))
    }

/** Loaded assets with URL helpers, HTML tag helpers, and HTTP routes. */
final class StaticAssets private (
  mountPath: Path,
  cache: StaticAssetCache,
  serveOriginals: Boolean,
  manifest: StaticAssetManifest):

  private val mountPrefix = StaticAssets.mountPrefix(mountPath)

  /** Returns the mounted, root-relative URL for an asset's versioned or final path. */
  def path(asset: String): String =
    url(manifest(asset).servedPath)

  /** Returns the mounted, root-relative URL when the asset is present. */
  def pathOption(asset: String): Option[String] =
    manifest.get(asset).map(entry => url(entry.servedPath))

  /** Returns the load-time metadata for an asset. */
  def entry(asset: String): StaticAssetEntry =
    manifest(asset)

  /** Renders a stylesheet `<link>` using the asset's canonical URL. */
  def stylesheet[Msg](asset: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    linkTag(rel := "stylesheet", href := path(asset), Mod.flatten(mods))

  /** Renders a tracked stylesheet `<link>` using the asset's versioned or final URL. */
  def trackedStylesheet[Msg](asset: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    linkTag(phx.trackStatic := true, rel := "stylesheet", href := path(asset), Mod.flatten(mods))

  /** Renders a `<script>` using the asset's canonical URL. */
  def script[Msg](asset: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    scriptTag(src := path(asset), Mod.flatten(mods))

  /** Renders a tracked `<script>` using the asset's versioned or final URL. */
  def trackedScript[Msg](asset: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    scriptTag(phx.trackStatic := true, src := path(asset), Mod.flatten(mods))

  /** GET and HEAD routes serving loaded assets below the configured mount path. */
  val routes: Routes[Any, Nothing] =
    Routes
      .fromIterable(
        List(
          Method.GET / Path.toPathCodec(mountPath) / PathCodec.trailing -> handler {
            (assetPath: Path, request: Request) => serve(assetPath, request, includeBody = true)
          },
          Method.HEAD / Path.toPathCodec(mountPath) / PathCodec.trailing -> handler {
            (assetPath: Path, request: Request) => serve(assetPath, request, includeBody = false)
          }
        )
      ).handleErrorZIO(error => ZIO.logErrorCause(Cause.fail(error)).as(Response.notFound))

  private def url(servedPath: String): String =
    val encodedPath = StaticAssets.encodeRelativePath(servedPath)
    if mountPrefix.isEmpty then s"/$encodedPath" else s"$mountPrefix/$encodedPath"

  private def serve(assetPath: Path, request: Request, includeBody: Boolean): UIO[Response] =
    val served = StaticAssets
      .requestRelativePath(assetPath)
      .flatMap(manifest.served(_, serveOriginals))
    served match
      case None         => ZIO.succeed(Response.notFound)
      case Some(served) =>
        val content = served.content
        val coding  = content.encoded.find((coding, _) => StaticAssets.accepts(request, coding))
        val bytes   = coding.fold(content.loaded)(_._2)
        val cacheControl = served.cachePolicy match
          case StaticAssetCachePolicy.Immutable  => cache.immutable
          case StaticAssetCachePolicy.Revalidate => cache.revalidating
        val headers = StaticAssets.headers(served.entry, cacheControl, bytes.etag) ++
          coding.fold(Headers.empty)((name, _) => Headers("Content-Encoding", name)) ++
          (if content.encoded.isEmpty then Headers.empty else Headers("Vary", "Accept-Encoding"))
        if StaticAssets.holds(request, bytes.etag) then
          ZIO.succeed(Response(status = Status.NotModified, headers = headers))
        else
          val body = if includeBody then Body.fromChunk(bytes.bytes) else Body.empty
          ZIO.succeed(Response(status = Status.Ok, headers = headers, body = body))
end StaticAssets

/** The startup loaders for [[StaticAssets]], one for each asset model.
  *
  * Every asset is read once, at load time, and served from memory, along with the precompressed
  * copies found next to it (`app.js.br`, `app.js.gz`), which are sent to browsers that accept them.
  */
object StaticAssets:
  final private case class LoadedAsset(path: String, digest: String, content: StaticAssetContent)

  // The content codings a precompressed copy may be stored in, next to its asset with this
  // extension, the preferred first.
  private val precompressed = Vector("br" -> ".br", "gzip" -> ".gz")

  final private case class DeploymentManifestAsset(file: String, cache: String) derives JsonDecoder

  final private case class DeploymentManifest(
    version: Int,
    assets: Json.Obj)
      derives JsonDecoder

  final private case class NormalizedDeploymentAsset(
    logicalPath: String,
    sourcePath: String,
    cachePolicy: StaticAssetCachePolicy)

  /** Loads an asset tree: files that Scalive versions.
    *
    * Scalive serves the unchanged tree below one immutable asset-set version, so relative
    * references between its files keep working and any change moves every URL. Choose it when the
    * build does not put content hashes in file names.
    *
    * @param root
    *   Where the tree is stored.
    * @param only
    *   The relative paths to serve. `None` serves every regular file below a directory root; a
    *   classpath root requires the list.
    * @param mountPath
    *   The HTTP path below which the assets are served.
    * @param serveOriginals
    *   Whether the unversioned original paths are also served, with revalidating cache headers.
    * @param cache
    *   The response cache-control headers for versioned and original paths.
    */
  def tree(
    root: StaticAssetRoot,
    only: Option[Iterable[String]] = None,
    mountPath: Path = Path.empty / "static",
    serveOriginals: Boolean = false,
    cache: StaticAssetCache = StaticAssetCache.default
  ): Task[StaticAssets] =
    for
      assets <- only match
                  case Some(paths) => normalizeConfiguredAssets(paths)
                  case None        => discover(root)
      loaded <- ZIO.foreach(assets)(loadAsset(root, _))
      version  = assetSetDigest(loaded)
      contents = loaded.map(asset => asset.path -> asset.content).toMap
      entries  = loaded.map { asset =>
                  entry(
                    logicalPath = asset.path,
                    sourcePath = asset.path,
                    servedPath = s"$version/${asset.path}",
                    asset,
                    StaticAssetCachePolicy.Immutable
                  )
                }
      byLogical = entries.map(entry => entry.logicalPath -> entry).toMap
      served    = entries
                 .map(entry =>
                   entry.servedPath -> ServedStaticAsset(
                     entry,
                     StaticAssetCachePolicy.Immutable,
                     contents(entry.sourcePath)
                   )
                 ).toMap
      originals =
        entries
          .map(entry =>
            entry.sourcePath -> ServedStaticAsset(
              entry,
              StaticAssetCachePolicy.Revalidate,
              contents(entry.sourcePath)
            )
          ).toMap
    yield new StaticAssets(
      mountPath,
      cache,
      serveOriginals,
      StaticAssetManifest(byLogical, served, originals)
    )

  /** Loads the files a deployment manifest lists: files that the build versions.
    *
    * The build puts a content hash in each file name and writes the manifest, so every file is
    * served at its final path without renaming and unchanged files keep their URLs across
    * deployments. Choose it when the build puts content hashes in file names.
    *
    * Version 1 of the manifest maps logical aliases to an exact relative `file` and an `immutable`
    * or `revalidate` cache policy.
    *
    * @param root
    *   Where the manifest and its files are stored.
    * @param name
    *   The manifest's path relative to `root`.
    * @param mountPath
    *   The HTTP path below which the assets are served.
    * @param cache
    *   The response cache-control headers for immutable and revalidating entries.
    */
  def manifest(
    root: StaticAssetRoot,
    name: String = "assets-manifest.json",
    mountPath: Path = Path.empty / "static",
    cache: StaticAssetCache = StaticAssetCache.default
  ): Task[StaticAssets] =
    for
      manifestPath  <- normalizePath(name)
      manifestBytes <- read(root, manifestPath).flatMap {
                         case Some(bytes) => ZIO.succeed(bytes)
                         case None        =>
                           ZIO.fail(
                             new IllegalArgumentException(
                               s"Static asset deployment manifest not found: $manifestPath"
                             )
                           )
                       }
      parsed <- ZIO.fromEither(
                  new String(manifestBytes, StandardCharsets.UTF_8)
                    .fromJson[DeploymentManifest]
                    .left.map(error =>
                      new IllegalArgumentException(
                        s"Invalid static asset deployment manifest $manifestPath: $error"
                      )
                    )
                )
      _ <-
        ZIO
          .fail(
            new IllegalArgumentException(
              s"Unsupported static asset deployment manifest version ${parsed.version}; expected 1"
            )
          ).unless(parsed.version == 1)
      definitions <-
        ZIO.foreach(parsed.assets.fields.toList.sortBy(_._1)) { case (logicalPath, assetJson) =>
          for
            asset <-
              ZIO.fromEither(
                assetJson
                  .as[DeploymentManifestAsset].left.map(error =>
                    new IllegalArgumentException(
                      s"Invalid static asset $logicalPath in deployment manifest $manifestPath: $error"
                    )
                  )
              )
            normalizedLogical <- normalizePath(logicalPath)
            normalizedFile    <- normalizePath(asset.file)
            cachePolicy       <- deploymentCachePolicy(asset.cache, logicalPath)
          yield NormalizedDeploymentAsset(
            normalizedLogical,
            normalizedFile,
            cachePolicy
          )
        }
      _      <- rejectDuplicateLogicalPaths(definitions)
      _      <- rejectConflictingCachePolicies(definitions)
      loaded <- ZIO.foreach(definitions.map(_.sourcePath).distinct)(
                  loadAsset(root, _)
                )
      loadedByPath = loaded.map(asset => asset.path -> asset).toMap
      entries      = definitions.map { definition =>
                  entry(
                    logicalPath = definition.logicalPath,
                    sourcePath = definition.sourcePath,
                    servedPath = definition.sourcePath,
                    loadedByPath(definition.sourcePath),
                    definition.cachePolicy
                  )
                }
      byLogical = entries.map(entry => entry.logicalPath -> entry).toMap
      served    = entries
                 .groupBy(_.servedPath).view.mapValues(_.head).toMap
                 .map { case (path, entry) =>
                   path -> ServedStaticAsset(
                     entry,
                     entry.cachePolicy,
                     loadedByPath(entry.sourcePath).content
                   )
                 }
    yield new StaticAssets(
      mountPath,
      cache,
      serveOriginals = false,
      StaticAssetManifest(byLogical, served, Map.empty)
    )

  private def normalizePath(path: String): Task[String] =
    val normalized =
      if path.startsWith("/") then Left(s"Static asset path must be relative: $path")
      else if path.exists(character => character == '?' || character == '#') then
        Left(s"Static asset path must not contain a query or fragment: $path")
      else normalizeRelativePath(path)
    ZIO.fromEither(normalized).mapError(new IllegalArgumentException(_))

  private def deploymentCachePolicy(
    cache: String,
    logicalPath: String
  ): Task[StaticAssetCachePolicy] =
    cache match
      case "immutable"  => ZIO.succeed(StaticAssetCachePolicy.Immutable)
      case "revalidate" => ZIO.succeed(StaticAssetCachePolicy.Revalidate)
      case value        =>
        ZIO.fail(
          new IllegalArgumentException(
            s"Invalid cache policy '$value' for static asset $logicalPath; expected immutable or revalidate"
          )
        )

  private def rejectDuplicateLogicalPaths(
    assets: List[NormalizedDeploymentAsset]
  ): Task[Unit] =
    assets.groupBy(_.logicalPath).collectFirst {
      case (path, values) if values.size > 1 => path
    } match
      case Some(path) =>
        ZIO.fail(
          new IllegalArgumentException(
            s"Static asset deployment manifest contains duplicate logical path after normalization: $path"
          )
        )
      case None => ZIO.unit

  private def rejectConflictingCachePolicies(
    assets: List[NormalizedDeploymentAsset]
  ): Task[Unit] =
    assets
      .groupBy(_.sourcePath).collectFirst {
        case (path, values) if values.map(_.cachePolicy).distinct.size > 1 => path
      } match
      case Some(path) =>
        ZIO.fail(
          new IllegalArgumentException(
            s"Static asset deployment manifest assigns conflicting cache policies to $path"
          )
        )
      case None => ZIO.unit

  private def loadAsset(root: StaticAssetRoot, relativePath: String): Task[LoadedAsset] =
    read(root, relativePath).flatMap {
      case Some(bytes) =>
        val digest = sha256(bytes)
        ZIO
          .foreach(precompressed) { (coding, extension) =>
            read(root, relativePath + extension).map(
              _.map(copy => coding -> StaticAssetBytes(Chunk.fromArray(copy), sha256(copy)))
            )
          }.map { copies =>
            val loaded = StaticAssetBytes(Chunk.fromArray(bytes), digest)
            LoadedAsset(relativePath, digest, StaticAssetContent(loaded, copies.flatten))
          }
      case None =>
        ZIO.fail(new IllegalArgumentException(s"Static asset not found: $relativePath"))
    }

  private[scalive] def normalizeRelativePath(path: String): Either[String, String] =
    val noQuery      = path.takeWhile(character => character != '?' && character != '#')
    val relativePath = noQuery.stripPrefix("/")
    val parts        = relativePath.split("/", -1).toList

    if relativePath.isEmpty then Left("Static asset path is empty")
    else if relativePath.contains('\\') then Left(s"Static asset path contains a backslash: $path")
    else if parts.exists(part => part.isEmpty || part == "." || part == "..") then
      Left(s"Static asset path must be relative and normalized: $path")
    else Right(parts.mkString("/"))

  private[scalive] def normalizeServedPath(path: String): Either[String, String] =
    if path.exists(character => character == '?' || character == '#') then
      Left(s"Static asset request path contains a query or fragment delimiter: $path")
    else normalizeRelativePath(path)

  private def discover(root: StaticAssetRoot): Task[List[String]] =
    root match
      case StaticAssetRoot.Classpath(prefix, _) =>
        ZIO.fail(
          new IllegalArgumentException(
            s"The files of the classpath asset tree below '$prefix' cannot be listed; pass their relative paths as `only`"
          )
        )
      case StaticAssetRoot.Directory(directory) =>
        ZIO
          .attemptBlocking {
            val normalizedRoot = directory.toRealPath()
            val stream         = Files.walk(normalizedRoot)
            try
              stream
                .iterator().asScala.filter(Files.isRegularFile(_, LinkOption.NOFOLLOW_LINKS))
                .map(path => toRelativePath(normalizedRoot.relativize(path))).toList
            finally stream.close()
          }.flatMap(normalizeConfiguredAssets)

  private def normalizeConfiguredAssets(assets: Iterable[String]): Task[List[String]] =
    ZIO.foreach(assets.toList.sorted)(normalizePath)

  private def read(root: StaticAssetRoot, relativePath: String): Task[Option[Array[Byte]]] =
    normalizeRelativePath(relativePath) match
      case Left(error) => ZIO.fail(new IllegalArgumentException(error))
      case Right(path) =>
        root match
          case StaticAssetRoot.Classpath(prefix, loader) =>
            val resource = s"${prefix.stripSuffix("/")}/$path"
            ZIO.attemptBlocking(Option(loader.getResourceAsStream(resource)).map(readAllBytes))
          case StaticAssetRoot.Directory(directory) =>
            readDirectory(directory, path)

  private def readDirectory(root: NioPath, path: String): Task[Option[Array[Byte]]] =
    ZIO.attemptBlocking {
      val realRoot  = root.toRealPath()
      val candidate = realRoot.resolve(path).normalize()
      if !candidate.startsWith(realRoot) || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) then
        None
      else
        val realTarget = candidate.toRealPath()
        if realTarget.startsWith(realRoot) &&
          Files.isRegularFile(realTarget, LinkOption.NOFOLLOW_LINKS)
        then Some(Files.readAllBytes(realTarget))
        else None
    }

  private def readAllBytes(stream: InputStream): Array[Byte] =
    try stream.readAllBytes()
    finally stream.close()

  private def entry(
    logicalPath: String,
    sourcePath: String,
    servedPath: String,
    asset: LoadedAsset,
    cachePolicy: StaticAssetCachePolicy
  ): StaticAssetEntry =
    StaticAssetEntry(
      logicalPath = logicalPath,
      sourcePath = sourcePath,
      servedPath = servedPath,
      digest = asset.digest,
      size = asset.content.loaded.bytes.length.toLong,
      mediaType = mediaType(sourcePath),
      cachePolicy = cachePolicy
    )

  private def encodeRelativePath(path: String): String =
    val segments = path.split('/').foldLeft(Path.empty)((encoded, segment) => encoded / segment)
    URL(path = segments).encode.stripPrefix("/")

  private def requestRelativePath(path: Path): Option[String] =
    Option.unless(path.segments.exists(segment => segment.contains('/') || segment.contains('\\')))(
      path.segments.mkString("/")
    )

  private[scalive] def sha256(bytes: Array[Byte]): String =
    hexadecimal(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def assetSetDigest(assets: List[LoadedAsset]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    assets.sortBy(_.path).foreach { asset =>
      updateLengthPrefixed(digest, asset.path.getBytes(StandardCharsets.UTF_8))
      updateLengthPrefixed(digest, asset.digest.getBytes(StandardCharsets.US_ASCII))
    }
    hexadecimal(digest.digest())

  private def updateLengthPrefixed(digest: MessageDigest, bytes: Array[Byte]): Unit =
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array())
    digest.update(bytes)

  private def hexadecimal(bytes: Array[Byte]): String =
    bytes.map(byte => f"$byte%02x").mkString

  private def toRelativePath(path: NioPath): String =
    path.iterator().asScala.map(_.toString).mkString("/")

  private def mountPrefix(path: Path): String =
    val encoded = URL(path = path.addLeadingSlash.dropTrailingSlash).encode
    if encoded == "/" then "" else encoded

  /** Whether `request` says the browser holds the bytes whose ETag is `digest`.
    *
    * `If-None-Match` lists entity tags, or is `*`, and compares them weakly, as RFC 9110 requires.
    */
  private def holds(request: Request, digest: String): Boolean =
    request.rawHeader("If-None-Match").exists { value =>
      value.split(',').iterator.map(_.trim).exists { tag =>
        tag == "*" || tag.stripPrefix("W/") == s"\"$digest\""
      }
    }

  /** Whether `request` accepts the content coding `coding`: `Accept-Encoding` names it without a
    * zero quality value, as RFC 9110 defines.
    */
  private def accepts(request: Request, coding: String): Boolean =
    request.rawHeader("Accept-Encoding").exists { value =>
      value.split(',').iterator.map(_.split(';').map(_.trim)).exists { parts =>
        parts.head.equalsIgnoreCase(coding) && !parts.tail.exists { parameter =>
          val lower = parameter.toLowerCase
          lower.startsWith("q=") && lower.drop(2).toDoubleOption.contains(0.0)
        }
      }
    }

  private def headers(
    entry: StaticAssetEntry,
    cacheControl: Header.CacheControl,
    etag: String
  ): Headers =
    Headers(
      Header.ContentType(entry.mediaType),
      cacheControl,
      Header.ETag.Strong(etag)
    )

  private def mediaType(relativePath: String): MediaType =
    MediaType
      .forFileExtension(fileExtension(relativePath))
      .getOrElse(MediaType("application", "octet-stream", binary = true))

  private def fileExtension(relativePath: String): String =
    val file     = relativePath.substring(relativePath.lastIndexOf('/') + 1)
    val dotIndex = file.lastIndexOf('.')
    if dotIndex >= 0 && dotIndex < file.length - 1 then file.substring(dotIndex + 1).toLowerCase
    else ""
end StaticAssets
