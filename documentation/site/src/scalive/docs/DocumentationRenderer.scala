package scalive.docs

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import scalive.*
import scalive.docs.examples.ExampleRegistry
import scalive.docs.model.*
import scalive.docs.xray.{DocumentationTraceStore, XRayInspector}

final private[docs] class DocumentationRenderer(
  application: DocumentationApplication,
  traceStore: Option[DocumentationTraceStore] = None):
  private val metadata      = application.bundle.apiReference.metadata
  private val repositoryUrl = metadata.repositoryUrl.stripSuffix("/")
  private val ariaExpanded  = htmlAttr("aria-expanded", scalive.codecs.StringAsIsEncoder)
  private val role          = htmlAttr("role", scalive.codecs.StringAsIsEncoder)

  def render(page: Page): HtmlElement[Nothing] =
    page.source match
      case _: PageSource.GeneratedApi => renderGeneratedApiPage(page)
      case _                          => renderAuthoredPage(page)

  private def renderAuthoredPage(page: Page): HtmlElement[Nothing] =
    articleTag(
      cls := "docs-content docs-prose",
      h1(page.metadata.title),
      page.content.map(renderBlock(page.route)),
      pageLinks(page)
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
    val groups = apiMemberGroups(owners, members)

    articleTag(
      cls := "docs-content docs-prose docs-api-page",
      apiBreadcrumb(primary),
      div(
        cls := "docs-api-title-row",
        h1(primary.name),
        div(
          cls := "docs-api-title-kinds",
          owners.map(owner => span(cls := "docs-api-title-kind", apiKindName(owner.kind)))
        )
      ),
      p(cls := "docs-api-qualified-name", primary.qualifiedName),
      companionReference(primary).map(Mod.Content.Tag(_)).toVector,
      h2(cls := "docs-api-group-heading", "Declaration"),
      renderApiSymbol(page.route, primary, None, 2),
      groups.map { case (id, title, groupedMembers) =>
        Mod.Content.Tag(
          sectionTag(
            cls    := "docs-api-member-group",
            idAttr := id,
            h2(title),
            groupedMembers.map(member => renderApiSymbol(page.route, member, member.fragment, 3))
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

  private def apiMemberGroups(
    owners: Vector[ApiSymbol],
    members: Vector[ApiSymbol]
  ): Vector[(String, String, Vector[ApiSymbol])] =
    val groups = owners.flatMap { owner =>
      val owned = members.filter(_.ownerId.contains(owner.id))
      Option.when(owned.nonEmpty) {
        val companion =
          owner.kind == ApiSymbolKind.Object && owners.exists(_.kind != ApiSymbolKind.Object)
        val id    = if companion then "companion-members" else "members"
        val title = if companion then "Companion members" else "Members"
        (id, title, owned)
      }
    }
    val groupedIds = groups.flatMap(_._3.map(_.id)).toSet
    val remaining  = members.filterNot(member => groupedIds(member.id))
    if remaining.isEmpty then groups else groups :+ ("other-members", "Other members", remaining)

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
    case Block.ExampleRef(id)                             => renderExample(pageRoute, id)
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
    sourceRegion: Option[SourceRegion]
  ): HtmlElement[Nothing] =
    val expandable = sourceRegion.nonEmpty && (text.linesIterator.size > 24 || text.length > 1600)
    figure(
      cls := "docs-code-block",
      sourceRegion.map(region => dataAttr("source") := sourceLabel(region)).toVector,
      Option.when(expandable)(dataAttr("code-expandable") := "").toVector,
      sourceRegion.map(_ => Mod.Content.Tag(HtmlTag("figcaption")("Source"))).toVector,
      div(
        cls := "docs-code-toolbar",
        span(cls := "docs-code-language", language.getOrElse("text")),
        div(
          cls := "docs-code-controls",
          button(
            typ                   := "button",
            cls                   := "docs-code-control",
            dataAttr("code-copy") := "",
            hidden                := true,
            "Copy"
          ),
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
          span(
            cls                     := "docs-visually-hidden",
            dataAttr("code-status") := "",
            role                    := "status",
            aria.live               := "polite"
          )
        )
      ),
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
    headingLevel: Int
  ): HtmlElement[Nothing] =
    val heading = fragment.map { value =>
      Mod.Content.Tag(
        if headingLevel == 3 then h3(idAttr := value, symbol.name)
        else h2(idAttr                      := value, symbol.name)
      )
    }.toVector
    val hasDocumentation = symbol.signatures.exists(_.documentation.nonEmpty)
    val showSummary      = !hasDocumentation && !isFallbackSummary(symbol.summary)
    sectionTag(
      cls                    := "docs-api-symbol",
      dataAttr("api-symbol") := symbol.id,
      heading,
      p(cls := "docs-api-kind", apiKindName(symbol.kind)),
      Option.when(showSummary)(Mod.Content.Tag(p(symbol.summary))).toVector,
      symbol.signatures.map(renderApiSignature(pageRoute, _))
    )

  private def isFallbackSummary(summary: String): Boolean =
    summary.startsWith("Public API for the `") ||
      summary.startsWith("Public APIs in the `") ||
      (summary.startsWith("The `") && summary.endsWith("."))

  private def renderApiSignature(
    pageRoute: String,
    signature: ApiSignature
  ): HtmlElement[Nothing] =
    val source = metadata.sourceLink(signature.source)
    div(
      cls := "docs-api-signature",
      codeBlock(Some("scala"), signature.signature, signature.tokens, None),
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
    val nestedId       = ExampleRegistry.instanceId(pageRoute, id)
    val observedTopic  = ExampleRegistry.topic(pageRoute, id)
    val inspectorId    = ExampleRegistry.inspectorInstanceId(pageRoute, id)
    val inspectorTopic = ExampleRegistry.inspectorTopic(pageRoute, id)
    val source         = definition.source

    sectionTag(
      idAttr                      := s"example-$id",
      cls                         := "docs-example",
      dataAttr("example")         := id,
      dataAttr("example-child")   := nestedId,
      dataAttr("example-topic")   := observedTopic,
      dataAttr("inspector-child") := inspectorId,
      dataAttr("inspector-topic") := inspectorTopic,
      codeBlock(source.language, source.text, source.tokens, Some(source.region)),
      definition.compilationFailures.map(renderCompilationFailure),
      div(
        cls := "docs-example-rendered",
        headerTag(
          h3("Result"),
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
      traceStore.toVector.map(store =>
        XRayInspector.nested(inspectorId, observedTopic, inspectorTopic, registered, store)
      )
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
