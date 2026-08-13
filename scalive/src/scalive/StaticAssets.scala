package scalive

import java.io.InputStream
import java.nio.file.{Files, Path as NioPath}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.*
import zio.http.codec.PathCodec

/** Configuration used to load, identify, and serve a static asset manifest.
  *
  * Asset paths are normalized when [[StaticAssets.load]] runs. Lookup URLs always use the loaded
  * digested path; `serveOriginals` controls only whether the routes also accept undigested paths.
  *
  * @param source
  *   where configured assets are listed and read
  * @param mountPath
  *   the HTTP path below which asset routes and generated URLs are mounted; defaults to `/static`
  * @param serveOriginals
  *   whether routes also serve undigested manifest paths; defaults to `true`
  * @param cache
  *   cache-control headers for digested and original responses
  */
final case class StaticAssetConfig(
  source: StaticAssetSource,
  mountPath: Path = Path.empty / "static",
  serveOriginals: Boolean = true,
  cache: StaticAssetCache = StaticAssetCache.default)

/** Convenience constructors for the built-in static asset sources. */
object StaticAssetConfig:
  /** Configures an explicit set of classpath resources.
    *
    * The loader does not scan the classpath. Every normalized path in `assets` is read below
    * `resourcePrefix` while the manifest is loaded, and loading fails if any resource is absent.
    * Duplicate names are collapsed. The prefix is used as supplied except for one trailing `/`, so
    * it should use the resource-name form expected by `ClassLoader.getResourceAsStream` (normally
    * without a leading slash). This convenience constructor uses [[StaticAssetCache.default]];
    * customize the returned configuration with `copy(cache = ...)` when needed.
    *
    * @param resourcePrefix
    *   the classpath directory containing the assets
    * @param assets
    *   the asset paths to include; paths are normalized by [[StaticAssets.load]]
    * @param mountPath
    *   the HTTP mount path, defaulting to `/static`
    * @param serveOriginals
    *   whether to serve undigested paths, defaulting to `true`
    * @param classLoader
    *   the loader used both while building the manifest and for later requests; defaults to the
    *   current thread's context class loader
    */
  def classpath(
    resourcePrefix: String,
    assets: Iterable[String],
    mountPath: Path = Path.empty / "static",
    serveOriginals: Boolean = true,
    classLoader: ClassLoader = Thread.currentThread().getContextClassLoader
  ): StaticAssetConfig =
    StaticAssetConfig(
      StaticAssetSource.Classpath(resourcePrefix, assets.toSet, classLoader),
      mountPath,
      serveOriginals
    )

  /** Configures assets stored below a filesystem directory.
    *
    * With `assets = Some(...)`, only those paths are loaded and each must resolve to a regular
    * file. Duplicate configured strings are collapsed. With `None`, loading recursively discovers
    * regular files below the normalized absolute root. Directory contents are read with blocking
    * filesystem operations.
    *
    * The root is lexically normalized rather than resolved through the filesystem. Symbolic links
    * are not a security boundary and may refer outside it, so expose only a trusted asset tree.
    * This convenience constructor uses [[StaticAssetCache.default]]; customize the returned
    * configuration with `copy(cache = ...)` when needed.
    *
    * @param root
    *   the directory against which normalized asset paths are resolved
    * @param mountPath
    *   the HTTP mount path, defaulting to `/static`
    * @param serveOriginals
    *   whether to serve undigested paths, defaulting to `true`
    * @param assets
    *   explicit asset paths, or `None` to discover all regular files recursively
    */
  def directory(
    root: NioPath,
    mountPath: Path = Path.empty / "static",
    serveOriginals: Boolean = true,
    assets: Option[Iterable[String]] = None
  ): StaticAssetConfig =
    StaticAssetConfig(
      StaticAssetSource.Directory(root, assets.map(_.toSet)),
      mountPath,
      serveOriginals
    )
end StaticAssetConfig

/** Describes where [[StaticAssets]] obtains asset names and bytes.
  *
  * Sources are consulted while loading the manifest and again for every HTTP request; asset bytes
  * are not retained in memory by [[StaticAssets]].
  */
sealed trait StaticAssetSource

/** Built-in classpath and filesystem asset sources. */
object StaticAssetSource:
  /** An explicit set of resources loaded through a class loader.
    *
    * @param resourcePrefix
    *   the prefix prepended to each normalized asset path
    * @param assets
    *   configured asset paths; the classpath is never scanned
    * @param classLoader
    *   the loader used to open each resource
    */
  final case class Classpath(
    resourcePrefix: String,
    assets: Set[String],
    classLoader: ClassLoader)
      extends StaticAssetSource

  /** Assets stored below a filesystem directory.
    *
    * @param root
    *   the root directory for reads and discovery
    * @param assets
    *   explicit paths, or `None` to recursively discover regular files when loading
    */
  final case class Directory(root: NioPath, assets: Option[Set[String]]) extends StaticAssetSource

