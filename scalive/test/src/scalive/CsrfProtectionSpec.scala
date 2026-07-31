package scalive

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.test.*

import scalive.WebSocketMessage.JoinErrorReason
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Protocol
import scalive.WebSocketMessage.ReplyStatus
import scalive.socket.ComponentRuntimeState
import scalive.socket.SocketComponentRuntime

object CsrfProtectionSpec extends ZIOSpecDefault:
  private val tokenConfig = TokenConfig("csrf-spec-secret", 1.hour)
  private val security    = LiveSecurity(tokenConfig)
  private val protection  = security.csrf

  private val SubmitRoute = Method.POST / "submit"
  private val SearchRoute = Method.GET / "search"

  private def view = new LiveView[Unit, Unit]:
    def mount(ctx: MountContext) = ZIO.unit
    def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
    def render(model: Unit): HtmlElement[Unit] = div("ok")

  private def ordinaryFormsView = new LiveView[Unit, Unit]:
    private val formModel = scalive.Form.of(
      "login",
      FormState(FormData.empty, Right(FormData.empty), submitted = false),
      FormCodec.formData
    )

    def mount(ctx: MountContext) = ZIO.unit
    def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
    def render(model: Unit): HtmlElement[Unit] =
      div(
        formModel.http(FormAction.from(SubmitRoute))(idAttr := "checked-post"),
        formModel.http(FormAction.from(SearchRoute))(idAttr := "checked-get"),
        formModel.http(FormAction.unsafe(FormAction.Method.Post, "https://example.com/submit"))(
          idAttr := "unsafe-post"
        )
      )

  private val rootLayout = LiveRootLayout("csrf-root")((content, _) =>
    htmlRootTag(
      headTag(titleTag("CSRF")),
      bodyTag(content)
    )
  )

  private def runRequest(routes: Routes[Any, Nothing], request: Request) =
    ZIO.scoped(routes.runZIO(request))

  private def url(raw: String): UIO[URL] =
    ZIO.fromEither(URL.decode(raw)).orDie

  private def websocketRequest(token: String, cookie: Cookie.Response): UIO[Request] =
    url(s"/live/websocket?${CsrfProtection.ParamName}=$token").map { url =>
      Request.get(url).addCookie(Cookie.Request(cookie.name, cookie.content))
    }

  private def csrfCookie(response: Response): Task[Cookie.Response] =
    ZIO.fromOption(
      response
        .rawHeaders("set-cookie")
        .flatMap(raw => Cookie.Response.decode(raw).toOption)
        .find(_.name == CsrfProtection.CookieName)
    ).orElseFail(new NoSuchElementException(CsrfProtection.CookieName))

  private def extractMetaToken(body: String): Task[String] =
    val pattern = s"""<meta name="${CsrfProtection.MetaName}" content="([^"]+)""".r
    ZIO.fromOption(pattern.findFirstMatchIn(body).map(_.group(1))).orElseFail(
      new NoSuchElementException("csrf meta")
    )

  private def extractFormToken(body: String): Task[String] =
    val pattern = s"""<input type="hidden" name="${CsrfProtection.ParamName}" value="([^"]+)""".r
    ZIO.fromOption(pattern.findFirstMatchIn(body).map(_.group(1))).orElseFail(
      new NoSuchElementException("csrf form field")
    )

  private def extractAttr(body: String, attr: String): Task[String] =
    val pattern = s"""$attr="([^"]+)""".r
    ZIO.fromOption(pattern.findFirstMatchIn(body).map(_.group(1))).orElseFail(
      new NoSuchElementException(attr)
    )

  private def runtimeFor(route: LiveRouteFragment[Any, Any]) =
    new LiveRoutesRuntime[Any](
      Nil,
      rootLayout,
      route.liveRoutes.asInstanceOf[List[LiveRoute[Any, ?, Any, ?, ?, ?]]],
      PathCodec.empty / "live",
      tokenConfig
    )

  private def joinMessage(topic: String, session: String, path: String) =
    WebSocketMessage(
      joinRef = Some(1),
      messageRef = Some(1),
      topic = topic,
      eventType = Protocol.EventJoin,
      payload = Payload.Join(
        url = Some(path),
        redirect = None,
        session = session,
        static = None,
        params = None,
        flash = None,
        sticky = false
      )
    )

  private def staleJoin(reply: Option[WebSocketMessage]): Boolean =
    reply.exists(
      _.payload == Payload.Reply(
        ReplyStatus.Error,
        LiveResponse.JoinError(JoinErrorReason.Stale)
      )
    )

  override def spec = suite("CsrfProtectionSpec")(
    test("emits csrf meta token and matching HttpOnly cookie on disconnected render") {
      val routes =
        scalive.Live.router.withTokenConfig(tokenConfig).withRootLayout(rootLayout)(
          scalive.live(view)
        )

      for
        response <- runRequest(routes, Request.get(URL.root))
        body     <- response.body.asString
        token    <- extractMetaToken(body)
        cookie   <- csrfCookie(response)
        request  <- websocketRequest(token, cookie)
      yield assertTrue(
        response.status == Status.Ok,
        cookie.isHttpOnly,
        cookie.sameSite.contains(Cookie.SameSite.Lax),
        protection.validateWebSocket(request).isDefined
      )
    },
    test("injects matching tokens into checked POST forms only") {
      val routes =
        scalive.Live.router
          .withSecurity(security)
          .withRootLayout(rootLayout)(scalive.live(ordinaryFormsView))

      for
        response  <- runRequest(routes, Request.get(URL.root))
        body      <- response.body.asString
        metaToken <- extractMetaToken(body)
        formToken <- extractFormToken(body)
      yield assertTrue(
        formToken == metaToken,
        body.split(s"name=\"${CsrfProtection.ParamName}\"", -1).length - 1 == 1,
        !body.contains("data-scalive-csrf")
      )
    },
    test("validates exactly one ordinary form token from the same browser context") {
      val first       = protection.prepare(Request.get(URL.root))
      val second      = protection.prepare(Request.get(URL.root))
      val firstCookie = first.cookie.get
      val request = Request.get(URL.root).addCookie(
        Cookie.Request(firstCookie.name, firstCookie.content)
      )
      val valid       = FormData(Vector(CsrfProtection.ParamName -> first.value))
      val missing     = FormData.empty
      val duplicated  = FormData(Vector.fill(2)(CsrfProtection.ParamName -> first.value))
      val tampered    = FormData(Vector(CsrfProtection.ParamName -> s"${first.value}x"))
      val transferred = FormData(Vector(CsrfProtection.ParamName -> second.value))

      assertTrue(
        protection.validate(request, valid) == Right(()),
        protection.validate(request, missing) == Left(
          CsrfProtection.ValidationError.MissingToken
        ),
        protection.validate(request, duplicated) == Left(
          CsrfProtection.ValidationError.DuplicateToken
        ),
        protection.validate(request, tampered) == Left(
          CsrfProtection.ValidationError.InvalidToken
        ),
        protection.validate(request, transferred) == Left(
          CsrfProtection.ValidationError.InvalidToken
        )
      )
    },
    test("configures the CSRF cookie Secure attribute explicitly") {
      val secure = LiveSecurity(tokenConfig, CookiePolicy(secure = true)).csrf
      assertTrue(secure.prepare(Request.get(URL.root)).cookie.exists(_.isSecure))
    },
    test("keeps the verified token in connected render finalization") {
      val token = "connected-csrf-token"
      for
        components <- Ref.make(ComponentRuntimeState.empty)
        rendered <- SocketComponentRuntime.renderRoot(
                      scalive.Form.http(FormAction.from(SubmitRoute))(idAttr := "logout"),
                      components,
                      LiveContext(
                        staticChanged = false,
                        connected = true,
                        csrfToken = Some(token)
                      )
                    )
        html = HtmlBuilder.build(rendered)
      yield assertTrue(
        html.contains(s"name=\"${CsrfProtection.ParamName}\""),
        html.contains(s"value=\"$token\""),
        !html.contains(CsrfProtection.MarkerName)
      )
    },
    test("rejects missing, tampered, and mismatched csrf tokens") {
      val first  = protection.prepare(Request.get(URL.root))
      val second = protection.prepare(Request.get(URL.root))
      val cookie = first.cookie.get

      for
        missing    <- url("/live/websocket").map(Request.get(_).addCookie(Cookie.Request(cookie.name, cookie.content)))
        tampered   <- websocketRequest(s"${first.value}x", cookie)
        mismatched <- websocketRequest(second.value, cookie)
      yield assertTrue(
        protection.validateWebSocket(missing).isEmpty,
        protection.validateWebSocket(tampered).isEmpty,
        protection.validateWebSocket(mismatched).isEmpty
      )
    },
    test("reuses a valid csrf cookie without resetting it") {
      val first      = protection.prepare(Request.get(URL.root))
      val firstCookie = first.cookie.get
      val request = Request.get(URL.root).addCookie(
        Cookie.Request(firstCookie.name, firstCookie.content)
      )
      val second = protection.prepare(request)

      for wsRequest <- websocketRequest(second.value, firstCookie)
      yield assertTrue(
        second.cookie.isEmpty,
        protection.validateWebSocket(wsRequest).isDefined
      )
    },
    test("invalid websocket csrf causes stale liveview join") {
      val route   = scalive.live(view)
      val runtime = runtimeFor(route)

      for
        response   <- runRequest(runtime.routes, Request.get(URL.root))
        body       <- response.body.asString
        session    <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic   = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig, connectAuthorized = false)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/"), channel)
        socket  <- channel.socket(topic)
      yield assertTrue(
        staleJoin(reply),
        socket.isEmpty
      )
    }
  )
end CsrfProtectionSpec
