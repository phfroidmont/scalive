package scalive.testing

import java.time.Duration

import org.jsoup.Jsoup
import zio.*
import zio.http.{Request, URL}
import zio.json.JsonCodec
import zio.test.*

import scalive.*

object ConnectedRenderSpec extends ZIOSpecDefault:
  private enum Msg:
    case Increment

  private enum FormMsg:
    case Changed(event: FormEvent[String])
    case Submitted(event: FormEvent[String])

  private enum ResourceMsg:
    case Tick

  private enum TerminalMsg:
    case Trigger

  private enum NavigationMsg:
    case Patch

  private enum RoutedNavigationMsg:
    case Navigate

  private enum AdmissionMsg:
    case Navigate

  private final case class TestSessionId(value: String) derives JsonCodec
  private final case class TestClaims(sessionId: TestSessionId) derives JsonCodec
  private final case class TestUser(name: String)
  private final case class TestAuthState(
    active: Ref[Set[TestSessionId]],
    revalidations: Ref[Int])

  private val Name = FormField.requiredString(FormPath("profile", "name"), "Name is required.")

  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("scalive.test"))
  ).toOption.get

  def spec = suite("ConnectedRenderSpec")(
    test("joins through production admission and dispatches bindings and typed messages") {
      val view = new LiveView[Msg, Int]:
        def mount(ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(model: Int, ctx: MessageContext) =
          case Msg.Increment => ZIO.succeed(model + 1)
        override def view(model: Signal[Int]) =
          div(
            span(dataAttr("count") := "", model.map(_.toString)),
            button(dataAttr("increment") := "", on.click(Msg.Increment), "Increment")
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          initial   <- connected.text("[data-count]")
          _         <- connected.click("[data-increment]")
          clicked   <- connected.text("[data-count]")
          _         <- connected.send(Msg.Increment)
          sent      <- connected.text("[data-count]")
          joined    <- connected.isJoined
          _         <- connected.leave
          left      <- connected.isJoined
        yield assertTrue(initial == "0", clicked == "1", sent == "2", joined, !left)
      }
    },
    test("dispatches typed form change and submit bindings") {
      val view = new LiveView[FormMsg, FormEvent[String] | Null]:
        def mount(ctx: MountContext) = ZIO.succeed(null)
        def handleMessage(model: FormEvent[String] | Null, ctx: MessageContext) =
          case FormMsg.Changed(event)   => ZIO.succeed(event)
          case FormMsg.Submitted(event) => ZIO.succeed(event)
        override def view(model: Signal[FormEvent[String] | Null]) =
          val event = model.map(Option(_))
          form(
            dataAttr("profile-form") := "",
            Name.onChange(FormMsg.Changed(_)),
            Name.onSubmit(FormMsg.Submitted(_)),
            input(nameAttr := Name.name),
            span(dataAttr("target") := "", event.map(_.flatMap(_.target).fold("")(_.name))),
            span(dataAttr("submitted") := "", event.map(_.exists(_.submitted).toString)),
            span(dataAttr("used") := "", event.map(_.exists(_.state.isUsed(Name.path)).toString))
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _ <- connected.changeForm(
                 "[data-profile-form]",
                 Vector(Name.name -> "", "profile[_unused_email]" -> ""),
                 target = Some(Name.name)
               )
          changedTarget <- connected.text("[data-target]")
          changedUsed   <- connected.text("[data-used]")
          _ <- connected.submitForm("[data-profile-form]", Vector(Name.name -> "Ada"))
          submitted <- connected.text("[data-submitted]")
          submitUsed <- connected.text("[data-used]")
        yield assertTrue(
          changedTarget == Name.name,
          changedUsed == "true",
          submitted == "true",
          submitUsed == "true"
        )
      }
    },
    test("infers component targets from the nearest rendered component root") {
      object CounterComponent extends LiveComponent[Unit, Unit, Int]:
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          case () => ZIO.succeed(model + 1)
        override def view(props: Signal[Unit], model: Signal[Int], self: ComponentRef[Unit]) =
          div(
            dataAttr("component-counter") := "",
            span(dataAttr("component-count") := "", model.map(_.toString)),
            button(on.click.to(self)(()), "Increase component")
          )

      val counter = component(CounterComponent, "counter")
      val parent = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) = div(counter.render(()))

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(parent)
          _         <- connected.click("[data-component-counter] button")
          count     <- connected.text("[data-component-count]")
        yield assertTrue(count == "1")
      }
    },
    test("streams hosted uploads using the preflight chunk size") {
      val definition = LiveUploadDef.inMemory(
        "small-chunks",
        LiveUploadAccept.only(".txt"),
        maxFileSize = 32L,
        chunkSize = 3
      )
      val view = new LiveView.Eventless[LiveUpload[Chunk[Byte]]]:
        def mount(ctx: MountContext) = ctx.uploads.allow(definition)
        override def view(model: Signal[LiveUpload[Chunk[Byte]]]) =
          input(dataAttr("upload-ref") := model.map(_.ref.value))

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          html      <- connected.html
          uploadRef <- ZIO.attempt(
                         Jsoup.parseBodyFragment(html).selectFirst("[data-upload-ref]")
                           .attr("data-upload-ref")
                       )
          _ <- connected.upload(
                 uploadRef,
                 "small-entry",
                 "small.txt",
                 "text/plain",
                 Chunk.fromArray("seven77".getBytes(java.nio.charset.StandardCharsets.UTF_8))
               )
          joined <- connected.isJoined
        yield assertTrue(joined)
      }
    },
    test("joins nested views and releases their resources when the parent leaves") {
      for
        acquired <- Promise.make[Nothing, Unit]
        released <- Promise.make[Nothing, Unit]
        result <- ZIO.scoped {
                    val child = new LiveView[ResourceMsg, Unit]:
                      def mount(ctx: MountContext) =
                        ctx.connection match
                          case Connection.Disconnected => ZIO.unit
                          case Connection.Connected(connected) =>
                            connected.resources
                              .acquireRelease(acquired.succeed(()).unit)(_ =>
                                released.succeed(()).unit
                              ).unit
                      def handleMessage(model: Unit, ctx: MessageContext) =
                        case ResourceMsg.Tick => ZIO.succeed(model)
                      override def view(model: Signal[Unit]) = div("resource child")

                    val parent = new LiveView.Eventless[Unit]:
                      def mount(ctx: MountContext) = ZIO.unit
                      override def view(model: Signal[Unit]) =
                        div(liveView("resource-child", child))

                    for
                      connected <- ConnectedRender.join(parent)
                      nested    <- connected.joinNested("resource-child")
                      joined    <- nested.isJoined
                      _ <- acquired.await.timeoutFail(
                             new RuntimeException("Nested resource did not start")
                           )(3.seconds)
                      _         <- connected.leave
                      _ <- released.await.timeoutFail(
                             new RuntimeException("Nested resource was not released")
                           )(3.seconds)
                      removed <- nested.isJoined
                    yield assertTrue(joined, !removed)
                  }
      yield result
    },
    test("disconnect guards from either joined view retire the shared physical session") {
      ZIO.scoped {
        ZIO.foreach(Vector(false, true))(triggerNested =>
          terminalGuardCleanup(LiveConnectedTurnFailure.disconnect("test"), triggerNested)
        ).map(results => assertTrue(results.forall(identity)))
      }
    },
    test("reload guards retire the shared physical session") {
      ZIO.scoped {
        terminalGuardCleanup(LiveConnectedTurnFailure.reload("test"), triggerNested = true)
          .map(assertTrue(_))
      }
    },
    test("same-session patch navigation keeps root and nested views joined") {
      val child = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) = div("patch child")

      val parent = new LiveView[NavigationMsg, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case NavigationMsg.Patch => ctx.nav.pushPatchUnsafe("?patched=true").as(model)
        override def view(model: Signal[Unit]) =
          div(
            button(dataAttr("patch") := "", on.click(NavigationMsg.Patch), "Patch"),
            liveView("patch-child", child)
          )

      val application = scalive.Live.router(
        scalive.live.guardConnectedTurns(_ => ZIO.unit)(parent)
      )

      ZIO.scoped {
        for
          root   <- ConnectedRender.join(application, config, Request.get(URL.root))
          nested <- root.joinNested("patch-child")
          _      <- root.click("[data-patch]")
          rootJoined   <- root.isJoined
          nestedJoined <- nested.isJoined
        yield assertTrue(rootJoined, nestedJoined)
      }
    },
    test("routed joins execute physical session admission") {
      val sessionId = TestSessionId("admitted")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        state          = TestAuthState(active, revalidations)
        application    = admittedApplication
        result <- ZIO.scoped {
                    for
                      connected <- ConnectedRender.join(
                                     application,
                                     config,
                                     Request.get(url("/?session=admitted"))
                                   )
                      name   <- connected.text("[data-user-name]")
                      checks <- revalidations.get
                    yield assertTrue(name == "admitted", checks == 1)
                  }.provide(
                    ZLayer.succeed[TestAuthState](state),
                    ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                  )
      yield result
    },
    test("invalidation retires the transport and reconnect revalidates authorization") {
      val sessionId = TestSessionId("revoked")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        state          = TestAuthState(active, revalidations)
        result <- ZIO.scoped {
                    for
                      client <- ConnectedRender.open(
                                  admittedApplication,
                                  config,
                                  Request.get(url("/?session=revoked"))
                                )
                      connected <- client.join
                      _         <- active.set(Set.empty)
                      _         <- connections.disconnect(sessionId)
                      _         <- connected.awaitDisconnected
                      retry     <- client.reconnect.either
                      checks    <- revalidations.get
                    yield assertTrue(
                      retry == Left(ConnectedJoinFailure.Unauthorized),
                      checks == 2
                    )
                  }.provide(
                    ZLayer.succeed[TestAuthState](state),
                    ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                  )
      yield result
    },
    test("disconnect waits for an in-flight join and closes its transport") {
      for
        connectedMount <- Promise.make[Nothing, Unit]
        releaseMount   <- Promise.make[Nothing, Unit]
        view = new LiveView.Eventless[Unit]:
                 def mount(ctx: MountContext) = ctx.connection match
                   case Connection.Disconnected => ZIO.unit
                   case Connection.Connected(_) =>
                     connectedMount.succeed(()).unit *> releaseMount.await
                 override def view(model: Signal[Unit]) = div("connected")
        result <- ZIO.scoped {
                    for
                      client <- ConnectedRender.open(
                                  scalive.Live.router(scalive.live(view)),
                                  config,
                                  Request.get(URL.root)
                                )
                      joining         <- client.join.fork
                      _               <- connectedMount.await
                      disconnecting   <- client.disconnect.fork
                      _               <- releaseMount.succeed(())
                      connected       <- joining.join
                      _               <- disconnecting.join
                      _               <- connected.awaitDisconnected
                      joinedAfterClose <- connected.isJoined
                    yield assertTrue(!joinedAfterClose)
                  }
      yield result
    },
    test("live navigation revalidates session admission and reports rejection") {
      val sessionId = TestSessionId("navigation")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        state          = TestAuthState(active, revalidations)
        result <- ZIO.scoped {
                    for
                      client <- ConnectedRender.open(
                                  admittedApplication,
                                  config,
                                  Request.get(url("/?session=navigation"))
                                )
                      connected <- client.join
                      outcome   <- connected.click("[data-admission-navigate]")
                      _         <- active.set(Set.empty)
                      rejected <- outcome match
                                    case ConnectedAction.LiveNavigation(navigation) =>
                                      navigation.follow.either
                                    case other =>
                                      ZIO.fail(Exception(s"Expected live navigation, got $other."))
                      _      <- connected.awaitDisconnected
                      checks <- revalidations.get
                    yield assertTrue(
                      rejected == Left(ConnectedJoinFailure.Disconnected),
                      checks == 2
                    )
                  }.provide(
                    ZLayer.succeed[TestAuthState](state),
                    ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                  )
      yield result
    },
    test("live navigation rejoins the destination through redirect admission") {
      val source = new LiveView[RoutedNavigationMsg, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case RoutedNavigationMsg.Navigate => ctx.nav.pushNavigateUnsafe("/next").as(model)
        override def view(model: Signal[Unit]) =
          button(dataAttr("navigate") := "", on.click(RoutedNavigationMsg.Navigate), "Next")

      val destination = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) = div(dataAttr("destination") := "", "destination")

      val application = scalive.Live.router(
        scalive.live(source),
        (scalive.live / "next")(destination)
      )

      ZIO.scoped {
        for
          root    <- ConnectedRender.join(application, config, Request.get(URL.root))
          outcome <- root.click("[data-navigate]")
          next <- outcome match
                    case ConnectedAction.LiveNavigation(navigation) => navigation.follow
                    case other => ZIO.fail(Exception(s"Expected live navigation, got $other."))
          rendered <- next.text("[data-destination]")
          sourceJoined <- root.isJoined
          nextJoined   <- next.isJoined
        yield assertTrue(rendered == "destination", !sourceJoined, nextJoined)
      }
    },
    test("server-originated live navigation remains observable and followable") {
      for
        release <- Promise.make[Nothing, Unit]
        source = new LiveView[RoutedNavigationMsg, Unit]:
                   private val navigate = AsyncKey[Unit]("navigate-after-join")
                   def mount(ctx: MountContext) = ctx.connection match
                     case Connection.Disconnected => ZIO.unit
                     case Connection.Connected(connected) =>
                       connected.async
                         .start(navigate)(release.await)(_ => RoutedNavigationMsg.Navigate).unit
                   def handleMessage(model: Unit, ctx: MessageContext) =
                     case RoutedNavigationMsg.Navigate =>
                       ctx.nav.pushNavigateUnsafe("/next").as(model)
                   override def view(model: Signal[Unit]) = div("source")
        destination = new LiveView.Eventless[Unit]:
                        def mount(ctx: MountContext) = ZIO.unit
                        override def view(model: Signal[Unit]) =
                          div(dataAttr("async-destination") := "", "destination")
        application = scalive.Live.router(
                        scalive.live(source),
                        (scalive.live / "next")(destination)
                      )
        result <- ZIO.scoped {
                    for
                      connected <- ConnectedRender.join(application, config, Request.get(URL.root))
                      _         <- release.succeed(())
                      outcome   <- connected.awaitAction
                      next <- outcome match
                                case ConnectedAction.LiveNavigation(navigation) => navigation.follow
                                case other =>
                                  ZIO.fail(Exception(s"Expected live navigation, got $other."))
                      rendered <- next.text("[data-async-destination]")
                    yield assertTrue(rendered == "destination")
                  }
      yield result
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds)

  private val authentication =
    LiveMountAspect.fromRequest[TestAuthState, Any, TestClaims, TestUser](
      request =>
        for
          state <- ZIO.service[TestAuthState]
          id     = TestSessionId(request.url.queryParam("session").getOrElse("missing"))
          valid <- state.active.get.map(_.contains(id))
          user  <- if valid then ZIO.succeed(TestUser(id.value))
                   else ZIO.fail(zio.http.Response.unauthorized)
        yield TestClaims(id) -> user,
      (claims, _) =>
        for
          state <- ZIO.service[TestAuthState]
          _     <- state.revalidations.update(_ + 1)
          valid <- state.active.get.map(_.contains(claims.sessionId))
          user  <- if valid then ZIO.succeed(TestUser(claims.sessionId.value))
                   else ZIO.fail(LiveMountFailure.unauthorized("revoked test session"))
        yield user
    )

  private val admittedApplication
    : LiveApplication[TestAuthState & LiveConnections[TestSessionId]] = scalive.Live.router(
    scalive.Live
      .session("authenticated")
      .withAdmission(authentication)(_.sessionId)(
        scalive.live.context((user: TestUser) => new LiveView[AdmissionMsg, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext) =
            case AdmissionMsg.Navigate => ctx.nav.pushNavigateUnsafe("/next").as(model)
          override def view(model: Signal[Unit]) =
            div(
              dataAttr("user") := "",
              span(dataAttr("user-name") := "", user.name),
              button(
                dataAttr("admission-navigate") := "",
                on.click(AdmissionMsg.Navigate),
                "Next"
              )
            )
        ),
        (scalive.live / "next").context((user: TestUser) => new LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          override def view(model: Signal[Unit]) = div(dataAttr("user") := "", user.name)
        )
      )
  )

  private def url(value: String): URL = URL.decode(value).toOption.get

  private def terminalGuardCleanup(
    failure: LiveConnectedTurnFailure,
    triggerNested: Boolean
  ): ZIO[Scope, Throwable, Boolean] =
    val child = new LiveView[TerminalMsg, Unit]:
      def mount(ctx: MountContext) = ZIO.unit
      def handleMessage(model: Unit, ctx: MessageContext) =
        case TerminalMsg.Trigger => ZIO.succeed(model)
      override def view(model: Signal[Unit]) =
        button(dataAttr("trigger-nested") := "", on.click(TerminalMsg.Trigger), "Trigger nested")

    val parent = new LiveView[TerminalMsg, Unit]:
      def mount(ctx: MountContext) = ZIO.unit
      def handleMessage(model: Unit, ctx: MessageContext) =
        case TerminalMsg.Trigger => ZIO.succeed(model)
      override def view(model: Signal[Unit]) =
        div(
          button(dataAttr("trigger-root") := "", on.click(TerminalMsg.Trigger), "Trigger root"),
          liveView("terminal-child", child)
        )

    val application = scalive.Live.router(
      scalive.live.guardConnectedTurns(_ => ZIO.fail(failure))(parent)
    )

    for
      root   <- ConnectedRender.join(application, config, Request.get(URL.root))
      nested <- root.joinNested("terminal-child")
      _ <- if triggerNested then nested.click("[data-trigger-nested]")
           else root.click("[data-trigger-root]")
      _ <- awaitUnjoined(root, nested)
      rootJoined   <- root.isJoined
      nestedJoined <- nested.isJoined
    yield !rootJoined && !nestedJoined

  private def awaitUnjoined(root: ConnectedView[?], nested: ConnectedView[?]): Task[Unit] =
    root.isJoined.zip(nested.isJoined).flatMap {
      case (false, false) => ZIO.unit
      case _              => ZIO.sleep(10.millis) *> awaitUnjoined(root, nested)
    }.timeoutFail(Exception("ConnectedRender session did not retire."))(3.seconds)
end ConnectedRenderSpec
