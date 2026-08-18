package scalive.docs

import scalive.*
import scalive.codecs.StringAsIsEncoder
import scalive.docs.model.*

final private[docs] class DocumentationRootLayout(
  application: DocumentationApplication,
  assets: StaticAssets,
  origin: PublicOrigin)
    extends LiveRootLayout[Any, Any]:

  def key(ctx: LiveRootLayoutContext[Any, Any]): String = "documentation-root"

  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveRootLayoutContext[Any, Any]
  ): HtmlElement[Msg] =
    val route    = ctx.currentUrl.path.encode
    val metadata = application
      .metadata(route).getOrElse(
        throw new IllegalArgumentException(s"Missing documentation metadata for route '$route'.")
      )
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset  := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr    := "width=device-width, initial-scale=1"),
        metaTag(nameAttr := "description", contentAttr := metadata.description),
        Option
          .when(!metadata.indexable)(
            Mod.Content.Tag(
              metaTag(
                idAttr      := "docs-robots",
                nameAttr    := "robots",
                contentAttr := "noindex,follow"
              )
            )
          ).toVector,
        linkTag(rel := "canonical", href := origin.absolute(metadata.canonicalPath)),
        scriptTag(
          typ := "text/javascript",
          "try{var t=localStorage.getItem(`scalive.docs.theme`);if(t===`light`||t===`dark`){document.documentElement.dataset.theme=t}else{document.documentElement.removeAttribute(`data-theme`)}}catch(e){document.documentElement.removeAttribute(`data-theme`)}"
        ),
        linkTag(rel := "icon", typ := "image/svg+xml", href := assets.path("favicon.svg")),
        assets.trackedStylesheet("fonts.css"),
        assets.trackedStylesheet("app.css"),
        assets.trackedScript("app.js", defer := true, typ := "text/javascript"),
        liveTitle(pageTitle, default = "Scalive")
      ),
      bodyTag(content)
    )
  end render
end DocumentationRootLayout

