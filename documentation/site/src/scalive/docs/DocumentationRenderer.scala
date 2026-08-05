package scalive.docs

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import scalive.*
import scalive.docs.model.*

final private[docs] class DocumentationRenderer(application: DocumentationApplication):
  private val metadata      = application.bundle.apiReference.metadata
  private val repositoryUrl = metadata.repositoryUrl.stripSuffix("/")

  def render(page: Page): HtmlElement[Nothing] =
    articleTag(
      cls := "docs-content docs-prose",
      h1(page.metadata.title),
      page.content.map(renderBlock),
      pageLinks(page)
    )

  private def renderBlock(block: Block): HtmlElement[Nothing] = block match
    case Block.Paragraph(content)          => p(content.map(renderInline))
    case Block.Heading(level, id, content) =>
      val mods = Vector[Mod[Nothing]](idAttr := id) ++ content.map(renderInline)
      level match
        case 2 => h2(mods)
        case 3 => h3(mods)
        case 4 => h4(mods)
        case 5 => h5(mods)
        case 6 => h6(mods)
        case _ =>
          throw new IllegalArgumentException(s"Unsupported documentation heading level: $level")
    case Block.Code(language, text, tokens, sourceRegion) =>
      codeBlock(language, text, tokens, sourceRegion)
    case Block.BulletList(items)         => ul(items.map(item => li(item.content.map(renderBlock))))
    case Block.OrderedList(start, items) =>
      ol(
        htmlAttr("start", scalive.codecs.IntAsStringEncoder) := start,
        items.map(item => li(item.content.map(renderBlock)))
      )
    case Block.Quote(content)      => blockQuote(content.map(renderBlock))
    case Block.Table(header, rows) =>
      table(
        cls := "docs-table",
        thead(tr(header.map(cell => th(cell.content.map(renderInline))))),
        tbody(rows.map(row => tr(row.cells.map(cell => td(cell.content.map(renderInline))))))
      )
    case Block.Rule                                   => hr()
    case Block.Image(source, alternative, imageTitle) =>
      img(src := source, alt := alternative, imageTitle.map(title := _).toVector)
    case Block.Callout(kind, calloutTitle, content) =>
      asideTag(
        cls                 := "docs-callout",
        dataAttr("callout") := calloutName(kind),
        calloutTitle.map(value => Mod.Content.Tag(h3(value))).toVector,
        content.map(renderBlock)
      )
    case Block.ExampleRef(id)                             => renderExamplePlaceholder(id)
    case Block.SourceCode(region, language, text, tokens) =>
      codeBlock(language, text, tokens, Some(region))
    case Block.ApiSymbolRef(id) =>
      application.apiSymbol(id) match
        case Some(symbol) => renderApiSymbol(symbol)
        case None         => throw new IllegalArgumentException(s"Unknown API symbol: $id")
    case Block.CompatibilityRef(id) =>
      sectionTag(
        idAttr                    := s"compatibility-$id",
        dataAttr("compatibility") := id,
        h2(id),
        p("This compatibility entry will be expanded with its curated evidence.")
      )

  private def renderInline(inline: Inline): Mod[Nothing] = inline match
    case Inline.Text(value)       => Mod.Content.Text(value)
    case Inline.Emphasis(content) => Mod.Content.Tag(em(content.map(renderInline)))
    case Inline.Strong(content)   => Mod.Content.Tag(strong(content.map(renderInline)))
    case Inline.Strike(content)   => Mod.Content.Tag(del(content.map(renderInline)))
    case Inline.Code(value)       => Mod.Content.Tag(code(value))
    case Inline.Link(content, target, linkTitle) =>
      val titleMods = linkTitle.map(value => title := value).toVector
      target match
        case LinkTarget.Internal(route, fragment) =>
          val location = fragment
            .fold(application.location(route)) { value =>
              application.location(route).map(_.withFragment(value))
            }.getOrElse(throw new IllegalArgumentException(s"Unknown documentation route: $route"))
          Mod.Content.Tag(link.pushNavigate(location, titleMods ++ content.map(renderInline)*))
        case LinkTarget.External(url) =>
          Mod.Content.Tag(a(href := url, titleMods, content.map(renderInline)))
    case Inline.LineBreak => Mod.Content.Tag(br())

  private def codeBlock(
    language: Option[String],
    text: String,
    tokens: Vector[CodeToken],
    sourceRegion: Option[SourceRegion]
  ): HtmlElement[Nothing] =
    figure(
      cls := "docs-code-block",
      sourceRegion.map(region => dataAttr("source") := sourceLabel(region)).toVector,
      pre(
        cls := "docs-code",
        code(dataAttr("language") := language.getOrElse("text"), renderCode(text, tokens))
      ),
      sourceRegion.map { region =>
        val source = metadata.sourceLink(ApiSource.Repository(region))
        Mod.Content.Tag(p(a(href := source.url, "View source"), s" (${source.label})"))
      }.toVector
    )

  private def renderCode(text: String, tokens: Vector[CodeToken]): Vector[Mod[Nothing]] =
    if tokens.isEmpty then Vector(Mod.Content.Text(text))
    else
      tokens.map { token =>
        if token.styles.isEmpty then Mod.Content.Text(token.text)
        else Mod.Content.Tag(span(cls := token.styles.mkString(" "), token.text))
      }

  private def renderApiSymbol(symbol: ApiSymbol): HtmlElement[Nothing] =
    val heading = symbol.fragment
      .map(fragment => Mod.Content.Tag(h2(idAttr := fragment, symbol.name)))
      .toVector
    sectionTag(
      cls                    := "docs-api-symbol",
      dataAttr("api-symbol") := symbol.id,
      heading,
      p(cls := "docs-api-kind", apiKindName(symbol.kind)),
      p(symbol.summary),
      symbol.signatures.map(renderApiSignature)
    )

  private def renderApiSignature(signature: ApiSignature): HtmlElement[Nothing] =
    val source = metadata.sourceLink(signature.source)
    div(
      cls := "docs-api-signature",
      pre(cls := "docs-code", code(signature.signature)),
      p(
        exposureLabel(signature.origin),
        " ",
        a(href := source.url, "View source"),
        s" (${source.label})"
      )
    )

  private def renderExamplePlaceholder(id: String): HtmlElement[Nothing] =
    sectionTag(
      idAttr              := s"example-$id",
      cls                 := "docs-example",
      dataAttr("example") := id,
      div(
        cls := "docs-example-rendered",
        h2(s"Example: $id"),
        p("This example remains readable while live interaction is unavailable.")
      ),
      HtmlTag("fieldset")(
        disabled                           := true,
        cls                                := "docs-example-controls",
        dataAttr("example-controls")       := "",
        dataAttr("disabled-by-connection") := ""
      ),
      p(
        dataAttr("example-disconnected") := "",
        "Interaction is unavailable until the live connection returns."
      )
    )

  private def pageLinks(page: Page): HtmlElement[Nothing] =
    val issueUrl = issueLink(page)
    footerTag(
      cls := "docs-page-links",
      page.source match
        case PageSource.Authored(location) =>
          val editUrl = s"$repositoryUrl/edit/master/${location.path}#L${location.line}"
          Vector[Mod[Nothing]](
            Mod.Content.Tag(a(href := editUrl, "Edit this page")),
            Mod.Content.Text(" · "),
            Mod.Content.Tag(a(href := issueUrl, "Report a documentation issue"))
          )
        case PageSource.GeneratedApi(_) =>
          Vector[Mod[Nothing]](
            Mod.Content.Tag(a(href := issueUrl, "Report a documentation issue"))
          )
    )

  private def issueLink(page: Page): String =
    val source = page.source match
      case PageSource.Authored(location) => location.path
      case PageSource.GeneratedApi(id)   => s"generated API symbol $id"
    val title = encode(s"Documentation: ${page.metadata.title}")
    val body  = encode(
      s"Page: ${page.route}\nSource: $source\nDocumented revision: ${metadata.revision}\n\nDescribe the issue:"
    )
    s"$repositoryUrl/issues/new?title=$title&body=$body"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private def sourceLabel(region: SourceRegion): String =
    s"${region.path}:${region.startLine}-${region.endLineInclusive}"

  private def exposureLabel(origin: ApiOrigin): String = origin.exposure match
    case ApiExposure.Direct    => s"Defined by ${origin.qualifiedName}."
    case ApiExposure.Exported  => s"Exported from ${origin.qualifiedName}."
    case ApiExposure.Inherited => s"Inherited from ${origin.qualifiedName}."

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

  private def calloutName(kind: CalloutKind): String = kind match
    case CalloutKind.Info    => "info"
    case CalloutKind.Tip     => "tip"
    case CalloutKind.Warning => "warning"
    case CalloutKind.Error   => "error"
end DocumentationRenderer
