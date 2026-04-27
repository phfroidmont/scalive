package scalive

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Protocol

object LiveMountAspectSpec extends ZIOSpecDefault:

  private final case class MountClaims(value: String) derives JsonCodec
  private final case class MountUser(name: String)

  private final case class FirstClaims(value: String) derives JsonCodec
  private final case class SecondClaims(value: String) derives JsonCodec

  private val identityLayout: HtmlElement[?] => HtmlElement[?] = element => element

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
      def mount =
        callsRef.update(_ :+ s"mount:${user.name}").as(user.name)

      def handleMessage(model: String) = _ => ZIO.succeed(model)

      def render(model: String): HtmlElement[Unit] =
        div(model)

      def subscriptions(model: String) = ZStream.empty

  private def runtimeFor(
    route: LiveRoute[Any, Unit, MountUser, Unit, String],
    tokenConfig: TokenConfig
  ): LiveRoutesRuntime[Any] =
    new LiveRoutesRuntime[Any](
      identityLayout,
      List(route),
      LiveRoutes.websocketMountCodec("live"),
      tokenConfig
    )

  private def joinMessage(topic: String, session: String, url: String): WebSocketMessage =
    WebSocketMessage(
      joinRef = Some(1),
      messageRef = Some(1),
      topic = topic,
      eventType = Protocol.EventJoin,
      payload = Payload.Join(
        url = Some(url),
        redirect = None,
        session = session,
        static = None,
        params = None,
        flash = None,
        sticky = false
      )
    )

  def spec = suite("LiveMountAspectSpec")(
    test("runs route mount aspect before disconnected and connected mount") {
      for
        callsRef <- Ref.make(List.empty[String])
        tokenConfig = TokenConfig("mount-secret", 1.hour)
        aspect = LiveMountAspect.make[Any, MountClaims, MountUser](
                   request =>
                     callsRef.update(_ :+ s"disconnected:${request.url.path.encode}") *>
                       ZIO.succeed(MountClaims("signed") -> MountUser("disconnected")),
                   (claims, url) =>
                     callsRef.update(_ :+ s"connected:${claims.value}:${url.path.encode}") *>
                       ZIO.succeed(MountUser(s"connected:${claims.value}"))
                 )
        route = ((Method.GET / Root) @@ aspect) -> liveHandler {
                  (_: Unit, _: Request, user: MountUser) => liveView(callsRef, user)
                }
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
    test("composes aspects in order and reloads tuple claims") {
      for
        callsRef <- Ref.make(List.empty[String])
        first = LiveMountAspect.make[Any, FirstClaims, String](
                  _ => callsRef.update(_ :+ "disconnected:first").as(FirstClaims("one") -> "first"),
                  (claims, _) => callsRef.update(_ :+ s"connected:first:${claims.value}").as("first-connected")
                )
        second = LiveMountAspect.make[Any, SecondClaims, String](
                   _ => callsRef.update(_ :+ "disconnected:second").as(SecondClaims("two") -> "second"),
                   (claims, _) => callsRef.update(_ :+ s"connected:second:${claims.value}").as("second-connected")
                 )
        composed = (first ++ second).map { case (left, right) => s"$left/$right" }
        claimsAndContext <- composed.runtime.runDisconnected(Request.get(URL.root))
        connectedContext <- composed.runtime.runConnected(Some(claimsAndContext._1), URL.root)
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
    test("connected mount aspect redirect returns websocket redirect") {
      for
        callsRef    <- Ref.make(List.empty[String])
        tokenConfig  = TokenConfig("redirect-secret", 1.hour)
        redirectUrl <- ZIO.fromEither(URL.decode("/login")).orDie
        aspect = LiveMountAspect.make[Any, MountClaims, MountUser](
                   _ => ZIO.succeed(MountClaims("signed") -> MountUser("disconnected")),
                   (_, _) => ZIO.fail(Response.redirect(redirectUrl))
                 )
        route = ((Method.GET / Root) @@ aspect) -> liveHandler {
                  (_: Unit, _: Request, user: MountUser) => liveView(callsRef, user)
                }
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
      yield assertTrue(
        response.status == Status.Ok,
        reply.exists(message =>
          message.eventType == Protocol.EventRedirect &&
            message.payload == Payload.Redirect("/login", None)
        ),
        socket.isEmpty
      )
    }
  )

end LiveMountAspectSpec
