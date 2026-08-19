package scalive

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.*
import zio.test.*

import scalive.protocol.phoenix.{PhoenixRef, RootJoin}

object ZioHttpSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false
  ).toOption.get

  private def run(routes: Routes[Any, Nothing], request: Request): UIO[Response] =
    ZIO.scoped(routes.runZIO(request))

  def spec = suite("ZioHttp root transport")(
    test("disconnected GET creates and mounts a fresh view and emits bootstrap metadata") {
      val factories = AtomicInteger()
      val mounts     = AtomicInteger()

      final class View(number: Int) extends LiveView.Eventless[Int]:
        def mount(ctx: MountContext) = ZIO.succeed {
          mounts.incrementAndGet()
          number
        }
        def view(model: Signal[Int]) = div(model.map(_.toString))

      val application = scalive.Live.router(scalive.live {
        View(factories.incrementAndGet())
      })
      val routes      = ZioHttp.routes(application, config)

      for
        first       <- run(routes, Request.get(URL.root))
        second      <- run(routes, Request.get(URL.root))
        firstBody   <- first.body.asString.orDie
        secondBody  <- second.body.asString.orDie
        session      = attribute(firstBody, "data-phx-session").get
        static       = attribute(firstBody, "data-phx-static").get
        rootId       = attribute(firstBody, "id").get
        sessionClaim <- ZioHttpSecurity.verifySession(config, session)
        staticClaim  <- ZioHttpSecurity.verifyStatic(config, static)
        cookie        = first.headers(Header.SetCookie).map(_.value).find(_.name == "_scalive_csrf")
      yield assertTrue(
        first.status == Status.Ok,
        factories.get() == 2,
        mounts.get() == 2,
        firstBody.contains("<meta name=\"csrf-token\""),
        firstBody.contains("data-phx-main"),
        firstBody.contains(">1</div>"),
        secondBody.contains(">2</div>"),
        sessionClaim == staticClaim,
        sessionClaim.rootId == rootId,
        sessionClaim.routeIndex == 0,
        sessionClaim.canonicalUrl == "/",
        cookie.exists(value =>
          value.isHttpOnly && !value.isSecure && value.path.contains(Path.root) &&
            value.sameSite.contains(Cookie.SameSite.Lax) && value.domain.isEmpty
        )
      )
    },
    test("a disconnected lifecycle failure is logged and becomes 500") {
      object Broken extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.fail(Exception("broken mount"))
        def view(model: Signal[Unit]) = div()

      run(
        ZioHttp.routes(scalive.Live.router(scalive.live(Broken)), config),
        Request.get(URL.root)
      ).map(response =>
        assertTrue(response.status == Status.InternalServerError)
      )
    },
    test("unsupported sessions and application layouts fail during assembly") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div()

      object RoutedView extends LiveView.Routed.Eventless[Unit, Unit]:
        def mount(params: Unit, ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div()

      val sessionApplication = scalive.Live.router(
        scalive.Live.session("unsupported")(scalive.live(View))
      )
      val layoutApplication = scalive.Live.router
        .withLayout(LiveLayout.identity)(scalive.live(View))
      val routedApplication = scalive.Live.router(scalive.live.params(RoutedView))

      val sessionFailure = scala.util.Try(ZioHttp.routes(sessionApplication, config)).failed.toOption
      val layoutFailure  = scala.util.Try(ZioHttp.routes(layoutApplication, config)).failed.toOption
      val routedFailure  = scala.util.Try(ZioHttp.routes(routedApplication, config)).failed.toOption

      assertTrue(
        sessionFailure.exists(_.isInstanceOf[ZioHttp.AssemblyException]),
        layoutFailure.exists(_.isInstanceOf[ZioHttp.AssemblyException]),
        routedFailure.exists(_.isInstanceOf[ZioHttp.AssemblyException])
      )
    },
    test("join admission binds all bootstrap claims before invoking a route factory") {
      val factories = AtomicInteger()
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div()

      val application = scalive.Live.router(scalive.live {
        factories.incrementAndGet()
        View
      })
      val directRoutes = ZioHttp.validate(application)

      for
        csrf   <- ZioHttpSecurity.issueCsrf(config)
        rootId  = "root-id"
        session <- ZioHttpSecurity.issueSession(config, rootId, 0, "/")
        static  <- ZioHttpSecurity.issueStatic(config, rootId, 0, "/")
        join     = RootJoin(
                     url = Some("/"),
                     redirect = None,
                     flash = None,
                     session = session,
                     static = Some(static),
                     params = Map.empty,
                     sticky = false
                   )
        valid <- ZioHttpAdmission
                   .admit(
                     directRoutes,
                     config,
                      Some(csrf.cookieToken),
                     Some(csrf.token),
                     rootExists = false,
                     s"lv:$rootId",
                     join
                   ).either
        invalid <- ZioHttpAdmission
                     .admit(
                       directRoutes,
                       config,
                        Some(csrf.cookieToken),
                       Some(csrf.token),
                       rootExists = false,
                       s"lv:$rootId",
                       join.copy(session = session + "tampered")
                     ).either
      yield assertTrue(
        valid.exists(_.route.index == 0),
        invalid.isLeft,
        factories.get() == 0
      )
    },
    test("canonical page identity accepts absolute joins and rejects path or query changes") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div()

      val application  = scalive.Live.router((scalive.live / "page")(View))
      val directRoutes = ZioHttp.validate(application)
      val pageUrl       = URL.decode("/page?tab=one#discarded").toOption.get

      for
        response <- run(ZioHttp.routes(application, config), Request.get(pageUrl))
        body     <- response.body.asString.orDie
        rootId    = attribute(body, "id").get
        session   = attribute(body, "data-phx-session").get
        static    = attribute(body, "data-phx-static").get
        csrfToken = attribute(body, "content").get
        csrfCookie = response.headers(Header.SetCookie).map(_.value)
                       .find(_.name == "_scalive_csrf").map(_.content).get
        sessionClaims <- ZioHttpSecurity.verifySession(config, session)
        baseJoin = RootJoin(
                     url = Some("https://example.test/page?tab=one#client-fragment"),
                     redirect = None,
                     flash = None,
                     session = session,
                     static = Some(static),
                     params = Map.empty,
                     sticky = false
                   )
        absolute <- ZioHttpAdmission
                      .admit(
                        directRoutes,
                        config,
                        Some(csrfCookie),
                        Some(csrfToken),
                        rootExists = false,
                        s"lv:$rootId",
                        baseJoin
                      ).either
        wrongQuery <- ZioHttpAdmission
                        .admit(
                          directRoutes,
                          config,
                          Some(csrfCookie),
                          Some(csrfToken),
                          rootExists = false,
                          s"lv:$rootId",
                          baseJoin.copy(url = Some("https://example.test/page?tab=two"))
                        ).either
        wrongPath <- ZioHttpAdmission
                       .admit(
                         directRoutes,
                         config,
                         Some(csrfCookie),
                         Some(csrfToken),
                         rootExists = false,
                         s"lv:$rootId",
                         baseJoin.copy(url = Some("https://example.test/other?tab=one"))
                       ).either
      yield assertTrue(
        ZioHttp.canonicalUrl(pageUrl) == "/page?tab=one",
        sessionClaims.canonicalUrl == "/page?tab=one",
        absolute.exists(admitted => ZioHttp.canonicalUrl(admitted.url) == "/page?tab=one"),
        wrongQuery.isLeft,
        wrongPath.isLeft
      )
    },
    test("effective Phoenix join ref falls back to the initial push ref") {
      val one = PhoenixRef.Value("1")
      val two = PhoenixRef.Value("2")

      assertTrue(
        ZioHttp.effectiveJoinRef(PhoenixRef.Null, one).contains(one),
        ZioHttp.effectiveJoinRef(two, one).contains(two),
        ZioHttp.effectiveJoinRef(PhoenixRef.Null, PhoenixRef.Null).isEmpty
      )
    },
    test("connected requests preserve socket metadata but use the admitted page identity") {
      val remote      = java.net.InetAddress.getLoopbackAddress
      val socketUrl   = URL.decode("https://socket.example/live/websocket?vsn=2.0.0").toOption.get
      val admittedUrl = URL.decode("https://page.example/page?tab=one#ignored").toOption.get
      val socketRequest = Request(
        method = Method.GET,
        url = socketUrl,
        headers = Headers(Header.Custom("x-auth", "signed-user")),
        remoteAddress = Some(remote)
      ).addCookie(Cookie.Request("session", "browser-session"))

      val connected = ZioHttp.connectedRequest(socketRequest, admittedUrl)

      assertTrue(
        connected.method == Method.GET,
        connected.url == URL(path = admittedUrl.path, queryParams = admittedUrl.queryParams),
        connected.headers == socketRequest.headers,
        connected.cookie("session").exists(_.content == "browser-session"),
        connected.remoteAddress.contains(remote)
      )
    },
    test("event references must belong to the active channel generation") {
      val joined: PhoenixRef.Value = PhoenixRef.Value("1")
      val push: PhoenixRef.Value   = PhoenixRef.Value("2")
      val stale: PhoenixRef.Value  = PhoenixRef.Value("0")

      assertTrue(
        ZioHttp.correlatedEventRefs(joined, joined, push).contains(joined -> push),
        ZioHttp.correlatedEventRefs(joined, stale, push).isEmpty,
        ZioHttp.correlatedEventRefs(joined, PhoenixRef.Null, push).isEmpty,
        ZioHttp.correlatedEventRefs(joined, joined, PhoenixRef.Null).isEmpty
      )
    },
    test("reloads and concurrent tabs reuse one signed CSRF cookie") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div()

      val routes = ZioHttp.routes(scalive.Live.router(scalive.live(View)), config)

      for
        first     <- run(routes, Request.get(URL.root))
        firstBody <- first.body.asString.orDie
        firstCookie <- ZIO.fromOption(
                         first.headers(Header.SetCookie).map(_.value)
                           .find(_.name == "_scalive_csrf")
                       ).orElseFail(Exception("missing initial CSRF cookie")).orDie
        browserCookie = Cookie.Request(firstCookie.name, firstCookie.content)
        repeated <- run(routes, Request.get(URL.root).addCookie(browserCookie)).zipPar(
                      run(routes, Request.get(URL.root).addCookie(browserCookie))
                    )
        (reload, tab) = repeated
        reloadBody   <- reload.body.asString.orDie
        tabBody      <- tab.body.asString.orDie
        firstToken    = attribute(firstBody, "content").get
        reloadToken   = attribute(reloadBody, "content").get
        tabToken      = attribute(tabBody, "content").get
        firstValid   <- ZioHttpSecurity.verifyCsrf(config, firstToken, firstCookie.content).either
        reloadValid  <- ZioHttpSecurity.verifyCsrf(config, reloadToken, firstCookie.content).either
        tabValid     <- ZioHttpSecurity.verifyCsrf(config, tabToken, firstCookie.content).either
        invalid      <- run(
                          routes,
                          Request.get(URL.root).addCookie(
                            Cookie.Request(firstCookie.name, firstCookie.content + "tampered")
                          )
                        )
        invalidBody  <- invalid.body.asString.orDie
        replacement <- ZIO.fromOption(
                         invalid.headers(Header.SetCookie).map(_.value)
                           .find(_.name == "_scalive_csrf")
                       ).orElseFail(Exception("missing replacement CSRF cookie")).orDie
        replacementValid <- ZioHttpSecurity
                              .verifyCsrf(
                                config,
                                attribute(invalidBody, "content").get,
                                replacement.content
                              ).either
      yield assertTrue(
        firstValid.isRight,
        reloadValid.isRight,
        tabValid.isRight,
        reload.headers(Header.SetCookie).isEmpty,
        tab.headers(Header.SetCookie).isEmpty,
        replacement.content != firstCookie.content,
        replacementValid.isRight
      )
    }
  )

  private def attribute(html: String, name: String): Option[String] =
    val pattern = (java.util.regex.Pattern.quote(name) + "=\"([^\"]+)\"").r
    pattern.findFirstMatchIn(html).map(_.group(1))
end ZioHttpSpec