final private[docs] class DocumentationLayout(
  application: DocumentationApplication,
  assets: StaticAssets,
  origin: PublicOrigin)
    extends LiveLayout[Any, Any]:

  private val ariaCurrent      = htmlAttr("aria-current", StringAsIsEncoder)
  private val ariaLive         = htmlAttr("aria-live", StringAsIsEncoder)
  private val ariaControls     = htmlAttr("aria-controls", StringAsIsEncoder)
  private val ariaExpanded     = htmlAttr("aria-expanded", StringAsIsEncoder)
  private val ariaAutocomplete = htmlAttr("aria-autocomplete", StringAsIsEncoder)
  private val role             = htmlAttr("role", StringAsIsEncoder)
  private val disclosureOpen   = htmlAttr("open", scalive.codecs.BooleanAsAttrPresenceEncoder)
  private val apiOwnerKinds    = application.bundle.apiReference.symbols
    .filter(_.fragment.isEmpty)
    .groupBy(_.route)
    .view.mapValues(_.map(_.kind).distinct.sortBy(apiKindRank)).toMap

  def view[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    val currentRoute = ctx.currentUrl.map(_.path.encode)
    val page         = currentRoute.map(application.page)
    val documentPage = page.map(_.filterNot(_.metadata.section == Section.Home))
    val metadata     = currentRoute.map(route =>
      application
        .metadata(route).getOrElse(
          throw new IllegalArgumentException(
            s"Missing documentation metadata for route '$route'."
          )
        )
    )
    val shellClass = documentPage.map { current =>
      val hasSectionNavigation = current.exists(sectionNavigationVisible)
      if current.exists(_.metadata.section == Section.Api) then "docs-shell docs-shell-api"
      else if current.nonEmpty && !hasSectionNavigation then "docs-shell docs-shell-no-section"
      else if current.nonEmpty then "docs-shell"
      else "docs-shell docs-shell-wide"
    }
    val apiSectionRoutes = currentRoute.map(route =>
      application
        .page(route).filterNot(_.metadata.section == Section.Home)
        .filter(_.metadata.section == Section.Api).toList.map(_ => route)
    )
    val editorialSectionRoutes = currentRoute.map(route =>
      application
        .page(route).filterNot(_.metadata.section == Section.Home)
        .filter(page => page.metadata.section != Section.Api && sectionNavigationVisible(page))
        .toList.map(_ => route)
    )
    val outlineRoutes = currentRoute.map(route =>
      application
        .page(route).filterNot(_.metadata.section == Section.Home)
        .filter(_.outline.items.nonEmpty).toList.map(_ => route)
    )
    div(
      dom.hook("PageMetadata", DomRef("docs-page-metadata")),
      dataAttr("page-description") := metadata.map(_.description),
      dataAttr("page-canonical")   := metadata.map(value => origin.absolute(value.canonicalPath)),
      dataAttr("page-indexable")   := metadata.map(_.indexable.toString),
      a(cls := "docs-skip-link", href := "#docs-main", "Skip to content"),
      header(page, currentRoute),
      div(
        cls := shellClass,
        apiSectionRoutes.splitBy(identity) { (route, _) =>
          sectionNavigation(
            application
              .page(route).getOrElse(
                throw new IllegalArgumentException(
                  s"Missing documentation page for route '$route'."
                )
              ),
            route
          )
        },
        mainTag(idAttr := "docs-main", cls := "docs-main", content),
        editorialSectionRoutes.splitBy(identity) { (route, _) =>
          sectionNavigation(
            application
              .page(route).getOrElse(
                throw new IllegalArgumentException(
                  s"Missing documentation page for route '$route'."
                )
              ),
            route
          )
        },
        outlineRoutes.splitBy(identity) { (route, _) =>
          outline(
            application
              .page(route).getOrElse(
                throw new IllegalArgumentException(
                  s"Missing documentation page for route '$route'."
                )
              )
          )
        }
      ),
      footerTag(
        cls := "docs-footer",
        "Scalive documentation for revision ",
        code(application.bundle.apiReference.metadata.revision.take(12)),
        "."
      )
    )
  end view

  private def header[Msg](
    page: Signal[Option[Page]],
    currentRoute: Signal[String]
  ): HtmlElement[Msg] =
    headerTag(
      cls := "docs-header",
      div(
        cls := "docs-header-inner",
        brandLink(currentRoute),
        detailsTag(
          dom.hook("NavigationDisclosure", DomRef("docs-navigation-disclosure")),
          cls            := "docs-nav-disclosure",
          disclosureOpen := true,
          summaryTag(
            cls := "docs-nav-summary",
            span("Menu"),
            span(cls := "docs-nav-summary-icon", aria.hidden := true, "+")
          ),
          div(
            cls := "docs-nav-panel",
            navTag(
              cls        := "docs-primary-nav",
              aria.label := "Primary navigation",
              ul(
                application.bundle.navigation.items
                  .filterNot(_.section == Section.Home)
                  .map { item =>
                    val className = page.map(current =>
                      if current.exists(_.metadata.section == item.section) then
                        "docs-current-section"
                      else ""
                    )
                    li(
                      navigationLink(
                        application
                          .location(item.route).getOrElse(
                            throw new IllegalArgumentException(
                              s"Unknown navigation route: ${item.route}"
                            )
                          ),
                        currentRoute.map(_ == item.route),
                        className,
                        item.title
                      )
                    )
                  }
              )
            ),
            div(
              cls := "docs-header-actions",
              searchForm,
              connectionIndicator,
              themeSelector
            )
          )
        )
      )
    )

  private def brandLink[Msg](currentRoute: Signal[String]): HtmlElement[Msg] =
    link.pushNavigate(
      application
        .location("/").getOrElse(throw new IllegalStateException("Missing homepage route.")),
      cls        := "docs-brand",
      aria.label := "Scalive home",
      ariaCurrent.optional(currentRoute.map(route => Option.when(route == "/")("page"))),
      DocumentationBrand.lockup
    )

  private def searchForm[Msg]: HtmlElement[Msg] =
    div(
      dom.hook("DocumentationSearch", DomRef("docs-global-search")),
      phx.update               := PhxUpdate.Ignore,
      cls                      := "docs-global-search",
      dataAttr("search-index") := assets.path("search-index.json"),
      form(
        action := DocumentationApplication.SearchRoute,
        method := "get",
        role   := "search",
        label(
          cls                                := "docs-visually-hidden",
          htmlAttr("for", StringAsIsEncoder) := "docs-global-search-input",
          "Search documentation"
        ),
        div(
          cls := "docs-global-search-control",
          input(
            idAttr                                      := "docs-global-search-input",
            typ                                         := "search",
            nameAttr                                    := DocumentationApplication.SearchParameter,
            placeholder                                 := "Search docs",
            htmlAttr("autocomplete", StringAsIsEncoder) := "off",
            role                                        := "combobox",
            ariaControls                                := "docs-global-search-results",
            ariaExpanded                                := "false",
            ariaAutocomplete                            := "list"
          ),
          kbd(aria.hidden := true, "Ctrl K")
        ),
        button(typ := "submit", cls := "docs-visually-hidden", "Search")
      ),
      div(
        idAttr := "docs-global-search-results",
        cls    := "docs-global-search-results",
        role   := "listbox",
        hidden := true
      ),
      p(
        idAttr   := "docs-global-search-status",
        cls      := "docs-visually-hidden",
        role     := "status",
        ariaLive := "polite"
      )
    )

  private def sectionNavigation[Msg](page: Page, currentRoute: String): HtmlElement[Msg] =
    val section = application.bundle.navigation.items.find(_.section == page.metadata.section)
    if page.metadata.section != Section.Api then
      return editorialSectionNavigation(page, currentRoute, section)
    val navigation = navTag(
      aria.label := s"${section.fold(page.metadata.title)(_.title)} section navigation",
      p(cls := "docs-nav-title", "Packages and types"),
      Option
        .when(page.metadata.section == Section.Api)(
          Mod.Content.Tag(
            input(
              cls                        := "docs-api-nav-filter",
              typ                        := "search",
              placeholder                := "Filter packages and types",
              aria.label                 := "Filter API packages and types",
              dataAttr("api-nav-filter") := ""
            )
          )
        ).toVector,
      ul(section.toVector.flatMap(_.children).map(item => navigationTree(item, currentRoute, 0)))
    )
    asideTag(
      dom.hook("ApiNavigation", DomRef("docs-api-navigation")),
      cls := "docs-section-nav docs-api-navigation",
      navigation
    )

  private def sectionNavigationVisible(page: Page): Boolean =
    page.metadata.section match
      case Section.Learn | Section.Guides => true
      case Section.Project                =>
        application.bundle.navigation.items
          .find(_.section == Section.Project).exists(_.children.nonEmpty)
      case Section.Api => true
      case _           => false

  private def editorialSectionNavigation[Msg](
    page: Page,
    currentRoute: String,
    section: Option[NavigationItem]
  ): HtmlElement[Msg] =
    val root = section.getOrElse(
      throw new IllegalArgumentException(
        s"Missing navigation for section ${page.metadata.section}."
      )
    )
    val items         = root +: root.children
    val indexedGroups = items.zipWithIndex.foldLeft(
      Vector.empty[(Option[String], Vector[(NavigationItem, Int)])]
    ) { case (result, (item, index)) =>
      val entry      = item -> (index + 1)
      val groupIndex = result.indexWhere(_._1 == item.group)
      if groupIndex < 0 then result :+ (item.group -> Vector(entry))
      else
        val (group, entries) = result(groupIndex)
        result.updated(groupIndex, group -> (entries :+ entry))
    }
    val lists = indexedGroups.flatMap { case (group, entries) =>
      val links = entries.map { case (item, position) =>
        li(editorialNavigationLink[Msg](item, currentRoute, position))
      }
      val linkMods = links.map(Mod.Content.Tag(_))
      group.map(value => p(cls := "docs-nav-group", value)).toVector :+
        (if page.metadata.section == Section.Learn then ol(linkMods) else ul(linkMods))
    }
    asideTag(
      cls := "docs-section-nav docs-section-index",
      navTag(
        aria.label := s"${root.title} section navigation",
        p(cls := "docs-nav-title", root.title),
        lists.map(Mod.Content.Tag(_))
      )
    )
  end editorialSectionNavigation

  private def editorialNavigationLink[Msg](
    item: NavigationItem,
    currentRoute: String,
    position: Int
  ): HtmlElement[Msg] =
    val location = application
      .location(item.route).getOrElse(
        throw new IllegalArgumentException(s"Unknown navigation route: ${item.route}")
      )
    val mods = Vector.newBuilder[Mod[Msg]]
    if item.route == currentRoute then mods += (ariaCurrent := "page")
    if item.section == Section.Learn then
      mods += Mod.Content.Tag(
        span(cls := "docs-section-index-number", aria.hidden := true, f"$position%02d")
      )
    mods += Mod.Content.Tag(
      span(
        cls := "docs-section-index-label",
        editorialNavigationLabel(item, position)
      )
    )
    link.pushNavigate(location, mods.result()*)

  private def editorialNavigationLabel(item: NavigationItem, position: Int): String =
    if position == 1 then
      item.section match
        case Section.Learn   => "Start here"
        case Section.Guides  => "Overview"
        case Section.Project => "Overview"
        case _               => item.title
    else item.title

  private def navigationTree[Msg](
    item: NavigationItem,
    currentRoute: String,
    depth: Int
  ): HtmlElement[Msg] =
    val active     = item.route == currentRoute
    val entryClass =
      if active then "docs-api-nav-entry docs-api-nav-entry-active" else "docs-api-nav-entry"
    val row = apiNavigationItemLink(item, currentRoute)
    li(
      dataAttr("api-nav-item") := item.title.toLowerCase,
      if item.children.nonEmpty then
        detailsTag(
          disclosureOpen := navigationContains(item, currentRoute),
          summaryTag(
            cls       := entryClass,
            styleAttr := s"--docs-api-nav-depth: $depth",
            span(cls := "docs-tree-marker", aria.hidden := true),
            row
          ),
          ul(item.children.map(child => navigationTree(child, currentRoute, depth + 1)))
        )
      else
        span(
          cls       := s"docs-nav-leaf $entryClass",
          styleAttr := s"--docs-api-nav-depth: $depth",
          span(cls := "docs-tree-marker-spacer", aria.hidden := true),
          row
        )
    )
  end navigationTree

  private def navigationContains(item: NavigationItem, route: String): Boolean =
    item.route == route || item.children.exists(navigationContains(_, route))

  private def apiKindBadge[Msg](kind: ApiSymbolKind): HtmlElement[Msg] =
    span(
      cls                  := s"docs-api-nav-kind docs-api-nav-kind-${apiKindKey(kind)}",
      aria.label           := apiKindName(kind),
      dataAttr("api-kind") := apiKindKey(kind),
      apiKindKey(kind).take(1)
    )

  private def apiKindRank(kind: ApiSymbolKind): Int = kind match
    case ApiSymbolKind.Trait      => 0
    case ApiSymbolKind.Class      => 1
    case ApiSymbolKind.Enum       => 2
    case ApiSymbolKind.OpaqueType => 3
    case ApiSymbolKind.TypeAlias  => 4
    case ApiSymbolKind.Object     => 5
    case ApiSymbolKind.Package    => 6
    case _                        => 7

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

  private def apiKindName(kind: ApiSymbolKind): String = apiKindKey(kind).replace('-', ' ')

  private def apiNavigationItemLink[Msg](
    item: NavigationItem,
    currentRoute: String
  ): HtmlElement[Msg] =
    val location = application
      .location(item.route).getOrElse(
        throw new IllegalArgumentException(s"Unknown navigation route: ${item.route}")
      )
    val mods                                            = Vector.newBuilder[Mod[Msg]]
    mods += (cls                                            := "docs-nav-row")
    if item.route == currentRoute then mods += (ariaCurrent := "page")
    mods ++= apiOwnerKinds.getOrElse(item.route, Vector.empty).map(apiKindBadge)
    mods += Mod.Content.Tag(span(cls := "docs-api-nav-label", item.title))
    link.pushNavigate(location, mods.result()*)

  private def navigationLink[Msg](
    location: LiveLocation,
    active: Signal[Boolean],
    className: Signal[String],
    text: String
  ): HtmlElement[Msg] =
    link.pushNavigate(
      location,
      cls.optional(className.map(value => Option.when(value.nonEmpty)(value))),
      ariaCurrent.optional(active.map(value => Option.when(value)("page"))),
      text
    )

  private def outline[Msg](page: Page): HtmlElement[Msg] =
    asideTag(
      cls := "docs-outline",
      detailsTag(
        dom.hook("PageOutline", DomRef("docs-page-outline")),
        cls := "docs-outline-disclosure",
        summaryTag(
          span("On this page"),
          span(cls := "docs-disclosure-marker", aria.hidden := true)
        ),
        navTag(
          aria.label := "On this page",
          p(cls := "docs-outline-title", "On this page"),
          ul(
            page.outline.items.map(item =>
              outlineItem(
                page.route,
                item,
                page.source match
                  case _: PageSource.GeneratedApi => false
                  case _                          => true
              )
            )
          )
        )
      )
    )

  private def outlineItem[Msg](
    route: String,
    item: OutlineItem,
    showChildren: Boolean
  ): HtmlElement[Msg] =
    val location = application
      .location(route)
      .map(_.withFragment(item.id))
      .getOrElse(throw new IllegalArgumentException(s"Unknown outline route: $route"))
    li(
      link.pushNavigate(location, item.title),
      Option
        .when(showChildren && item.children.nonEmpty)(
          Mod.Content.Tag(ul(item.children.map(child => outlineItem(route, child, showChildren))))
        ).toVector
    )

  private def connectionIndicator[Msg]: HtmlElement[Msg] =
    div(
      dom.hook("ConnectionStatus", DomRef("docs-connection-status")),
      cls                          := "docs-connection-indicator",
      dataAttr("connection-state") := "connecting",
      title                        := "Live connection status",
      role                         := "status",
      ariaLive                     := "polite",
      span(dataAttr("connection-label") := "connecting", "Connecting"),
      span(dataAttr("connection-label") := "connected", "Live"),
      span(dataAttr("connection-label") := "reconnecting", "Reconnecting"),
      span(dataAttr("connection-label") := "offline", "Offline")
    )

  private def themeSelector[Msg]: HtmlElement[Msg] =
    div(
      cls := "docs-theme-control",
      span(cls := "docs-theme-icon", aria.hidden := true),
      HtmlTag("select")(
        dom.hook("ThemeSelector", DomRef("docs-theme-selector")),
        cls        := "docs-theme-selector",
        title      := "Color theme: System",
        aria.label := "Color theme: System",
        HtmlTag("option")(value := "system", "System"),
        HtmlTag("option")(value := "light", "Light"),
        HtmlTag("option")(value := "dark", "Dark")
      )
    )
end DocumentationLayout

final private[docs] class DocumentationPageLiveView(
  page: Page,
  renderer: DocumentationRenderer)
    extends LiveView.Eventless[Unit]:

  def mount(ctx: MountContext): LiveIO[Unit] = LiveIO.succeed(())

  override def pageTitle(model: Unit): Option[String] =
    Some(if page.route == "/" then "Scalive" else s"${page.metadata.title} | Scalive")

  override def view(model: Signal[Unit]): HtmlElement[Nothing] = renderer.render(page)
