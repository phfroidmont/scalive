package scalive.docs.pipeline

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import laika.api.MarkupParser
import laika.api.bundle.BlockDirectives
import laika.api.bundle.DirectiveRegistry
import laika.api.bundle.SpanDirectives
import laika.api.config.ConfigParser
import laika.api.config.ConfigValue
import laika.api.config.ConfigValue.LongValue
import laika.api.config.ConfigValue.StringValue
import laika.ast.Block as LaikaBlock
import laika.ast.Id
import laika.ast.Options
import laika.ast.Path as LaikaPath
import laika.ast.RewriteAction
import laika.ast.RewritePhase
import laika.ast.RewriteRules
import laika.ast.Span as LaikaSpan
import laika.config.LaikaKeys
import laika.format.Markdown
import laika.io.model.InputTree
import laika.io.syntax.*
import laika.theme.Theme

import scalive.docs.model.*

final case class PipelineError(messages: Vector[String]):
  def message: String = messages.mkString("\n")

object ContentPipeline:
  private val RequiredMetadata = Set("title", "description", "order", "section")
  private val AllowedMetadata  = RequiredMetadata + "group"
  private val RouteSegment     = "^[a-z0-9]+(?:-[a-z0-9]+)*$".r
  private val HeadingId        = "^[a-z0-9]+(?:-[a-z0-9]+)*$".r
  private val AtxHeading       = "^ {0,3}(#{1,6})(?:[ \\t]+|$)(.*)$".r
  private val ExplicitHeading  = "^(.*\\S)[ \\t]+\\{#([^{}]+)\\}[ \\t]*$".r
  private val Fence            = "^ {0,3}(`{3,}|~{3,})(.*)$".r
  private val RawHtml          =
    "(?i)<(?!https?://|mailto:)(?:!--|\\?|![a-z\\[]|/?[a-z][a-z0-9:-]*(?=[\\s/>]|$))".r
  private val DirectiveName   = "@:([A-Za-z][A-Za-z0-9]*|@)".r
  private val SingleDirective =
    "^\\s*@:(example|lab|trace|compatibility)\\(([^\\s,(){}]+)\\)\\s*$".r
  private val StandaloneApiSymbol =
    "^\\s*@:apiSymbol\\(([^\\s,(){}]+)\\)\\s*$".r
  private val InlineApiSymbol =
    "@:apiSymbol\\(([^\\s,(){}]+)\\)(?s:.*?)@:@".r
  private val SourceDirective =
    "^\\s*@:sourceRegion\\(([^\\s,(){}]+)\\s*,\\s*([^\\s,(){}]+)\\)\\s*$".r
  private val CalloutDirective =
    "^\\s*@:callout\\(([^\\s,(){}]+)\\)\\s*$".r
  private val ExplicitHeadingStyle   = "scalive-explicit-heading"
  private val AllowedExternalSchemes = Set("http", "https", "mailto")
  private val SectionOrder           = Vector(
    Section.Home,
    Section.Learn,
    Section.Guides,
    Section.Examples,
    Section.Api,
    Section.Project
  )

  def generate(
    repositoryRoot: Path,
    contentRoot: Path,
    allowedSourceRoots: Seq[Path],
    apiReference: ApiReference,
    examples: Vector[ExampleDescriptor]
  ): Either[PipelineError, DocumentationBundle] =
    try
      val traceValidation = TraceCatalog.validate()
      for
        _ <- Either.cond(
               traceValidation.isEmpty,
               (),
               PipelineError(traceValidation)
             )
        paths       <- validatePaths(repositoryRoot, contentRoot, allowedSourceRoots)
        definitions <- resolveExamples(paths, examples)
        authored    <- readAndValidate(paths)
        parsed      <- parseTree(authored, paths)
        converted   <- convertTree(parsed, authored, paths, apiReference, definitions)
      yield converted
    catch
      case NonFatal(_) => Left(PipelineError(Vector("Unexpected documentation pipeline failure.")))

  final private case class ValidatedPaths(
    repository: Path,
    content: Path,
    allowedSourceRoots: Seq[Path],
    files: Vector[Path])

  final private case class HeadingData(level: Int, id: String, line: Int)

  final private case class AuthoredDocument(
    file: Path,
    virtualPath: LaikaPath,
    sourcePath: String,
    route: String,
    metadata: PageMetadata,
    headings: Vector[HeadingData],
    normalized: String)

  private def validatePaths(
    repositoryRoot: Path,
    contentRoot: Path,
    allowedSourceRoots: Seq[Path]
  ): Either[PipelineError, ValidatedPaths] =
    val errors = ArrayBuffer.empty[String]

    val repository = canonicalDirectory(repositoryRoot).getOrElse {
      errors += "Repository root must be an existing directory."
      Path.of(".").toAbsolutePath.normalize()
    }
    val content = canonicalDirectory(contentRoot).getOrElse {
      errors += "Content root must be an existing directory."
      repository
    }

    if errors.isEmpty then
      if !content.startsWith(repository) then
        errors += "Content root must be inside the repository."
      else
        val relativeContent = repository.relativize(content)
        if !relativeContent.startsWith(Path.of("documentation/content")) then
          errors += "Content root must be under documentation/content."

    allowedSourceRoots.zipWithIndex.foreach { case (root, index) =>
      if root == null || root.isAbsolute || escapesParent(root.normalize()) then
        errors += s"Allowed source root ${index + 1} must be repository-relative."
    }

    val files =
      if errors.nonEmpty then Vector.empty
      else
        try
          val stream = Files.walk(content)
          try
            stream
              .iterator().asScala.filter(Files.isRegularFile(_)).toVector
              .sortBy(path => posix(content.relativize(path)))
          finally stream.close()
        catch
          case _: Exception =>
            errors += "Unable to read the content directory."
            Vector.empty

    files.foreach { file =>
      try
        if !file.toRealPath().startsWith(content) then
          errors += s"Content file resolves outside the content root: '${posix(content.relativize(file))}'."
      catch
        case _: Exception =>
          errors += s"Unable to resolve content file: '${posix(content.relativize(file))}'."
    }

    if errors.nonEmpty then Left(PipelineError(errors.toVector.sorted))
    else Right(ValidatedPaths(repository, content, allowedSourceRoots, files))
  end validatePaths

  private def canonicalDirectory(path: Path): Option[Path] =
    if path == null then None
    else
      try
        val canonical = path.toRealPath()
        Option.when(Files.isDirectory(canonical))(canonical)
      catch case _: Exception => None

  private def escapesParent(path: Path): Boolean =
    path.getNameCount > 0 && path.getName(0).toString == ".."

  private def readAndValidate(
    paths: ValidatedPaths
  ): Either[PipelineError, Vector[AuthoredDocument]] =
    val markdownFiles = paths.files.filter(_.getFileName.toString.endsWith(".md"))
    val errors        = ArrayBuffer.empty[String]
    val documents     = ArrayBuffer.empty[AuthoredDocument]

    if markdownFiles.isEmpty then errors += "Content root contains no Markdown documents."
    paths.files.filter(_.getFileName.toString == "directory.conf").foreach { file =>
      errors += s"${contentPath(paths, file)}: inherited document metadata is not allowed."
    }

    markdownFiles.foreach { file =>
      val sourcePath  = repositoryPath(paths, file)
      val relative    = paths.content.relativize(file)
      val routeResult = routeFor(relative).left.map(message => s"$sourcePath: $message")
      val textResult  =
        try Right(Files.readString(file, StandardCharsets.UTF_8))
        catch case _: Exception => Left(s"$sourcePath: unable to read Markdown source.")

      (routeResult, textResult) match
        case (Right(route), Right(text)) =>
          val validation = validateDocument(sourcePath, text)
          errors ++= validation.errors
          validation.result.foreach { case (metadata, headings, normalized) =>
            documents += AuthoredDocument(
              file,
              virtualPath(relative),
              sourcePath,
              route,
              metadata,
              headings,
              normalized
            )
          }
        case (Left(error), _) => errors += error
        case (_, Left(error)) => errors += error
    }

    val validDocuments = documents.toVector
    errors ++= duplicateErrors(validDocuments)

    if errors.nonEmpty then Left(PipelineError(errors.toVector.distinct.sorted))
    else Right(validDocuments)
  end readAndValidate

  final private case class DocumentValidation(
    result: Option[(PageMetadata, Vector[HeadingData], String)],
    errors: Vector[String])

  private def validateDocument(sourcePath: String, text: String): DocumentValidation =
    val lines  = text.linesIterator.toVector
    val errors = ArrayBuffer.empty[String]
    val header = parseMetadata(sourcePath, lines)
    errors ++= header.errors

    val bodyStart = header.endLine.getOrElse(0)
    val body      = validateBody(sourcePath, lines, bodyStart)
    errors ++= body.errors

    val result = for
      metadata   <- header.metadata
      normalized <- body.normalized
    yield (metadata, body.headings, normalized)
    DocumentValidation(result, errors.toVector)

  final private case class MetadataValidation(
    metadata: Option[PageMetadata],
    endLine: Option[Int],
    errors: Vector[String])

  private def parseMetadata(sourcePath: String, lines: Vector[String]): MetadataValidation =
    val errors = ArrayBuffer.empty[String]
    if lines.headOption.forall(_.trim != "{%") then
      MetadataValidation(
        None,
        None,
        Vector(s"$sourcePath: HOCON metadata header must start on line 1 with '{%'.")
      )
    else
      val closing = lines.indexWhere(_.trim == "%}", from = 1)
      if closing < 0 then
        MetadataValidation(
          None,
          None,
          Vector(s"$sourcePath: HOCON metadata header is missing closing '%}'.")
        )
      else
        val input  = lines.slice(1, closing).mkString("\n")
        val values = ConfigParser
          .parse(input).resolve().flatMap(
            _.get[Map[String, ConfigValue]](laika.api.config.Key.root)
          )

        val metadata = values match
          case Left(error) =>
            errors += s"$sourcePath: invalid HOCON metadata: ${singleLine(error.message)}"
            None
          case Right(fields) =>
            fields.keySet.diff(AllowedMetadata).toVector.sorted.foreach { field =>
              errors += s"$sourcePath: unknown metadata field '$field'."
            }
            RequiredMetadata.diff(fields.keySet).toVector.sorted.foreach { field =>
              errors += s"$sourcePath: missing metadata field '$field'."
            }

            val title       = stringMetadata(sourcePath, "title", fields, errors)
            val description = stringMetadata(sourcePath, "description", fields, errors)
            val order       = fields.get("order") match
              case Some(LongValue(value)) if value.isValidInt => Some(value.toInt)
              case Some(_)                                    =>
                errors += s"$sourcePath: metadata field 'order' must be an integer."
                None
              case None => None
            val section = stringMetadata(sourcePath, "section", fields, errors).flatMap { value =>
              sectionFrom(value) match
                case Some(section) => Some(section)
                case None          =>
                  errors += s"$sourcePath: unknown documentation section '$value'."
                  None
            }
            val group = optionalStringMetadata(sourcePath, "group", fields, errors)

            for
              validTitle       <- title
              validDescription <- description
              validOrder       <- order
              validSection     <- section
            yield PageMetadata(validTitle, validDescription, validOrder, validSection, group)

        MetadataValidation(metadata.filter(_ => errors.isEmpty), Some(closing + 1), errors.toVector)
      end if
    end if
  end parseMetadata

  private def stringMetadata(
    sourcePath: String,
    name: String,
    fields: Map[String, ConfigValue],
    errors: ArrayBuffer[String]
  ): Option[String] =
    fields.get(name) match
      case Some(StringValue(value)) if value.trim.nonEmpty => Some(value.trim)
      case Some(StringValue(_))                            =>
        errors += s"$sourcePath: metadata field '$name' must not be blank."
        None
      case Some(_) =>
        errors += s"$sourcePath: metadata field '$name' must be a string."
        None
      case None => None

  private def optionalStringMetadata(
    sourcePath: String,
    name: String,
    fields: Map[String, ConfigValue],
    errors: ArrayBuffer[String]
  ): Option[String] =
    fields.get(name) match
      case Some(StringValue(value)) if value.trim.nonEmpty => Some(value.trim)
      case Some(StringValue(_))                            =>
        errors += s"$sourcePath: metadata field '$name' must not be blank."
        None
      case Some(_) =>
        errors += s"$sourcePath: metadata field '$name' must be a string."
        None
      case None => None

  private def sectionFrom(value: String): Option[Section] = value match
    case "home"     => Some(Section.Home)
    case "learn"    => Some(Section.Learn)
    case "guides"   => Some(Section.Guides)
    case "examples" => Some(Section.Examples)
    case "api"      => Some(Section.Api)
    case "project"  => Some(Section.Project)
    case _          => None

  final private case class BodyValidation(
    headings: Vector[HeadingData],
    normalized: Option[String],
    errors: Vector[String])

  private def validateBody(
    sourcePath: String,
    lines: Vector[String],
    bodyStart: Int
  ): BodyValidation =
    val errors                     = ArrayBuffer.empty[String]
    val headings                   = ArrayBuffer.empty[HeadingData]
    val normalized                 = lines.toArray
    var fence: Option[(Char, Int)] = None
    var calloutDepth               = 0
    var previousBodyLine           = ""

    lines.zipWithIndex.drop(bodyStart).foreach { case (line, index) =>
      val lineNumber = index + 1
      fence match
        case Some((character, length)) if closesFence(line, character, length) =>
          fence = None
        case Some(_) => ()
        case None    =>
          line match
            case Fence(marker, _) => fence = Some(marker.head -> marker.length)
            case _                =>
              val withoutCode = stripInlineCode(line)
              if RawHtml.findFirstIn(withoutCode).nonEmpty then
                errors += s"$sourcePath:$lineNumber: raw HTML is not allowed."

              line match
                case AtxHeading(marks, content) =>
                  val level = marks.length
                  if level == 1 then
                    errors += s"$sourcePath:$lineNumber: level-1 headings are not allowed."
                  content match
                    case ExplicitHeading(title, id) if title.trim.nonEmpty =>
                      if HeadingId.matches(id) then
                        headings += HeadingData(level, id, lineNumber)
                        normalized(index) =
                          s"${line.takeWhile(_ == ' ')}$marks ${title.trim} @:scaliveHeadingId($id)"
                      else errors += s"$sourcePath:$lineNumber: invalid heading id '$id'."
                    case _ if content.contains("{#") =>
                      errors += s"$sourcePath:$lineNumber: invalid heading id."
                    case _ =>
                      errors +=
                        s"$sourcePath:$lineNumber: heading requires a trailing explicit id."
                case _ if isSetextHeading(line, previousBodyLine) =>
                  if line.trim.startsWith("=") then
                    errors += s"$sourcePath:$lineNumber: level-1 headings are not allowed."
                  errors += s"$sourcePath:$lineNumber: heading requires a trailing explicit id."
                case _ => ()

              calloutDepth = validateDirectiveLine(
                sourcePath,
                lineNumber,
                withoutCode,
                calloutDepth,
                errors
              )
              previousBodyLine = line
      end match
    }

    if fence.nonEmpty then errors += s"$sourcePath: unclosed fenced code block."
    if calloutDepth > 0 then errors += s"$sourcePath: unclosed @:callout directive."

    headings.groupBy(_.id).toVector.sortBy(_._1).foreach { case (id, duplicates) =>
      if duplicates.sizeIs > 1 then errors += s"$sourcePath: duplicate anchor '$id'."
    }
    headings.zipWithIndex.foreach { case (heading, index) =>
      val previousLevel = if index == 0 then 1 else headings(index - 1).level
      if heading.level > previousLevel + 1 then
        errors += s"$sourcePath:${heading.line}: heading level ${heading.level} skips a heading level."
    }

    BodyValidation(
      headings.toVector,
      Option.when(errors.isEmpty)(normalized.mkString("\n")),
      errors.toVector
    )
  end validateBody

  private def closesFence(line: String, character: Char, length: Int): Boolean =
    val trimmed = line.dropWhile(_ == ' ')
    trimmed.takeWhile(_ == character).length >= length &&
    trimmed.dropWhile(_ == character).trim.isEmpty

  private def stripInlineCode(line: String): String =
    val result = new StringBuilder
    var index  = 0
    while index < line.length do
      if line(index) == '`' then
        val fenceLength = line.drop(index).takeWhile(_ == '`').length
        val closing     = line.indexOf("`" * fenceLength, index + fenceLength)
        if closing >= 0 then
          result.append(" " * (closing + fenceLength - index))
          index = closing + fenceLength
        else
          result.append(line(index))
          index += 1
      else
        result.append(line(index))
        index += 1
    result.result()

  private def isSetextHeading(line: String, previousLine: String): Boolean =
    previousLine.trim.nonEmpty && line.matches("^ {0,3}(=+|-+)\\s*$")

  private def validateDirectiveLine(
    sourcePath: String,
    lineNumber: Int,
    line: String,
    calloutDepth: Int,
    errors: ArrayBuffer[String]
  ): Int =
    val names = DirectiveName.findAllMatchIn(line).map(_.group(1)).toVector
    if names.isEmpty then calloutDepth
    else
      line match
        case StandaloneApiSymbol(_) =>
          errors += s"$sourcePath:$lineNumber: apiSymbol must be embedded in inline content."
          calloutDepth
        case SingleDirective(name, id) =>
          if Set("example", "lab", "trace", "compatibility").contains(name) && !HeadingId.matches(
              id
            )
          then errors += s"$sourcePath:$lineNumber: invalid $name id '$id'."
          else if name == "lab" && LabCatalog.get(id).isEmpty then
            errors += s"$sourcePath:$lineNumber: unknown lab '$id'."
          else if name == "trace" && TraceCatalog.get(id).isEmpty then
            errors += s"$sourcePath:$lineNumber: unknown trace '$id'."
          calloutDepth
        case SourceDirective(path, _) =>
          try
            val parsedPath = Path.of(path)
            if parsedPath.isAbsolute || escapesParent(parsedPath.normalize()) then
              errors += s"$sourcePath:$lineNumber: sourceRegion path must be repository-relative."
          catch
            case _: Exception =>
              errors += s"$sourcePath:$lineNumber: sourceRegion path is invalid."
          calloutDepth
        case CalloutDirective(kind) =>
          if calloutKind(kind).isEmpty then
            errors += s"$sourcePath:$lineNumber: unsupported callout kind '$kind'."
          calloutDepth + 1
        case value if value.trim == "@:@" =>
          if calloutDepth == 0 then
            errors += s"$sourcePath:$lineNumber: unexpected directive closing marker."
            0
          else calloutDepth - 1
        case value if DirectiveName.findFirstIn(InlineApiSymbol.replaceAllIn(value, "")).isEmpty =>
          calloutDepth
        case _ =>
          names.distinct.sorted.foreach {
            case "@" =>
              errors += s"$sourcePath:$lineNumber: unexpected directive closing marker."
            case name
                if Set(
                  "example",
                  "lab",
                  "trace",
                  "sourceRegion",
                  "apiSymbol",
                  "compatibility",
                  "callout"
                )
                  .contains(name) =>
              errors += s"$sourcePath:$lineNumber: invalid @:$name directive."
            case name => errors += s"$sourcePath:$lineNumber: directive '$name' is not supported."
          }
          calloutDepth
    end if
  end validateDirectiveLine

  private def duplicateErrors(documents: Vector[AuthoredDocument]): Vector[String] =
    val errors = ArrayBuffer.empty[String]
    duplicateGroups(documents)(_.route).foreach { case (route, docs) =>
      errors += s"route collision '$route': ${docs.map(_.sourcePath).sorted.mkString(", ")}."
    }
    duplicateGroups(documents)(_.metadata.title).foreach { case (title, docs) =>
      errors += s"duplicate page title '$title': ${docs.map(_.sourcePath).sorted.mkString(", ")}."
    }
    duplicateGroups(documents.filter(isNavigationDocument))(doc =>
      doc.metadata.section -> doc.metadata.order
    ).foreach { case ((section, order), docs) =>
      errors +=
        s"duplicate navigation position (${sectionName(section)}, $order): ${docs.map(_.sourcePath).sorted.mkString(", ")}."
    }
    errors.toVector

  private def duplicateGroups[A](
    documents: Vector[AuthoredDocument]
  )(
    key: AuthoredDocument => A
  ): Vector[(A, Vector[AuthoredDocument])] =
    documents
      .groupBy(key).toVector.collect {
        case (value, matches) if matches.sizeIs > 1 =>
          value -> matches
      }.sortBy(_._1.toString)

  private def isNavigationDocument(document: AuthoredDocument): Boolean =
    document.metadata.section != Section.Examples || document.route == "/examples"

  private def parseTree(
    documents: Vector[AuthoredDocument],
    paths: ValidatedPaths
  ): Either[PipelineError, laika.ast.DocumentTreeRoot] =
    val documentFiles = documents.map(_.file).toSet
    val inputs        = documents.foldLeft(InputTree[IO]) { (input, document) =>
      input.addString(document.normalized, document.virtualPath)
    }
    val allInputs = paths.files.filterNot(documentFiles).foldLeft(inputs) { (input, file) =>
      input.addFile(file.toString, virtualPath(paths.content.relativize(file)))
    }
    val parser = MarkupParser
      .of(Markdown)
      .using(Markdown.GitHubFlavor, CodeHighlighter.syntaxHighlighting, PipelineDirectives)
      .withConfigValue(LaikaKeys.firstHeaderAsTitle, false)
      .sequential[IO]
      .withTheme(Theme.empty)
      .build

    try Right(parser.use(_.fromInput(allInputs).parse).map(_.root).unsafeRunSync())
    catch
      case NonFatal(error) =>
        val message = sanitizeParserError(
          Option(error.getMessage).getOrElse(error.getClass.getSimpleName),
          paths
        )
        Left(PipelineError(Vector(message)))

  private def sanitizeParserError(message: String, paths: ValidatedPaths): String =
    singleLine(message)
      .replace(paths.repository.toString, "<repository>")
      .replace(posix(paths.repository), "<repository>")

  private def convertTree(
    tree: laika.ast.DocumentTreeRoot,
    authored: Vector[AuthoredDocument],
    paths: ValidatedPaths,
    apiReference: ApiReference,
    examples: Vector[ExampleDefinition]
  ): Either[PipelineError, DocumentationBundle] =
    val routeByPath =
      authored.map(document => document.virtualPath.toString -> document.route).toMap
    val documentByPath = tree.allDocuments.map(document => document.path.toString -> document).toMap
    val pageResults    = authored.map { source =>
      documentByPath.get(source.virtualPath.toString) match
        case None =>
          Left(Vector(s"${source.sourcePath}: parsed document is missing from the Laika tree."))
        case Some(document) =>
          convertBlocks(
            document.content.content,
            source,
            routeByPath,
            paths,
            apiReference.symbols.map(symbol => symbol.id -> symbol).toMap
          ).map { blocks =>
            Page(
              route = source.route,
              metadata = source.metadata,
              source = PageSource.Authored(SourceLocation(source.sourcePath, 1)),
              outline = buildOutline(collectHeadings(blocks), apiReference),
              content = blocks
            )
          }
    }
    val (failures, pages) = pageResults.partitionMap(identity)
    if failures.nonEmpty then Left(PipelineError(failures.flatten.sorted))
    else
      val referenceErrors =
        validateApiReferences(pages, apiReference) ++
          validateExampleReferences(pages, examples) ++
          validateCanonicalExamplePages(pages, examples)
      if referenceErrors.nonEmpty then Left(PipelineError(referenceErrors))
      else
        val hasApiLanding  = pages.exists(_.route == "/api")
        val apiPages       = if hasApiLanding then generatedApiPages(apiReference) else Vector.empty
        val authoredRoutes = pages.map(_.route).toSet
        val collisions     = apiPages.collect {
          case page if authoredRoutes(page.route) =>
            s"generated API route collides with an authored page: '${page.route}'."
        }
        if collisions.nonEmpty then Left(PipelineError(collisions))
        else
          val sortedPages = (pages ++ apiPages).sortBy(pageSortKey)
          SearchCorpus
            .build(sortedPages, apiReference, examples)
            .left.map(PipelineError.apply)
            .map { searchEntries =>
              DocumentationBundle(
                formatVersion = DocumentationBundle.CurrentFormatVersion,
                navigation = buildNavigation(sortedPages, apiReference),
                pages = sortedPages,
                examples = examples,
                apiReference = apiReference,
                searchEntries = searchEntries
              )
            }
    end if
  end convertTree

  private def validateApiReferences(
    pages: Vector[Page],
    apiReference: ApiReference
  ): Vector[String] =
    val symbols = apiReference.symbols.map(_.id).toSet
    pages
      .flatMap { page =>
        collectApiReferences(page.content).collect {
          case id if !symbols(id) => s"${pageSourceName(page)}: unknown API symbol '$id'."
        }
      }.distinct.sorted

  private def collectApiReferences(blocks: Vector[Block]): Vector[String] =
    blocks.flatMap {
      case Block.ApiSymbolRef(id)       => Vector(id)
      case Block.Paragraph(content)     => collectInlineApiReferences(content)
      case Block.Heading(_, _, content) => collectInlineApiReferences(content)
      case Block.Table(header, rows)    =>
        (header ++ rows.flatMap(_.cells)).flatMap(cell => collectInlineApiReferences(cell.content))
      case Block.BulletList(items)      => items.flatMap(item => collectApiReferences(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => collectApiReferences(item.content))
      case Block.Quote(content)         => collectApiReferences(content)
      case Block.Callout(_, _, content) => collectApiReferences(content)
      case _                            => Vector.empty
    }

  private def collectInlineApiReferences(inlines: Vector[Inline]): Vector[String] =
    inlines.flatMap {
      case Inline.ApiSymbolRef(id, _) => Vector(id)
      case Inline.Emphasis(content)   => collectInlineApiReferences(content)
      case Inline.Strong(content)     => collectInlineApiReferences(content)
      case Inline.Strike(content)     => collectInlineApiReferences(content)
      case Inline.Link(content, _, _) => collectInlineApiReferences(content)
      case _                          => Vector.empty
    }

  private def validateExampleReferences(
    pages: Vector[Page],
    examples: Vector[ExampleDefinition]
  ): Vector[String] =
    val ids = examples.map(_.descriptor.id).toSet
    pages
      .flatMap { page =>
        collectExampleReferences(page.content).collect {
          case id if !ids(id) => s"${pageSourceName(page)}: unknown example '$id'."
        }
      }.distinct.sorted

  private def validateCanonicalExamplePages(
    pages: Vector[Page],
    examples: Vector[ExampleDefinition]
  ): Vector[String] =
    val errors  = ArrayBuffer.empty[String]
    val byRoute = pages.map(page => page.route -> page).toMap

    if examples.nonEmpty then
      byRoute.get("/examples") match
        case None       => errors += "example catalog page '/examples' is missing."
        case Some(page) =>
          if page.metadata.section != Section.Examples then
            errors += "example catalog page '/examples' must use section examples."
          if collectExampleReferences(page.content).nonEmpty then
            errors += "example catalog page '/examples' must not embed executable examples."

    examples.foreach { example =>
      val descriptor = example.descriptor
      val route      = s"/examples/${descriptor.id}"
      byRoute.get(route) match
        case None       => errors += s"example '${descriptor.id}' requires canonical page '$route'."
        case Some(page) =>
          if page.metadata.section != Section.Examples then
            errors += s"canonical example page '$route' must use section examples."
          if page.metadata.title != descriptor.title then
            errors += s"canonical example page '$route' title must match example '${descriptor.id}'."
          if page.metadata.description != descriptor.description then
            errors +=
              s"canonical example page '$route' description must match example '${descriptor.id}'."
          val references = collectExampleReferences(page.content)
          if references != Vector(descriptor.id) then
            errors += s"canonical example page '$route' must embed exactly '${descriptor.id}'."
    }

    errors.toVector.distinct.sorted
  end validateCanonicalExamplePages

  private def collectExampleReferences(blocks: Vector[Block]): Vector[String] =
    blocks.flatMap {
      case Block.ExampleRef(id)    => Vector(id)
      case Block.BulletList(items) => items.flatMap(item => collectExampleReferences(item.content))
      case Block.OrderedList(_, items) =>
        items.flatMap(item => collectExampleReferences(item.content))
      case Block.Quote(content)         => collectExampleReferences(content)
      case Block.Callout(_, _, content) => collectExampleReferences(content)
      case _                            => Vector.empty
    }

  private def resolveExamples(
    paths: ValidatedPaths,
    descriptors: Vector[ExampleDescriptor]
  ): Either[PipelineError, Vector[ExampleDefinition]] =
    val errors = ArrayBuffer.empty[String]

    descriptors.groupBy(_.id).toVector.sortBy(_._1).foreach { case (id, matches) =>
      if matches.sizeIs > 1 then errors += s"duplicate example id '$id'."
    }
    descriptors.foreach { descriptor =>
      if !HeadingId.matches(descriptor.id) then
        errors += s"invalid example id '${descriptor.id}'; expected lowercase kebab-case."
      validateExampleText(descriptor.id, "title", descriptor.title, errors)
      validateExampleText(descriptor.id, "description", descriptor.description, errors)
      validateExampleText(descriptor.id, "reset description", descriptor.resetDescription, errors)
      if descriptor.sources.isEmpty then errors += s"example '${descriptor.id}' must have source."
      descriptor.sources.foreach { source =>
        if source.label.trim.isEmpty then
          errors += s"example '${descriptor.id}' source label must not be blank."
        if source.path.trim.isEmpty then
          errors += s"example '${descriptor.id}' source path must not be blank."
        if source.region.trim.isEmpty then
          errors += s"example '${descriptor.id}' source region must not be blank."
      }
      descriptor.sources.groupBy(_.label.trim.toLowerCase).foreach { case (label, matches) =>
        if label.nonEmpty && matches.sizeIs > 1 then
          errors += s"example '${descriptor.id}' has duplicate source label '$label'."
      }
      (descriptor.topics ++ descriptor.aliases).foreach { value =>
        if value.trim.isEmpty then
          errors += s"example '${descriptor.id}' search terms must not be blank."
      }
      val topicKeys = descriptor.topics.map(ExampleTopic.key)
      if topicKeys.exists(_.isEmpty) then
        errors += s"example '${descriptor.id}' topics must contain letters or digits."
      topicKeys.groupBy(identity).foreach { case (key, matches) =>
        if key.nonEmpty && matches.sizeIs > 1 then
          errors += s"example '${descriptor.id}' has duplicate topic key '$key'."
      }
    }

    if errors.nonEmpty then Left(PipelineError(errors.toVector.distinct.sorted))
    else
      val results = descriptors.sortBy(_.id).map { descriptor =>
        val sources = descriptor.sources.map { source =>
          val sourcePath: Either[String, Path] =
            try Right(Path.of(source.path))
            catch
              case _: Exception => Left(s"example '${descriptor.id}' has an invalid source path.")
          sourcePath.flatMap { path =>
            SourceExtractor
              .extract(
                paths.repository,
                paths.allowedSourceRoots,
                path,
                source.region
              ).left.map(_.message).map { extracted =>
                val language = source.language.orElse(inferLanguage(extracted.path))
                ExampleSourceCode(
                  label = source.label,
                  region = SourceRegion(extracted.path, extracted.startLine, extracted.endLine),
                  language = language,
                  text = extracted.content,
                  tokens = CodeHighlighter.highlight(language, extracted.content)
                )
              }
          }
        }
        val (sourceFailures, sourceDefinitions) = sources.partitionMap(identity)
        if sourceFailures.nonEmpty then Left(sourceFailures.sorted.mkString("\n"))
        else
          Right(
            ExampleDefinition(
              descriptor = descriptor,
              sources = sourceDefinitions,
              compilationFailures = Vector.empty
            )
          )
      }
      val (failures, definitions) = results.partitionMap(identity)
      if failures.nonEmpty then Left(PipelineError(failures.sorted)) else Right(definitions)
    end if
  end resolveExamples

  private def validateExampleText(
    id: String,
    field: String,
    value: String,
    errors: ArrayBuffer[String]
  ): Unit =
    if value.trim.isEmpty then errors += s"example '$id' $field must not be blank."

  private def generatedApiPages(apiReference: ApiReference): Vector[Page] =
    val symbolsByRoute = apiReference.symbols.groupBy(_.route).toVector.sortBy(_._1)
    symbolsByRoute.zipWithIndex.map { case ((route, routeSymbols), index) =>
      val owners         = routeSymbols.filter(_.fragment.isEmpty).sortBy(ownerSortKey)
      val members        = routeSymbols.filter(_.fragment.nonEmpty)
      val representative = owners.headOption.getOrElse(routeSymbols.sortBy(_.id).head)
      val orderedMembers = owners.flatMap(owner =>
        members.filter(_.ownerId.contains(owner.id)).sortBy(memberSortKey)
      ) ++
        members
          .filter(member => !owners.exists(owner => member.ownerId.contains(owner.id))).sortBy(
            memberSortKey
          )
      val symbols     = owners ++ orderedMembers
      val description = owners
        .map(_.summary).find(summary => summary.nonEmpty && !isFallbackSummary(summary))
        .getOrElse(representative.summary)
      val outline = ApiMemberCategory.group(orderedMembers).map { case (category, groupedMembers) =>
        OutlineItem(
          category.id,
          category.title,
          2,
          groupedMembers.flatMap { symbol =>
            symbol.fragment.map(fragment => OutlineItem(fragment, symbol.name, 3, Vector.empty))
          }
        )
      }
      Page(
        route = route,
        metadata = PageMetadata(
          title =
            if isCompanionObject(representative, apiReference) then
              s"${representative.qualifiedName} companion object"
            else representative.qualifiedName,
          description = description,
          order = index,
          section = Section.Api
        ),
        source = PageSource.GeneratedApi(representative.id),
        outline = PageOutline(outline),
        content = symbols.map(symbol => Block.ApiSymbolRef(symbol.id))
      )
    }
  end generatedApiPages

  private def ownerSortKey(symbol: ApiSymbol): (Int, String) =
    val rank = symbol.kind match
      case ApiSymbolKind.Trait      => 0
      case ApiSymbolKind.Class      => 1
      case ApiSymbolKind.Enum       => 2
      case ApiSymbolKind.OpaqueType => 3
      case ApiSymbolKind.TypeAlias  => 4
      case ApiSymbolKind.Object     => 5
      case ApiSymbolKind.Package    => 6
      case _                        => 7
    (rank, symbol.id)

  private def memberSortKey(symbol: ApiSymbol): (Int, String, String) =
    val rank = symbol.kind match
      case ApiSymbolKind.Def | ApiSymbolKind.Extension                                         => 0
      case ApiSymbolKind.Val | ApiSymbolKind.LazyVal | ApiSymbolKind.Var | ApiSymbolKind.Given => 1
      case ApiSymbolKind.TypeAlias | ApiSymbolKind.OpaqueType                                  => 2
      case _                                                                                   => 3
    (rank, symbol.name.toLowerCase, symbol.id)

  private def isFallbackSummary(summary: String): Boolean =
    summary.startsWith("Public API for the `") ||
      summary.startsWith("Public APIs in the `") ||
      (summary.startsWith("The `") && summary.endsWith("."))

  private def isCompanionObject(symbol: ApiSymbol, apiReference: ApiReference): Boolean =
    symbol.kind == ApiSymbolKind.Object && apiReference.symbols.exists(candidate =>
      candidate.fragment.isEmpty &&
        candidate.qualifiedName == symbol.qualifiedName &&
        candidate.kind != ApiSymbolKind.Object
    )

  private def pageSourceName(page: Page): String = page.source match
    case PageSource.Authored(location) => location.path
    case PageSource.GeneratedApi(id)   => s"generated API page '$id'"

  private def convertBlocks(
    blocks: Seq[LaikaBlock],
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    paths: ValidatedPaths,
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], Vector[Block]] =
    val converted        = blocks.map(convertBlock(_, source, routeByPath, paths, apiSymbols))
    val (errors, values) = converted.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector)
    else Right(values.flatten.toVector)

  private def convertBlock(
    block: LaikaBlock,
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    paths: ValidatedPaths,
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], Vector[Block]] = block match
    case laika.ast.Paragraph(Seq(image: laika.ast.Image), _) =>
      convertImage(image).map(value => Vector(value))
    case laika.ast.Paragraph(content, _) =>
      convertInlines(content, source, routeByPath, apiSymbols).map(value =>
        Vector(Block.Paragraph(value))
      )
    case section: laika.ast.Section =>
      for
        heading <- convertHeading(section.header, source, routeByPath, apiSymbols)
        content <- convertBlocks(section.content, source, routeByPath, paths, apiSymbols)
      yield heading +: content
    case header: laika.ast.Header =>
      convertHeading(header, source, routeByPath, apiSymbols).map(value => Vector(value))
    case laika.ast.CodeBlock(language, content, _, _) =>
      val tokens = CodeHighlighter.fromSpans(content)
      Right(
        Vector(
          Block.Code(
            language = Option(language).filter(_.nonEmpty),
            text = tokens.map(_.text).mkString,
            tokens = tokens,
            sourceRegion = None
          )
        )
      )
    case list: laika.ast.BulletList =>
      convertListItems(list.content.map(_.content), source, routeByPath, paths, apiSymbols)
        .map(items => Vector(Block.BulletList(items)))
    case list: laika.ast.EnumList =>
      convertListItems(list.content.map(_.content), source, routeByPath, paths, apiSymbols)
        .map { items =>
          val start = list.content.headOption.map(_.position).getOrElse(list.start)
          Vector(Block.OrderedList(start, items))
        }
    case quote: laika.ast.QuotedBlock if quote.attribution.isEmpty =>
      convertBlocks(quote.content, source, routeByPath, paths, apiSymbols)
        .map(content => Vector(Block.Quote(content)))
    case _: laika.ast.QuotedBlock => unsupported(source, block)
    case table: laika.ast.Table   =>
      convertTable(table, source, routeByPath, apiSymbols).map(value => Vector(value))
    case _: laika.ast.Rule       => Right(Vector(Block.Rule))
    case node: ExampleNode       => Right(Vector(Block.ExampleRef(node.id)))
    case node: LabNode           => Right(Vector(Block.LabRef(node.id)))
    case node: TraceNode         => Right(Vector(Block.TraceRef(node.id)))
    case node: CompatibilityNode => Right(Vector(Block.CompatibilityRef(node.id)))
    case node: SourceRegionNode  => convertSourceRegion(node, paths).map(value => Vector(value))
    case node: CalloutNode       =>
      for
        kind <- calloutKind(node.kind).toRight(
                  Vector(s"${source.sourcePath}: unsupported callout kind '${node.kind}'.")
                )
        content <- convertBlocks(node.content, source, routeByPath, paths, apiSymbols)
      yield Vector(Block.Callout(kind, None, content))
    case sequence: laika.ast.BlockSequence =>
      convertBlocks(sequence.content, source, routeByPath, paths, apiSymbols)
    case _ => unsupported(source, block)

  private def convertHeading(
    header: laika.ast.Header,
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], Block.Heading] =
    if !header.hasStyle(ExplicitHeadingStyle) || header.options.id.isEmpty then
      Left(Vector("Laika produced a heading without an explicit author-supplied id."))
    else
      convertInlines(header.content, source, routeByPath, apiSymbols)
        .map(content => Block.Heading(header.level, header.options.id.get, content))

  private def convertListItems(
    items: Seq[Seq[LaikaBlock]],
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    paths: ValidatedPaths,
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], Vector[ListItem]] =
    val results =
      items.map(convertBlocks(_, source, routeByPath, paths, apiSymbols).map(ListItem(_)))
    val (errors, converted) = results.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector) else Right(converted.toVector)

  private def convertTable(
    table: laika.ast.Table,
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], Block.Table] =
    val headerRows = table.head.content
    if headerRows.size != 1 then Left(Vector("GFM tables must contain exactly one header row."))
    else
      for
        header <- convertTableRow(headerRows.head, source, routeByPath, apiSymbols)
        rows   <-
          sequence(table.body.content.map(convertTableRow(_, source, routeByPath, apiSymbols)))
      yield Block.Table(header.cells, rows)

  private def convertTableRow(
    row: laika.ast.Row,
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], TableRow] =
    sequence(row.content.map(convertTableCell(_, source, routeByPath, apiSymbols))).map(cells =>
      TableRow(cells)
    )

  private def convertTableCell(
    cell: laika.ast.Cell,
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], TableCell] =
    if cell.colspan != 1 || cell.rowspan != 1 then
      Left(Vector("Spanning table cells are not supported."))
    else
      cell.content match
        case Seq(laika.ast.Paragraph(content, _)) =>
          convertInlines(content, source, routeByPath, apiSymbols).map(TableCell(_))
        case _ => Left(Vector("Table cells must contain a single paragraph."))

  private def convertInlines(
    spans: Seq[LaikaSpan],
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], Vector[Inline]] =
    val converted        = spans.map(convertInline(_, source, routeByPath, apiSymbols))
    val (errors, values) = converted.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector)
    else Right(values.flatten.toVector)

  private def convertInline(
    span: LaikaSpan,
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    apiSymbols: Map[String, ApiSymbol]
  ): Either[Vector[String], Vector[Inline]] = span match
    case laika.ast.Text(content, _)       => Right(Vector(Inline.Text(content)))
    case laika.ast.Emphasized(content, _) =>
      convertInlines(content, source, routeByPath, apiSymbols).map(value =>
        Vector(Inline.Emphasis(value))
      )
    case laika.ast.Strong(content, _) =>
      convertInlines(content, source, routeByPath, apiSymbols).map(value =>
        Vector(Inline.Strong(value))
      )
    case laika.ast.Deleted(content, _) =>
      convertInlines(content, source, routeByPath, apiSymbols).map(value =>
        Vector(Inline.Strike(value))
      )
    case laika.ast.Literal(content, _)       => Right(Vector(Inline.Code(content)))
    case laika.ast.InlineCode(_, content, _) =>
      Right(Vector(Inline.Code(extractText(content))))
    case link: laika.ast.SpanLink =>
      for
        content <- convertInlines(link.content, source, routeByPath, apiSymbols)
        target  <- convertLinkTarget(link.target, routeByPath)
      yield Vector(Inline.Link(content, target, link.title))
    case _: laika.ast.LineBreak => Right(Vector(Inline.LineBreak))
    case node: ApiSymbolNode    =>
      if !apiSymbols.contains(node.id) then
        Left(Vector(s"${source.sourcePath}: unknown API symbol '${node.id}'."))
      else
        apiSymbolLabel(node.content)
          .toRight(
            Vector(s"${source.sourcePath}: apiSymbol label must be one non-empty code span.")
          ).map(label => Vector(Inline.ApiSymbolRef(node.id, label)))
    case sequence: laika.ast.SpanSequence =>
      convertInlines(sequence.content, source, routeByPath, apiSymbols)
    case image: laika.ast.Image =>
      Left(Vector(s"Image '${image.target.render()}' must be the sole content of a paragraph."))
    case _ => Left(Vector(s"Unsupported inline Markdown node: ${span.productPrefix}."))

  private def convertLinkTarget(
    target: laika.ast.Target,
    routeByPath: Map[String, String]
  ): Either[Vector[String], LinkTarget] = target match
    case laika.ast.ExternalTarget(url) =>
      val scheme =
        try Option(URI.create(url).getScheme).map(_.toLowerCase)
        catch case _: IllegalArgumentException => None
      scheme match
        case Some(value) if AllowedExternalSchemes(value) => Right(LinkTarget.External(url))
        case Some(value) => Left(Vector(s"external link scheme '$value' is not allowed: '$url'."))
        case None        => Left(Vector(s"external link must use an allowed absolute URI: '$url'."))
    case resolved: laika.ast.InternalTarget.Resolved =>
      val path = resolved.absolutePath.withoutFragment.toString
      routeByPath.get(path) match
        case Some(route) => Right(LinkTarget.Internal(route, resolved.absolutePath.fragment))
        case None => Left(Vector(s"Internal link does not target a documentation page: '$path'."))
    case _ => Left(Vector(s"Unresolved internal link target: '${target.render()}'."))

  private def convertImage(image: laika.ast.Image): Either[Vector[String], Block.Image] =
    Left(
      Vector(
        s"Markdown images are not supported until documentation assets are packaged: '${image.target.render()}'."
      )
    )

  private def extractText(spans: Seq[LaikaSpan]): String = spans.map {
    case container: laika.ast.TextContainer => container.content
    case container: laika.ast.SpanContainer => extractText(container.content)
    case _                                  => ""
  }.mkString

  private def apiSymbolLabel(content: Seq[LaikaSpan]): Option[String] = content match
    case Seq(laika.ast.Literal(value, _)) if value.trim.nonEmpty => Some(value)
    case Seq(laika.ast.InlineCode(_, spans, _))                  =>
      Option(extractText(spans)).filter(_.trim.nonEmpty)
    case _ => None

  private def convertSourceRegion(
    node: SourceRegionNode,
    paths: ValidatedPaths
  ): Either[Vector[String], Block.SourceCode] =
    SourceExtractor
      .extract(
        paths.repository,
        paths.allowedSourceRoots,
        Path.of(node.path),
        node.region
      ).left.map(error => Vector(error.message)).map { extracted =>
        val region = SourceRegion(extracted.path, extracted.startLine, extracted.endLine)
        Block.SourceCode(
          region,
          inferLanguage(extracted.path),
          extracted.content,
          CodeHighlighter.highlight(inferLanguage(extracted.path), extracted.content)
        )
      }

  private def inferLanguage(path: String): Option[String] =
    path.split('.').lastOption.map(_.toLowerCase).flatMap {
      case "scala" | "sc" => Some("scala")
      case "java"         => Some("java")
      case "js"           => Some("javascript")
      case "jsx"          => Some("jsx")
      case "ts"           => Some("typescript")
      case "tsx"          => Some("tsx")
      case "py"           => Some("python")
      case "sh" | "bash"  => Some("bash")
      case "html"         => Some("html")
      case "css"          => Some("css")
      case "xml"          => Some("xml")
      case "yaml" | "yml" => Some("yaml")
      case "json"         => Some("json")
      case "sql"          => Some("sql")
      case "hs"           => Some("haskell")
      case _              => None
    }

  private def unsupported(
    source: AuthoredDocument,
    block: LaikaBlock
  ): Left[Vector[String], Nothing] =
    Left(Vector(s"${source.sourcePath}: unsupported Markdown node: ${block.productPrefix}."))

  private def sequence[A](
    values: Seq[Either[Vector[String], A]]
  ): Either[Vector[String], Vector[A]] =
    val (errors, converted) = values.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector) else Right(converted.toVector)

  private def collectHeadings(blocks: Vector[Block]): Vector[Block.Heading] =
    blocks.flatMap {
      case heading: Block.Heading       => Vector(heading)
      case Block.BulletList(items)      => items.flatMap(item => collectHeadings(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => collectHeadings(item.content))
      case Block.Quote(content)         => collectHeadings(content)
      case Block.Callout(_, _, content) => collectHeadings(content)
      case _                            => Vector.empty
    }

  final private case class MutableOutline(
    id: String,
    title: String,
    level: Int,
    children: ArrayBuffer[MutableOutline] = ArrayBuffer.empty):
    def result: OutlineItem = OutlineItem(id, title, level, children.map(_.result).toVector)

  private def buildOutline(
    headings: Vector[Block.Heading],
    apiReference: ApiReference
  ): PageOutline =
    val apiSymbols = apiReference.symbols.map(symbol => symbol.id -> symbol).toMap
    val roots      = ArrayBuffer.empty[MutableOutline]
    val stack      = ArrayBuffer.empty[MutableOutline]
    headings.foreach { heading =>
      val item = MutableOutline(heading.id, inlineText(heading.content, apiSymbols), heading.level)
      while stack.nonEmpty && stack.last.level >= item.level do
        val _ = stack.remove(stack.size - 1)
      if stack.isEmpty then roots += item else stack.last.children += item
      stack += item
    }
    PageOutline(roots.map(_.result).toVector)

  private def inlineText(inlines: Vector[Inline], apiSymbols: Map[String, ApiSymbol]): String =
    inlines.map {
      case Inline.Text(value)            => value
      case Inline.Emphasis(content)      => inlineText(content, apiSymbols)
      case Inline.Strong(content)        => inlineText(content, apiSymbols)
      case Inline.Strike(content)        => inlineText(content, apiSymbols)
      case Inline.Code(value)            => value
      case Inline.Link(content, _, _)    => inlineText(content, apiSymbols)
      case Inline.ApiSymbolRef(_, label) => label
      case Inline.LineBreak              => " "
    }.mkString

  private def buildNavigation(pages: Vector[Page], apiReference: ApiReference): Navigation =
    val items = SectionOrder.flatMap { section =>
      val sectionPages = pages.filter(_.metadata.section == section).sortBy(pageSortKey)
      val rootRoute    = if section == Section.Home then "/" else s"/${sectionName(section)}"
      sectionPages.find(_.route == rootRoute) match
        case Some(root) =>
          Vector(
            if section == Section.Api then
              apiNavigation(root, sectionPages.filterNot(_.route == rootRoute), apiReference)
            else if section == Section.Examples then navigationItem(root)
            else
              navigationItem(root, sectionPages.filterNot(_.route == rootRoute).map(navigationItem))
          )
        case None => sectionPages.map(navigationItem)
    }
    Navigation(items)

  private def apiNavigation(
    root: Page,
    pages: Vector[Page],
    apiReference: ApiReference
  ): NavigationItem =
    val symbolsById  = apiReference.symbols.map(symbol => symbol.id -> symbol).toMap
    val pagesByRoute = pages.map(page => page.route -> page).toMap

    def pageSymbol(page: Page): Option[ApiSymbol] = page.source match
      case PageSource.GeneratedApi(id) => symbolsById.get(id)
      case _                           => None

    def parentRoute(page: Page): String =
      pageSymbol(page)
        .flatMap(_.ownerId)
        .flatMap(symbolsById.get)
        .map(_.route)
        .filter(route => route != page.route && pagesByRoute.contains(route))
        .getOrElse(root.route)

    val pagesByParent = pages.groupBy(parentRoute)

    def apiItem(page: Page): NavigationItem =
      val children = pagesByParent
        .getOrElse(page.route, Vector.empty)
        .filter(navigationOwner)
        .sortBy(child => pageSymbol(child).map(navigationSortKey))
        .map(apiItem)
      NavigationItem(
        pageSymbol(page).map(_.name).getOrElse(page.metadata.title),
        page.route,
        page.metadata.section,
        children,
        page.metadata.group
      )

    def navigationOwner(page: Page): Boolean = pageSymbol(page).exists(symbol =>
      symbol.kind match
        case ApiSymbolKind.Package | ApiSymbolKind.Class | ApiSymbolKind.Trait |
            ApiSymbolKind.Object | ApiSymbolKind.Enum =>
          true
        case _ => false
    )

    navigationItem(
      root,
      pagesByParent
        .getOrElse(root.route, Vector.empty)
        .filter(navigationOwner)
        .sortBy(page => pageSymbol(page).map(navigationSortKey))
        .map(apiItem)
    )
  end apiNavigation

  private def navigationSortKey(symbol: ApiSymbol): (String, Int, String) =
    val (kindRank, id) = ownerSortKey(symbol)
    (symbol.name.toLowerCase, kindRank, id)

  private def navigationItem(page: Page): NavigationItem =
    navigationItem(page, Vector.empty)

  private def navigationItem(page: Page, children: Vector[NavigationItem]): NavigationItem =
    NavigationItem(
      page.metadata.title,
      page.route,
      page.metadata.section,
      children,
      page.metadata.group
    )

  private def pageSortKey(page: Page): (Int, Int, String) =
    val sourceKey = page.source match
      case PageSource.Authored(location) => location.path
      case PageSource.GeneratedApi(id)   => id
    (SectionOrder.indexOf(page.metadata.section), page.metadata.order, sourceKey)

  private def routeFor(relative: Path): Either[String, String] =
    val segments      = relative.iterator().asScala.map(_.toString).toVector
    val fileName      = segments.lastOption.getOrElse("")
    val stem          = fileName.stripSuffix(".md")
    val routeSegments =
      if stem == "index" then segments.dropRight(1) else segments.dropRight(1) :+ stem
    val invalid = routeSegments.find(segment => !RouteSegment.matches(segment))
    invalid match
      case Some(segment) =>
        Left(s"route segment '$segment' must use lowercase kebab-case.")
      case None => Right(if routeSegments.isEmpty then "/" else "/" + routeSegments.mkString("/"))

  private def virtualPath(relative: Path): LaikaPath =
    relative.iterator().asScala.foldLeft[LaikaPath](LaikaPath.Root) { (path, segment) =>
      path / segment.toString
    }

  private def repositoryPath(paths: ValidatedPaths, file: Path): String =
    posix(paths.repository.relativize(file))

  private def contentPath(paths: ValidatedPaths, file: Path): String =
    posix(paths.content.relativize(file))

  private def sectionName(section: Section): String = section match
    case Section.Home     => "home"
    case Section.Learn    => "learn"
    case Section.Guides   => "guides"
    case Section.Examples => "examples"
    case Section.Api      => "api"
    case Section.Project  => "project"

  private def calloutKind(value: String): Option[CalloutKind] = value match
    case "info"    => Some(CalloutKind.Info)
    case "tip"     => Some(CalloutKind.Tip)
    case "warning" => Some(CalloutKind.Warning)
    case "error"   => Some(CalloutKind.Error)
    case _         => None

  private def singleLine(value: String): String = value.linesIterator.map(_.trim).mkString(" ")

  private def posix(path: Path): String = path.iterator().asScala.map(_.toString).mkString("/")

  final private case class HeadingMarker(id: String, options: Options = Options.empty)
      extends LaikaSpan:
    type Self = HeadingMarker
    def withOptions(options: Options): HeadingMarker = copy(options = options)

  final private case class ExampleNode(id: String, options: Options = Options.empty)
      extends LaikaBlock:
    type Self = ExampleNode
    def withOptions(options: Options): ExampleNode = copy(options = options)

  final private case class SourceRegionNode(
    path: String,
    region: String,
    options: Options = Options.empty)
      extends LaikaBlock:
    type Self = SourceRegionNode
    def withOptions(options: Options): SourceRegionNode = copy(options = options)

  final private case class ApiSymbolNode(
    id: String,
    content: Seq[LaikaSpan],
    options: Options = Options.empty)
      extends LaikaSpan:
    type Self = ApiSymbolNode
    def withOptions(options: Options): ApiSymbolNode = copy(options = options)

  final private case class CompatibilityNode(id: String, options: Options = Options.empty)
      extends LaikaBlock:
    type Self = CompatibilityNode
    def withOptions(options: Options): CompatibilityNode = copy(options = options)

  final private case class LabNode(id: String, options: Options = Options.empty) extends LaikaBlock:
    type Self = LabNode
    def withOptions(options: Options): LabNode = copy(options = options)

  final private case class TraceNode(id: String, options: Options = Options.empty)
      extends LaikaBlock:
    type Self = TraceNode
    def withOptions(options: Options): TraceNode = copy(options = options)

  final private case class CalloutNode(
    kind: String,
    content: Seq[LaikaBlock],
    options: Options = Options.empty)
      extends LaikaBlock
      with laika.ast.BlockContainer:
    type Self = CalloutNode
    def withContent(content: Seq[LaikaBlock]): CalloutNode = copy(content = content)
    def withOptions(options: Options): CalloutNode         = copy(options = options)

  private object PipelineDirectives extends DirectiveRegistry:
    import BlockDirectives.dsl as block
    import SpanDirectives.dsl as span

    val blockDirectives = Seq(
      BlockDirectives.create("example") {
        block.attribute(0).as[String].map(ExampleNode(_))
      },
      BlockDirectives.create("lab") {
        block.attribute(0).as[String].map(LabNode(_))
      },
      BlockDirectives.create("trace") {
        block.attribute(0).as[String].map(TraceNode(_))
      },
      BlockDirectives.create("sourceRegion") {
        (
          block.attribute(0).as[String].widen,
          block.attribute(1).as[String].widen
        ).mapN((path, region) => SourceRegionNode(path, region))
      },
      BlockDirectives.create("compatibility") {
        block.attribute(0).as[String].map(CompatibilityNode(_))
      },
      BlockDirectives.create("callout") {
        (block.attribute(0).as[String].widen, block.parsedBody).mapN(CalloutNode(_, _))
      }
    )
    val spanDirectives = Seq(
      SpanDirectives.create("apiSymbol") {
        (span.attribute(0).as[String].widen, span.parsedBody).mapN(ApiSymbolNode(_, _))
      },
      SpanDirectives.create("scaliveHeadingId") {
        span.attribute(0).as[String].map(HeadingMarker(_))
      }
    )
    val templateDirectives = Seq.empty
    val linkDirectives     = Seq.empty

    override val description = "Scalive documentation directives"

    override lazy val rewriteRules: RewriteRules.RewritePhaseBuilder = { case RewritePhase.Build =>
      Seq(
        RewriteRules.forBlocks {
          case header: laika.ast.Header
              if header.content.lastOption.exists(_.isInstanceOf[HeadingMarker]) =>
            val marker = header.content.last.asInstanceOf[HeadingMarker]
            RewriteAction.Replace(
              header
                .copy(
                  content = trimHeadingContent(header.content.dropRight(1)),
                  options = header.options + Id(marker.id)
                ).withStyle(ExplicitHeadingStyle)
            )
        }.asBuilder
      )
    }
  end PipelineDirectives

  private def trimHeadingContent(content: Seq[LaikaSpan]): Seq[LaikaSpan] =
    content.lastOption match
      case Some(laika.ast.Text(value, options)) =>
        val trimmed = value.replaceFirst("\\s+$", "")
        if trimmed.isEmpty then content.dropRight(1)
        else content.dropRight(1) :+ laika.ast.Text(trimmed, options)
      case _ => content
end ContentPipeline
