package scalive.docs

import java.net.URI

import scalive.StaticAssets
import scalive.docs.model.*

private[docs] object DocumentationMarkdown:
  def path(route: String): String = if route == "/" then "/index.md" else s"$route.md"

final private[docs] class DocumentationMarkdown(
  application: DocumentationApplication,
  assets: StaticAssets,
  origin: PublicOrigin):
  private val metadata = application.bundle.apiReference.metadata

  def indexEntry(page: Page): String =
    s"- [${escape(oneLine(page.metadata.title))}](${origin.absolute(DocumentationMarkdown.path(page.route))}): ${escape(oneLine(page.metadata.description))}"

  def render(page: Page): String =
    val body = page.source match
      case _: PageSource.GeneratedApi => generatedApi(page)
      case _                          => blocks(page.content, page.route)
    val additions = page.route match
      case "/api" =>
        val generated =
          application.bundle.pages.filter(_.source.isInstanceOf[PageSource.GeneratedApi])
        "\n\n## Generated API pages\n\n" + generated.map(indexEntry).mkString("\n")
      case "/examples" =>
        val examples = application.bundle.examples.map { example =>
          val descriptor = example.descriptor
          s"- [${escape(descriptor.title)}](${docUrl(s"/examples/${descriptor.id}")}): ${escape(oneLine(descriptor.description))}"
        }
        val labs = LabCatalog.entries.map(lab =>
          s"- [${escape(lab.title)}](${browserUrl(lab.route)}): ${escape(oneLine(lab.description))}"
        )
        "\n\n## Example catalog\n\n" + examples.mkString("\n") +
          "\n\n## Complete applications\n\n" + labs.mkString("\n")
      case _ => ""
    s"# ${escape(page.metadata.title)}\n\n$body$additions\n"

  private def generatedApi(page: Page): String =
    val symbols    = page.content.collect { case Block.ApiSymbolRef(id) => symbol(id) }
    val owners     = symbols.filter(_.fragment.isEmpty)
    val members    = symbols.filter(_.fragment.nonEmpty)
    val ownerText  = owners.map(apiSymbol(_, page.route)).mkString("\n\n")
    val memberText = ApiMemberCategory
      .group(members).map { case (category, entries) =>
        s"${anchor(category.id)}\n## ${escape(category.title)}\n\n" +
          entries.map(apiSymbol(_, page.route)).mkString("\n\n")
      }.mkString("\n\n")
    Vector(ownerText, memberText).filter(_.nonEmpty).mkString("\n\n")

  private def apiSymbol(value: ApiSymbol, route: String): String =
    val heading = value.fragment
      .map(id => s"${anchor(id)}\n### ${escape(value.name)}\n\n")
      .getOrElse(s"### ${escape(value.qualifiedName)} (${apiKindName(value.kind)})\n\n")
    val companion =
      if value.fragment.nonEmpty then ""
      else
        val other = application.bundle.apiReference.symbols.find(candidate =>
          candidate.fragment.isEmpty && candidate.qualifiedName == value.qualifiedName &&
            candidate.id != value.id &&
            (if value.kind == ApiSymbolKind.Object then candidate.kind != ApiSymbolKind.Object
             else candidate.kind == ApiSymbolKind.Object)
        )
        other.fold("")(symbol =>
          s"[Companion: ${apiKindName(symbol.kind).toLowerCase} ${escape(symbol.name)}](${docUrl(symbol.route)})\n\n"
        )
    val signatures = value.signatures
      .map { signature =>
        val source   = metadata.sourceLink(signature.source)
        val exposure = signature.origin.exposure match
          case ApiExposure.Direct    => "Defined by"
          case ApiExposure.Exported  => "Exported from"
          case ApiExposure.Inherited => "Inherited from"
        val docs = signature.documentation
          .map { documentation =>
            val body = blocks(documentation.body, route)
            val tags = documentation.tags
              .map { tag =>
                val subject = tag.subject.fold("")(value => s" ${inlineCode(value)}")
                s"**${escape(tagTitle(tag.name))}$subject**\n\n${blocks(tag.content, route)}"
              }.mkString("\n\n")
            Vector(body, tags).filter(_.nonEmpty).mkString("\n\n")
          }.filter(_.nonEmpty).map("\n\n" + _).getOrElse("")
        s"${codeFence(Some("scala"), signature.signature)}\n\n$exposure ${inlineCode(signature.origin.qualifiedName)}. [View source](${destination(source.url)}) (${escape(source.label)})$docs"
      }.mkString("\n\n")
    val summary =
      if value.signatures.exists(_.documentation.nonEmpty) || fallback(value.summary) then ""
      else s"${escape(value.summary)}\n\n"
    heading + companion + summary + signatures
  end apiSymbol

  private def blocks(content: Vector[Block], route: String): String =
    content.map(block(_, route)).filter(_.nonEmpty).mkString("\n\n")

  private def block(value: Block, route: String): String = value match
    case Block.Paragraph(content)          => inlines(content)
    case Block.Heading(level, id, content) =>
      s"${anchor(id)}\n${"#" * level} ${inlines(content)}"
    case Block.Code(language, text, _, region)       => sourceCode(language, text, region, None)
    case Block.SourceCode(region, language, text, _) =>
      sourceCode(language, text, Some(region), None)
    case Block.BulletList(items)         => list(items, _ => "- ", route)
    case Block.OrderedList(start, items) => list(items, index => s"${start + index}. ", route)
    case Block.Quote(content)            => quote(blocks(content, route))
    case Block.Table(header, rows)       =>
      def cell(value: TableCell): String =
        inlines(value.content, tableCell = true).replace("\n", "<br>")
      val headings = "| " + header.map(cell).mkString(" | ") + " |"
      val divider  = "| " + header.map(_ => "---").mkString(" | ") + " |"
      (Vector(headings, divider) ++ rows.map(row =>
        "| " + row.cells.map(cell).mkString(" | ") + " |"
      ))
        .mkString("\n")
    case Block.Rule                              => "---"
    case Block.Image(source, alternative, title) =>
      s"![${escape(
          alternative
        )}](${imageUrl(source, route)}${title.fold("")(t => s" \"${linkTitle(t)}\"")})"
    case Block.Callout(kind, title, content) =>
      quote(
        s"**${escape(kind.toString)}${title.fold("")(t => s": ${escape(t)}")}**\n\n${blocks(content, route)}"
      )
    case Block.ExampleRef(id) =>
      val example = application
        .example(id).getOrElse(throw new IllegalArgumentException(s"Unknown example: $id"))
      val sources = example.sources.map(source =>
        sourceCode(source.language, source.text, Some(source.region), Some(source.label))
      )
      val failures = example.compilationFailures.map(failure =>
        s"**Type safety, demonstrated (${escape(failure.id)})**\n\n" +
          codeFence(Some("scala"), failure.source) + "\n\nCompiler diagnostic:\n\n" +
          codeFence(Some("text"), failure.diagnostic)
      )
      (Vector(
        s"${anchor(s"example-$id")}\n**Example: ${escape(example.descriptor.title)}**\n\n[Open the live example](${origin.absolute(route)}#example-$id)"
      ) ++
        sources ++ failures).mkString("\n\n")
    case Block.LabRef(id) =>
      val lab =
        LabCatalog.get(id).getOrElse(throw new IllegalArgumentException(s"Unknown lab: $id"))
      s"## ${escape(lab.title)}\n\n${escape(lab.description)}\n\n[${escape(lab.actionLabel)}](${browserUrl(lab.route)})"
    case Block.TraceRef(id) =>
      val trace =
        TraceCatalog.get(id).getOrElse(throw new IllegalArgumentException(s"Unknown trace: $id"))
      val labels = trace.participants.map(participant => participant.id -> participant.label).toMap
      val participants =
        trace.participants.map(p => s"- **${escape(p.label)}:** ${escape(p.description)}")
      val phases = trace.phases.map { phase =>
        s"### ${escape(phase.title)}\n\n" + phase.steps
          .map { step =>
            val (label, description, evidence) = step match
              case TraceStep.Operation(participant, label, description, evidence) =>
                (s"${labels(participant)}: $label", description, evidence)
              case TraceStep.Message(from, to, label, description, evidence) =>
                (s"${labels(from)} → ${labels(to)}: $label", description, evidence)
              case TraceStep.Boundary(label, description, evidence) =>
                (label, description, evidence)
            val details = evidence
              .map { e =>
                Vector(
                  Some(s"**${escape(e.label)}**"),
                  e.summary.map(escape),
                  Option.when(e.facts.nonEmpty)(
                    e.facts.map((key, value) => s"${escape(key)}: ${escape(value)}").mkString("; ")
                  ),
                  e.scalaValue.map(codeFence(Some("scala"), _)),
                  e.code.map(codeFence(Some("text"), _))
                ).flatten.mkString("\n\n")
              }.map(value => "\n  \n  " + value.replace("\n", "\n  ")).getOrElse("")
            s"- **${escape(label)}** — ${escape(description)}$details"
          }.mkString("\n\n")
      }
      (Vector(
        s"## ${escape(trace.title)}",
        escape(trace.description),
        "### Participants",
        participants.mkString("\n")
      ) ++ phases)
        .mkString("\n\n")
    case Block.DiagramRef(id) =>
      val diagram = DiagramCatalog
        .get(id).getOrElse(throw new IllegalArgumentException(s"Unknown diagram: $id"))
      val links = diagram.assets.map(asset =>
        s"- [${escape(asset.label)} SVG](${origin.absolute(assets.path(asset.filename))})"
      )
      (Vector(s"**${escape(diagram.caption)}**", escape(diagram.description)) ++ links)
        .mkString("\n\n")
    case Block.ApiSymbolRef(id) =>
      val value   = symbol(id)
      val summary = if fallback(value.summary) then "" else s" — ${escape(oneLine(value.summary))}"
      s"${apiKindName(value.kind)} [${inlineCode(value.qualifiedName)}](${symbolUrl(value)})$summary"
    case Block.CompatibilityRef(id) =>
      s"${anchor(s"compatibility-$id")}\n## ${escape(id)}\n\nThis compatibility entry will be expanded with its curated evidence."

  private def sourceCode(
    language: Option[String],
    text: String,
    region: Option[SourceRegion],
    label: Option[String]
  ): String =
    val heading =
      label.orElse(region.map(_ => "Source")).map(t => s"${escape(t)}\n\n").getOrElse("")
    val source = region
      .map { value =>
        val link = metadata.sourceLink(ApiSource.Repository(value))
        s"\n\n[View source](${destination(link.url)}) (${escape(link.label)})"
      }.getOrElse("")
    heading + codeFence(language, text) + source

  private def list(items: Vector[ListItem], marker: Int => String, route: String): String =
    items.zipWithIndex
      .map { (item, index) =>
        val prefix = marker(index)
        val body   = blocks(item.content, route)
        prefix + body.replace("\n", "\n" + (" " * prefix.length))
      }.mkString("\n")

  private def quote(text: String): String = text
    .split("\n", -1).map { line =>
      if line.isEmpty then ">" else s"> $line"
    }.mkString("\n")

  private def inlines(content: Vector[Inline], tableCell: Boolean = false): String =
    content.map(value => renderInline(value, tableCell)).mkString

  private def renderInline(value: Inline, tableCell: Boolean): String = value match
    case Inline.Text(text)        => escape(text)
    case Inline.Emphasis(content) => s"*${inlines(content, tableCell)}*"
    case Inline.Strong(content)   => s"**${inlines(content, tableCell)}**"
    case Inline.Strike(content)   => s"~~${inlines(content, tableCell)}~~"
    case Inline.Code(text) => inlineCode(if tableCell then text.replace("|", "\\|") else text)
    case Inline.LineBreak  => "  \n"
    case Inline.ApiSymbolRef(id, label) =>
      s"[${inlineCode(if tableCell then label.replace("|", "\\|") else label)}](${symbolUrl(symbol(id))})"
    case Inline.Link(content, target, title) =>
      val url = target match
        case LinkTarget.Internal(route, fragment) =>
          docUrl(route) + fragment.fold("")(value => s"#$value")
        case LinkTarget.External(value) => browserUrl(value)
      val tooltip =
        title.fold("")(value => s" \"${linkTitle(value, tableCell)}\"")
      s"[${inlines(content, tableCell)}]($url$tooltip)"

  private def docUrl(route: String): String = origin.absolute(DocumentationMarkdown.path(route))
  private def symbolUrl(value: ApiSymbol): String =
    docUrl(value.route) + value.fragment.fold("")(id => s"#$id")
  private def symbol(id: String): ApiSymbol =
    application
      .apiSymbol(id).getOrElse(throw new IllegalArgumentException(s"Unknown API symbol: $id"))

  private def anchor(id: String): String =
    s"<a id=\"${id.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")}\"></a>"
  private def browserUrl(url: String): String =
    val absolute =
      if url.startsWith("//") then s"${URI.create(origin.value).getScheme}:$url"
      else if url.startsWith("/") then origin.absolute(url)
      else url
    destination(absolute)
  private def imageUrl(source: String, route: String): String =
    if source.startsWith("/") then browserUrl(source)
    else if source.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") then destination(source)
    else URI.create(origin.absolute(route)).resolve(destination(source)).toString
  private def linkTitle(value: String, tableCell: Boolean = false): String =
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    if tableCell then escaped.replace("|", "\\|") else escaped
  private def destination(url: String): String =
    url
      .replace("\\", "%5C").replace("<", "%3C").replace(">", "%3E")
      .replace("|", "%7C").replace("\"", "%22").replace("\n", "%0A")
      .replace("\r", "%0D").replace("\t", "%09")
      .replace(" ", "%20").replace("(", "%28").replace(")", "%29")
  private def oneLine(text: String): String = text.trim.replaceAll("\\s+", " ")
  private def escape(text: String): String  = text
    .split("\n", -1).map { line =>
      val escaped = line.flatMap {
        case c @ ('\\' | '`' | '*' | '_' | '{' | '}' | '[' | ']' | '<' | '>' | '#' | '+' | '-' |
            '!' | '|' | '~') =>
          s"\\$c"
        case '&' => "&amp;"
        case c   => c.toString
      }
      if line.matches("[ \\t]*[0-9]+[.)][ \\t].*") then
        val punctuation = line.indexWhere(c => c == '.' || c == ')')
        escaped.take(punctuation) + "\\" + escaped.drop(punctuation)
      else escaped
    }.mkString("\n")
  private def inlineCode(text: String): String =
    val delimiter = "`" * math.max(1, longestRun(text, '`') + 1)
    val padding   =
      if text.startsWith("`") || text.endsWith("`") ||
        (text.trim.nonEmpty && text.startsWith(" ") && text.endsWith(" "))
      then " "
      else ""
    s"$delimiter$padding${text.replace("\n", " ")}$padding$delimiter"
  private def codeFence(language: Option[String], text: String): String =
    val fence = "`" * math.max(3, longestRun(text, '`') + 1)
    s"$fence${language.getOrElse("").replaceAll("[^a-zA-Z0-9_+-]", "")}\n$text${
        if text.endsWith("\n") then "" else "\n"
      }$fence"
  private def longestRun(text: String, character: Char): Int =
    text
      .foldLeft((0, 0)) { case ((maximum, current), next) =>
        val run = if next == character then current + 1 else 0
        (maximum.max(run), run)
      }._1
  private def fallback(summary: String): Boolean =
    summary.startsWith("Public API for the `") || summary.startsWith("Public APIs in the `") ||
      (summary.startsWith("The `") && summary.endsWith("."))

  private def apiKindName(kind: ApiSymbolKind): String = kind match
    case ApiSymbolKind.Package    => "Package"
    case ApiSymbolKind.Class      => "Class"
    case ApiSymbolKind.Trait      => "Trait"
    case ApiSymbolKind.Object     => "Object"
    case ApiSymbolKind.Enum       => "Enum"
    case ApiSymbolKind.OpaqueType => "Opaque type"
    case ApiSymbolKind.TypeAlias  => "Type alias"
    case ApiSymbolKind.Def        => "Method"
    case ApiSymbolKind.Extension  => "Extension method"
    case ApiSymbolKind.Val        => "Value"
    case ApiSymbolKind.LazyVal    => "Lazy value"
    case ApiSymbolKind.Var        => "Variable"
    case ApiSymbolKind.Given      => "Given"

  private def tagTitle(name: String): String = name match
    case "param"      => "Parameters"
    case "tparam"     => "Type parameters"
    case "return"     => "Returns"
    case "throws"     => "Throws"
    case "see"        => "See also"
    case "note"       => "Notes"
    case "example"    => "Examples"
    case "author"     => "Authors"
    case "version"    => "Version"
    case "since"      => "Since"
    case "todo"       => "To do"
    case "deprecated" => "Deprecated"
    case other        => other
end DocumentationMarkdown
