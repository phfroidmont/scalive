package scalive.docs.pipeline

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final case class ExtractedSource(
  path: String,
  startLine: Int,
  endLine: Int,
  content: String)

final case class SourceExtractionError(message: String)

object SourceExtractor:
  private val MarkerPattern = """^\s*// docs:(start|end) ([^\s]+)\s*$""".r
  private val MarkerLike    = """^\s*//\s*docs\s*:.*$""".r
  private val NamePattern   = """^[^\s]+$""".r

  def extract(
    repositoryRoot: Path,
    allowedRoots: Seq[Path],
    sourcePath: Path,
    regionName: String
  ): Either[SourceExtractionError, ExtractedSource] =
    val renderedSourcePath = Option(sourcePath).fold("<null>")(posix)

    try
      for
        repository      <- canonicalRepository(repositoryRoot)
        relativeRoots   <- validateAllowedRoots(allowedRoots)
        relativeSource  <- validateRelative(sourcePath, "Source path")
        _               <- ensureLexicallyAllowed(relativeSource, relativeRoots)
        canonicalRoots  <- canonicalAllowedRoots(repository, relativeRoots)
        canonicalSource <- canonicalSourceFile(repository, relativeSource)
        _               <- ensureCanonicallyAllowed(canonicalSource, canonicalRoots, relativeSource)
        _               <- ensureRegularFile(canonicalSource, relativeSource)
        _               <- validateRegionName(regionName)
        lines           <- readSource(canonicalSource, relativeSource)
        extracted       <- extractRegion(lines, relativeSource.rendered, regionName)
      yield extracted
    catch
      case NonFatal(_) =>
        Left(SourceExtractionError(s"Unable to extract source '$renderedSourcePath'."))

  final private case class RelativePath(value: Path, rendered: String)

  final private case class Marker(name: String, line: Int, isStart: Boolean)

  final private case class Region(name: String, start: Marker, end: Marker)

  private def canonicalRepository(
    repositoryRoot: Path
  ): Either[SourceExtractionError, Path] =
    try
      val canonical = repositoryRoot.toRealPath()
      if Files.isDirectory(canonical) then Right(canonical)
      else Left(SourceExtractionError("Repository root is not a directory."))
    catch
      case _: NoSuchFileException =>
        Left(SourceExtractionError("Repository root does not exist."))
      case _: IOException | _: SecurityException =>
        Left(SourceExtractionError("Unable to resolve the repository root."))

  private def validateAllowedRoots(
    allowedRoots: Seq[Path]
  ): Either[SourceExtractionError, Vector[RelativePath]] =
    allowedRoots.foldLeft[Either[SourceExtractionError, Vector[RelativePath]]](
      Right(Vector.empty)
    ) { (validated, root) =>
      for
        roots        <- validated
        relativeRoot <- validateRelative(root, "Allowed root")
      yield roots :+ relativeRoot
    }

  private def validateRelative(
    path: Path,
    description: String
  ): Either[SourceExtractionError, RelativePath] =
    val rendered = posix(path)
    if path.isAbsolute then
      Left(
        SourceExtractionError(
          s"$description must be repository-relative: '$rendered'."
        )
      )
    else
      val normalized = path.normalize()
      if startsWithParent(normalized) then
        Left(
          SourceExtractionError(
            s"$description escapes the repository: '$rendered'."
          )
        )
      else Right(RelativePath(normalized, posix(normalized)))

  private def startsWithParent(path: Path): Boolean =
    path.getNameCount > 0 && path.getName(0).toString == ".."

  private def ensureLexicallyAllowed(
    source: RelativePath,
    allowedRoots: Vector[RelativePath]
  ): Either[SourceExtractionError, Unit] =
    val allowed = allowedRoots.exists { root =>
      root.value.toString.isEmpty || source.value.startsWith(root.value)
    }
    if allowed then Right(())
    else outsideAllowedRoots(source)

  private def canonicalAllowedRoots(
    repository: Path,
    roots: Vector[RelativePath]
  ): Either[SourceExtractionError, Vector[Path]] =
    roots.foldLeft[Either[SourceExtractionError, Vector[Path]]](Right(Vector.empty)) {
      (resolved, root) =>
        for
          paths     <- resolved
          canonical <- canonicalAllowedRoot(repository, root)
        yield paths :+ canonical
    }

  private def canonicalAllowedRoot(
    repository: Path,
    root: RelativePath
  ): Either[SourceExtractionError, Path] =
    try
      val canonical = repository.resolve(root.value).toRealPath()
      if !canonical.startsWith(repository) then
        Left(
          SourceExtractionError(
            s"Allowed root resolves outside the repository: '${root.rendered}'."
          )
        )
      else if !Files.isDirectory(canonical) then
        Left(SourceExtractionError(s"Allowed root is not a directory: '${root.rendered}'."))
      else Right(canonical)
    catch
      case _: NoSuchFileException =>
        Left(SourceExtractionError(s"Allowed root does not exist: '${root.rendered}'."))
      case _: IOException | _: SecurityException =>
        Left(SourceExtractionError(s"Unable to resolve allowed root: '${root.rendered}'."))

  private def canonicalSourceFile(
    repository: Path,
    source: RelativePath
  ): Either[SourceExtractionError, Path] =
    try Right(repository.resolve(source.value).toRealPath())
    catch
      case _: NoSuchFileException =>
        Left(SourceExtractionError(s"Source file does not exist: '${source.rendered}'."))
      case _: IOException | _: SecurityException =>
        Left(SourceExtractionError(s"Unable to resolve source file: '${source.rendered}'."))

  private def ensureCanonicallyAllowed(
    source: Path,
    allowedRoots: Vector[Path],
    relativeSource: RelativePath
  ): Either[SourceExtractionError, Unit] =
    if allowedRoots.exists(source.startsWith) then Right(())
    else outsideAllowedRoots(relativeSource)

  private def outsideAllowedRoots(
    source: RelativePath
  ): Left[SourceExtractionError, Nothing] =
    Left(
      SourceExtractionError(
        s"Source file is outside the allowed roots: '${source.rendered}'."
      )
    )

  private def ensureRegularFile(
    canonicalSource: Path,
    relativeSource: RelativePath
  ): Either[SourceExtractionError, Unit] =
    if Files.isRegularFile(canonicalSource) then Right(())
    else
      Left(
        SourceExtractionError(
          s"Source path is not a regular file: '${relativeSource.rendered}'."
        )
      )

  private def validateRegionName(regionName: String): Either[SourceExtractionError, Unit] =
    if regionName != null && NamePattern.matches(regionName) then Right(())
    else
      Left(
        SourceExtractionError(
          "Region name must be a non-empty value without whitespace."
        )
      )

  private def readSource(
    canonicalSource: Path,
    relativeSource: RelativePath
  ): Either[SourceExtractionError, Vector[String]] =
    try Right(Files.readAllLines(canonicalSource, StandardCharsets.UTF_8).asScala.toVector)
    catch
      case _: IOException | _: SecurityException =>
        Left(SourceExtractionError(s"Unable to read source file: '${relativeSource.rendered}'."))

  private def extractRegion(
    lines: Vector[String],
    sourcePath: String,
    regionName: String
  ): Either[SourceExtractionError, ExtractedSource] =
    for
      markers <- parseMarkers(lines, sourcePath)
      regions <- validateMarkers(markers, sourcePath)
      region  <- regions
                  .find(_.name == regionName).toRight(
                    SourceExtractionError(
                      s"Missing start marker for region '$regionName' in '$sourcePath'."
                    )
                  )
      contentLines = lines.slice(region.start.line, region.end.line - 1)
      _ <- Either.cond(
             contentLines.exists(_.trim.nonEmpty),
             (),
             SourceExtractionError(s"Region '$regionName' is empty in '$sourcePath'.")
           )
    yield ExtractedSource(
      path = sourcePath,
      startLine = region.start.line + 1,
      endLine = region.end.line - 1,
      content = contentLines.mkString("\n")
    )

  private def parseMarkers(
    lines: Vector[String],
    sourcePath: String
  ): Either[SourceExtractionError, Vector[Marker]] =
    lines.zipWithIndex.foldLeft[Either[SourceExtractionError, Vector[Marker]]](
      Right(Vector.empty)
    ) { case (parsed, (line, index)) =>
      parsed.flatMap { markers =>
        line match
          case MarkerPattern(kind, name) =>
            Right(markers :+ Marker(name, index + 1, isStart = kind == "start"))
          case _ if MarkerLike.matches(line) =>
            Left(
              SourceExtractionError(
                s"Malformed source marker at '$sourcePath:${index + 1}': '${line.trim}'."
              )
            )
          case _ => Right(markers)
      }
    }

  private def validateMarkers(
    markers: Vector[Marker],
    sourcePath: String
  ): Either[SourceExtractionError, Vector[Region]] =
    for
      _       <- rejectDuplicateMarkers(markers, sourcePath, isStart = true)
      _       <- rejectDuplicateMarkers(markers, sourcePath, isStart = false)
      regions <- pairMarkers(markers, sourcePath)
      _       <- rejectOverlappingRegions(regions, sourcePath)
    yield regions

  private def rejectDuplicateMarkers(
    markers: Vector[Marker],
    sourcePath: String,
    isStart: Boolean
  ): Either[SourceExtractionError, Unit] =
    val matching = markers.filter(_.isStart == isStart)
    matching
      .map(_.name).distinct
      .find(name => matching.count(_.name == name) > 1) match
      case None       => Right(())
      case Some(name) =>
        val lines = matching.filter(_.name == name).map(_.line).mkString(", ")
        val kind  = if isStart then "start" else "end"
        Left(
          SourceExtractionError(
            s"Duplicate $kind marker for region '$name' at lines $lines in '$sourcePath'."
          )
        )

  private def pairMarkers(
    markers: Vector[Marker],
    sourcePath: String
  ): Either[SourceExtractionError, Vector[Region]] =
    markers
      .map(_.name).distinct.foldLeft[Either[SourceExtractionError, Vector[Region]]](
        Right(Vector.empty)
      ) { (paired, name) =>
        paired.flatMap { regions =>
          val start = markers.find(marker => marker.name == name && marker.isStart)
          val end   = markers.find(marker => marker.name == name && !marker.isStart)
          (start, end) match
            case (None, _) =>
              Left(
                SourceExtractionError(
                  s"Missing start marker for region '$name' in '$sourcePath'."
                )
              )
            case (_, None) =>
              Left(
                SourceExtractionError(
                  s"Missing end marker for region '$name' in '$sourcePath'."
                )
              )
            case (Some(startMarker), Some(endMarker)) if endMarker.line < startMarker.line =>
              Left(
                SourceExtractionError(
                  s"End marker precedes start marker for region '$name' in '$sourcePath'."
                )
              )
            case (Some(startMarker), Some(endMarker)) =>
              Right(regions :+ Region(name, startMarker, endMarker))
        }
      }

  private def rejectOverlappingRegions(
    regions: Vector[Region],
    sourcePath: String
  ): Either[SourceExtractionError, Unit] =
    regions.sortBy(_.start.line).sliding(2).collectFirst {
      case Seq(first, second) if second.start.line < first.end.line =>
        SourceExtractionError(
          s"Nested or overlapping regions '${first.name}' and '${second.name}' in '$sourcePath'."
        )
    } match
      case Some(error) => Left(error)
      case None        => Right(())

  private def posix(path: Path): String =
    path.toString.replace('\\', '/')
end SourceExtractor
