package scalive.docs.pipeline

import java.net.URI
import scala.collection.mutable.ArrayBuffer

import laika.api.MarkupParser
import laika.ast.Block as LaikaBlock
import laika.ast.Span as LaikaSpan
import laika.config.LaikaKeys
import laika.format.Markdown

import scalive.docs.model.*

private[pipeline] object ScaladocParser:
  private val WikiLinkPrefix         = "scalive-api-ref:"
  private val RawLessThanPlaceholder = "SCALIVEDOCRAWLESSTHANX"
  private val AllowedExternalSchemes = Set("http", "https", "mailto")
  private val RawHtml                =
    "(?i)<(?!https?://|mailto:)(?:!--|\\?|![a-z\\[]|/?[a-z][a-z0-9:-]*(?=[\\s/>]|$))".r
  private val SubjectTags = Set(
    "param",
    "tparam",
    "throws",
    "groupdesc",
    "groupname",
    "groupprio"
  )
  private val Tag = "^@([A-Za-z][A-Za-z0-9]*)(?:\\s+(.*))?$".r

  private val markdown = MarkupParser
    .of(Markdown)
    .using(Markdown.GitHubFlavor, CodeHighlighter.syntaxHighlighting)
    .withConfigValue(LaikaKeys.firstHeaderAsTitle, false)
    .build

  final private case class RawTag(name: String, subject: Option[String], lines: Vector[String])
  final private case class WikiLink(target: String, label: String)

  def parse(
    comment: String,
    anchorPrefix: String,
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], ApiDocumentation] =
    val (body, tags) = splitComment(clean(comment))
    for
      parsedBody <- parseMarkdown(body.mkString("\n"), anchorPrefix, resolveSymbol)
      parsedTags <- sequence(tags.map { tag =>
                      parseMarkdown(tag.lines.mkString("\n"), anchorPrefix, resolveSymbol)
                        .map(ApiDocumentationTag(tag.name, tag.subject, _))
                    })
    yield ApiDocumentation(parsedBody, parsedTags)

  def summary(documentation: ApiDocumentation): Option[String] =
    documentation.body
      .collectFirst { case Block.Paragraph(content) => inlineText(content).trim }
      .filter(_.nonEmpty)

  def text(documentation: ApiDocumentation): String =
    val body = blockText(documentation.body)
    val tags = documentation.tags
      .map { tag =>
        Vector(tag.name, tag.subject.getOrElse(""), blockText(tag.content)).mkString(" ")
      }.mkString(" ")
    s"$body $tags".trim.replaceAll("\\s+", " ")

  private def clean(comment: String): Vector[String] =
    val lines    = comment.linesIterator.toVector
    val stripped = lines.zipWithIndex.map { case (line, index) =>
      val withoutOpen =
        if index == 0 then line.replaceFirst("^\\s*/\\*\\*", "")
        else line
      val withoutClose =
        if index == lines.size - 1 then withoutOpen.replaceFirst("\\*/\\s*$", "")
        else withoutOpen
      withoutClose.replaceFirst("^\\s*\\* ?", "").stripTrailing()
    }
    stripped.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse

  private def splitComment(lines: Vector[String]): (Vector[String], Vector[RawTag]) =
    val body       = ArrayBuffer.empty[String]
    val tags       = ArrayBuffer.empty[RawTag]
    var current    = Option.empty[RawTag]
    var blockFence = Option.empty[String]

    def finishTag(): Unit =
      current.foreach(tag => tags += tag.copy(lines = trimBlankLines(tag.lines)))
      current = None

    lines.foreach { line =>
      val trimmed           = line.trim
      val fence             = fenceMarker(trimmed)
      val startsOrEndsFence = fence.nonEmpty

      if blockFence.isEmpty then
        trimmed match
          case Tag(name, remainder) if !startsOrEndsFence =>
            finishTag()
            val value                  = Option(remainder).getOrElse("").trim
            val (subject, description) =
              if SubjectTags(name) then
                value.split("\\s+", 2).toVector match
                  case head +: tail => Some(head) -> tail.headOption.getOrElse("")
                  case _            => None       -> ""
              else None -> value
            current = Some(RawTag(name, subject, Vector(description)))
          case _ =>
            current match
              case Some(tag) => current = Some(tag.copy(lines = tag.lines :+ line))
              case None      => body += line
      else
        current match
          case Some(tag) => current = Some(tag.copy(lines = tag.lines :+ line))
          case None      => body += line

      fence.foreach { marker =>
        blockFence = blockFence match
          case Some(open) if open == marker => None
          case None                         => Some(marker)
          case value                        => value
      }
    }
    finishTag()
    trimBlankLines(body.toVector) -> tags.toVector
  end splitComment

  private def trimBlankLines(lines: Vector[String]): Vector[String] =
    lines.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse

  private def fenceMarker(trimmed: String): Option[String] =
    if trimmed.startsWith("```") then Some("```")
    else if trimmed.startsWith("~~~") then Some("~~~")
    else if trimmed == "{{{" || trimmed == "}}}" then Some("{{{")
    else None

  private def parseMarkdown(
    input: String,
    anchorPrefix: String,
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], Vector[Block]] =
    if input.trim.isEmpty then Right(Vector.empty)
    else
      val escaped = RawHtml.replaceAllIn(
        input,
        matched => matched.matched.replace("<", RawLessThanPlaceholder)
      )
      val (normalized, links) = preprocess(escaped)
      markdown.parse(normalized).left.map(error => Vector(error.toString)).flatMap { document =>
        convertBlocks(document.content.content, links, anchorPrefix, resolveSymbol)
      }

  private def preprocess(input: String): (String, Vector[WikiLink]) =
    val links      = ArrayBuffer.empty[WikiLink]
    var blockFence = Option.empty[String]
    val normalized = input.linesIterator
      .map { original =>
        val trimmed = original.trim
        val line    =
          if trimmed == "{{{" then "```scala"
          else if trimmed == "}}}" then "```"
          else original
        val marker = fenceMarker(trimmed)
        val result =
          if blockFence.isEmpty && marker.isEmpty then replaceWikiLinks(line, links) else line
        marker.foreach { value =>
          blockFence = blockFence match
            case Some(open) if open == value => None
            case None                        => Some(value)
            case current                     => current
        }
        result
      }.mkString("\n")
    normalized -> links.toVector

  private def replaceWikiLinks(line: String, links: ArrayBuffer[WikiLink]): String =
    val result = StringBuilder()
    var index  = 0
    var inCode = false
    while index < line.length do
      if line.charAt(index) == '`' then
        inCode = !inCode
        result += line.charAt(index)
        index += 1
      else if !inCode && line.startsWith("[[", index) then
        val end = line.indexOf("]]", index + 2)
        if end < 0 then
          result.append(line.substring(index))
          index = line.length
        else
          val value  = line.substring(index + 2, end).trim
          val parts  = value.split("\\s+", 2)
          val target = parts.headOption.getOrElse("")
          val label  =
            if parts.length == 2 then parts(1)
            else target.split("[.#]").lastOption.filter(_.nonEmpty).getOrElse(target)
          val reference = links.size
          links += WikiLink(target, label)
          result.append(s"[${escapeLinkLabel(label)}]($WikiLinkPrefix$reference)")
          index = end + 2
      else
        result += line.charAt(index)
        index += 1
    result.result()

  private def escapeLinkLabel(label: String): String =
    label.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")

  private def convertBlocks(
    blocks: Seq[LaikaBlock],
    links: Vector[WikiLink],
    anchorPrefix: String,
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], Vector[Block]] =
    val converted        = blocks.map(convertBlock(_, links, anchorPrefix, resolveSymbol))
    val (errors, values) = converted.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector) else Right(values.flatten.toVector)

  private def convertBlock(
    block: LaikaBlock,
    links: Vector[WikiLink],
    anchorPrefix: String,
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], Vector[Block]] = block match
    case laika.ast.Paragraph(content, _) =>
      convertInlines(content, links, resolveSymbol).map(value => Vector(Block.Paragraph(value)))
    case laika.ast.ForcedParagraph(content, _) =>
      convertInlines(content, links, resolveSymbol).map(value => Vector(Block.Paragraph(value)))
    case section: laika.ast.Section =>
      for
        heading <- convertHeading(section.header, links, anchorPrefix, resolveSymbol)
        content <- convertBlocks(section.content, links, anchorPrefix, resolveSymbol)
      yield heading +: content
    case header: laika.ast.Header =>
      convertHeading(header, links, anchorPrefix, resolveSymbol).map(Vector(_))
    case laika.ast.CodeBlock(language, content, _, _) =>
      val tokens = CodeHighlighter.fromSpans(content)
      Right(
        Vector(
          Block.Code(
            Option(language).filter(_.nonEmpty),
            tokens.map(_.text).mkString,
            tokens,
            None
          )
        )
      )
    case list: laika.ast.BulletList =>
      convertListItems(list.content.map(_.content), links, anchorPrefix, resolveSymbol)
        .map(items => Vector(Block.BulletList(items)))
    case list: laika.ast.EnumList =>
      convertListItems(list.content.map(_.content), links, anchorPrefix, resolveSymbol)
        .map(items =>
          Vector(
            Block.OrderedList(list.content.headOption.map(_.position).getOrElse(list.start), items)
          )
        )
    case quote: laika.ast.QuotedBlock if quote.attribution.isEmpty =>
      convertBlocks(quote.content, links, anchorPrefix, resolveSymbol)
        .map(content => Vector(Block.Quote(content)))
    case _: laika.ast.Rule                 => Right(Vector(Block.Rule))
    case sequence: laika.ast.BlockSequence =>
      convertBlocks(sequence.content, links, anchorPrefix, resolveSymbol)
    case _ => Left(Vector(s"Unsupported Scaladoc block: ${block.productPrefix}."))

  private def convertHeading(
    header: laika.ast.Header,
    links: Vector[WikiLink],
    anchorPrefix: String,
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], Block.Heading] =
    convertInlines(header.content, links, resolveSymbol).map { content =>
      val localId = header.options.id.getOrElse(slug(inlineText(content)))
      Block.Heading((header.level + 2).max(4).min(6), s"$anchorPrefix-$localId", content)
    }

  private def convertListItems(
    items: Seq[Seq[LaikaBlock]],
    links: Vector[WikiLink],
    anchorPrefix: String,
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], Vector[ListItem]] =
    val converted = items.map { item =>
      convertBlocks(item, links, anchorPrefix, resolveSymbol).map(ListItem(_))
    }
    val (errors, values) = converted.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector) else Right(values.toVector)

  private def convertInlines(
    spans: Seq[LaikaSpan],
    links: Vector[WikiLink],
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], Vector[Inline]] =
    val converted        = spans.map(convertInline(_, links, resolveSymbol))
    val (errors, values) = converted.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten.toVector) else Right(values.flatten.toVector)

  private def convertInline(
    span: LaikaSpan,
    links: Vector[WikiLink],
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], Vector[Inline]] = span match
    case laika.ast.Text(content, _) =>
      Right(Vector(Inline.Text(content.replace(RawLessThanPlaceholder, "<"))))
    case laika.ast.Emphasized(content, _) =>
      convertInlines(content, links, resolveSymbol).map(value => Vector(Inline.Emphasis(value)))
    case laika.ast.Strong(content, _) =>
      convertInlines(content, links, resolveSymbol).map(value => Vector(Inline.Strong(value)))
    case laika.ast.Deleted(content, _) =>
      convertInlines(content, links, resolveSymbol).map(value => Vector(Inline.Strike(value)))
    case laika.ast.Literal(content, _)       => Right(Vector(Inline.Code(content)))
    case laika.ast.InlineCode(_, content, _) => Right(Vector(Inline.Code(extractText(content))))
    case link: laika.ast.SpanLink            =>
      val renderedTarget = link.target.render()
      if renderedTarget.startsWith(WikiLinkPrefix) then
        val index = renderedTarget.stripPrefix(WikiLinkPrefix).toIntOption
        index.flatMap(links.lift) match
          case Some(reference) => resolveWikiLink(reference, resolveSymbol).map(Vector(_))
          case None            =>
            Left(Vector(s"Invalid generated Scaladoc link target: '$renderedTarget'."))
      else
        link.target match
          case laika.ast.ExternalTarget(url) =>
            allowedExternal(url).flatMap { target =>
              convertInlines(link.content, links, resolveSymbol)
                .map(content => Vector(Inline.Link(content, target, link.title)))
            }
          case _ =>
            Left(Vector(s"Unsupported Scaladoc link target: '$renderedTarget'."))
    case _: laika.ast.LineBreak           => Right(Vector(Inline.LineBreak))
    case sequence: laika.ast.SpanSequence => convertInlines(sequence.content, links, resolveSymbol)
    case _ => Left(Vector(s"Unsupported Scaladoc inline: ${span.productPrefix}."))

  private def resolveWikiLink(
    link: WikiLink,
    resolveSymbol: String => Option[LinkTarget]
  ): Either[Vector[String], Inline] =
    scheme(link.target) match
      case Left(error)    => Left(Vector(error))
      case Right(Some(_)) =>
        allowedExternal(link.target)
          .map(target => Inline.Link(Vector(Inline.Text(link.label)), target, None))
      case Right(None) =>
        Right(
          resolveSymbol(link.target)
            .map(target => Inline.Link(Vector(Inline.Text(link.label)), target, None))
            .getOrElse(Inline.Code(link.label))
        )

  private def allowedExternal(url: String): Either[Vector[String], LinkTarget] =
    scheme(url).left.map(Vector(_)).flatMap {
      case Some(value) if AllowedExternalSchemes(value) => Right(LinkTarget.External(url))
      case Some(value)                                  =>
        Left(Vector(s"External Scaladoc link scheme '$value' is not allowed: '$url'."))
      case None => Left(Vector(s"Scaladoc link must use an allowed absolute URI: '$url'."))
    }

  private def scheme(value: String): Either[String, Option[String]] =
    try Right(Option(URI.create(value).getScheme).map(_.toLowerCase))
    catch case _: IllegalArgumentException => Left(s"Invalid Scaladoc link: '$value'.")

  private def extractText(spans: Seq[LaikaSpan]): String = spans.map {
    case container: laika.ast.TextContainer => container.content
    case container: laika.ast.SpanContainer => extractText(container.content)
    case _                                  => ""
  }.mkString

  private def blockText(blocks: Vector[Block]): String = blocks
    .flatMap {
      case Block.Paragraph(content)     => Vector(inlineText(content))
      case Block.Heading(_, _, content) => Vector(inlineText(content))
      case Block.Code(_, code, _, _)    => Vector(code)
      case Block.BulletList(items)      => items.map(item => blockText(item.content))
      case Block.OrderedList(_, items)  => items.map(item => blockText(item.content))
      case Block.Quote(content)         => Vector(blockText(content))
      case Block.Table(header, rows)    =>
        (header ++ rows.flatMap(_.cells)).map(cell => inlineText(cell.content))
      case Block.Image(_, alt, title)       => alt +: title.toVector
      case Block.Callout(_, title, content) => title.toVector :+ blockText(content)
      case _                                => Vector.empty
    }.mkString(" ")

  private def inlineText(inlines: Vector[Inline]): String = inlines.map {
    case Inline.Text(value)            => value
    case Inline.Emphasis(content)      => inlineText(content)
    case Inline.Strong(content)        => inlineText(content)
    case Inline.Strike(content)        => inlineText(content)
    case Inline.Code(value)            => value
    case Inline.Link(content, _, _)    => inlineText(content)
    case Inline.ApiSymbolRef(_, label) => label
    case Inline.LineBreak              => " "
  }.mkString

  private def slug(value: String): String =
    value.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .stripPrefix("-")
      .stripSuffix("-") match
      case ""     => "section"
      case result => result

  private def sequence[A](
    values: Vector[Either[Vector[String], A]]
  ): Either[Vector[String], Vector[A]] =
    val (errors, successes) = values.partitionMap(identity)
    if errors.nonEmpty then Left(errors.flatten) else Right(successes)
end ScaladocParser