/** Cache-control policy for static asset responses.
  *
  * These values are emitted verbatim according to whether a request used a digested or original
  * path. They do not enable conditional or range request handling.
  *
  * @param digested
  *   cache control for content-addressed URLs
  * @param original
  *   cache control for undigested URLs
  */
final case class StaticAssetCache(
  digested: Header.CacheControl,
  original: Header.CacheControl)

/** Standard static asset cache policies. */
object StaticAssetCache:
  /** The default policy: public, immutable one-year caching for digested URLs and `no-cache` for
    * original URLs.
    */
  val default: StaticAssetCache = StaticAssetCache(
    digested = Header.CacheControl.Multiple(
      NonEmptyChunk(
        Header.CacheControl.Public,
        Header.CacheControl.MaxAge(31536000),
        Header.CacheControl.Immutable
      )
    ),
    original = Header.CacheControl.NoCache
  )

/** Metadata calculated for one asset when its manifest is loaded.
  *
  * @param originalPath
  *   the normalized source-relative path
  * @param digestedPath
  *   `originalPath` with the full SHA-256 digest inserted before its last file extension, or
  *   appended when it has no extension
  * @param digest
  *   the lowercase, 64-character hexadecimal SHA-256 digest of the loaded bytes
  * @param size
  *   the byte length observed while loading
  * @param mediaType
  *   the media type inferred from the final file extension, or `application/octet-stream`
  */
final case class StaticAssetEntry(
  originalPath: String,
  digestedPath: String,
  digest: String,
  size: Long,
  mediaType: MediaType)

final private[scalive] case class StaticAssetManifest(entries: Map[String, StaticAssetEntry]):
  private val digestedEntries: Map[String, StaticAssetEntry] =
    entries.valuesIterator.map(entry => entry.digestedPath -> entry).toMap

  def get(path: String): Option[StaticAssetEntry] =
    StaticAssets.normalizeRelativePath(path).toOption.flatMap(entries.get)

  def apply(path: String): StaticAssetEntry =
    get(path).getOrElse(throw new IllegalArgumentException(s"Static asset not found: $path"))

  private[scalive] def served(path: String, includeOriginals: Boolean)
    : Option[(StaticAssetEntry, Boolean)] =
    StaticAssets.normalizeRelativePath(path).toOption.flatMap { normalized =>
      digestedEntries
        .get(normalized).map(_ -> true)
        .orElse(entries.get(normalized).filter(_ => includeOriginals).map(_ -> false))
    }

/** A loaded static asset manifest, URL/tag helper, and HTTP route set.
  *
  * The manifest records metadata from load time but does not retain file contents. Requests reopen
  * the original source and read the complete asset, including for `HEAD`. Keep source contents
  * unchanged for this instance's lifetime: changing bytes can make a digested URL and ETag describe
  * different content, while removing an asset makes its route return `404 Not Found`.
  */
