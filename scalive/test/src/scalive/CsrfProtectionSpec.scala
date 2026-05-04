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

object CsrfProtectionSpec extends ZIOSpecDefault:
  private val tokenConfig = TokenConfig("csrf-spec-secret", 1.hour)

  private def view = new LiveView[Unit, Unit]:
    def mount(ctx: MountContext) = ZIO.unit
    def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
    def render(model: Unit): HtmlElement[Unit] = div("ok")

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
        (scalive.Live.router @@ scalive.Live.tokenConfig(tokenConfig) @@ rootLayout)(
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
        CsrfProtection.validate(tokenConfig, request)
      )
    },
    test("rejects missing, tampered, and mismatched csrf tokens") {
      val first  = CsrfProtection.prepare(tokenConfig, Request.get(URL.root))
      val second = CsrfProtection.prepare(tokenConfig, Request.get(URL.root))
      val cookie = first.cookie.get

      for
        missing    <- url("/live/websocket").map(Request.get(_).addCookie(Cookie.Request(cookie.name, cookie.content)))
        tampered   <- websocketRequest(s"${first.value}x", cookie)
        mismatched <- websocketRequest(second.value, cookie)
      yield assertTrue(
        !CsrfProtection.validate(tokenConfig, missing),
        !CsrfProtection.validate(tokenConfig, tampered),
        !CsrfProtection.validate(tokenConfig, mismatched)
      )
    },
    test("reuses a valid csrf cookie without resetting it") {
      val first      = CsrfProtection.prepare(tokenConfig, Request.get(URL.root))
      val firstCookie = first.cookie.get
      val request = Request.get(URL.root).addCookie(
        Cookie.Request(firstCookie.name, firstCookie.content)
      )
      val second = CsrfProtection.prepare(tokenConfig, request)

      for wsRequest <- websocketRequest(second.value, firstCookie)
      yield assertTrue(
        second.cookie.isEmpty,
        CsrfProtection.validate(tokenConfig, wsRequest)
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
