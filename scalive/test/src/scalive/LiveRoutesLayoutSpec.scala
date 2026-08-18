package scalive

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.JoinErrorReason
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Protocol
import scalive.WebSocketMessage.ReplyStatus

object LiveRoutesLayoutSpec extends ZIOSpecDefault:

  final private case class UserClaims(value: String) derives JsonCodec
  final private case class OrgClaims(value: String) derives JsonCodec
  final private case class User(name: String)
  final private case class Org(name: String)

  private def view(text: String) = new LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ZIO.unit
    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.unit
    override def view(model: Signal[Unit]): HtmlElement[Unit] = div(idAttr := "view", text)

  private def runtimeFor(route: LiveRouteFragment[Any, Any], tokenConfig: TokenConfig) =
    new LiveRoutesRuntime[Any](
      Nil,
      LiveRootLayout.identity,
      route.liveRoutes.asInstanceOf[List[LiveRoute[Any, ?, Any, ?, ?, ?]]],
      PathCodec.empty / "live",
      tokenConfig
    )

  private def runRequest(routes: Routes[Any, Nothing], path: String) =
    URL.decode(path) match
      case Left(error) => ZIO.die(error)
      case Right(url)  => ZIO.scoped(routes.runZIO(Request.get(url)))

  private def extractAttr(body: String, attr: String): Task[String] =
    val pattern = s"""$attr="([^"]+)""".r
    ZIO
      .fromOption(pattern.findFirstMatchIn(body).map(_.group(1))).orElseFail(
        new NoSuchElementException(attr)
      )

  private def joinMessage(
    topic: String,
    session: String,
    url: String,
    redirect: Boolean = false,
    params: Option[Map[String, Json]] = None
  ) =
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
        params = params,
        flash = None,
        sticky = false
      )
    )

  private def containsValue(diff: Diff, value: String): Boolean =
    diff match
      case Diff.Tag(static, dynamic, _, _, _, components, _, _) =>
        static.exists(_.contains(value)) ||
          dynamic.exists(d => containsValue(d.diff, value)) ||
          components.values.exists(containsValue(_, value))
      case Diff.Comprehension(_, entries, _, _, _) =>
        entries.exists {
          case Diff.Dynamic(_, diff)       => containsValue(diff, value)
          case Diff.IndexMerge(_, _, diff) => containsValue(diff, value)
          case _: Diff.IndexChange         => false
        }
      case Diff.Value(text) => text.contains(value)
      case _                => false

  private def unauthorizedJoin(reply: Option[WebSocketMessage]): Boolean =
    reply.exists(
      _.payload == Payload.Reply(
        ReplyStatus.Error,
        LiveResponse.JoinError(JoinErrorReason.Unauthorized)
      )
    )

  override def spec = suite("LiveRoutesLayoutSpec")(
    test("live layouts compose and receive typed route/session context") {
      for
        tokenConfig <- ZIO.succeed(TokenConfig("layout-secret", 1.hour))
        userAspect = LiveMountAspect.fromRequest[Any, Any, UserClaims, User](
                       _ => ZIO.succeed(UserClaims("user") -> User("disconnected-user")),
                       (_, _) => ZIO.succeed(User("connected-user"))
                     )
        orgAspect = LiveMountAspect.make[Any, Int, User, OrgClaims, Org](
                      (request, user) =>
                        ZIO.succeed(
                          OrgClaims(request.params.toString) -> Org(
                            s"${user.name}:org-${request.params}"
                          )
                        ),
                      (claims, _, user) => ZIO.succeed(Org(s"${user.name}:org-${claims.value}"))
                    )
        globalLayout =
          LiveLayout[Any, Any]((content, _) => div(idAttr := "global-layout", "global", content))
        sessionLayout = LiveLayout[Any, User]((content, ctx) =>
                          div(
                            idAttr := "session-layout",
                            ctx.currentUrl.map(_ => s"session:${ctx.context.name}"),
                            content
                          )
                        )
        routeLayout = LiveLayout[Int, (User, Org)]((content, ctx) =>
                        val (user, org) = ctx.context
                        div(
                          idAttr := "route-layout",
                          ctx.params.map(id => s"route:$id:${user.name}:${org.name}"),
                          content
                        )
                      )
        route =
          scalive.Live.session("layout").withMountAspect(userAspect).withLayout(sessionLayout)(
            (scalive.live / "orgs" / PathCodec.int("id"))
              .withMountAspect(orgAspect)
              .withLayout(routeLayout) {
              (id, _, user: User, org: Org) => view(s"view:$id:${user.name}:${org.name}")
            }
          )
        routes =
          scalive.Live.router.withTokenConfig(tokenConfig).withLayout(globalLayout)(route)
        response   <- runRequest(routes, "/orgs/42")
        body       <- response.body.asString
        session    <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic   = s"lv:$liveViewId"
        runtime = runtimeFor(route, tokenConfig)
        channel <- LiveChannel.make(tokenConfig)
        reply   <- runtime.handleMessage(joinMessage(topic, session, "/orgs/42"), channel)
        socket  <- channel.socket(topic).some
        init    <- socket.outbox.take(1).runHead.some
        initDiff = init._1 match
                     case Payload.Reply(_, LiveResponse.InitDiff(diff)) => Some(diff)
                     case _                                             => None
        patch <- channel.livePatch(
                   topic,
                   "/orgs/43",
                   WebSocketMessage.Meta(Some(1), Some(2), topic, Protocol.EventLivePatch)
                 )
        patchDiff = patch match
                      case Payload.Reply(_, LiveResponse.Diff(diff)) => Some(diff)
                      case _                                         => None
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("global"),
        body.contains("session:disconnected-user"),
        body.contains("route:42:disconnected-user:disconnected-user:org-42"),
        body.contains("view:42:disconnected-user:disconnected-user:org-42"),
        reply.isEmpty,
        initDiff.exists(containsValue(_, "session:connected-user")),
        initDiff.exists(containsValue(_, "route:42:connected-user:connected-user:org-42")),
        initDiff.exists(containsValue(_, "view:42:connected-user:connected-user:org-42")),
        patchDiff.exists(containsValue(_, "route:43:connected-user:connected-user:org-42"))
      )
    },
    test("signal-backed layout assets participate in static tracking") {
      val tokenConfig = TokenConfig("layout-static-secret", 1.hour)
      val layout = LiveLayout[Any, Any]((content, _) =>
        div(
          scriptTag(phx.trackStatic := true, src := "/layout.js"),
          content
        )
      )
      val liveView = new LiveView[Unit, Boolean]:
        def mount(ctx: MountContext) = ZIO.succeed(ctx.staticChanged)

        def handleMessage(model: Boolean, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        override def view(model: Signal[Boolean]): HtmlElement[Unit] =
          div(idAttr := "static-changed", model.map(_.toString))

      val route = (scalive.live / "tracked-layout").withLayout[Any](layout)(liveView)
      val runtime = runtimeFor(route, tokenConfig)

      for
        response   <- runRequest(runtime.routes, "/tracked-layout")
        body       <- response.body.asString
        session    <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        _ <- runtime.handleMessage(
               joinMessage(
                 topic,
                 session,
                 "/tracked-layout",
                 params = Some(
                   Map("_track_static" -> Json.Arr(Json.Str("/different.js")))
                 )
               ),
               channel
             )
        socket <- channel.socket(topic).some
        init   <- socket.outbox.take(1).runHead.some
        initDiff = init._1 match
                     case Payload.Reply(_, LiveResponse.InitDiff(diff)) => Some(diff)
                     case _                                             => None
      yield assertTrue(
        body.contains("/layout.js"),
        initDiff.exists(containsValue(_, "true"))
      )
    },
    test("root layout key changes reject websocket live navigation") {
      val tokenConfig = TokenConfig("root-layout-secret", 1.hour)
      val leftRoot    =
        LiveRootLayout("left-root")((content, _, _) => div(idAttr := "left-root", content))
      val rightRoot =
        LiveRootLayout("right-root")((content, _, _) => div(idAttr := "right-root", content))
      val route = scalive.Live.session("navigation")(
        (scalive.live / "left").withRootLayout(leftRoot)(view("left")),
        (scalive.live / "right").withRootLayout(rightRoot)(view("right"))
      )
      val runtime = runtimeFor(route, tokenConfig)

      for
        response   <- runRequest(runtime.routes, "/left")
        body       <- response.body.asString
        session    <- extractAttr(body, "data-phx-session")
        liveViewId <- ZIO
                        .fromEither(LiveSessionPayload.verify(tokenConfig, session))
                        .map(_._1)
                        .mapError(new IllegalArgumentException(_))
        topic = s"lv:$liveViewId"
        channel <- LiveChannel.make(tokenConfig)
        reply   <-
          runtime.handleMessage(joinMessage(topic, session, "/right", redirect = true), channel)
        socket <- channel.socket(topic)
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("left-root"),
        unauthorizedJoin(reply),
        socket.isEmpty
      )
    }
  )
end LiveRoutesLayoutSpec
