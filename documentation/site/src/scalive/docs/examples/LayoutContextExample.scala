package scalive.docs.examples

import zio.*

import scalive.*

// docs:start shared-document-shell
object LayoutContextExample:
  final case class DocumentContext(language: String)
  final case class PublicContext(document: DocumentContext)
  final case class AccountContext(document: DocumentContext)

  final class EventlessPage(content: String, title: String) extends LiveView.Eventless[String]:
    def mount(ctx: MountContext)                          = ZIO.succeed(content)
    override def pageTitle(model: String): Option[String] = Some(title)
    def view(model: Signal[String])                       = div(idAttr := "page", model)

  object DocumentLayout extends LiveLayout[Any, DocumentContext]:
    def view[Msg](
      content: HtmlElement[Msg],
      ctx: LiveLayoutContext[Any, DocumentContext]
    ): HtmlElement[Msg] =
      sectionTag(
        idAttr                  := s"document-${ctx.context.language}",
        dataAttr("params")      := ctx.params.map(_.toString),
        dataAttr("request-url") := ctx.request.map(_.url.encode),
        dataAttr("current-url") := ctx.currentUrl.map(_.encode),
        content
      )

  // docs:start document-root-key
  object DocumentRoot extends LiveRootLayout[Any, DocumentContext]:
    override def key(ctx: LiveRootLayoutContext[Any, DocumentContext]): String =
      s"document:${ctx.context.language}"

    def view[Msg](
      content: HtmlElement[Msg],
      pageTitle: Option[String],
      ctx: LiveRootLayoutContext[Any, DocumentContext]
    ): HtmlElement[Msg] =
      htmlRootTag(
        lang := ctx.context.language,
        headTag(titleTag(pageTitle.getOrElse("Scalive"))),
        bodyTag(
          dataAttr("params")      := ctx.params.toString,
          dataAttr("request-url") := ctx.request.url.encode,
          dataAttr("current-url") := ctx.currentUrl.encode,
          content
        )
      )
  // docs:end document-root-key

  object ApplicationLayout extends LiveLayout[Any, Any]:
    def view[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
      mainTag(idAttr := "application", content)

  object ApplicationRoot extends LiveRootLayout[Any, Any]:
    def view[Msg](
      content: HtmlElement[Msg],
      pageTitle: Option[String],
      ctx: LiveRootLayoutContext[Any, Any]
    ): HtmlElement[Msg] =
      htmlRootTag(
        lang := "en",
        headTag(titleTag(pageTitle.getOrElse("Scalive"))),
        bodyTag(content)
      )

  val publicContext = LiveSessionMountAspect.fromRequest[Any, String, PublicContext](
    _ => ZIO.succeed("fr" -> PublicContext(DocumentContext("fr"))),
    (language, _) => ZIO.succeed(PublicContext(DocumentContext(language)))
  )
  val accountContext = LiveSessionMountAspect.fromRequest[Any, String, AccountContext](
    _ => ZIO.succeed("de" -> AccountContext(DocumentContext("de"))),
    (language, _) => ZIO.succeed(AccountContext(DocumentContext(language)))
  )
  val accountAudit = LiveSessionMountAspect.make[Any, AccountContext, String, String](
    (_, _) => ZIO.succeed("audited" -> "audited"),
    (claim, _, _) => ZIO.succeed(claim)
  )
  val accountVariant =
    LiveRouteMountAspect.make[Any, Unit, (AccountContext, String), DocumentContext]((_, _) =>
      ZIO.succeed(DocumentContext("de-CH"))
    )

  val defaultRoute = scalive.live / "default" -> EventlessPage("default", "Default title")
  val publicRoute  = (scalive.live / "public").context { (context: PublicContext) =>
    EventlessPage(s"public-${context.document.language}", "Public title")
  }
  val publicSession = scalive.Live
    .session("public")
    .withMountAspect(publicContext)
    .withLayout(DocumentLayout.forContext[PublicContext](_.document))
    .withRootLayout(DocumentRoot.forContext[PublicContext](_.document))(publicRoute)

  val accountBuilder = scalive.Live
    .session("account")
    .withMountAspect(accountContext)
    .withLayout(DocumentLayout, _.document)
    .withRootLayout(DocumentRoot, _.document)
    .withMountAspect(accountAudit)
  val accountRoute = (scalive.live / "account").context { (context: (AccountContext, String)) =>
    EventlessPage(s"account-${context._1.document.language}-${context._2}", "Account title")
  }
  val accountOverride = (scalive.live / "account" / "variant")
    .withMountAspect(accountVariant)
    .withLayout(DocumentLayout, _._2)
    .withRootLayout(DocumentRoot, _._2)(EventlessPage("account-variant", "Variant title"))

  val application = scalive.Live.router
    .withLayout(ApplicationLayout)
    .withRootLayout(ApplicationRoot)(
      defaultRoute,
      publicSession,
      accountBuilder(accountRoute, accountOverride)
    )
end LayoutContextExample
// docs:end shared-document-shell
