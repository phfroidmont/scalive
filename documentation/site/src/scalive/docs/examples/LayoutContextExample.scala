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

  val documentLayout = LiveLayout[Any, DocumentContext]([Msg] =>
    (content, context) =>
      sectionTag(
        idAttr                  := s"document-${context.context.language}",
        dataAttr("params")      := context.params.map(_.toString),
        dataAttr("request-url") := context.request.map(_.url.encode),
        dataAttr("current-url") := context.currentUrl.map(_.encode),
        content
      )
  )
  val documentRoot = LiveRootLayout.dynamic[Any, DocumentContext](context =>
    s"document:${context.context.language}"
  )([Msg] =>
    (content, pageTitle, context) =>
      htmlRootTag(
        lang := context.context.language,
        headTag(titleTag(pageTitle.getOrElse("Scalive"))),
        bodyTag(
          dataAttr("params")      := context.params.toString,
          dataAttr("request-url") := context.request.url.encode,
          dataAttr("current-url") := context.currentUrl.encode,
          content
        )
      )
  )
  val applicationLayout =
    LiveLayout[Any, Any]([Msg] => (content, _) => mainTag(idAttr := "application", content))
  val applicationRoot = LiveRootLayout[Any, Any]("application-root")([Msg] =>
    (content, pageTitle, _) =>
      htmlRootTag(
        lang := "en",
        headTag(titleTag(pageTitle.getOrElse("Scalive"))),
        bodyTag(content)
      )
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
    .withLayout(documentLayout.forContext[PublicContext](_.document))
    .withRootLayout(documentRoot.forContext[PublicContext](_.document))(publicRoute)

  val accountBuilder = scalive.Live
    .session("account")
    .withMountAspect(accountContext)
    .withLayout(documentLayout, _.document)
    .withRootLayout(documentRoot, _.document)
    .withMountAspect(accountAudit)
  val accountRoute = (scalive.live / "account").context { (context: (AccountContext, String)) =>
    EventlessPage(s"account-${context._1.document.language}-${context._2}", "Account title")
  }
  val accountOverride = (scalive.live / "account" / "variant")
    .withMountAspect(accountVariant)
    .withLayout(documentLayout, _._2)
    .withRootLayout(documentRoot, _._2)(EventlessPage("account-variant", "Variant title"))

  val application = scalive.Live.router
    .withLayout(applicationLayout)
    .withRootLayout(applicationRoot)(
      defaultRoute,
      publicSession,
      accountBuilder(accountRoute, accountOverride)
    )
end LayoutContextExample
// docs:end shared-document-shell