final class StaticAssets private (
  private[scalive] val config: StaticAssetConfig,
  private[scalive] val manifest: StaticAssetManifest):

  private val mountPrefix = StaticAssets.mountPrefix(config.mountPath)

  /** Returns the mounted, root-relative URL for an asset's digested path.
    *
    * Lookup accepts an optional leading `/` and ignores a `?query` or `#fragment`, then requires a
    * normalized manifest path. The returned URL itself has no query or fragment.
    *
    * @throws IllegalArgumentException
    *   if the path is invalid or no matching asset was loaded
    */
  def path(asset: String): String =
    url(manifest(asset).digestedPath)

  /** Returns the mounted, root-relative digested URL when `asset` is valid and present.
    *
    * Invalid, empty, and unknown paths return `None`. An optional leading `/` is accepted and query
    * or fragment suffixes are ignored during lookup.
    */
  def pathOption(asset: String): Option[String] =
    manifest.get(asset).map(entry => url(entry.digestedPath))

  /** Returns the load-time metadata for `asset`.
    *
    * Lookup accepts an optional leading `/` and ignores a query or fragment suffix.
    *
    * @throws IllegalArgumentException
    *   if the path is invalid or no matching asset was loaded
    */
  def entry(asset: String): StaticAssetEntry =
    manifest(asset)

  /** Renders an untracked stylesheet `<link>` using the asset's digested URL.
    *
    * The generated tag includes `rel="stylesheet"` and `href=path(asset)` before the supplied
    * modifiers. Lookup has the same failure behavior as [[path]].
    */
  def stylesheet[Msg](asset: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    linkTag(rel := "stylesheet", href := path(asset), mods)

  /** Renders a tracked stylesheet `<link>` using the asset's digested URL.
    *
    * In addition to [[stylesheet]] attributes, the tag includes `phx-track-static`, allowing the
    * LiveView client to detect that a tracked bundle changed between mounts.
    */
  def trackedStylesheet[Msg](asset: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    linkTag(phx.trackStatic := true, rel := "stylesheet", href := path(asset), mods)

  /** Renders an untracked `<script>` using the asset's digested URL.
    *
    * The generated tag includes `src=path(asset)` before the supplied modifiers. Lookup has the
    * same failure behavior as [[path]].
    */
  def script[Msg](asset: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    scriptTag(src := path(asset), mods)

  /** Renders a tracked `<script>` using the asset's digested URL.
    *
    * In addition to [[script]] attributes, the tag includes `phx-track-static`, allowing the
    * LiveView client to detect that a tracked bundle changed between mounts.
    */
  def trackedScript[Msg](asset: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    scriptTag(phx.trackStatic := true, src := path(asset), mods)

  /** `GET` and `HEAD` routes serving manifest entries below the configured mount path.
    *
    * Digested paths are always eligible; original paths are eligible only when
    * `config.serveOriginals` is true. Lookup ignores query strings and accepts only the exact
    * loaded original or digested path, so an unknown digest returns `404 Not Found`. Successful
    * responses include the entry's content type, configured cache control, and a strong ETag
    * containing its load-time digest. `HEAD` returns the same status and metadata with an empty
    * body.
    *
    * Each request reads the entire source asset again. A missing source asset returns 404; read
    * failures are logged and also return 404. The routes do not evaluate conditional ETags, produce
    * `304 Not Modified`, or serve byte ranges, so an upstream static server may be preferable for
    * large or high-volume production assets.
    */
  val routes: Routes[Any, Nothing] =
    Routes
      .fromIterable(
        List(
          Method.GET / Path.toPathCodec(config.mountPath) / PathCodec.trailing -> handler {
            (assetPath: Path, _: Request) => serve(assetPath, includeBody = true)
          },
          Method.HEAD / Path.toPathCodec(config.mountPath) / PathCodec.trailing -> handler {
            (assetPath: Path, _: Request) => serve(assetPath, includeBody = false)
          }
        )
      ).handleErrorZIO(error => ZIO.logErrorCause(Cause.fail(error)).as(Response.notFound))

  private def url(digestedPath: String): String =
    if mountPrefix.isEmpty then s"/$digestedPath" else s"$mountPrefix/$digestedPath"

  private def serve(assetPath: Path, includeBody: Boolean): UIO[Response] =
    val rel = assetPath.encode.stripPrefix("/")
    manifest.served(rel, config.serveOriginals) match
      case None                    => ZIO.succeed(Response.notFound)
      case Some((entry, digested)) =>
        StaticAssets
          .read(config.source, entry.originalPath).foldCauseZIO(
            cause =>
              ZIO
                .logErrorCause(s"Could not read static asset ${entry.originalPath}", cause).as(
                  Response.notFound
                ),
            {
              case None        => ZIO.succeed(Response.notFound)
              case Some(bytes) =>
                val headers = StaticAssets
                  .headers(entry, if digested then config.cache.digested else config.cache.original)
                val body = if includeBody then Body.fromArray(bytes) else Body.empty
                ZIO.succeed(Response(status = Status.Ok, headers = headers, body = body))
            }
          )
end StaticAssets

/** Loads static asset manifests. */
object StaticAssets:
  /** Validates and loads all configured assets, calculates their metadata, and constructs helpers
    * and routes.
    *
    * Configured paths are sorted and normalized before reads. Normalization removes one leading `/`
    * and ignores any query or fragment suffix; the remaining path must be non-empty, use `/`
    * separators, and contain no empty, `.`, or `..` segment. Each asset is read completely to
    * calculate its full SHA-256 digest, byte size, and media type. If multiple configured strings
    * normalize to the same path, the resulting manifest keeps one entry. Classpath and explicitly
    * listed directory sources fail if any asset is missing; an unlisted directory source discovers
    * all regular files recursively.
    *
    * Bytes are not cached. The returned routes reopen each original asset when requested, so the
    * source should remain available and unchanged for the lifetime of the returned instance.
    *
    * @return
    *   an effect which fails on invalid paths, inaccessible sources, missing configured assets, or
    *   read/digest errors
    */
  def load(config: StaticAssetConfig): Task[StaticAssets] =
    for
      assets  <- list(config.source)
      entries <- ZIO.foreach(assets) { rel =>
                   read(config.source, rel).flatMap {
                     case Some(bytes) => ZIO.succeed(entry(rel, bytes))
                     case None        =>
                       ZIO.fail(new IllegalArgumentException(s"Static asset not found: $rel"))
                   }
                 }
    yield new StaticAssets(
      config,
      StaticAssetManifest(entries.map(entry => entry.originalPath -> entry).toMap)
    )

  private[scalive] def normalizeRelativePath(path: String): Either[String, String] =
    val noQuery = path.takeWhile(ch => ch != '?' && ch != '#')
    val rel     = noQuery.stripPrefix("/")
    val parts   = rel.split("/", -1).toList

    if rel.isEmpty then Left("Static asset path is empty")
    else if rel.contains('\\') then Left(s"Static asset path contains a backslash: $path")
    else if parts.exists(part => part.isEmpty || part == "." || part == "..") then
      Left(s"Static asset path must be relative and normalized: $path")
    else Right(parts.mkString("/"))

  private def list(source: StaticAssetSource): Task[List[String]] =
    source match
      case StaticAssetSource.Classpath(_, assets, _) =>
        normalizeConfiguredAssets(assets)
      case StaticAssetSource.Directory(root, Some(assets)) =>
        normalizeConfiguredAssets(assets)
      case StaticAssetSource.Directory(root, None) =>
        ZIO
          .attemptBlocking {
            val normalizedRoot = root.toAbsolutePath.normalize()
            val stream         = Files.walk(normalizedRoot)
            try
              stream
                .iterator()
                .asScala
                .filter(Files.isRegularFile(_))
                .map(path => toRelativePath(normalizedRoot.relativize(path)))
                .toList
            finally stream.close()
          }.flatMap(normalizeConfiguredAssets)

  private def normalizeConfiguredAssets(assets: Iterable[String]): Task[List[String]] =
    ZIO.foreach(assets.toList.sorted)(asset =>
      ZIO.fromEither(normalizeRelativePath(asset)).mapError(new IllegalArgumentException(_))
    )

  private[scalive] def read(source: StaticAssetSource, rel: String): Task[Option[Array[Byte]]] =
    normalizeRelativePath(rel) match
      case Left(error) => ZIO.fail(new IllegalArgumentException(error))
      case Right(path) =>
        source match
          case StaticAssetSource.Classpath(prefix, _, loader) =>
            val resource = s"${prefix.stripSuffix("/")}/$path"
            ZIO.attemptBlocking(Option(loader.getResourceAsStream(resource)).map(readAllBytes))
          case StaticAssetSource.Directory(root, _) =>
            ZIO.attemptBlocking {
              val normalizedRoot = root.toAbsolutePath.normalize()
              val target         = normalizedRoot.resolve(path).normalize()
              if target.startsWith(normalizedRoot) && Files.isRegularFile(target) then
                Some(Files.readAllBytes(target))
              else None
            }

  private def readAllBytes(stream: InputStream): Array[Byte] =
    try stream.readAllBytes()
    finally stream.close()

  private def entry(rel: String, bytes: Array[Byte]): StaticAssetEntry =
    val digest = sha256(bytes)
    StaticAssetEntry(
      originalPath = rel,
      digestedPath = digestedPath(rel, digest),
      digest = digest,
      size = bytes.length.toLong,
      mediaType = mediaType(rel)
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"$b%02x").mkString

  private def digestedPath(rel: String, digest: String): String =
    val slashIndex = rel.lastIndexOf('/')
    val dir        = if slashIndex >= 0 then rel.substring(0, slashIndex + 1) else ""
    val file       = if slashIndex >= 0 then rel.substring(slashIndex + 1) else rel
    val dotIndex   = file.lastIndexOf('.')
    val hasExt     = dotIndex > 0
    val stem       = if hasExt then file.substring(0, dotIndex) else file
    val ext        = if hasExt then file.substring(dotIndex) else ""
    s"$dir$stem-$digest$ext"

  private def toRelativePath(path: NioPath): String =
    path.iterator().asScala.map(_.toString).mkString("/")

  private def mountPrefix(path: Path): String =
    val encoded = path.addLeadingSlash.dropTrailingSlash.encode
    if encoded == "/" then "" else encoded

  private def headers(entry: StaticAssetEntry, cacheControl: Header.CacheControl): Headers =
    Headers(
      Header.ContentType(entry.mediaType),
      cacheControl,
      Header.ETag.Strong(entry.digest)
    )

  private def mediaType(rel: String): MediaType =
    MediaType
      .forFileExtension(fileExtension(rel))
      .getOrElse(MediaType("application", "octet-stream", binary = true))

  private def fileExtension(rel: String): String =
    val file     = rel.substring(rel.lastIndexOf('/') + 1)
    val dotIndex = file.lastIndexOf('.')
    if dotIndex >= 0 && dotIndex < file.length - 1 then file.substring(dotIndex + 1).toLowerCase
    else ""
end StaticAssets
