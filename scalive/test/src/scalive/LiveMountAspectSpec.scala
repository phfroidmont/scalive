package scalive

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.json.*
import zio.test.*

import scalive.WebSocketMessage.JoinErrorReason
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Protocol
import scalive.WebSocketMessage.ReplyStatus

object LiveMountAspectSpec extends ZIOSpecDefault:

  private final case class MountClaims(value: String) derives JsonCodec
  private final case class MountUser(name: String)

  private final case class FirstClaims(value: String) derives JsonCodec
  private final case class SecondClaims(value: String) derives JsonCodec
  private val LoginRoute = scalive.live / "login"

  private def runRequest(routes: Routes[Any, Nothing], path: String) =
    URL.decode(path) match
      case Left(error) => ZIO.die(error)
      case Right(url)  => ZIO.scoped(routes.runZIO(Request.get(url)))

  private def extractAttr(body: String, attr: String): Task[String] =
    val pattern = s"""$attr="([^"]+)""".r
    ZIO.fromOption(pattern.findFirstMatchIn(body).map(_.group(1))).orElseFail(
      new NoSuchElementException(attr)
    )

  private def liveView(callsRef: Ref[List[String]], user: MountUser) =
    new LiveView[Unit, String]:
      def mount(ctx: MountContext) =
        callsRef.update(_ :+ s"mount:${user.name}").as(user.name)

      def handleMessage(model: String, ctx: MessageContext) =
        (_: Unit) => ZIO.succeed(model)

      def render(model: String): HtmlElement[Unit] =
        div(model)

  private def runtimeFor(
    route: LiveRouteFragment[Any, Any],
    tokenConfig: TokenConfig
  ): LiveRoutesRuntime[Any] =
    new LiveRoutesRuntime[Any](
      Nil,
      LiveRootLayout.identity,
      route.liveRoutes.asInstanceOf[List[LiveRoute[Any, ?, Any, ?, ?, ?]]],
      PathCodec.empty / "live",
      tokenConfig
    )

  private def joinMessage(
    topic: String,
    session: String,
    url: String,
    redirect: Boolean = false
  ): WebSocketMessage =
    WebSocketMessage(
      joinRef = Some(1),
      messageRef = Some(1),
      topic = topic,
      eventType = Protocol.EventJoin,
      payload = Payload.Join(
        url = Option.when(!redirect)(url),
        redirect = Option.when(redirect)(url),
        session = session,
        static = None,
        params = None,
        flash = None,
        sticky = false
      )
    )

  private def unauthorizedJoin(reply: Option[WebSocketMessage]): Boolean =
    joinFailure(reply, JoinErrorReason.Unauthorized)

  private def joinFailure(reply: Option[WebSocketMessage], reason: JoinErrorReason): Boolean =
    reply.exists(
      _.payload == Payload.Reply(
        ReplyStatus.Error,
        LiveResponse.JoinError(reason)
      )
    )

  def spec = suite("LiveMountAspectSpec")(
    test("authenticated aspect reads the cookie and resumes signed claims") {
      val aspect = LiveMountAspect.authenticated[Any, MountClaims, MountUser](
        "session",
        LoginRoute.location
      )(
        token =>
          ZIO.succeed(
            Option.when(token == "valid")(MountClaims("alice") -> MountUser("alice"))
          ),
        claims => ZIO.succeed(Option.when(claims.value == "alice")(MountUser(claims.value)))
      )
      val disconnectedRequest = LiveMountRequest(
        (),
        Request.get(URL.root).addCookie(Cookie.Request("session", "valid"))
      )
      val connectedRequest = LiveMountRequest((), Request.get(URL.root))

      for
        disconnected <- aspect.disconnected(disconnectedRequest, ())
        connected    <- aspect.connected(disconnected._1, connectedRequest, ())
      yield assertTrue(
        disconnected == (MountClaims("alice") -> MountUser("alice")),
        connected == MountUser("alice"),
        connectedRequest.request.cookie("session").isEmpty
      )
    },
    test("authenticated aspect redirects missing, invalid, and revoked sessions") {
      val aspect = LiveMountAspect.authenticated[Any, MountClaims, MountUser](
        "session",
        LoginRoute.location
      )(
        (_: String) => ZIO.succeed(Option.empty[(MountClaims, MountUser)]),
        (_: MountClaims) => ZIO.succeed(Option.empty[MountUser])
      )
      val missing = LiveMountRequest((), Request.get(URL.root))
      val invalid = LiveMountRequest(
        (),
        Request.get(URL.root).addCookie(Cookie.Request("session", "invalid"))
      )

      for
        missingResult <- aspect.disconnected(missing, ()).either
        invalidResult <- aspect.disconnected(invalid, ()).either
        revokedResult <- aspect.connected(MountClaims("revoked"), missing, ()).either
      yield assertTrue(
        List(missingResult, invalidResult).forall(
          _.left.exists(response =>
            response.status == Status.SeeOther &&
              response.header(Header.Location).exists(_.url.encode == LoginRoute.location.href)
          )
        ),
        revokedResult.left.exists {
          case LiveMountFailure.Redirect(location) => location.href == LoginRoute.location.href
          case _                                   => false
        }
      )
    },
    test("builds a fresh layered LiveView for disconnected and connected mount") {
      trait Dependency:
        def value: String

      val builds = AtomicInteger()
      final class LayeredView(dependency: Dependency) extends LiveView[Unit, Int]:
        private val instance = builds.incrementAndGet()

        def mount(ctx: MountContext) = ZIO.succeed(instance)
        def handleMessage(model: Int, ctx: MessageContext) = (_: Unit) => ZIO.succeed(model)
        def render(model: Int): HtmlElement[Unit] = div(s"${dependency.value}:$model")

      val dependency = new Dependency:
        def value = "dependency"
      val route      = scalive.live -> ZLayer.fromFunction(LayeredView.apply)
      val runtime    = new LiveRoutesRuntime[Dependency](
        Nil,
        LiveRootLayout.identity,
        route.liveRoutes.asInstanceOf[List[LiveRoute[Dependency, ?, Any, ?, ?, ?]]],
        PathCodec.empty / "live",
        TokenConfig.default
      )

      (for
        response <- ZIO.scoped(runtime.routes.runZIO(Request.get(URL.root)))
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(TokenConfig.default, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        channel <- LiveChannel.make(TokenConfig.default)
        reply <- ZIO.scoped(
                   runtime.handleMessage(
                     joinMessage(s"lv:$liveViewId", session, "/"),
                     channel
                   )
                 )
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(">dependency:1<"),
        reply.isEmpty,
        builds.get() == 2
      )).provide(ZLayer.succeed(dependency))
    },
    test("runs route mount aspect before disconnected and connected mount") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("mount-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   request =>
                      callsRef.update(_ :+ s"disconnected:${request.url.path.encode}") *>
                        ZIO.succeed(MountClaims("signed") -> MountUser("disconnected")),
                   (claims, request) =>
                     callsRef.update(_ :+ s"connected:${claims.value}:${request.url.path.encode}") *>
                       ZIO.succeed(MountUser(s"connected:${claims.value}"))
                 )
        route = scalive.live.withMountAspect(aspect) { (_, _, user) => liveView(callsRef, user) }
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("disconnected"),
        reply.isEmpty,
        socket.isDefined,
        calls == List(
          "disconnected:/",
          "mount:disconnected",
          "connected:signed:/",
          "mount:connected:signed"
        )
      )
    },
    test("runs LiveSession mount aspect for grouped routes") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("session-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Any, MountClaims, MountUser](
                   request =>
                     callsRef.update(_ :+ s"session-disconnected:${request.url.path.encode}") *>
                       ZIO.succeed(MountClaims("session") -> MountUser("session-disconnected")),
                   (claims, request) =>
                     callsRef.update(_ :+ s"session-connected:${claims.value}:${request.url.path.encode}") *>
                       ZIO.succeed(MountUser(s"session-connected:${claims.value}"))
                 )
        route = scalive.Live.session("admin").withMountAspect(aspect)(
                  (scalive.live / "admin") { (_, _, user: MountUser) => liveView(callsRef, user) }
                )
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/admin")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        payload <- ZIO
                     .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                     .map(_._2)
                     .mapError(new IllegalArgumentException(_))
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/admin"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("session-disconnected"),
        payload.sessionName == "admin",
        reply.isEmpty,
        socket.isDefined,
        calls == List(
          "session-disconnected:/admin",
          "mount:session-disconnected",
          "session-connected:session:/admin",
          "mount:session-connected:session"
        )
      )
    },
    test("route mount aspect receives typed path params") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("params-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Int, MountClaims, MountUser](
                   request =>
                     callsRef.update(_ :+ s"disconnected:${request.params}") *>
                       ZIO.succeed(MountClaims(request.params.toString) -> MountUser("disconnected")),
                   (claims, request) =>
                     callsRef.update(_ :+ s"connected:${request.params}:${claims.value}") *>
                       ZIO.succeed(MountUser(s"connected:${request.params}"))
                 )
        route = (scalive.live / "users" / PathCodec.int("id")).withMountAspect(aspect) {
          (_, _, user) =>
                  liveView(callsRef, user)
                }
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/users/42")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/users/42"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        reply.isEmpty,
        socket.isDefined,
        calls == List(
          "disconnected:42",
          "mount:disconnected",
          "connected:42:42",
          "mount:connected:42"
        )
      )
    },
    test("route mount aspect can authorize using typed path params") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("param-auth-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Int, MountClaims, MountUser](
                   request =>
                     callsRef.update(_ :+ s"authorize-disconnected:${request.params}") *>
                       (if request.params == 1 then
                          ZIO.succeed(MountClaims("1") -> MountUser("allowed-disconnected"))
                        else ZIO.fail(Response(status = Status.Forbidden))),
                   (claims, request) =>
                     callsRef.update(_ :+ s"authorize-connected:${request.params}:${claims.value}") *>
                       (if request.params == 1 && claims.value == "1" then
                          ZIO.succeed(MountUser("allowed-connected"))
                        else ZIO.fail(LiveMountFailure.unauthorized("forbidden id")))
                 )
        route = (scalive.live / "secure" / PathCodec.int("id")).withMountAspect(aspect) {
          (_, _, user) =>
                  liveView(callsRef, user)
                }
        runtime = runtimeFor(route, tokenConfig)
        allowedResponse <- runRequest(runtime.routes, "/secure/1")
        allowedBody     <- allowedResponse.body.asString
        deniedResponse  <- runRequest(runtime.routes, "/secure/2")
        session         <- extractAttr(allowedBody, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        allowedChannel <- LiveChannel.make(tokenConfig)
        allowedReply   <- runtime.handleMessage(joinMessage(topic, session, "/secure/1"), allowedChannel)
        allowedSocket  <- allowedChannel.socket(topic)
        deniedChannel  <- LiveChannel.make(tokenConfig)
        deniedReply    <- runtime.handleMessage(joinMessage(topic, session, "/secure/2"), deniedChannel)
        deniedSocket   <- deniedChannel.socket(topic)
        calls          <- callsRef.get
      yield assertTrue(
        allowedResponse.status == Status.Ok,
        allowedBody.contains("allowed-disconnected"),
        deniedResponse.status == Status.Forbidden,
        allowedReply.isEmpty,
        allowedSocket.isDefined,
        unauthorizedJoin(deniedReply),
        deniedSocket.isEmpty,
        calls == List(
          "authorize-disconnected:1",
          "mount:allowed-disconnected",
          "authorize-disconnected:2",
          "authorize-connected:1:1",
          "mount:allowed-connected",
          "authorize-connected:2:1"
        )
      )
    },
    test("session and route mount aspects compose in order") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("combined-secret", 1.hour)
        sessionAspect = LiveMountAspect.fromRequest[Any, Any, FirstClaims, String](
                          request =>
                            callsRef.update(_ :+ s"session-disconnected:${request.url.path.encode}") *>
                              ZIO.succeed(FirstClaims("session") -> "session"),
                          (claims, request) =>
                            callsRef.update(_ :+ s"session-connected:${claims.value}:${request.url.path.encode}") *>
                              ZIO.succeed(s"session:${claims.value}")
                        )
        routeAspect = LiveMountAspect.make[Any, Int, String, SecondClaims, String](
                        (request, _) =>
                          callsRef.update(_ :+ s"route-disconnected:${request.params}") *>
                            ZIO.succeed(SecondClaims(request.params.toString) -> s"route:${request.params}"),
                        (claims, request, _) =>
                          callsRef.update(_ :+ s"route-connected:${request.params}:${claims.value}") *>
                            ZIO.succeed(s"route:${claims.value}")
                      )
        route = scalive.Live.session("combined").withMountAspect(sessionAspect)(
                  (scalive.live / "combined" / PathCodec.int("id")).withMountAspect(routeAspect) {
                    (_, _, sessionCtx, routeCtx) =>
                      liveView(callsRef, MountUser(s"$sessionCtx/$routeCtx"))
                  }
                )
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/combined/7")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/combined/7"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("session/route:7"),
        reply.isEmpty,
        socket.isDefined,
        calls == List(
          "session-disconnected:/combined/7",
          "route-disconnected:7",
          "mount:session/route:7",
          "session-connected:session:/combined/7",
          "route-connected:7:7",
          "mount:session:session/route:7"
        )
      )
    },
    test("composes aspects in order and reloads tuple claims") {
      for
        callsRef <- Ref.make(List.empty[String])
        first = LiveMountAspect.fromRequest[Any, Unit, FirstClaims, String](
                  _ => callsRef.update(_ :+ "disconnected:first").as(FirstClaims("one") -> "first"),
                  (claims, _) => callsRef.update(_ :+ s"connected:first:${claims.value}").as("first-connected")
                )
        second = LiveMountAspect.fromRequest[Any, Unit, SecondClaims, String](
                   _ => callsRef.update(_ :+ "disconnected:second").as(SecondClaims("two") -> "second"),
                   (claims, _) => callsRef.update(_ :+ s"connected:second:${claims.value}").as("second-connected")
                 )
        composed = (first ++ second).map { case (left, right) => s"$left/$right" }
        mountRequest = LiveMountRequest((), Request.get(URL.root))
        claimsAndContext <- composed.runtime.runDisconnected(mountRequest, ())
        connectedContext <- composed.runtime.runConnected(Some(claimsAndContext._1), mountRequest, ())
        calls            <- callsRef.get
      yield assertTrue(
        claimsAndContext._2 == "first/second",
        connectedContext == "first-connected/second-connected",
        calls == List(
          "disconnected:first",
          "disconnected:second",
          "connected:first:one",
          "connected:second:two"
        )
      )
    },
    test("supports mixed plain and aspected routes") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("mixed-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   request =>
                     callsRef.update(_ :+ s"aspect:${request.url.path.encode}") *>
                       ZIO.succeed(MountClaims("signed") -> MountUser("aspected")),
                   (_, _) => ZIO.succeed(MountUser("connected"))
                 )
        routes = scalive.Live.router.withTokenConfig(tokenConfig)(
                   (scalive.live / "plain")(liveView(callsRef, MountUser("plain"))),
                   (scalive.live / "aspected").withMountAspect(aspect) { (_, _, user) =>
                     liveView(callsRef, user)
                   },
                   (scalive.live / "guarded").withMountAspect(aspect)(
                      liveView(callsRef, MountUser("guarded"))
                    )
                  )
        plainResponse    <- runRequest(routes, "/plain")
        plainBody        <- plainResponse.body.asString
        aspectedResponse <- runRequest(routes, "/aspected")
        aspectedBody     <- aspectedResponse.body.asString
        guardedResponse  <- runRequest(routes, "/guarded")
        guardedBody      <- guardedResponse.body.asString
        calls            <- callsRef.get
      yield assertTrue(
        plainResponse.status == Status.Ok,
        plainBody.contains("plain"),
        aspectedResponse.status == Status.Ok,
        aspectedBody.contains("aspected"),
        guardedResponse.status == Status.Ok,
        guardedBody.contains("guarded"),
        calls == List(
          "mount:plain",
          "aspect:/aspected",
          "mount:aspected",
          "aspect:/guarded",
          "mount:guarded"
        )
      )
    },
    test("live redirect within a LiveSession can join a session-aspected target") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("session-redirect-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Any, MountClaims, MountUser](
                   request =>
                     callsRef.update(_ :+ s"session-disconnected:${request.url.path.encode}") *>
                       ZIO.succeed(
                         MountClaims("session") -> MountUser(s"disconnected:${request.url.path.encode}")
                       ),
                   (claims, request) =>
                     callsRef.update(_ :+ s"session-connected:${claims.value}:${request.url.path.encode}") *>
                       ZIO.succeed(MountUser(s"connected:${request.url.path.encode}"))
                 )
        route = scalive.Live.session("navigation").withMountAspect(aspect)(
                  (scalive.live / "nav" / "a") { (_, _, user: MountUser) => liveView(callsRef, user) },
                  (scalive.live / "nav" / "b") { (_, _, user: MountUser) => liveView(callsRef, user) }
                )
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/nav/a")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/nav/b", redirect = true), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        reply.isEmpty,
        socket.isDefined,
        calls == List(
          "session-disconnected:/nav/a",
          "mount:disconnected:/nav/a",
          "session-connected:session:/nav/b",
          "mount:connected:/nav/b"
        )
      )
    },
    test("live redirect from route-specific mount claims is unauthorized") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("source-route-claims-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   request =>
                     callsRef.update(_ :+ s"route-disconnected:${request.url.path.encode}") *>
                       ZIO.succeed(MountClaims("source") -> MountUser("source")),
                   (claims, request) =>
                     callsRef.update(_ :+ s"route-connected:${claims.value}:${request.url.path.encode}") *>
                       ZIO.succeed(MountUser("connected"))
                 )
        route = scalive.Live.session("navigation")(
                  (scalive.live / "route-claims" / "source").withMountAspect(aspect) {
                    (_, _, user) =>
                    liveView(callsRef, user)
                  },
                  (scalive.live / "route-claims" / "target")(
                    liveView(callsRef, MountUser("target"))
                  )
                )
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/route-claims/source")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        payload <- ZIO
                     .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                     .map(_._2)
                     .mapError(new IllegalArgumentException(_))
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply <- runtime.handleMessage(
                   joinMessage(topic, session, "/route-claims/target", redirect = true),
                   channel
                 )
        socket <- channel.socket(topic)
        calls  <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        payload.hasRouteMountClaims,
        unauthorizedJoin(reply),
        socket.isEmpty,
        calls == List("route-disconnected:/route-claims/source", "mount:source")
      )
    },
    test("live redirect to route-specific mount claims is unauthorized") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("target-route-claims-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   request =>
                     callsRef.update(_ :+ s"route-disconnected:${request.url.path.encode}") *>
                       ZIO.succeed(MountClaims("target") -> MountUser("target")),
                   (claims, request) =>
                     callsRef.update(_ :+ s"route-connected:${claims.value}:${request.url.path.encode}") *>
                       ZIO.succeed(MountUser("connected"))
                 )
        route = scalive.Live.session("navigation")(
                  (scalive.live / "target-claims" / "source")(
                    liveView(callsRef, MountUser("source"))
                  ),
                  (scalive.live / "target-claims" / "target").withMountAspect(aspect) {
                    (_, _, user) =>
                    liveView(callsRef, user)
                  }
                )
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/target-claims/source")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        payload <- ZIO
                     .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                     .map(_._2)
                     .mapError(new IllegalArgumentException(_))
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply <- runtime.handleMessage(
                   joinMessage(topic, session, "/target-claims/target", redirect = true),
                   channel
                 )
        socket <- channel.socket(topic)
        calls  <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        !payload.hasRouteMountClaims,
        unauthorizedJoin(reply),
        socket.isEmpty,
        calls == List("mount:source")
      )
    },
    test("connected mount aspect redirect returns websocket redirect") {
      for
        callsRef    <- Ref.make(List.empty[String])
        tokenConfig  = TokenConfig("redirect-secret", 1.hour)
        redirectUrl <- ZIO.fromEither(URL.decode("/login")).orDie
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   _ => ZIO.succeed(MountClaims("signed") -> MountUser("disconnected")),
                   (_, _) => ZIO.fail(LiveMountFailure.redirectUnsafe(redirectUrl))
                 )
        route = scalive.live.withMountAspect(aspect) { (_, _, user) => liveView(callsRef, user) }
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        reply.exists(message =>
          message.eventType == Protocol.EventRedirect &&
            message.payload == Payload.Redirect("/login", None)
        ),
        socket.isEmpty,
        calls == List("mount:disconnected")
      )
    },
    test("connected mount aspect typed redirect returns websocket redirect") {
      for
        callsRef   <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("typed-redirect-secret", 1.hour)
        login       = (scalive.live / "login").location
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   _ => ZIO.succeed(MountClaims("signed") -> MountUser("disconnected")),
                   (_, _) => ZIO.fail(LiveMountFailure.redirect(login))
                 )
        route = scalive.live.withMountAspect(aspect) { (_, _, user) => liveView(callsRef, user) }
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        reply.exists(message =>
          message.eventType == Protocol.EventRedirect &&
            message.payload == Payload.Redirect("/login", None)
        ),
        socket.isEmpty,
        calls == List("mount:disconnected")
      )
    },
    test("connected mount aspect unauthorized failure returns unauthorized join error") {
      for
        callsRef   <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("unauthorized-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   _ => ZIO.succeed(MountClaims("signed") -> MountUser("disconnected")),
                   (_, _) => ZIO.fail(LiveMountFailure.unauthorized)
                 )
        route = scalive.live.withMountAspect(aspect) { (_, _, user) => liveView(callsRef, user) }
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        joinFailure(reply, JoinErrorReason.Unauthorized),
        socket.isEmpty,
        calls == List("mount:disconnected")
      )
    },
    test("connected mount aspect unauthorized reason returns unauthorized join error") {
      for
        callsRef   <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("forbidden-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   _ => ZIO.succeed(MountClaims("signed") -> MountUser("disconnected")),
                   (_, _) => ZIO.fail(LiveMountFailure.unauthorized("forbidden"))
                 )
        route = scalive.live.withMountAspect(aspect) { (_, _, user) => liveView(callsRef, user) }
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        joinFailure(reply, JoinErrorReason.Unauthorized),
        socket.isEmpty,
        calls == List("mount:disconnected")
      )
    },
    test("connected mount aspect server failure returns stale join error") {
      for
        callsRef   <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("stale-secret", 1.hour)
        aspect = LiveMountAspect.fromRequest[Any, Unit, MountClaims, MountUser](
                   _ => ZIO.succeed(MountClaims("signed") -> MountUser("disconnected")),
                   (_, _) => ZIO.fail(LiveMountFailure.stale("server failure"))
                 )
        route = scalive.live.withMountAspect(aspect) { (_, _, user) => liveView(callsRef, user) }
        runtime = runtimeFor(route, tokenConfig)
        response <- runRequest(runtime.routes, "/")
        body     <- response.body.asString
        session  <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/"), channel)
        socket  <- channel.socket(topic)
        calls   <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        joinFailure(reply, JoinErrorReason.Stale),
        socket.isEmpty,
        calls == List("mount:disconnected")
      )
    }
  )

end LiveMountAspectSpec
