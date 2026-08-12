package scalive.docs

import scalive.*
import scalive.codecs.StringAsIsEncoder
import scalive.docs.model.*

final private[docs] class DocumentationRootLayout(
  application: DocumentationApplication,
  assets: StaticAssets,
  origin: PublicOrigin)
    extends LiveRootLayout[Any, Any]:

  def key(ctx: LiveLayoutContext[Any, Any]): String = "documentation-root"

  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveLayoutContext[Any, Any]
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

  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    val currentRoute         = ctx.currentUrl.path.encode
    val page                 = application.page(currentRoute)
    val documentPage         = page.filterNot(_.metadata.section == Section.Home)
    val hasSectionNavigation = documentPage.exists(sectionNavigationVisible)
    val metadata             = application
      .metadata(currentRoute).getOrElse(
        throw new IllegalArgumentException(
          s"Missing documentation metadata for route '$currentRoute'."
        )
      )
    div(
      dom.hook("PageMetadata", DomRef("docs-page-metadata")),
      dataAttr("page-description") := metadata.description,
      dataAttr("page-canonical")   := origin.absolute(metadata.canonicalPath),
      dataAttr("page-indexable")   := metadata.indexable.toString,
      a(cls := "docs-skip-link", href := "#docs-main", "Skip to content"),
      header(page, currentRoute),
      div(
        cls :=
          (if documentPage.exists(_.metadata.section == Section.Api) then
             "docs-shell docs-shell-api"
           else if documentPage.nonEmpty && !hasSectionNavigation then
             "docs-shell docs-shell-no-section"
           else if documentPage.nonEmpty then "docs-shell"
           else "docs-shell docs-shell-wide"),
        documentPage
          .filter(_.metadata.section == Section.Api)
          .map(value => Mod.Content.Tag(sectionNavigation(value, currentRoute))).toVector,
        mainTag(idAttr := "docs-main", cls := "docs-main", content),
        documentPage
          .filter(value => value.metadata.section != Section.Api && sectionNavigationVisible(value))
          .map(value => Mod.Content.Tag(sectionNavigation(value, currentRoute))).toVector,
        documentPage
          .filter(_.outline.items.nonEmpty)
          .map(value => Mod.Content.Tag(outline(value))).toVector
      ),
      footerTag(
        cls := "docs-footer",
        "Scalive documentation for revision ",
        code(application.bundle.apiReference.metadata.revision.take(12)),
        "."
      )
    )
  end render

  private def header[Msg](page: Option[Page], currentRoute: String): HtmlElement[Msg] =
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
                    val className =
                      if page.exists(_.metadata.section == item.section) then "docs-current-section"
                      else ""
                    li(
                      navigationLink(
                        application
                          .location(item.route).getOrElse(
                            throw new IllegalArgumentException(
                              s"Unknown navigation route: ${item.route}"
                            )
                          ),
                        item.route == currentRoute,
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

  private def brandLink[Msg](currentRoute: String): HtmlElement[Msg] =
    val mods                                     = Vector.newBuilder[Mod[Msg]]
    mods += (cls                                     := "docs-brand")
    mods += (aria.label                              := "Scalive home")
    if currentRoute == "/" then mods += (ariaCurrent := "page")
    mods += Mod.Content.Tag(DocumentationBrand.lockup)
    link.pushNavigate(
      application
        .location("/").getOrElse(throw new IllegalStateException("Missing homepage route.")),
      mods.result()*
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
      p(cls := "docs-nav-title", section.fold(page.metadata.title)(_.title)),
      Option
        .when(page.metadata.section == Section.Api)(
          Mod.Content.Tag(
            input(
              cls                        := "docs-api-nav-filter",
              typ                        := "search",
              placeholder                := "Filter symbols",
              aria.label                 := "Filter API symbols",
              dataAttr("api-nav-filter") := ""
            )
          )
        ).toVector,
      ul(section.toVector.map(item => navigationTree(item, currentRoute)))
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
    val items                           = root +: root.children
    val links: Vector[HtmlElement[Msg]] = items.zipWithIndex.map { case (item, index) =>
      li(editorialNavigationLink[Msg](item, currentRoute, index + 1))
    }
    val linkMods = links.map(Mod.Content.Tag(_))
    val list     =
      if page.metadata.section == Section.Learn then ol(linkMods)
      else ul(linkMods)
    asideTag(
      cls := "docs-section-nav docs-section-index",
      navTag(
        aria.label := s"${root.title} section navigation",
        p(cls := "docs-nav-title", root.title),
        list
      )
    )

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

  private def navigationTree[Msg](item: NavigationItem, currentRoute: String): HtmlElement[Msg] =
    val row = span(
      cls := "docs-nav-row",
      navigationItemLink(item, currentRoute),
      apiOwnerKinds.getOrElse(item.route, Vector.empty).map(apiKindBadge)
    )
    li(
      dataAttr("api-nav-item") := item.title.toLowerCase,
      if item.children.nonEmpty then
        detailsTag(
          disclosureOpen := navigationContains(item, currentRoute),
          summaryTag(
            span(cls := "docs-tree-marker", aria.hidden := true),
            row
          ),
          ul(item.children.map(child => navigationTree(child, currentRoute)))
        )
      else
        span(
          cls := "docs-nav-leaf",
          span(cls := "docs-tree-marker-spacer", aria.hidden := true),
          row
        )
    )

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

  private def navigationItemLink[Msg](
    item: NavigationItem,
    currentRoute: String
  ): HtmlElement[Msg] =
    val location = application
      .location(item.route).getOrElse(
        throw new IllegalArgumentException(s"Unknown navigation route: ${item.route}")
      )
    val active = item.route == currentRoute ||
      (item.section == Section.Examples && currentRoute.startsWith("/examples/"))
    navigationLink(location, active, "", item.title)

  private def navigationLink[Msg](
    location: LiveLocation,
    active: Boolean,
    className: String,
    text: String
  ): HtmlElement[Msg] =
    val mods = Vector.newBuilder[Mod[Msg]]
    if className.nonEmpty then mods += (cls := className)
    if active then mods += (ariaCurrent     := "page")
    mods += Mod.Content.Text(text)
    link.pushNavigate(location, mods.result()*)

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
          ul(page.outline.items.map(item => outlineItem(page.route, item)))
        )
      )
    )

  private def outlineItem[Msg](route: String, item: OutlineItem): HtmlElement[Msg] =
    val location = application
      .location(route)
      .map(_.withFragment(item.id))
      .getOrElse(throw new IllegalArgumentException(s"Unknown outline route: $route"))
    li(
      link.pushNavigate(location, item.title),
      Option
        .when(item.children.nonEmpty)(
          Mod.Content.Tag(ul(item.children.map(child => outlineItem(route, child))))
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

  def render(model: Unit): HtmlElement[Nothing] = renderer.render(page)
