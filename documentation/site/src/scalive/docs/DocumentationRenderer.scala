package scalive.docs

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import scalive.*
import scalive.docs.examples.ExampleRegistry
import scalive.docs.model.*
import scalive.docs.trace.{LiveTraceViewer, TraceViewer}
import scalive.docs.xray.DocumentationTraceStore

final private[docs] class DocumentationRenderer(
  application: DocumentationApplication,
  assets: Option[StaticAssets] = None,
  traceStore: Option[DocumentationTraceStore] = None):
  private val metadata        = application.bundle.apiReference.metadata
  private val repositoryUrl   = metadata.repositoryUrl.stripSuffix("/")
  private val ariaExpanded    = htmlAttr("aria-expanded", scalive.codecs.StringAsIsEncoder)
  private val ariaLive        = htmlAttr("aria-live", scalive.codecs.StringAsIsEncoder)
  private val ariaLabelledBy  = htmlAttr("aria-labelledby", scalive.codecs.StringAsIsEncoder)
  private val ariaDescribedBy = htmlAttr("aria-describedby", scalive.codecs.StringAsIsEncoder)
  private val objectData      = htmlAttr("data", scalive.codecs.StringAsIsEncoder)
  private val tabIndex        = htmlAttr("tabindex", scalive.codecs.StringAsIsEncoder)
  private val widthAttr       = htmlAttr("width", scalive.codecs.IntAsStringEncoder)
  private val heightAttr      = htmlAttr("height", scalive.codecs.IntAsStringEncoder)
  private val role            = htmlAttr("role", scalive.codecs.StringAsIsEncoder)

  def render(page: Page): HtmlElement[Nothing] =
    page.source match
      case _: PageSource.GeneratedApi => renderGeneratedApiPage(page)
      case _                          => renderAuthoredPage(page)

  private def renderAuthoredPage(page: Page): HtmlElement[Nothing] =
    articleTag(
      cls := "docs-content docs-prose",
      h1(page.metadata.title),
      page.content.map(renderBlock(page.route)),
      learnProgressNavigation(page),
      pageLinks(page)
    )

  private def learnProgressNavigation(page: Page): Vector[Mod[Nothing]] =
    if page.metadata.section != Section.Learn || !page.source.isInstanceOf[PageSource.Authored] then
      Vector.empty
    else
      val root = application.bundle.navigation.items
        .find(_.section == Section.Learn).getOrElse(
          throw new IllegalArgumentException("Missing navigation for section Learn.")
        )
      val pages        = root +: root.children
      val currentIndex = pages.indexWhere(_.route == page.route)
      if currentIndex < 0 then
        throw new IllegalArgumentException(s"Missing Learn navigation route: ${page.route}")

      def progressLink(item: NavigationItem, direction: String, cssClass: String) =
        val location = application
          .location(item.route).getOrElse(
            throw new IllegalArgumentException(s"Unknown navigation route: ${item.route}")
          )
        link.pushNavigate(
          location,
          cls        := s"docs-learn-progress-link $cssClass",
          aria.label := s"$direction: ${item.title}",
          span(cls := "docs-learn-progress-direction", s"$direction "),
          strong(item.title)
        )

      val previous = pages
        .lift(currentIndex - 1).map { item =>
          Mod.Content.Tag(progressLink(item, "Previous", "docs-learn-progress-previous"))
        }.toVector
      val next = pages
        .lift(currentIndex + 1).map { item =>
          Mod.Content.Tag(progressLink(item, "Next", "docs-learn-progress-next"))
        }.toVector
      val separator = Option
        .when(previous.nonEmpty && next.nonEmpty)(
          Mod.Content.Tag(
            span(cls := "docs-learn-progress-separator", aria.hidden := true, " ")
          )
        ).toVector
      Vector(
        Mod.Content.Tag(
          navTag(
            cls        := "docs-learn-progress",
            aria.label := "Learn progress",
            p(cls   := "docs-learn-progress-count", s"${currentIndex + 1} of ${pages.size}"),
            div(cls := "docs-learn-progress-links", previous, separator, next)
          )
        )
      )

  private def renderGeneratedApiPage(page: Page): HtmlElement[Nothing] =
    val symbols = page.content.collect { case Block.ApiSymbolRef(id) =>
      application
        .apiSymbol(id).getOrElse(
          throw new IllegalArgumentException(s"Unknown API symbol: $id")
        )
    }
    val owners  = symbols.filter(_.fragment.isEmpty).sortBy(ownerSortKey)
    val members = symbols.filter(_.fragment.nonEmpty)
    val primary = owners.headOption.getOrElse(
      throw new IllegalArgumentException(s"Generated API page has no owner: ${page.route}")
    )
    val groups = ApiMemberCategory.group(members)

    articleTag(
      dom.hook("ApiMembers", DomRef("docs-api-members")),
      cls := "docs-content docs-prose docs-api-page",
      apiBreadcrumb(primary),
      div(
        cls := "docs-api-title-row",
        span(
          cls        := s"docs-api-title-kind docs-api-title-kind-${apiKindKey(primary.kind)}",
          aria.label := apiKindName(primary.kind),
          dataAttr("api-kind") := apiKindKey(primary.kind),
          apiKindKey(primary.kind).take(1)
        ),
        h1(primary.name)
      ),
      companionReference(primary).map(Mod.Content.Tag(_)).toVector,
      renderApiSymbol(page.route, primary, None),
      Option
        .when(groups.nonEmpty)(
          Mod.Content.Tag(
            div(
              cls                          := "docs-api-member-tools",
              hidden                       := true,
              dataAttr("api-member-tools") := "",
              label(forId := "docs-api-member-filter", "Find a member"),
              input(
                idAttr                        := "docs-api-member-filter",
                typ                           := "search",
                placeholder                   := "Filter names, signatures, or categories",
                dataAttr("api-member-filter") := ""
              ),
              p(
                cls                           := "docs-api-member-status",
                ariaLive                      := "polite",
                dataAttr("api-member-status") := ""
              )
            )
          )
        ).toVector,
      groups.map { case (category, groupedMembers) =>
        Mod.Content.Tag(
          sectionTag(
            cls                             := "docs-api-member-group",
            idAttr                          := category.id,
            dataAttr("api-member-group")    := "",
            dataAttr("api-member-category") := category.title.toLowerCase,
            h2(
              category.title,
              span(cls := "docs-api-member-count", groupedMembers.size.toString)
            ),
            groupedMembers.map(member =>
              renderApiSymbol(
                page.route,
                member,
                member.fragment,
                Some(category)
              )
            )
          )
        )
      },
      pageLinks(page)
    )
  end renderGeneratedApiPage

  private def apiBreadcrumb(symbol: ApiSymbol): HtmlElement[Nothing] =
    val packages = application.bundle.apiReference.symbols
      .filter(candidate =>
        candidate.kind == ApiSymbolKind.Package &&
          candidate.fragment.isEmpty &&
          symbol.qualifiedName.startsWith(s"${candidate.qualifiedName}.")
      ).sortBy(_.qualifiedName.length)
    navTag(
      cls        := "docs-api-breadcrumb",
      aria.label := "Breadcrumb",
      ol(
        li(a(href := "/api", "API")),
        packages.map(value => li(a(href := value.route, value.name)))
      )
    )

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

  private def companionReference(symbol: ApiSymbol): Option[HtmlElement[Nothing]] =
    val matchingOwners = application.bundle.apiReference.symbols.filter(candidate =>
      candidate.fragment.isEmpty &&
        candidate.qualifiedName == symbol.qualifiedName &&
        candidate.id != symbol.id
    )
    val companion =
      if symbol.kind == ApiSymbolKind.Object then
        matchingOwners.find(_.kind != ApiSymbolKind.Object)
      else matchingOwners.find(_.kind == ApiSymbolKind.Object)
    companion.map { value =>
      asideTag(
        cls := "docs-api-companion-reference",
        span("Companion: "),
        a(href := value.route, s"${apiKindName(value.kind).toLowerCase} ${value.name}")
      )
    }

  private[docs] def renderBlock(pageRoute: String)(block: Block): HtmlElement[Nothing] = block match
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
    case Block.BulletList(items) =>
      ul(items.map(item => li(item.content.map(renderBlock(pageRoute)))))
    case Block.OrderedList(start, items) =>
      ol(
        htmlAttr("start", scalive.codecs.IntAsStringEncoder) := start,
        items.map(item => li(item.content.map(renderBlock(pageRoute))))
      )
    case Block.Quote(content)      => blockQuote(content.map(renderBlock(pageRoute)))
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
        cls                 := s"docs-callout docs-callout-${calloutName(kind)}",
        dataAttr("callout") := calloutName(kind),
        div(
          cls := "docs-callout-heading",
          span(cls   := "docs-callout-icon", aria.hidden := true, calloutIcon(kind)),
          strong(cls := "docs-callout-label", calloutName(kind)),
          calloutTitle.map(value => Mod.Content.Tag(h3(value))).toVector
        ),
        content.map(renderBlock(pageRoute))
      )
    case Block.ExampleRef(id) => renderExample(pageRoute, id)
    case Block.LabRef(id)     => renderLab(id)
    case Block.TraceRef(id)   =>
      TraceCatalog
        .get(id).map(trace => TraceViewer.render(trace)).getOrElse(
          throw new IllegalArgumentException(s"Unknown documentation trace: $id")
        )
    case Block.DiagramRef(id)                             => renderDiagram(id)
    case Block.SourceCode(region, language, text, tokens) =>
      codeBlock(language, text, tokens, Some(region))
    case Block.ApiSymbolRef(id) =>
      application.apiSymbol(id) match
        case Some(symbol) => renderApiReference(symbol)
        case None         => throw new IllegalArgumentException(s"Unknown API symbol: $id")
    case Block.CompatibilityRef(id) =>
      sectionTag(
        idAttr                    := s"compatibility-$id",
        dataAttr("compatibility") := id,
        h2(id),
        p("This compatibility entry will be expanded with its curated evidence.")
      )

  private def renderDiagram(id: String): HtmlElement[Nothing] =
    val diagram = DiagramCatalog
      .get(id).getOrElse(throw new IllegalArgumentException(s"Unknown documentation diagram: $id"))
    val staticAssets = assets.getOrElse(
      throw new IllegalStateException("Documentation diagram rendering requires static assets.")
    )
    val captionId     = s"docs-diagram-$id-caption"
    val descriptionId = s"docs-diagram-$id-description"
    diagram.layout match
      case DiagramLayout.Single(asset) =>
        val assetUrl = staticAssets.path(asset.filename)
        figure(
          cls                 := "docs-diagram docs-diagram-single",
          dataAttr("diagram") := id,
          ariaLabelledBy      := captionId,
          ariaDescribedBy     := descriptionId,
          HtmlTag("figcaption")(
            cls := "docs-diagram-heading",
            span(idAttr := captionId, cls := "docs-diagram-caption", diagram.caption),
            a(href      := assetUrl, "Open full-size SVG")
          ),
          div(
            cls := "docs-diagram-single-canvas",
            renderDiagramObject(asset, assetUrl)
          ),
          p(idAttr := descriptionId, cls := "docs-visually-hidden", diagram.description)
        )
      case DiagramLayout.Comparison(left, right) =>
        figure(
          cls                 := "docs-diagram docs-diagram-comparison",
          dataAttr("diagram") := id,
          ariaLabelledBy      := captionId,
          ariaDescribedBy     := descriptionId,
          HtmlTag("figcaption")(
            cls := "docs-diagram-heading",
            span(idAttr := captionId, cls := "docs-diagram-caption", diagram.caption)
          ),
          div(
            cls := "docs-diagram-panels",
            renderDiagramPanel(left, staticAssets),
            renderDiagramPanel(right, staticAssets)
          ),
          p(idAttr := descriptionId, cls := "docs-visually-hidden", diagram.description)
        )
    end match
  end renderDiagram

  private def renderDiagramPanel(
    asset: DiagramAsset,
    staticAssets: StaticAssets
  ): HtmlElement[Nothing] =
    val assetUrl = staticAssets.path(asset.filename)
    div(
      cls := "docs-diagram-panel",
      div(
        cls := "docs-diagram-panel-heading",
        span(cls := "docs-diagram-panel-title", asset.label),
        a(href   := assetUrl, s"Open ${asset.label} SVG")
      ),
      div(
        cls := "docs-diagram-panel-canvas",
        renderDiagramObject(asset, assetUrl)
      )
    )

  private def renderDiagramObject(asset: DiagramAsset, assetUrl: String): HtmlElement[Nothing] =
    HtmlTag("object")(
      typ         := "image/svg+xml",
      objectData  := assetUrl,
      widthAttr   := asset.intrinsicSize.width,
      heightAttr  := asset.intrinsicSize.height,
      tabIndex    := "-1",
      aria.hidden := true
    )

  private def renderLab(id: String): HtmlElement[Nothing] =
    val lab = LabCatalog
      .get(id).getOrElse(throw new IllegalArgumentException(s"Unknown documentation lab: $id"))
    asideTag(
      cls                 := "docs-lab-cta",
      dataAttr("lab-cta") := lab.id,
      div(
        p(cls := "docs-lab-cta-label", "Standalone lab"),
        h2(lab.title),
        p(lab.description)
      ),
      a(href := lab.route, lab.actionLabel)
    )

  private def renderApiReference(symbol: ApiSymbol): HtmlElement[Nothing] =
    val location = symbol.fragment.fold(symbol.route)(fragment => s"${symbol.route}#$fragment")
    asideTag(
      cls                       := "docs-api-reference",
      dataAttr("api-reference") := symbol.id,
      p(cls  := "docs-api-kind", apiKindName(symbol.kind)),
      a(href := location, code(symbol.qualifiedName)),
      Option
        .when(!isFallbackSummary(symbol.summary))(
          Mod.Content.Tag(p(symbol.summary))
        ).toVector
    )

  private[docs] def renderInline(inline: Inline): Mod[Nothing] = inline match
    case Inline.Text(value)             => Mod.Content.Text(value)
    case Inline.Emphasis(content)       => Mod.Content.Tag(em(content.map(renderInline)))
    case Inline.Strong(content)         => Mod.Content.Tag(strong(content.map(renderInline)))
    case Inline.Strike(content)         => Mod.Content.Tag(del(content.map(renderInline)))
    case Inline.Code(value)             => Mod.Content.Tag(code(value))
    case Inline.ApiSymbolRef(id, label) => Mod.Content.Tag(renderInlineApiReference(id, label))
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

  private def renderInlineApiReference(id: String, label: String): HtmlElement[Nothing] =
    val symbol = application
      .apiSymbol(id).getOrElse(
        throw new IllegalArgumentException(s"Unknown API symbol: $id")
      )
    val location = symbol.fragment
      .fold(application.location(symbol.route)) { fragment =>
        application.location(symbol.route).map(_.withFragment(fragment))
      }.getOrElse(throw new IllegalArgumentException(s"Unknown API route: ${symbol.route}"))
    val summary =
      Option(symbol.summary).filter(value => value.nonEmpty && !isFallbackSummary(value))
    val signature = symbol.signatures.headOption
    span(
      cls                       := "docs-inline-api-reference",
      dataAttr("api-reference") := symbol.id,
      link.pushNavigate(
        location,
        dataAttr("api-reference-trigger") := "",
        code(label)
      ),
      span(
        dataAttr("api-reference-preview") := "",
        role                              := "tooltip",
        hidden                            := true,
        signature
          .map(value =>
            Mod.Content.Tag(
              span(
                cls := "docs-api-reference-signature",
                renderCode(value.signature, value.tokens)
              )
            )
          ).toVector,
        summary
          .map(value =>
            Mod.Content.Tag(
              span(
                cls := "docs-api-reference-summary",
                value
              )
            )
          ).toVector
      )
    )
  end renderInlineApiReference

  private[docs] def codeBlock(
    language: Option[String],
    text: String,
    tokens: Vector[CodeToken],
    sourceRegion: Option[SourceRegion],
    copyable: Boolean = true,
    caption: Option[String] = None
  ): HtmlElement[Nothing] =
    val expandable = sourceRegion.nonEmpty && (text.linesIterator.size > 24 || text.length > 1600)
    figure(
      cls := "docs-code-block",
      sourceRegion.map(region => dataAttr("source") := sourceLabel(region)).toVector,
      Option.when(expandable)(dataAttr("code-expandable") := "").toVector,
      caption
        .orElse(sourceRegion.map(_ => "Source"))
        .map(value => Mod.Content.Tag(HtmlTag("figcaption")(value))).toVector,
      Option
        .when(copyable || expandable)(
          Mod.Content.Tag(
            div(
              cls := "docs-code-toolbar",
              span(cls := "docs-code-language", language.getOrElse("text")),
              div(
                cls := "docs-code-controls",
                Option
                  .when(copyable)(
                    Mod.Content.Tag(
                      button(
                        typ                   := "button",
                        cls                   := "docs-code-control",
                        dataAttr("code-copy") := "",
                        hidden                := true,
                        "Copy"
                      )
                    )
                  ).toVector,
                Option
                  .when(expandable)(
                    Mod.Content.Tag(
                      button(
                        typ                     := "button",
                        cls                     := "docs-code-control",
                        dataAttr("code-expand") := "",
                        ariaExpanded            := "true",
                        hidden                  := true,
                        "Collapse"
                      )
                    )
                  ).toVector,
                Option
                  .when(copyable)(
                    Mod.Content.Tag(
                      span(
                        cls                     := "docs-visually-hidden",
                        dataAttr("code-status") := "",
                        role                    := "status",
                        aria.live               := "polite"
                      )
                    )
                  ).toVector
              )
            )
          )
        ).toVector,
      pre(
        cls := "docs-code",
        code(dataAttr("language") := language.getOrElse("text"), renderCode(text, tokens))
      ),
      sourceRegion.map { region =>
        val source = metadata.sourceLink(ApiSource.Repository(region))
        Mod.Content.Tag(
          p(
            cls := "docs-code-source-link",
            a(href := source.url, "View source"),
            s" (${source.label})"
          )
        )
      }.toVector
    )
  end codeBlock

  private def renderCode(text: String, tokens: Vector[CodeToken]): Vector[Mod[Nothing]] =
    if tokens.isEmpty then Vector(Mod.Content.Text(text))
    else
      tokens.map { token =>
        if token.styles.isEmpty then Mod.Content.Text(token.text)
        else Mod.Content.Tag(span(cls := token.styles.mkString(" "), token.text))
      }

  private def renderApiSymbol(
    pageRoute: String,
    symbol: ApiSymbol,
    fragment: Option[String],
    category: Option[ApiMemberCategory] = None
  ): HtmlElement[Nothing] =
    val hasDocumentation = symbol.signatures.exists(_.documentation.nonEmpty)
    val showSummary      = !hasDocumentation && !isFallbackSummary(symbol.summary)
    sectionTag(
      cls                    := "docs-api-symbol",
      dataAttr("api-symbol") := symbol.id,
      fragment.map(idAttr := _).toVector,
      category.map(value => dataAttr("api-member") := apiMemberSearchText(symbol, value)).toVector,
      fragment.map(_ => Mod.Content.Tag(h3(cls := "docs-visually-hidden", symbol.name))).toVector,
      symbol.signatures.map(renderApiSignature(pageRoute, _, member = category.nonEmpty)),
      Option
        .when(showSummary)(Mod.Content.Tag(p(cls := "docs-api-summary", symbol.summary))).toVector
    )

  private def apiMemberSearchText(symbol: ApiSymbol, category: ApiMemberCategory): String =
    (Vector(symbol.name, symbol.qualifiedName, apiKindName(symbol.kind), category.title) ++
      symbol.signatures.flatMap(signature =>
        Vector(signature.signature, signature.origin.qualifiedName)
      ))
      .mkString(" ").toLowerCase

  private def isFallbackSummary(summary: String): Boolean =
    summary.startsWith("Public API for the `") ||
      summary.startsWith("Public APIs in the `") ||
      (summary.startsWith("The `") && summary.endsWith("."))

  private def renderApiSignature(
    pageRoute: String,
    signature: ApiSignature,
    member: Boolean
  ): HtmlElement[Nothing] =
    val source = metadata.sourceLink(signature.source)
    div(
      cls := "docs-api-signature",
      if member then
        pre(
          cls := "docs-code docs-api-member-signature",
          code(dataAttr("language") := "scala", renderCode(signature.signature, signature.tokens))
        )
      else codeBlock(Some("scala"), signature.signature, signature.tokens, None, copyable = false),
      signature.documentation
        .map(documentation => Mod.Content.Tag(renderApiDocumentation(pageRoute, documentation)))
        .toVector,
      p(
        cls := "docs-api-source",
        exposureLabel(signature.origin),
        " ",
        a(href := source.url, "View source"),
        s" (${source.label})"
      )
    )

  private def renderApiDocumentation(
    pageRoute: String,
    documentation: ApiDocumentation
  ): HtmlElement[Nothing] =
    div(
      cls := "docs-api-documentation",
      documentation.body.map(renderBlock(pageRoute)),
      groupTags(documentation.tags).map { case (name, tags) =>
        sectionTag(
          cls := "docs-api-tag-section",
          h3(tagTitle(name)),
          HtmlTag("dl")(
            tags.flatMap { tag =>
              Vector(
                Mod.Content.Tag(
                  HtmlTag("dt")(
                    tag.subject
                      .map(subject => Mod.Content.Tag(code(subject)))
                      .getOrElse(Mod.Content.Text(tagTitle(name)))
                  )
                ),
                Mod.Content.Tag(HtmlTag("dd")(tag.content.map(renderBlock(pageRoute))))
              )
            }
          )
        )
      }
    )

  private def groupTags(
    tags: Vector[ApiDocumentationTag]
  ): Vector[(String, Vector[ApiDocumentationTag])] =
    tags.foldLeft(Vector.empty[(String, Vector[ApiDocumentationTag])]) { (groups, tag) =>
      groups.lastOption match
        case Some((name, values)) if name == tag.name => groups.init :+ (name -> (values :+ tag))
        case _                                        => groups :+ (tag.name  -> Vector(tag))
    }

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

  private def renderExample(pageRoute: String, id: String): HtmlElement[Nothing] =
    val definition = application.example(id).getOrElse {
      throw new IllegalArgumentException(s"Unknown generated example: $id")
    }
    val registered = ExampleRegistry.get(id).getOrElse {
      throw new IllegalArgumentException(s"Unknown runtime example: $id")
    }
    val nestedId         = ExampleRegistry.instanceId(pageRoute, id)
    val observedTopic    = ExampleRegistry.topic(pageRoute, id)
    val traceViewerId    = ExampleRegistry.traceViewerInstanceId(pageRoute, id)
    val traceViewerTopic = ExampleRegistry.traceViewerTopic(pageRoute, id)

    sectionTag(
      idAttr                         := s"example-$id",
      cls                            := "docs-example",
      dataAttr("example")            := id,
      dataAttr("example-child")      := nestedId,
      dataAttr("example-topic")      := observedTopic,
      dataAttr("trace-viewer-child") := traceViewerId,
      dataAttr("trace-viewer-topic") := traceViewerTopic,
      definition.sources.map(source =>
        codeBlock(
          source.language,
          source.text,
          source.tokens,
          Some(source.region),
          caption = Some(source.label)
        )
      ),
      definition.compilationFailures.map(renderCompilationFailure),
      div(
        cls := "docs-example-rendered",
        headerTag(
          h3("Live result"),
          span(
            cls         := "docs-example-connection",
            aria.live   := "polite",
            aria.atomic := true,
            span(cls := "docs-example-connected", "Connected"),
            span(cls := "docs-example-reconnecting", "Reconnecting"),
            span(cls := "docs-example-offline", "Read-only")
          )
        ),
        registered.render(nestedId)
      ),
      p(
        dataAttr("example-disconnected") := "",
        "Disconnected. Controls resume after reconnection."
      ),
      traceStore.toVector.map { store =>
        LiveTraceViewer.nested(
          traceViewerId,
          observedTopic,
          traceViewerTopic,
          registered,
          store
        )
      }
    )
  end renderExample

  private def renderCompilationFailure(failure: CompilationFailure): HtmlElement[Nothing] =
    figure(
      cls                             := "docs-compilation-failure",
      dataAttr("compilation-failure") := failure.id,
      HtmlTag("figcaption")(
        strong("Type safety, demonstrated"),
        span("This invalid model transition is rejected at compile time.")
      ),
      codeBlock(Some("scala"), failure.source, failure.sourceTokens, None),
      pre(cls := "docs-compiler-diagnostic", code(failure.diagnostic))
    )

  private[docs] def pageLinks(page: Page): HtmlElement[Nothing] =
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

  private def apiKindKey(kind: ApiSymbolKind): String = kind match
    case ApiSymbolKind.Package    => "package"
    case ApiSymbolKind.Class      => "class"
    case ApiSymbolKind.Trait      => "trait"
    case ApiSymbolKind.Object     => "object"
    case ApiSymbolKind.Enum       => "enum"
    case ApiSymbolKind.OpaqueType => "opaque-type"
    case ApiSymbolKind.TypeAlias  => "type-alias"
    case ApiSymbolKind.Def        => "method"
    case ApiSymbolKind.Extension  => "extension"
    case ApiSymbolKind.Val        => "value"
    case ApiSymbolKind.LazyVal    => "lazy-value"
    case ApiSymbolKind.Var        => "variable"
    case ApiSymbolKind.Given      => "given"

  private def calloutName(kind: CalloutKind): String = kind match
    case CalloutKind.Info    => "info"
    case CalloutKind.Tip     => "tip"
    case CalloutKind.Warning => "warning"
    case CalloutKind.Error   => "error"

  private def calloutIcon(kind: CalloutKind): String = kind match
    case CalloutKind.Info    => "i"
    case CalloutKind.Tip     => "+"
    case CalloutKind.Warning => "!"
    case CalloutKind.Error   => "x"
end DocumentationRenderer
