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
import laika.config.SyntaxHighlighting
import laika.format.Markdown
import laika.io.model.InputTree
import laika.io.syntax.*
import laika.theme.Theme

import scalive.docs.model.*

final case class PipelineError(messages: Vector[String]):
  def message: String = messages.mkString("\n")

object ContentPipeline:
  private val RequiredMetadata = Set("title", "description", "order", "section")
  private val RouteSegment     = "^[a-z0-9]+(?:-[a-z0-9]+)*$".r
  private val HeadingId        = "^[a-z0-9]+(?:-[a-z0-9]+)*$".r
  private val AtxHeading       = "^ {0,3}(#{1,6})(?:[ \\t]+|$)(.*)$".r
  private val ExplicitHeading  = "^(.*\\S)[ \\t]+\\{#([^{}]+)\\}[ \\t]*$".r
  private val Fence            = "^ {0,3}(`{3,}|~{3,})(.*)$".r
  private val RawHtml          =
    "(?i)<(?!https?://|mailto:)(?:!--|\\?|![a-z\\[]|/?[a-z][a-z0-9:-]*(?=[\\s/>]|$))".r
  private val DirectiveName   = "@:([A-Za-z][A-Za-z0-9]*|@)".r
  private val SingleDirective =
    "^\\s*@:(example|apiSymbol|compatibility)\\(([^\\s,(){}]+)\\)\\s*$".r
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
    apiReference: ApiReference
  ): Either[PipelineError, DocumentationBundle] =
    try
      for
        paths     <- validatePaths(repositoryRoot, contentRoot, allowedSourceRoots)
        authored  <- readAndValidate(paths)
        parsed    <- parseTree(authored, paths)
        converted <- convertTree(parsed, authored, paths, apiReference)
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
            fields.keySet.diff(RequiredMetadata).toVector.sorted.foreach { field =>
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

            for
              validTitle       <- title
              validDescription <- description
              validOrder       <- order
              validSection     <- section
            yield PageMetadata(validTitle, validDescription, validOrder, validSection)

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
        case SingleDirective(_, _)    => calloutDepth
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
        case _ =>
          names.distinct.sorted.foreach {
            case "@" =>
              errors += s"$sourcePath:$lineNumber: unexpected directive closing marker."
            case name
                if Set("example", "sourceRegion", "apiSymbol", "compatibility", "callout")
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
    duplicateGroups(documents)(doc => doc.metadata.section -> doc.metadata.order).foreach {
      case ((section, order), docs) =>
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
      .using(Markdown.GitHubFlavor, SyntaxHighlighting, PipelineDirectives)
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
    apiReference: ApiReference
  ): Either[PipelineError, DocumentationBundle] =
    val routeByPath =
      authored.map(document => document.virtualPath.toString -> document.route).toMap
    val documentByPath = tree.allDocuments.map(document => document.path.toString -> document).toMap
    val pageResults    = authored.map { source =>
      documentByPath.get(source.virtualPath.toString) match
        case None =>
          Left(Vector(s"${source.sourcePath}: parsed document is missing from the Laika tree."))
        case Some(document) =>
          convertBlocks(document.content.content, source, routeByPath, paths).map { blocks =>
            Page(
              route = source.route,
              metadata = source.metadata,
              source = PageSource.Authored(SourceLocation(source.sourcePath, 1)),
              outline = buildOutline(collectHeadings(blocks)),
              content = blocks
            )
          }
    }
    val (failures, pages) = pageResults.partitionMap(identity)
    if failures.nonEmpty then Left(PipelineError(failures.flatten.sorted))
    else
      val referenceErrors = validateApiReferences(pages, apiReference)
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
          Right(
            DocumentationBundle(
              formatVersion = DocumentationBundle.CurrentFormatVersion,
              navigation = buildNavigation(sortedPages),
              pages = sortedPages,
              apiReference = apiReference,
              searchEntries = if hasApiLanding then apiSearchEntries(apiReference) else Vector.empty
            )
          )
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
      case Block.BulletList(items)      => items.flatMap(item => collectApiReferences(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => collectApiReferences(item.content))
      case Block.Quote(content)         => collectApiReferences(content)
      case Block.Callout(_, _, content) => collectApiReferences(content)
      case _                            => Vector.empty
    }

  private def generatedApiPages(apiReference: ApiReference): Vector[Page] =
    val symbolsByRoute = apiReference.symbols.groupBy(_.route).toVector.sortBy(_._1)
    symbolsByRoute.zipWithIndex.map { case ((route, routeSymbols), index) =>
      val symbols        = routeSymbols.sortBy(symbol => (symbol.fragment.nonEmpty, symbol.id))
      val owners         = symbols.filter(_.fragment.isEmpty)
      val representative = owners.headOption.getOrElse(symbols.head)
      val description    = owners.map(_.summary).find(_.nonEmpty).getOrElse(representative.summary)
      val outline        = symbols.flatMap { symbol =>
        symbol.fragment.map(fragment => OutlineItem(fragment, symbol.name, 2, Vector.empty))
      }
      Page(
        route = route,
        metadata = PageMetadata(
          title = representative.qualifiedName,
          description = description,
          order = index,
          section = Section.Api
        ),
        source = PageSource.GeneratedApi(representative.id),
        outline = PageOutline(outline),
        content = symbols.map(symbol => Block.ApiSymbolRef(symbol.id))
      )
    }

  private def apiSearchEntries(apiReference: ApiReference): Vector[SearchEntry] =
    apiReference.symbols.sortBy(_.id).map { symbol =>
      SearchEntry(
        id = s"api:${symbol.id}",
        kind = SearchEntryKind.ApiSymbol,
        title = symbol.qualifiedName,
        description = symbol.summary,
        route = symbol.route,
        fragment = symbol.fragment,
        section = Section.Api,
        text = (Vector(symbol.qualifiedName, symbol.summary) ++
          symbol.signatures.flatMap(signature =>
            Vector(signature.signature, signature.origin.qualifiedName)
          )).mkString(" ")
      )
    }

  private def pageSourceName(page: Page): String = page.source match
    case PageSource.Authored(location) => location.path
    case PageSource.GeneratedApi(id)   => s"generated API page '$id'"

  private def convertBlocks(
    blocks: Seq[LaikaBlock],
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    paths: ValidatedPaths
  ): Either[Vector[String], Vector[Block]] =
    val converted        = blocks.map(convertBlock(_, source, routeByPath, paths))
    val (errors, values) = converted.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector)
    else Right(values.flatten.toVector)

  private def convertBlock(
    block: LaikaBlock,
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    paths: ValidatedPaths
  ): Either[Vector[String], Vector[Block]] = block match
    case laika.ast.Paragraph(Seq(image: laika.ast.Image), _) =>
      convertImage(image).map(value => Vector(value))
    case laika.ast.Paragraph(content, _) =>
      convertInlines(content, routeByPath).map(value => Vector(Block.Paragraph(value)))
    case section: laika.ast.Section =>
      for
        heading <- convertHeading(section.header, routeByPath)
        content <- convertBlocks(section.content, source, routeByPath, paths)
      yield heading +: content
    case header: laika.ast.Header =>
      convertHeading(header, routeByPath).map(value => Vector(value))
    case laika.ast.CodeBlock(language, content, _, _) =>
      val tokens = codeTokens(content)
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
      convertListItems(list.content.map(_.content), source, routeByPath, paths)
        .map(items => Vector(Block.BulletList(items)))
    case list: laika.ast.EnumList =>
      convertListItems(list.content.map(_.content), source, routeByPath, paths)
        .map { items =>
          val start = list.content.headOption.map(_.position).getOrElse(list.start)
          Vector(Block.OrderedList(start, items))
        }
    case quote: laika.ast.QuotedBlock if quote.attribution.isEmpty =>
      convertBlocks(quote.content, source, routeByPath, paths)
        .map(content => Vector(Block.Quote(content)))
    case _: laika.ast.QuotedBlock => unsupported(source, block)
    case table: laika.ast.Table   => convertTable(table, routeByPath).map(value => Vector(value))
    case _: laika.ast.Rule        => Right(Vector(Block.Rule))
    case node: ExampleNode        => Right(Vector(Block.ExampleRef(node.id)))
    case node: ApiSymbolNode      => Right(Vector(Block.ApiSymbolRef(node.id)))
    case node: CompatibilityNode  => Right(Vector(Block.CompatibilityRef(node.id)))
    case node: SourceRegionNode   => convertSourceRegion(node, paths).map(value => Vector(value))
    case node: CalloutNode        =>
      for
        kind <- calloutKind(node.kind).toRight(
                  Vector(s"${source.sourcePath}: unsupported callout kind '${node.kind}'.")
                )
        content <- convertBlocks(node.content, source, routeByPath, paths)
      yield Vector(Block.Callout(kind, None, content))
    case sequence: laika.ast.BlockSequence =>
      convertBlocks(sequence.content, source, routeByPath, paths)
    case _ => unsupported(source, block)

  private def convertHeading(
    header: laika.ast.Header,
    routeByPath: Map[String, String]
  ): Either[Vector[String], Block.Heading] =
    if !header.hasStyle(ExplicitHeadingStyle) || header.options.id.isEmpty then
      Left(Vector("Laika produced a heading without an explicit author-supplied id."))
    else
      convertInlines(header.content, routeByPath)
        .map(content => Block.Heading(header.level, header.options.id.get, content))

  private def convertListItems(
    items: Seq[Seq[LaikaBlock]],
    source: AuthoredDocument,
    routeByPath: Map[String, String],
    paths: ValidatedPaths
  ): Either[Vector[String], Vector[ListItem]] =
    val results = items.map(convertBlocks(_, source, routeByPath, paths).map(ListItem(_)))
    val (errors, converted) = results.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector) else Right(converted.toVector)

  private def convertTable(
    table: laika.ast.Table,
    routeByPath: Map[String, String]
  ): Either[Vector[String], Block.Table] =
    val headerRows = table.head.content
    if headerRows.size != 1 then Left(Vector("GFM tables must contain exactly one header row."))
    else
      for
        header <- convertTableRow(headerRows.head, routeByPath)
        rows   <- sequence(table.body.content.map(convertTableRow(_, routeByPath)))
      yield Block.Table(header.cells, rows)

  private def convertTableRow(
    row: laika.ast.Row,
    routeByPath: Map[String, String]
  ): Either[Vector[String], TableRow] =
    sequence(row.content.map(convertTableCell(_, routeByPath))).map(cells => TableRow(cells))

  private def convertTableCell(
    cell: laika.ast.Cell,
    routeByPath: Map[String, String]
  ): Either[Vector[String], TableCell] =
    if cell.colspan != 1 || cell.rowspan != 1 then
      Left(Vector("Spanning table cells are not supported."))
    else
      cell.content match
        case Seq(laika.ast.Paragraph(content, _)) =>
          convertInlines(content, routeByPath).map(TableCell(_))
        case _ => Left(Vector("Table cells must contain a single paragraph."))

  private def convertInlines(
    spans: Seq[LaikaSpan],
    routeByPath: Map[String, String]
  ): Either[Vector[String], Vector[Inline]] =
    val converted        = spans.map(convertInline(_, routeByPath))
    val (errors, values) = converted.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector)
    else Right(values.flatten.toVector)

  private def convertInline(
    span: LaikaSpan,
    routeByPath: Map[String, String]
  ): Either[Vector[String], Vector[Inline]] = span match
    case laika.ast.Text(content, _)       => Right(Vector(Inline.Text(content)))
    case laika.ast.Emphasized(content, _) =>
      convertInlines(content, routeByPath).map(value => Vector(Inline.Emphasis(value)))
    case laika.ast.Strong(content, _) =>
      convertInlines(content, routeByPath).map(value => Vector(Inline.Strong(value)))
    case laika.ast.Deleted(content, _) =>
      convertInlines(content, routeByPath).map(value => Vector(Inline.Strike(value)))
    case laika.ast.Literal(content, _)       => Right(Vector(Inline.Code(content)))
    case laika.ast.InlineCode(_, content, _) =>
      Right(Vector(Inline.Code(extractText(content))))
    case link: laika.ast.SpanLink =>
      for
        content <- convertInlines(link.content, routeByPath)
        target  <- convertLinkTarget(link.target, routeByPath)
      yield Vector(Inline.Link(content, target, link.title))
    case _: laika.ast.LineBreak           => Right(Vector(Inline.LineBreak))
    case sequence: laika.ast.SpanSequence => convertInlines(sequence.content, routeByPath)
    case image: laika.ast.Image           =>
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

  private def codeTokens(spans: Seq[LaikaSpan]): Vector[CodeToken] =
    spans.toVector.flatMap {
      case laika.ast.CodeSpan(content, categories, _) =>
        Vector(CodeToken(content, categories.toVector.map(_.name).sorted))
      case laika.ast.Text(content, _)           => Vector(CodeToken(content, Vector.empty))
      case sequence: laika.ast.CodeSpanSequence => codeTokens(sequence.content)
      case other => Vector(CodeToken(extractText(Seq(other)), Vector.empty))
    }

  private def extractText(spans: Seq[LaikaSpan]): String = spans.map {
    case container: laika.ast.TextContainer => container.content
    case container: laika.ast.SpanContainer => extractText(container.content)
    case _                                  => ""
  }.mkString

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
          Vector(CodeToken(extracted.content, Vector.empty))
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

  private def buildOutline(headings: Vector[Block.Heading]): PageOutline =
    val roots = ArrayBuffer.empty[MutableOutline]
    val stack = ArrayBuffer.empty[MutableOutline]
    headings.foreach { heading =>
      val item = MutableOutline(heading.id, inlineText(heading.content), heading.level)
      while stack.nonEmpty && stack.last.level >= item.level do
        val _ = stack.remove(stack.size - 1)
      if stack.isEmpty then roots += item else stack.last.children += item
      stack += item
    }
    PageOutline(roots.map(_.result).toVector)

  private def inlineText(inlines: Vector[Inline]): String = inlines.map {
    case Inline.Text(value)         => value
    case Inline.Emphasis(content)   => inlineText(content)
    case Inline.Strong(content)     => inlineText(content)
    case Inline.Strike(content)     => inlineText(content)
    case Inline.Code(value)         => value
    case Inline.Link(content, _, _) => inlineText(content)
    case Inline.LineBreak           => " "
  }.mkString

  private def buildNavigation(pages: Vector[Page]): Navigation =
    val items = SectionOrder.flatMap { section =>
      val sectionPages = pages.filter(_.metadata.section == section).sortBy(pageSortKey)
      val rootRoute    = if section == Section.Home then "/" else s"/${sectionName(section)}"
      sectionPages.find(_.route == rootRoute) match
        case Some(root) =>
          Vector(
            navigationItem(root, sectionPages.filterNot(_.route == rootRoute).map(navigationItem))
          )
        case None => sectionPages.map(navigationItem)
    }
    Navigation(items)

  private def navigationItem(page: Page): NavigationItem =
    navigationItem(page, Vector.empty)

  private def navigationItem(page: Page, children: Vector[NavigationItem]): NavigationItem =
    NavigationItem(page.metadata.title, page.route, page.metadata.section, children)

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

  final private case class ApiSymbolNode(id: String, options: Options = Options.empty)
      extends LaikaBlock:
    type Self = ApiSymbolNode
    def withOptions(options: Options): ApiSymbolNode = copy(options = options)

  final private case class CompatibilityNode(id: String, options: Options = Options.empty)
      extends LaikaBlock:
    type Self = CompatibilityNode
    def withOptions(options: Options): CompatibilityNode = copy(options = options)

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
      BlockDirectives.create("sourceRegion") {
        (
          block.attribute(0).as[String].widen,
          block.attribute(1).as[String].widen
        ).mapN((path, region) => SourceRegionNode(path, region))
      },
      BlockDirectives.create("apiSymbol") {
        block.attribute(0).as[String].map(ApiSymbolNode(_))
      },
      BlockDirectives.create("compatibility") {
        block.attribute(0).as[String].map(CompatibilityNode(_))
      },
      BlockDirectives.create("callout") {
        (block.attribute(0).as[String].widen, block.parsedBody).mapN(CalloutNode(_, _))
      }
    )
    val spanDirectives = Seq(
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
