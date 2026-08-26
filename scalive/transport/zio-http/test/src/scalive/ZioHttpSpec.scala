package scalive

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.protocol.phoenix.{PhoenixRef, PhoenixRenderedEncoder, RootJoin}
import scalive.render.{BindingId, RenderDelta}
import scalive.runtime.connection.{ConnectionConfig, ConnectionOutput, ConnectionSupervisor, RootConnection, RootConnectionMetadata}
import scalive.runtime.contracts.*
import scalive.runtime.kernel.{ClientEffect, SessionEffects}

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
    test("disconnected bootstrap wraps and preserves the rendered root element") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div(idAttr := "rendered-root", span("content"))

      for
        response <- run(
                      ZioHttp.routes(scalive.Live.router(scalive.live(View)), config),
                      Request.get(URL.root)
                    )
        body   <- response.body.asString.orDie
        rootId <- ZIO.fromOption(attribute(body, "id"))
      yield assertTrue(
        rootId != "rendered-root",
        body.contains("data-phx-main"),
        body.contains("<div id=\"rendered-root\"><span>content</span></div>")
      )
    },
    test("checked POST forms receive the browser-bound CSRF token") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) =
          scalive.Form.http(FormAction.from(Method.POST / "submit"))(
            input(nameAttr := "profile[name]")
          )

      for
        response <- run(
                      ZioHttp.routes(scalive.Live.router(scalive.live(View)), config),
                      Request.get(URL.root)
                    )
        body     <- response.body.asString.orDie
        csrf <- ZIO
                  .fromOption(attribute(body, "content"))
                  .orElseFail(new NoSuchElementException("csrf meta content"))
      yield assertTrue(
        body.contains(s"name=\"_csrf_token\" value=\"$csrf\""),
        !body.contains("data-scalive-csrf")
      )
    },
    test("disconnected GET recursively mounts nested lifecycles once with signed child containers") {
      val childMounts = AtomicInteger()
      val grandMounts = AtomicInteger()

      object Grandchild extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.succeed(grandMounts.incrementAndGet()).unit
        def view(model: Signal[Unit]) = span(idAttr := "grand-content", "grand")

      object Child extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.succeed(childMounts.incrementAndGet()).unit
        def view(model: Signal[Unit]) =
          sectionTag(idAttr := "child-content", "child", liveView("grandchild", Grandchild))

      object Parent extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = mainTag(liveView("child", Child))

      for
        response <- run(
                      ZioHttp.routes(scalive.Live.router(scalive.live(Parent)), config),
                      Request.get(URL.root)
                    )
        body <- response.body.asString.orDie
        childToken <- ZIO
                        .fromOption(nestedAttribute(body, "child", "data-phx-session"))
                        .orElseFail(AssertionError("missing child session token"))
        childClaims <- ZioHttpSecurity.verifyNestedJoin(config, childToken)
        rootToken <- ZIO
                       .fromOption(attribute(body, "data-phx-session"))
                       .orElseFail(AssertionError("missing root session token"))
        rootClaims <- ZioHttpSecurity.verifySession(config, rootToken)
        grandToken <- ZIO
                        .fromOption(nestedAttribute(body, "grandchild", "data-phx-session"))
                        .orElseFail(AssertionError("missing grandchild session token"))
        grandClaims <- ZioHttpSecurity.verifyNestedJoin(config, grandToken)
      yield assertTrue(
        response.status == Status.Ok,
        childMounts.get() == 1,
        grandMounts.get() == 1,
        body.contains("id=\"child-content\""),
        body.contains("id=\"grand-content\""),
        nestedAttribute(body, "child", "data-phx-parent-id").exists(_.nonEmpty),
        nestedAttribute(body, "grandchild", "data-phx-parent-id").contains("child"),
        childClaims.parentLifecycle == LifecycleId(rootClaims.lifecycle),
        childClaims.childLifecycle.contains(grandClaims.parentLifecycle),
        childClaims.childLifecycle.exists(lifecycle =>
          rootClaims.nestedLifecycles.get("child").contains(lifecycle.value)
        ),
        grandClaims.childLifecycle.nonEmpty,
        childClaims.topic == NestedTopic("lv:child"),
        grandClaims.topic == NestedTopic("lv:grandchild")
      )
    },
    test("a successful disconnected render consumes signed flash into HTML and root claims") {
      val notice = FlashKind("notice")
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div(flash(notice)(message => span(message)))

      for
        token <- ZioHttpSecurity
                   .issueFlash(config, Map("notice" -> "saved")).someOrFail(
                     AssertionError("flash token was not issued")
                   )
        request = Request.get(URL.root).addCookie(Cookie.Request("__phoenix_flash__", token))
        response <- run(ZioHttp.routes(scalive.Live.router(scalive.live(View)), config), request)
        body     <- response.body.asString.orDie
        claims  <- ZioHttpSecurity.verifySession(
                     config,
                     attribute(body, "data-phx-session").get
                   )
        consumed = response.headers(Header.SetCookie).map(_.value)
          .find(_.name == "__phoenix_flash__")
      yield assertTrue(
        body.contains("<span>saved</span>"),
        claims.initialFlash == Map("notice" -> "saved"),
        consumed.exists(cookie =>
          cookie.content.isEmpty && cookie.maxAge.contains(zio.Duration.Zero) &&
            cookie.isHttpOnly && cookie.sameSite.contains(Cookie.SameSite.Lax)
        )
      )
    },
    test("disconnected component flash changes reproject root HTML and signed claims") {
      val oldKind = FlashKind("old")
      val newKind = FlashKind("component")
      val mounts  = AtomicInteger()
      val definition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) =
          ZIO.succeed(mounts.incrementAndGet()).unit *>
            ctx.flash.clear(oldKind) *>
            ctx.flash.put(newKind, "component-final")
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) = div()
      val instance = component(definition, "flash-mutator")
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) =
          div(
            flash(oldKind)(message => span(idAttr := "old-flash", message)),
            flash(newKind)(message =>
              sectionTag(
                idAttr := "new-flash",
                message,
                scriptTag(phx.trackStatic := true, src := "/assets/final-flash.js")
              )
            ),
            instance.render(())
          )

      for
        token <- ZioHttpSecurity
                   .issueFlash(config, Map(oldKind.value -> "remove-me")).someOrFail(
                     AssertionError("flash token was not issued")
                   )
        request = Request.get(URL.root).addCookie(Cookie.Request("__phoenix_flash__", token))
        response <- run(ZioHttp.routes(scalive.Live.router(scalive.live(View)), config), request)
        body     <- response.body.asString.orDie
        claims  <- ZioHttpSecurity.verifySession(
                     config,
                     attribute(body, "data-phx-session").get
                   )
      yield assertTrue(
        mounts.get() == 1,
        !body.contains("old-flash"),
        !body.contains("remove-me"),
        body.contains("id=\"new-flash\""),
        body.contains("component-final"),
        claims.initialFlash == Map(newKind.value -> "component-final"),
        claims.trackedStatics == Vector("/assets/final-flash.js")
      )
    },
    test("signed bootstrap captures and compares tracked static assets") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) =
          div(
            scriptTag(phx.trackStatic := true, src := "/assets/app-123.js?vsn=d"),
            linkTag(phx.trackStatic := true, href := "/assets/app-123.css")
          )

      for
        response <- run(
                      ZioHttp.routes(scalive.Live.router(scalive.live(View)), config),
                      Request.get(URL.root)
                    )
        body   <- response.body.asString.orDie
        claims <- ZioHttpSecurity.verifySession(
                    config,
                    attribute(body, "data-phx-session").get
                  )
        staticClaims <- ZioHttpSecurity.verifyStatic(
                          config,
                          attribute(body, "data-phx-static").get
                        )
        matching = ZioHttp.clientTrackedStatics(
                     Map(
                       "_track_static" -> Json.Arr(
                         Json.Str("https://example.test/assets/app-123.js"),
                         Json.Str("/assets/app-123.css?cache=1")
                       )
                     )
                   )
      yield assertTrue(
        claims == staticClaims,
        claims.trackedStatics == Vector(
          "/assets/app-123.js?vsn=d",
          "/assets/app-123.css"
        ),
        !ZioHttp.staticChanged(matching, claims.trackedStatics, URL.root),
        ZioHttp.staticChanged(Some(Vector("/assets/other.js")), claims.trackedStatics, URL.root),
        !ZioHttp.staticChanged(None, claims.trackedStatics, URL.root),
        !ZioHttp.staticChanged(Some(Vector.empty), claims.trackedStatics, URL.root),
        !ZioHttp.staticChanged(
          Some(Vector("https://example.test/pages/assets/app%20name.js")),
          Vector("assets/app name.js"),
          URL.decode("/pages/").toOption.get
        ),
        ZioHttp.staticChanged(
          Some(Vector("/assets/a%2Fb.js")),
          Vector("/assets/a/b.js"),
          URL.root
        ),
        ZioHttp.clientTrackedStatics(Map("_track_static" -> Json.Arr(Json.Num(1)))).isEmpty
      )
    },
    test("disconnected GET mounts components and emits CID-addressable component HTML") {
      val mounts = AtomicInteger()
      val definition = new LiveComponent.Eventless[String, String]:
        def mount(props: String, ctx: MountContext) = ZIO.succeed {
          mounts.incrementAndGet()
          props
        }
        def view(
          props: Signal[String],
          model: Signal[String],
          self: ComponentRef[Nothing]
        ) =
          sectionTag(
            button(phx.target(self), model),
            scriptTag(phx.trackStatic := true, src := "/assets/component.js")
          )
      val instance = component(definition, "disconnected-component")
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div(instance.render("component model"))

      for
        response <- run(
                      ZioHttp.routes(scalive.Live.router(scalive.live(View)), config),
                      Request.get(URL.root)
                    )
        body   <- response.body.asString.orDie
        claims <- ZioHttpSecurity.verifySession(
                    config,
                    attribute(body, "data-phx-session").get
                  )
      yield assertTrue(
        response.status == Status.Ok,
        mounts.get() == 1,
        body.contains("<section data-phx-component=\"1\">"),
        body.contains("phx-target=\"1\""),
        body.contains("component model"),
        claims.trackedStatics == Vector("/assets/component.js")
      )
    },
    test("connected CID ingress releases the projection gate before exact component inspection") {
      ZIO.scoped {
        val componentEvent = BrowserToServerEvent[String]("component:event")
        val removeEvent    = BrowserToServerEvent[Boolean]("root:show-second")
        for
          calls <- Ref.make(Vector.empty[String])
          definition = new LiveComponent[String, String, String]:
                         override val hooks = ComponentLiveHooks.empty[String, String, String]
                           .onBrowserEvent(componentEvent) { (props, model, value, _) =>
                             calls.update(_ :+ props).as(s"$model:$value")
                           }
                         def mount(props: String, ctx: MountContext) = ZIO.succeed(props)
                         def handleMessage(
                           props: String,
                           model: String,
                           ctx: MessageContext
                         ): String => Task[String] = _ => ZIO.succeed(model)
                         def view(
                           props: Signal[String],
                           model: Signal[String],
                           self: ComponentRef[String]
                         ) = button(phx.target(self), model)
          first  = component(definition, "cid-first")
          second = component(definition, "cid-second")
          view = new LiveView[Nothing, Boolean]:
                   override val hooks = LiveHooks.empty[Nothing, Boolean]
                     .onBrowserEvent(removeEvent)((_, show, _) => ZIO.succeed(show))
                   def mount(ctx: MountContext) = ZIO.succeed(true)
                   def handleMessage(
                     model: Boolean,
                     ctx: MessageContext
                   ): Nothing => Task[Boolean] = identity
                   def view(model: Signal[Boolean]) =
                     div(first.render("first"), model.when(span(second.render("second"))))
          connectionConfig = ConnectionConfig.make(8, 8, 8, 8, 8).toOption.get
          metadata = RootConnectionMetadata(
                       staticChanged = false,
                       connectParams = Map.empty[String, Json]
                     )
          firstSink  <- Queue.bounded[ConnectionOutput](8)
          secondSink <- Queue.bounded[ConnectionOutput](8)
          firstConnection <- RootConnection.start(
                               connectionConfig,
                               metadata,
                               view,
                               firstSink.offer(_).unit
                             )
          secondConnection <- RootConnection.start(
                                connectionConfig,
                                metadata,
                                view,
                                secondSink.offer(_).unit
                              )
          firstJoined  <- firstSink.take
          secondJoined <- secondSink.take
          firstTree = firstJoined match
                        case ConnectionOutput.Joined(RenderDelta.Replace(tree), _) => tree
                        case other => throw AssertionError(s"unexpected first join output: $other")
          secondTree = secondJoined match
                         case ConnectionOutput.Joined(RenderDelta.Replace(tree), _) => tree
                         case other => throw AssertionError(s"unexpected second join output: $other")
          firstProjection  <- ZIO.fromEither(PhoenixRenderedEncoder.initial(firstTree))
          secondProjection <- ZIO.fromEither(PhoenixRenderedEncoder.initial(secondTree))
          html             <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(firstTree))
          firstState       <- Ref.make(Option(firstProjection._1))
          secondState      <- Ref.make(Option(secondProjection._1))
          firstGate        <- Semaphore.make(1L)
          secondGate       <- Semaphore.make(1L)
          target <- ZioHttp
                      .resolveComponentCid(
                        firstState,
                        firstGate,
                        2L,
                        firstConnection.componentForToken
                      )
                      .someOrFail(AssertionError("CID 2 did not resolve"))
          secondTarget <- ZioHttp.resolveComponentCid(
                            secondState,
                            secondGate,
                            2L,
                            secondConnection.componentForToken
                          )
          unknown <- ZioHttp.resolveComponentCid(
                       firstState,
                       firstGate,
                       999L,
                       firstConnection.componentForToken
                     )
          isolated <- ZioHttp.resolveComponentCid(
                         secondState,
                         secondGate,
                         2L,
                         firstConnection.componentForToken
                       )
          componentCommand = CommandId.fresh().toOption.get
          _ <- firstConnection.offerComponentNamedEvent(
                 componentCommand,
                 target,
                 BindingId.fromEncoded("not-a-binding"),
                 BindingPayload.Params(Map.empty),
                 componentEvent.value,
                 "\"handled\""
               )
          componentReply <- firstSink.take
          routedCalls    <- calls.get
          componentReplied = componentReply match
                               case ConnectionOutput.Reply(`componentCommand`, delta, _) =>
                                 delta != RenderDelta.Empty
                               case _ => false
          removeCommand   = CommandId.fresh().toOption.get
          _ <- firstConnection.offerNamedEvent(
                 removeCommand,
                 BindingId.fromEncoded("not-a-binding"),
                 BindingPayload.Params(Map.empty),
                 removeEvent.value,
                 "false"
               )
          removalReply <- firstSink.take
          removalDelta = removalReply match
                           case ConnectionOutput.Reply(`removeCommand`, delta, _) => delta
                           case other => throw AssertionError(s"unexpected removal output: $other")
          removedProjection <- ZIO.fromEither(
                                 PhoenixRenderedEncoder.update(firstProjection._1, removalDelta)
                               )
          inspectionDelayed <- Promise.make[Nothing, Unit]
          allowInspection   <- Promise.make[Nothing, Unit]
          delayedLookup <- ZioHttp
                             .resolveComponentCid(
                               firstState,
                               firstGate,
                               2L,
                               token =>
                                 inspectionDelayed.succeed(()).unit *>
                                   allowInspection.await *>
                                   firstConnection.componentForToken(token)
                             ).fork
          _ <- inspectionDelayed.await
          projectionCompleted <- firstGate
                                   .withPermit(firstState.set(Some(removedProjection._1)))
                                   .timeout(zio.Duration.fromSeconds(1))
          _       <- allowInspection.succeed(())
          retired <- delayedLookup.join
        yield assertTrue(
          html._2.contains("phx-target=\"2\""),
          componentReplied,
          routedCalls == Vector("second"),
          secondTarget.nonEmpty,
          unknown.isEmpty,
          isolated.isEmpty,
          projectionCompleted.nonEmpty,
          retired.isEmpty
        )
      }
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
    test("an ordinary connected mount failure is not classified as stale") {
      val joinRef = PhoenixRef.Value("1")
      val ref     = PhoenixRef.Value("2")
      val error   = ZioHttp.joinFailureEnvelope(joinRef, ref, "lv:root", Exception("broken mount"))

      assertTrue(
        error == scalive.protocol.phoenix.PhoenixEnvelope(
          joinRef,
          ref,
          "lv:root",
          "phx_reply",
          Json.Obj("status" -> Json.Str("error"), "response" -> Json.Obj.empty)
        )
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
        route    = directRoutes.head
        session <- ZioHttpSecurity.issueSession(
                     config,
                     rootId,
                     LifecycleId(1L),
                     0,
                     "/",
                     route.routeIdentity,
                     route.sessionName,
                     "scalive:identity-root"
                   )
        static  <- ZioHttpSecurity.issueStatic(
                     config,
                     rootId,
                     LifecycleId(1L),
                     0,
                     "/",
                     route.routeIdentity,
                     route.sessionName,
                     "scalive:identity-root"
                   )
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
        missing <- ZioHttpAdmission
                     .admit(
                       directRoutes,
                       config,
                       Some(csrf.cookieToken),
                       Some(csrf.token),
                       rootExists = false,
                       s"lv:$rootId",
                       join.copy(static = None)
                     ).either
        redirect <- ZioHttpAdmission
                      .admit(
                        directRoutes,
                        config,
                        Some(csrf.cookieToken),
                        Some(csrf.token),
                        rootExists = false,
                        s"lv:$rootId",
                        join.copy(redirect = Some("/next"))
                      ).either
      yield assertTrue(
        valid.exists(_.route.index == 0),
        invalid.isLeft,
        missing.isLeft,
        redirect.isLeft,
        factories.get() == 0
      )
    },
    test("root admission accepts same-route URL changes and rejects a different route") {
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
        patchedQuery <- ZioHttpAdmission
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
        patchedQuery.exists(admitted => ZioHttp.canonicalUrl(admitted.url) == "/page?tab=two"),
        wrongPath.isLeft
      )
    },
    test("redirect admission stays in the registered session and rejects route mount claims") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div()
      val routeAspect = LiveMountAspect.fromRequest[Any, Unit, String, Unit](
        _ => ZIO.succeed("route-claim" -> ()),
        (_, _) => ZIO.unit
      )
      val application = scalive.Live.router(
        scalive.live / "a" -> View,
        scalive.live / "b" -> View,
        (scalive.live / "guard").withMountAspect(routeAspect)(View),
        scalive.Live.session("other")(scalive.live / "c" -> View)
      )
      val catalog = ZioHttp.validate(application)
      val source  = catalog.find(_.matches(URL.decode("/a").toOption.get)).get

      for
        csrf  <- ZioHttpSecurity.issueCsrf(config)
        token <- ZioHttpSecurity.issueSession(
                   config,
                   "root-id",
                   LifecycleId(1L),
                   source.index,
                   "/a",
                   source.routeIdentity,
                   source.sessionName,
                   "shared-root"
                 )
        static <- ZioHttpSecurity.issueStatic(
                     config,
                     "root-id",
                     LifecycleId(1L),
                     source.index,
                    "/a",
                    source.routeIdentity,
                    source.sessionName,
                    "shared-root"
                  )
        registered <- ZioHttpSecurity.verifySession(config, token)
        join = RootJoin(
                 url = None,
                 redirect = Some("/b"),
                 flash = None,
                 session = token,
                 static = Some(static),
                 params = Map("_mounts" -> Json.Num(1)),
                 sticky = false
               )
        valid <- ZioHttpAdmission
                   .admitRedirect(
                     catalog,
                     config,
                     Some(csrf.cookieToken),
                     Some(csrf.token),
                     registered,
                     "lv:root-id",
                     join
                   ).either
        guarded <- ZioHttpAdmission
                     .admitRedirect(
                       catalog,
                       config,
                       Some(csrf.cookieToken),
                       Some(csrf.token),
                       registered,
                       "lv:root-id",
                       join.copy(redirect = Some("/guard"))
                     ).either
        other <- ZioHttpAdmission
                   .admitRedirect(
                     catalog,
                     config,
                     Some(csrf.cookieToken),
                     Some(csrf.token),
                     registered,
                     "lv:root-id",
                     join.copy(redirect = Some("/c"))
                   ).either
      yield assertTrue(
        valid.exists(_.route.matches(URL.decode("/b").toOption.get)),
        guarded.left.exists(_.contains("destination route mount claims")),
        other.left.exists(_.contains("session identity differs"))
      )
    },
    test("effective Phoenix join ref falls back to the initial push ref") {
      val one = PhoenixRef.Value("1")
      val two = PhoenixRef.Value("2")

      assertTrue(
        ZioHttp.DisconnectCloseCode == 1001,
        ZioHttp.effectiveJoinRef(PhoenixRef.Null, one).contains(one),
        ZioHttp.effectiveJoinRef(two, one).contains(two),
        ZioHttp.effectiveJoinRef(PhoenixRef.Null, PhoenixRef.Null).isEmpty
      )
    },
    test("page titles and ordered client events use Phoenix diff fields") {
      val rendered = Json.Obj("s" -> Json.Arr(Json.Str("<div></div>")))
      val effects = SessionEffects(
        pageTitle = Some("Dashboard"),
        clientEvents = Vector(
          ClientEffect("ready", Json.Obj("count" -> Json.Num(1))),
          ClientEffect("js:exec", Json.Obj("cmd" -> Json.Str("[]")))
        )
      )

      assertTrue(
        ZioHttp.addEffects(rendered, effects) == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div></div>")),
          "t" -> Json.Str("Dashboard"),
          "e" -> Json.Arr(
            Json.Arr(Json.Str("ready"), Json.Obj("count" -> Json.Num(1))),
            Json.Arr(Json.Str("js:exec"), Json.Obj("cmd" -> Json.Str("[]")))
          )
        ),
        ZioHttp.addEffects(Json.Obj.empty, SessionEffects(pageTitle = Some(""))) ==
          Json.Obj("t" -> Json.Str(""))
      )
    },
    test("connected requests strip socket metadata and use the admitted page identity") {
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
        connected.headers.isEmpty,
        connected.cookie("session").isEmpty,
        connected.remoteAddress.isEmpty,
        connected.body == Body.empty
      )
    },
    test("routed disconnected mount decodes before mount and then handles initial params") {
      val events = scala.collection.mutable.ArrayBuffer.empty[String]
      object View extends LiveView.Routed.Eventless[String, Unit]:
        def mount(params: Unit, ctx: MountContext) = ZIO.succeed {
          events += "mount"
          "mounted"
        }
        override def handleParams(model: String, params: Unit, url: URL, ctx: ParamsContext) =
          ZIO.succeed {
            events += "params"
            "handled"
          }
        def view(model: Signal[String]) = div(model)

      for
        response <- run(
                      ZioHttp.routes(scalive.Live.router(scalive.live.params(View)), config),
                      Request.get(URL.root)
                    )
        body <- response.body.asString.orDie
      yield assertTrue(events.toVector == Vector("mount", "params"), body.contains("handled"))
    },
    test("disconnected params and after-render hooks include dynamically mounted hooks") {
      val events = scala.collection.mutable.ArrayBuffer.empty[String]
      object View extends LiveView.Routed.Eventless[Int, Unit]:
        override val hooks = LiveHooks.empty[Nothing, Int]
          .onParams((model, _, _) => ZIO.succeed {
            events += "static-params"
            LiveHookResult.cont(model + 1)
          })
          .afterRender((_, _) => ZIO.succeed(events += "static-after").unit)
        def mount(params: Unit, ctx: MountContext) =
          ctx.hooks.params.attach("dynamic")((model, _, _) => ZIO.succeed {
            events += "dynamic-params"
            LiveHookResult.cont(model + 1)
          }) *>
            ctx.hooks.afterRender.attach("dynamic")((_, _) =>
              ZIO.succeed(events += "dynamic-after").unit
            ).as(0)
        override def handleParams(model: Int, params: Unit, url: URL, ctx: ParamsContext) =
          ZIO.succeed {
            events += "callback"
            model + 1
          }
        def view(model: Signal[Int]) = div(model.map(_.toString))

      for
        response <- run(
                      ZioHttp.routes(scalive.Live.router(scalive.live.params(View)), config),
                      Request.get(URL.root)
                    )
        body <- response.body.asString.orDie
      yield assertTrue(
        events.toVector == Vector(
          "static-params",
          "dynamic-params",
          "callback",
          "static-after",
          "dynamic-after"
        ),
        body.contains(">3</div>")
      )
    },
    test("disconnected mount navigation returns an HTTP redirect with transferable flash") {
      val notice  = FlashKind("notice")
      val renders = AtomicInteger()
      val from    = scalive.live / "from"
      val to      = scalive.live / "to"
      object From extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) =
          ctx.flash.put(notice, "moved") *> ctx.nav.pushNavigate(to.location)
        def view(model: Signal[Unit]) =
          renders.incrementAndGet()
          div()
      object To extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div(flash(notice)(message => span(message)))
      val routes = ZioHttp.routes(scalive.Live.router(from -> From, to -> To), config)

      for
        redirect <- run(routes, Request.get(URL.decode("/from").toOption.get))
        flashCookie <- ZIO.fromOption(
                         redirect.headers(Header.SetCookie).map(_.value)
                           .find(_.name == "__phoenix_flash__")
                       ).orElseFail(AssertionError("redirect flash cookie was not issued"))
        destination <- run(
                         routes,
                         Request.get(URL.decode("/to").toOption.get)
                           .addCookie(Cookie.Request(flashCookie.name, flashCookie.content))
                       )
        body <- destination.body.asString.orDie
      yield assertTrue(
        redirect.status == Status.SeeOther,
        redirect.header(Header.Location).exists(_.url.encode == "/to"),
        renders.get() == 0,
        body.contains("<span>moved</span>")
      )
    },
    test("initial routed decode failure happens before factory and mount") {
      val factories = AtomicInteger()
      val mounts     = AtomicInteger()
      val decoder = LiveParamsDecoder.custom[Unit, Unit]((_, _) => Left("invalid params"))
      object View extends LiveView.Routed.Eventless[Unit, Unit]:
        def mount(params: Unit, ctx: MountContext) = ZIO.succeed(mounts.incrementAndGet()).unit
        def view(model: Signal[Unit]) = div()

      for
        response <- run(
                      ZioHttp.routes(
                        scalive.Live.router(scalive.live.paramsDecodeOnly(decoder) {
                          factories.incrementAndGet()
                          View
                        }),
                        config
                      ),
                      Request.get(URL.root)
                    )
      yield assertTrue(
        response.status == Status.InternalServerError,
        factories.get() == 0,
        mounts.get() == 0
      )
    },
    test("routed connected lifecycle mounts, handles params, and recovers patch decode failures") {
      val events = scala.collection.mutable.ArrayBuffer.empty[String]
      val decoder = LiveParamsDecoder.custom[Unit, String]((_, url) =>
        if url.queryParam("bad").nonEmpty then Left("bad patch") else Right("decoded")
      )
      object View extends LiveView.Routed.Eventless[String, String]:
        def mount(params: String, ctx: MountContext) = ZIO.succeed {
          events += s"mount:$params"
          "mounted"
        }
        override def handleParams(model: String, params: String, url: URL, ctx: ParamsContext) =
          ZIO.succeed { events += s"params:$params"; "handled" }
        override def handleParamsDecodeError(
          model: String,
          error: LiveParamsCodec.DecodeError,
          url: URL,
          ctx: ParamsContext
        ) = ZIO.succeed { events += s"recover:${error.message}"; "recovered" }
        def view(model: Signal[String]) = div(model)
      val application = scalive.Live.router(scalive.live.paramsDecodeOnly(decoder)(View))
      val route = ZioHttp.validate(application).head.asInstanceOf[
        ZioHttp.CompiledRoute[Any] { type Msg = Nothing; type Model = String }
      ]

      for
        response <- run(ZioHttp.routes(application, config), Request.get(URL.root))
        body     <- response.body.asString.orDie
        claims  <- ZioHttpSecurity.verifySession(config, attribute(body, "data-phx-session").get)
        _         = events.clear()
        lifecycle <- route.prepareConnected(URL.root, Request.get(URL.root), claims)
        mountContext = scalive.runtime.connection.RootMountContext.disconnected[Nothing, String]
        paramsContext = ZioHttp.disconnectedParamsContext(mountContext)
        mounted   <- lifecycle.mount(mountContext)
        initial   <- lifecycle.prepareParams(URL.root)
        handled   <- initial.run(mounted, paramsContext)
        badUrl     = URL.decode("/?bad=1").toOption.get
        recovery  <- lifecycle.prepareParams(badUrl)
        recovered <- recovery.run(handled, paramsContext)
      yield assertTrue(
        events.toVector == Vector("mount:decoded", "params:decoded", "recover:bad patch"),
        recovered == "recovered"
      )
    },
    test("environment routes receive the provided service") {
      final case class Greeting(value: String)
      val application = scalive.Live.router(
        scalive.live.from[Greeting, Nothing, String]((_, _, greeting) =>
          new LiveView.Eventless[String]:
            def mount(ctx: MountContext) = ZIO.succeed(greeting.value)
            def view(model: Signal[String]) = div(model)
        )
      )

      for
        response <- run(
                      ZioHttp.routes(application, config).provideEnvironment(ZEnvironment(Greeting("hello"))),
                      Request.get(URL.root)
                    )
        body <- response.body.asString.orDie
      yield assertTrue(body.contains("hello"))
    },
    test("context environment services are resolved in both phases without entering mount claims") {
      final case class Greeting(value: String)
      val aspect = LiveMountAspect.fromRequest[Any, Any, String, String](
        _ => ZIO.succeed("session-claim" -> "disconnected-user"),
        (_, _) => ZIO.succeed("connected-user")
      )
      final class View(user: String, greeting: Greeting) extends LiveView.Eventless[String]:
        def mount(ctx: MountContext) = ZIO.succeed(s"$user:${greeting.value}")
        def view(model: Signal[String]) = div(model)

      val application = scalive.Live.router(
        scalive.Live
          .session("context-service")
          .withMountAspect(aspect)(
            scalive.live.context((user: String, greeting: Greeting) => View(user, greeting))
          )
      )
      val environment = ZEnvironment(Greeting("hello"))
      val route       = ZioHttp.validate(application).head.asInstanceOf[
        ZioHttp.CompiledRoute[Greeting] { type Msg = Nothing; type Model = String }
      ]

      for
        response <- run(
                      ZioHttp.routes(application, config).provideEnvironment(environment),
                      Request.get(URL.root)
                    )
        body   <- response.body.asString.orDie
        claims <- ZioHttpSecurity.verifySession(
                    config,
                    attribute(body, "data-phx-session").get
                  )
        lifecycle <- route
                       .prepareConnected(URL.root, Request.get(URL.root), claims)
                       .provideEnvironment(environment)
        model <- lifecycle.mount(scalive.runtime.connection.RootMountContext.disconnected).orDie
      yield assertTrue(
        body.contains("disconnected-user:hello"),
        model == "connected-user:hello",
        claims.sessionMountClaims.size == 1,
        claims.routeMountClaims.isEmpty
      )
    },
    test("duplicate route patterns and separately declared session names fail synchronously") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div()
      val duplicateRoutes = scalive.Live.router(scalive.live(View), scalive.live(View))
      val duplicateSessions = scalive.Live.router(
        scalive.Live.session("same")(scalive.live(View)),
        scalive.Live.session("same")(scalive.live / "other" -> View)
      )

      assertTrue(
        scala.util.Try(ZioHttp.routes(duplicateRoutes, config)).failed.toOption
          .exists(_.isInstanceOf[ZioHttp.AssemblyException]),
        scala.util.Try(ZioHttp.routes(duplicateSessions, config)).failed.toOption
          .exists(_.isInstanceOf[ZioHttp.AssemblyException])
      )
    },
    test("session and route aspects preserve order and independently signed claims") {
      val events = scala.collection.mutable.ArrayBuffer.empty[String]
      val sessionAspect = LiveMountAspect.fromRequest[Any, Any, String, String](
        _ => ZIO.succeed {
          events += "session-disconnected"
          "session-claim" -> "session-context"
        },
        (claim, _) => ZIO.succeed {
          events += s"session-connected:$claim"
          "session-connected-context"
        }
      )
      val routeAspect = LiveMountAspect.make[Any, Unit, String, String, String](
        (_, input) => ZIO.succeed {
          events += s"route-disconnected:$input"
          "route-claim" -> "route-context"
        },
        (claim, _, input) => ZIO.succeed {
          events += s"route-connected:$claim:$input"
          "route-connected-context"
        }
      )
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.succeed { events += "mount" }
        def view(model: Signal[Unit]) = div()
      val route = scalive.live.withMountAspect(routeAspect) {
        (_, _, _: (String, String)) =>
          events += "factory"
          View
      }
      val application = scalive.Live.router(
        scalive.Live.session("main").withMountAspect(sessionAspect)(route)
      )
      val catalog = ZioHttp.validate(application)

      for
        response <- run(ZioHttp.routes(application, config), Request.get(URL.root))
        body     <- response.body.asString.orDie
        claims  <- ZioHttpSecurity.verifySession(config, attribute(body, "data-phx-session").get)
        _         = events.clear()
        lifecycle <- catalog.head.prepareConnected(
                       URL.root,
                       Request.get(URL.root),
                       claims
                     )
        _ <- lifecycle.mount(scalive.runtime.connection.RootMountContext.disconnected).orDie
        validEvents = events.toVector
        malformed <- catalog.head.prepareConnected(
                       URL.root,
                       Request.get(URL.root),
                       claims.copy(routeMountClaims = Vector("{"))
                     ).either
      yield assertTrue(
        claims.sessionIdentity.contains("main"),
        claims.sessionMountClaims.nonEmpty,
        claims.routeMountClaims.nonEmpty,
        claims.hasRouteClaims,
        validEvents == Vector(
          "session-connected:session-claim",
          "route-connected:route-claim:session-connected-context",
          "factory",
          "mount"
        ),
        malformed.isLeft,
        events.count(_ == "factory") == 1
      )
    },
    test("ordinary layouts nest inside application layout and route root layout wins") {
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = div(idAttr := "view")
      val appLayout = LiveLayout[Any, Any]([Msg] => (content, _) =>
        sectionTag(idAttr := "application", content)
      )
      val routeLayout = LiveLayout[Unit, Any]([Msg] => (content, _) =>
        mainTag(idAttr := "route", content)
      )
      val routeRoot = LiveRootLayout[Unit, Any]("route-root")([Msg] => (content, _, _) =>
        htmlRootTag(headTag(titleTag("custom")), bodyTag(content))
      )
      val application = scalive.Live.router
        .withLayout(appLayout)
        .withRootLayout(LiveRootLayout[Any, Any]("application-root")([Msg] =>
          (content, _, _) => htmlRootTag(bodyTag(content))
        ))(
          scalive.live.withLayout(routeLayout).withRootLayout(routeRoot)(View)
        )

      for
        response <- run(ZioHttp.routes(application, config), Request.get(URL.root))
        body     <- response.body.asString.orDie
        claims  <- ZioHttpSecurity.verifySession(config, attribute(body, "data-phx-session").get)
      yield assertTrue(
        claims.rootLayoutKey == "route-root",
        body.startsWith("<!doctype html><html>"),
        body.contains("<meta name=\"csrf-token\""),
        body.contains("data-phx-main"),
        body.contains("<main id=\"route\"><div id=\"view\"></div></main>")
      )
    },
    test("nested join admission authenticates join claims and independently verifies static tokens") {
      val claims = NestedCredentialClaims(
        NestedRegistrationId(11L),
        NestedRegistrationEpoch(2L),
        LifecycleId(7L),
        Epoch(3L),
        NestedTopic("lv:child")
      )
      val other = claims.copy(
        registration = NestedRegistrationId(12L),
        childLifecycle = Some(LifecycleId(13L))
      )
      val stale = other.copy(registrationEpoch = NestedRegistrationEpoch(3L))

      for
        issued      <- ZioHttpSecurity.issueNested(config, claims)
        otherIssued <- ZioHttpSecurity.issueNested(config, other)
        staleIssued <- ZioHttpSecurity.issueNested(config, stale)
        static      <- ZIO.fromOption(issued.static).orElseFail(AssertionError("missing static"))
        otherStatic <- ZIO.fromOption(otherIssued.static).orElseFail(AssertionError("missing static"))
        staleStatic <- ZIO.fromOption(staleIssued.static).orElseFail(AssertionError("missing static"))
        join = RootJoin(
                 url = None,
                 redirect = None,
                 flash = None,
                 session = issued.join.value,
                 static = Some(static.value),
                 params = Map.empty,
                 sticky = false
               )
        valid <- ZioHttp.verifyNestedAdmission(config, "lv:child", join).either
        wrongTopic <- ZioHttp
                        .verifyNestedAdmission(config, "lv:other", join).either
        wrongStatic <- ZioHttp
                         .verifyNestedAdmission(
                           config,
                           "lv:child",
                           join.copy(static = Some(otherStatic.value))
                         ).either
        staticAsJoin <- ZioHttp
                          .verifyNestedAdmission(
                            config,
                            "lv:child",
                            join.copy(session = static.value, static = None)
                          ).either
        staleStaticResult <- ZioHttp
                               .verifyNestedAdmission(
                                 config,
                                 "lv:child",
                                 join.copy(static = Some(staleStatic.value))
                               ).either
        rootToken <- ZioHttpSecurity.issueSession(config, "root", LifecycleId(1L), 0, "/")
        rootAsNested <- ZioHttp
                          .verifyNestedAdmission(
                            config,
                            "lv:child",
                            join.copy(session = rootToken, static = None)
                          ).either
      yield assertTrue(
        valid == Right(claims),
        wrongTopic.isLeft,
        wrongStatic == Right(claims.copy(childLifecycle = other.childLifecycle)),
        staleStaticResult == Right(claims),
        staticAsJoin.isLeft,
        rootAsNested.isLeft
      )
    },
    test("nested URL decoding preserves exact path/query and safely inherits") {
      val inherited = URL.decode("/parent/path?from=parent").toOption.get

      assertTrue(
        ZioHttp.nestedJoinUrl(None, inherited) == Right(inherited),
        ZioHttp
          .nestedJoinUrl(Some("https://example.test/child/path?one=1&two=2#ignored"), inherited)
          .exists(url => url.encode == "/child/path?one=1&two=2"),
        ZioHttp.nestedJoinUrl(Some("http://["), inherited).isLeft
      )
    },
    test("unknown nested credentials are rejected by the supervisor before allocation") {
      ZIO.scoped {
        val claims = NestedCredentialClaims(
          NestedRegistrationId(999L),
          NestedRegistrationEpoch(1L),
          LifecycleId(1L),
          Epoch(1L),
          NestedTopic("lv:unknown")
        )
        for
          issuances <- Ref.make(0)
          supervisor <- ConnectionSupervisor.make(
                          ConnectionConfig.make(4, 4, 4, 4, 4).toOption.get,
                          new NestedCredentialIssuer:
                            def issue(value: NestedCredentialClaims) =
                              issuances.updateAndGet(_ + 1).as(
                                IssuedNestedCredentials(
                                  NestedJoinCredential("unused"),
                                  Some(NestedStaticCredential("unused"))
                                )
                              ),
                          applicationId => NestedTopic(s"lv:$applicationId")
                        )
          result <- supervisor.reserveNested(claims).either
          count  <- issuances.get
        yield assertTrue(result.isLeft, count == 0)
      }
    },
    test("lifecycle activation failure removes protocol state before retirement") {
      for
        cleanup <- Ref.make(Vector.empty[String])
        failed <- ZioHttp
                    .activateInstalledLifecycle(
                      ZIO.fail(Exception("publication failed")),
                      cleanup.update(_ :+ "removed"),
                      cleanup.update(_ :+ "retired")
                    ).either
        afterFailure <- cleanup.get
        _            <- cleanup.set(Vector.empty)
        constructed = scala.collection.mutable.ArrayBuffer.empty[String]
        succeeded <- ZioHttp
                       .activateInstalledLifecycle(
                         ZIO.unit,
                         {
                           constructed += "removed"
                           cleanup.update(_ :+ "removed")
                         },
                         {
                           constructed += "retired"
                           cleanup.update(_ :+ "retired")
                         }
                       ).either
        afterSuccess <- cleanup.get
      yield assertTrue(
        failed.isLeft,
        afterFailure == Vector("removed", "retired"),
        succeeded.isRight,
        constructed.isEmpty,
        afterSuccess.isEmpty
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
    test("joined protocol state is isolated by exact topic and generation") {
      val rootRef: PhoenixRef.Value  = PhoenixRef.Value("root-generation")
      val childRef: PhoenixRef.Value = PhoenixRef.Value("child-generation")
      val states = Map(
        "lv:root"  -> (rootRef  -> "root-state"),
        "lv:child" -> (childRef -> "child-state")
      )
      def generation(value: (PhoenixRef.Value, String)) = value._1

      assertTrue(
        ZioHttp
          .exactTopicGeneration(states, "lv:root", rootRef, generation)
          .exists(_._2 == "root-state"),
        ZioHttp
          .exactTopicGeneration(states, "lv:child", childRef, generation)
          .exists(_._2 == "child-state"),
        ZioHttp.exactTopicGeneration(states, "lv:child", rootRef, generation).isEmpty,
        ZioHttp.exactTopicGeneration(states, "lv:unknown", childRef, generation).isEmpty
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

  private def nestedAttribute(html: String, id: String, name: String): Option[String] =
    val container = ("<div id=\"" + java.util.regex.Pattern.quote(id) + "\"([^>]*)>").r
    container.findFirstMatchIn(html).flatMap(value => attribute(value.group(1), name))
end ZioHttpSpec
