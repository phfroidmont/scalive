package scalive.docs

import scalive.*
import scalive.docs.model.*
import zio.test.*

object GeneratedDocumentationSpec extends ZIOSpecDefault:
  override def spec = suite("GeneratedDocumentationSpec")(
    test("decodes and renders generated pages through typed Scalive nodes") {
      GeneratedDocumentation.load(getClass.getClassLoader) match
        case Left(error) => assertTrue(error.isEmpty)
        case Right(bundle) =>
          val rendered = bundle.pages.map(page => HtmlBuilder.build(renderPage(page))).mkString
          assertTrue(
            bundle.pages.map(_.route) == Vector("/", "/learn"),
            rendered.contains("<h1>Scalive</h1>"),
            rendered.contains("<h2 id=\"why-scalive\">Why Scalive</h2>"),
            rendered.contains("href=\"/learn#start-here\""),
            rendered.contains("data-callout=\"info\""),
            rendered.contains("GeneratedDocumentation.scala"),
            !rendered.contains("<script")
          )
    }
  )

  private def renderPage(page: Page): HtmlElement[Nothing] =
    articleTag(h1(page.metadata.title), page.content.map(renderBlock))

  private def renderBlock(block: Block): HtmlElement[Nothing] = block match
    case Block.Paragraph(content) => p(content.map(renderInline))
    case Block.Heading(level, id, content) =>
      val mods = Vector[Mod[Nothing]](idAttr := id) ++ content.map(renderInline)
      level match
        case 2 => h2(mods)
        case 3 => h3(mods)
        case 4 => h4(mods)
        case 5 => h5(mods)
        case 6 => h6(mods)
        case _ => throw new IllegalArgumentException(s"Unsupported documentation heading level: $level")
    case Block.Code(language, text, tokens, _) =>
      pre(code(dataAttr("language") := language.getOrElse("text"), renderCode(text, tokens)))
    case Block.BulletList(items) => ul(items.map(item => li(item.content.map(renderBlock))))
    case Block.OrderedList(start, items) =>
      ol(
        htmlAttr("start", scalive.codecs.IntAsStringEncoder) := start,
        items.map(item => li(item.content.map(renderBlock)))
      )
    case Block.Quote(content) => blockQuote(content.map(renderBlock))
    case Block.Table(header, rows) =>
      table(
        thead(tr(header.map(cell => th(cell.content.map(renderInline))))),
        tbody(rows.map(row => tr(row.cells.map(cell => td(cell.content.map(renderInline))))))
      )
    case Block.Rule => hr()
    case Block.Image(source, alternative, imageTitle) =>
      img(src := source, alt := alternative, imageTitle.map(title := _).toVector)
    case Block.Callout(kind, calloutTitle, content) =>
      asideTag(
        dataAttr("callout") := calloutName(kind),
        calloutTitle.map(value => Mod.Content.Tag(h3(value))).toVector,
        content.map(renderBlock)
      )
    case Block.ExampleRef(id) => div(dataAttr("example") := id, s"Example: $id")
    case Block.SourceCode(region, language, text, tokens) =>
      figure(
        dataAttr("source") := s"${region.path}:${region.startLine}-${region.endLineInclusive}",
        pre(code(dataAttr("language") := language.getOrElse("text"), renderCode(text, tokens)))
      )
    case Block.ApiSymbolRef(id) => div(dataAttr("api-symbol") := id, id)
    case Block.CompatibilityRef(id) => div(dataAttr("compatibility") := id, id)

  private def renderInline(inline: Inline): Mod[Nothing] = inline match
    case Inline.Text(value)       => Mod.Content.Text(value)
    case Inline.Emphasis(content) => Mod.Content.Tag(em(content.map(renderInline)))
    case Inline.Strong(content)   => Mod.Content.Tag(strong(content.map(renderInline)))
    case Inline.Strike(content)   => Mod.Content.Tag(del(content.map(renderInline)))
    case Inline.Code(value)       => Mod.Content.Tag(code(value))
    case Inline.Link(content, target, linkTitle) =>
      val location = target match
        case LinkTarget.Internal(route, fragment) => route + fragment.fold("")(value => s"#$value")
        case LinkTarget.External(url)             => url
      Mod.Content.Tag(a(href := location, linkTitle.map(title := _).toVector, content.map(renderInline)))
    case Inline.LineBreak => Mod.Content.Tag(br())

  private def renderCode(text: String, tokens: Vector[CodeToken]): Vector[Mod[Nothing]] =
    if tokens.isEmpty then Vector(Mod.Content.Text(text))
    else
      tokens.map { token =>
        if token.styles.isEmpty then Mod.Content.Text(token.text)
        else Mod.Content.Tag(span(cls := token.styles.mkString(" "), token.text))
      }

  private def calloutName(kind: CalloutKind): String = kind match
    case CalloutKind.Info    => "info"
    case CalloutKind.Tip     => "tip"
    case CalloutKind.Warning => "warning"
    case CalloutKind.Error   => "error"
end GeneratedDocumentationSpec
