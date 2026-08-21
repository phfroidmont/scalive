package scalive

import java.io.InputStream
import java.nio.file.{Files, Path as NioPath}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.*
import zio.http.codec.PathCodec

/** Configuration used to load, identify, and serve a static asset manifest. */
final case class StaticAssetConfig(
  source: StaticAssetSource,
  mountPath: Path = Path.empty / "static",
  serveOriginals: Boolean = true,
  cache: StaticAssetCache = StaticAssetCache.default)

object StaticAssetConfig:
  /** Configures an explicit set of classpath resources. */
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

  /** Configures assets stored below a filesystem directory. */
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

/** Describes where [[StaticAssets]] obtains asset names and bytes. */
sealed trait StaticAssetSource

object StaticAssetSource:
  final case class Classpath(
    resourcePrefix: String,
    assets: Set[String],
    classLoader: ClassLoader)
      extends StaticAssetSource

  final case class Directory(root: NioPath, assets: Option[Set[String]]) extends StaticAssetSource

/** Cache-control policy for static asset responses. */
final case class StaticAssetCache(
  digested: Header.CacheControl,
  original: Header.CacheControl)

object StaticAssetCache:
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

/** Metadata calculated for one asset when its manifest is loaded. */
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

  def served(path: String, includeOriginals: Boolean): Option[(StaticAssetEntry, Boolean)] =
    StaticAssets.normalizeRelativePath(path).toOption.flatMap { normalized =>
      digestedEntries
        .get(normalized).map(_ -> true)
        .orElse(entries.get(normalized).filter(_ => includeOriginals).map(_ -> false))
    }

/** A loaded static asset manifest, URL/tag helper, and HTTP route set. */
final class StaticAssets private (
  private[scalive] val config: StaticAssetConfig,
  private[scalive] val manifest: StaticAssetManifest):

  private val mountPrefix = StaticAssets.mountPrefix(config.mountPath)

  /** Returns the mounted, root-relative URL for an asset's digested path. */
  def path(asset: String): String =
    url(manifest(asset).digestedPath)

  /** Returns the mounted, root-relative digested URL when the asset is present. */
  def pathOption(asset: String): Option[String] =
    manifest.get(asset).map(entry => url(entry.digestedPath))

  /** Returns the load-time metadata for an asset. */
  def entry(asset: String): StaticAssetEntry =
    manifest(asset)

  def stylesheet[Msg](asset: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    linkTag(rel := "stylesheet", href := path(asset), mods)

  /** Renders a tracked stylesheet `<link>` using the asset's digested URL. */
  def trackedStylesheet[Msg](asset: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    linkTag(phx.trackStatic := true, rel := "stylesheet", href := path(asset), mods)

  def script[Msg](asset: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    scriptTag(src := path(asset), mods)

  /** Renders a tracked `<script>` using the asset's digested URL. */
  def trackedScript[Msg](asset: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    scriptTag(phx.trackStatic := true, src := path(asset), mods)

  /** GET and HEAD routes serving manifest entries below the configured mount path. */
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
    val relativePath = assetPath.encode.stripPrefix("/")
    manifest.served(relativePath, config.serveOriginals) match
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

object StaticAssets:
  /** Loads and validates an asset manifest. Bytes are read again when a request is served. */
  def load(config: StaticAssetConfig): Task[StaticAssets] =
    for
      assets  <- list(config.source)
      entries <- ZIO.foreach(assets) { relativePath =>
                   read(config.source, relativePath).flatMap {
                     case Some(bytes) => ZIO.succeed(entry(relativePath, bytes))
                     case None        =>
                       ZIO.fail(
                         new IllegalArgumentException(s"Static asset not found: $relativePath")
                       )
                   }
                 }
    yield new StaticAssets(
      config,
      StaticAssetManifest(entries.map(entry => entry.originalPath -> entry).toMap)
    )

  private[scalive] def normalizeRelativePath(path: String): Either[String, String] =
    val noQuery      = path.takeWhile(character => character != '?' && character != '#')
    val relativePath = noQuery.stripPrefix("/")
    val parts        = relativePath.split("/", -1).toList

    if relativePath.isEmpty then Left("Static asset path is empty")
    else if relativePath.contains('\\') then Left(s"Static asset path contains a backslash: $path")
    else if parts.exists(part => part.isEmpty || part == "." || part == "..") then
      Left(s"Static asset path must be relative and normalized: $path")
    else Right(parts.mkString("/"))

  private def list(source: StaticAssetSource): Task[List[String]] =
    source match
      case StaticAssetSource.Classpath(_, assets, _) =>
        normalizeConfiguredAssets(assets)
      case StaticAssetSource.Directory(_, Some(assets)) =>
        normalizeConfiguredAssets(assets)
      case StaticAssetSource.Directory(root, None) =>
        ZIO
          .attemptBlocking {
            val normalizedRoot = root.toAbsolutePath.normalize()
            val stream         = Files.walk(normalizedRoot)
            try
              stream
                .iterator().asScala.filter(Files.isRegularFile(_))
                .map(path => toRelativePath(normalizedRoot.relativize(path))).toList
            finally stream.close()
          }.flatMap(normalizeConfiguredAssets)

  private def normalizeConfiguredAssets(assets: Iterable[String]): Task[List[String]] =
    ZIO.foreach(assets.toList.sorted)(asset =>
      ZIO.fromEither(normalizeRelativePath(asset)).mapError(new IllegalArgumentException(_))
    )

  private[scalive] def read(
    source: StaticAssetSource,
    relativePath: String
  ): Task[Option[Array[Byte]]] =
    normalizeRelativePath(relativePath) match
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

  private def entry(relativePath: String, bytes: Array[Byte]): StaticAssetEntry =
    val digest = sha256(bytes)
    StaticAssetEntry(
      originalPath = relativePath,
      digestedPath = digestedPath(relativePath, digest),
      digest = digest,
      size = bytes.length.toLong,
      mediaType = mediaType(relativePath)
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"$byte%02x").mkString

  private def digestedPath(relativePath: String, digest: String): String =
    val slashIndex = relativePath.lastIndexOf('/')
    val directory  = if slashIndex >= 0 then relativePath.substring(0, slashIndex + 1) else ""
    val file     = if slashIndex >= 0 then relativePath.substring(slashIndex + 1) else relativePath
    val dotIndex = file.lastIndexOf('.')
    val hasExtension = dotIndex > 0
    val stem         = if hasExtension then file.substring(0, dotIndex) else file
    val extension    = if hasExtension then file.substring(dotIndex) else ""
    s"$directory$stem-$digest$extension"

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
