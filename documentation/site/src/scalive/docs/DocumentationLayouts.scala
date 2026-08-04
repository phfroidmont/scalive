package scalive.docs

import scalive.*
import scalive.codecs.StringAsIsEncoder
import scalive.docs.model.*

final private[docs] class DocumentationRootLayout(
  application: DocumentationApplication,
  assets: StaticAssets)
    extends LiveRootLayout[Any, Any]:

  def key(ctx: LiveLayoutContext[Any, Any]): String = "documentation-root"

  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveLayoutContext[Any, Any]
  ): HtmlElement[Msg] =
    val page = application.page(ctx.currentUrl.path.encode).getOrElse(application.pages.head.page)
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset  := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr    := "width=device-width, initial-scale=1"),
        metaTag(nameAttr := "description", contentAttr := page.metadata.description),
        linkTag(rel      := "canonical", href          := page.route),
        assets.trackedScript("app.js", defer := true, typ := "text/javascript"),
        assets.trackedStylesheet("app.css"),
        liveTitle(pageTitle, default = "Scalive")
      ),
      bodyTag(content)
    )

final private[docs] class DocumentationLayout(application: DocumentationApplication)
    extends LiveLayout[Any, Any]:

  private val ariaCurrent = htmlAttr("aria-current", StringAsIsEncoder)
  private val ariaLive    = htmlAttr("aria-live", StringAsIsEncoder)
  private val role        = htmlAttr("role", StringAsIsEncoder)

  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    val currentRoute = ctx.currentUrl.path.encode
    val page         = application.page(currentRoute).getOrElse(application.pages.head.page)
    div(
      dom.hook("PageMetadata", DomRef("docs-page-metadata")),
      dataAttr("page-description") := page.metadata.description,
      dataAttr("page-canonical")   := page.route,
      a(cls := "docs-skip-link", href := "#docs-main", "Skip to content"),
      header(page, currentRoute),
      div(
        cls := "docs-shell",
        mainTag(idAttr := "docs-main", cls := "docs-main", content),
        sectionNavigation(page, currentRoute),
        outline(page)
      ),
      footerTag(
        cls := "docs-footer",
        "Scalive documentation for revision ",
        code(application.bundle.apiReference.metadata.revision.take(12)),
        "."
      )
    )

  private def header[Msg](page: Page, currentRoute: String): HtmlElement[Msg] =
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
                  if item.section == page.metadata.section then "docs-current-section" else ""
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
          connectionIndicator,
          themeSelector
        )
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
