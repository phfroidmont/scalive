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
        assets.trackedScript("app.js", defer := true, typ := "text/javascript"),
        assets.trackedStylesheet("app.css"),
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

  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    val currentRoute = ctx.currentUrl.path.encode
    val page         = application.page(currentRoute)
    val metadata     = application
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
        cls := (if page.nonEmpty then "docs-shell" else "docs-shell docs-shell-wide"),
        mainTag(idAttr := "docs-main", cls := "docs-main", content),
        page.map(value => Mod.Content.Tag(sectionNavigation(value, currentRoute))).toVector,
        page.map(value => Mod.Content.Tag(outline(value))).toVector
      ),
      footerTag(
        cls := "docs-footer",
        "Scalive documentation for revision ",
        code(application.bundle.apiReference.metadata.revision.take(12)),
        "."
      )
    )

  private def header[Msg](page: Option[Page], currentRoute: String): HtmlElement[Msg] =
    headerTag(
      cls := "docs-header",
      div(
        cls := "docs-header-inner",
        navigationLink(
          application
            .location("/").getOrElse(throw new IllegalStateException("Missing homepage route.")),
          currentRoute == "/",
          "docs-brand",
          "Scalive"
        ),
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
        input(
          idAttr                                      := "docs-global-search-input",
          typ                                         := "search",
          nameAttr                                    := DocumentationApplication.SearchParameter,
          placeholder                                 := "Search",
          htmlAttr("autocomplete", StringAsIsEncoder) := "off",
          role                                        := "combobox",
          ariaControls                                := "docs-global-search-results",
          ariaExpanded                                := "false",
          ariaAutocomplete                            := "list"
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
    asideTag(
      cls := "docs-section-nav",
      navTag(
        aria.label := s"${section.fold(page.metadata.title)(_.title)} section navigation",
        p(cls := "docs-nav-title", section.fold(page.metadata.title)(_.title)),
        ul(section.toVector.map(item => navigationTree(item, currentRoute)))
      )
    )

  private def navigationTree[Msg](item: NavigationItem, currentRoute: String): HtmlElement[Msg] =
    li(
      navigationItemLink(item, currentRoute),
      Option
        .when(item.children.nonEmpty)(
          Mod.Content.Tag(ul(item.children.map(child => navigationTree(child, currentRoute))))
        ).toVector
    )

  private def navigationItemLink[Msg](
    item: NavigationItem,
    currentRoute: String
  ): HtmlElement[Msg] =
    val location = application
      .location(item.route).getOrElse(
        throw new IllegalArgumentException(s"Unknown navigation route: ${item.route}")
      )
    navigationLink(location, item.route == currentRoute, "", item.title)

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
      navTag(
        aria.label := "On this page",
        p(cls := "docs-outline-title", "On this page"),
        ul(page.outline.items.map(item => outlineItem(page.route, item)))
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
      role                         := "status",
      ariaLive                     := "polite",
      span(dataAttr("connection-label") := "connecting", "Connecting"),
      span(dataAttr("connection-label") := "connected", "Connected"),
      span(dataAttr("connection-label") := "reconnecting", "Reconnecting"),
      span(dataAttr("connection-label") := "offline", "Offline")
    )

  private def themeSelector[Msg]: HtmlElement[Msg] =
    HtmlTag("select")(
      dom.hook("ThemeSelector", DomRef("docs-theme-selector")),
      cls        := "docs-theme-selector",
      aria.label := "Color theme",
      HtmlTag("option")(value := "system", "System"),
      HtmlTag("option")(value := "light", "Light"),
      HtmlTag("option")(value := "dark", "Dark")
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
