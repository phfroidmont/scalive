package scaliveapi

import zio.test.*

object RoutedConstructionSpec extends ZIOSpecDefault:
  def spec = suite("RoutedConstructionSpec")(
    test("routed definitions have no unrouted mount") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid[Msg, Model, Params](
          view: LiveView.Routed[Msg, Model, Params],
          ctx: MountContext[Msg, Model]
        ) = view.mount(ctx)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("parameter codecs and routed definitions are packaged together") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        object View extends LiveView.Routed.Eventless[String, Int]:
          def mount(params: Int, ctx: MountContext) = ZIO.succeed(params.toString)
          def view(model: Signal[String]) = div(model)

        val route = (live / "items").query[Int]("id")(View)
        val location: LiveLocation = (live / "items").query[Int]("id").location(42)
      """)

      assertTrue(errors.isEmpty)
    },
    test("schema queries and parameter mappings retain their intended capabilities") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.codec.PathCodec
        import zio.schema.Schema
        import zio.schema.derived

        final case class Search(q: Option[String]) derives Schema
        final case class Page(value: Option[Int])

        object SearchView extends LiveView.Routed.Eventless[String, Search]:
          def mount(params: Search, ctx: MountContext) = ZIO.succeed("")
          def view(model: Signal[String]) = div(model)

        object PageView extends LiveView.Routed.Eventless[String, Page]:
          def mount(params: Page, ctx: MountContext) = ZIO.succeed("")
          def view(model: Signal[String]) = div(model)

        val search = (live / "search").query[Search]
        val searchRoute = search(SearchView)
        val searchLocation: LiveLocation = search.location(Search(Some("typed")))

        val page = (live / "page")
          .queryOptional[Int]("page")
          .mapParams(Page.apply)(_.value)
        val pageRoute = page(PageView)
        val pageLocation: LiveLocation = page.location(Page(Some(2)))

        val decoded = (live / "items" / PathCodec.int("id"))
          .params
          .mapParamsDecodeOnly(id => id.toString)

        val explicit = Live.route(PathCodec.empty / "explicit")
      """)

      assertTrue(errors.isEmpty)
    },
    test("mount aspects supply typed context to route factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val aspect: LiveMountAspect[Any, Unit, Any, String, String] =
          LiveMountAspect.fromRequest[Any, Unit, String, String](
          _ => ZIO.succeed("claim" -> "disconnected"),
          (claim, _) => ZIO.succeed(s"connected:$claim")
          )

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          def view(model: Signal[String]) = div(model)

        val route: LiveRoute[Any, Unit] =
          live
            .withMountAspect(aspect)
            .withLayout(LiveLayout[Unit, String]([Msg] => (content, _) => content))
            .apply((_, _, context: String) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("context factories infer route input and one environment service") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.Request
        import zio.http.codec.PathCodec

        trait Accounts

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          def view(model: Signal[String]) = div(model)

        object Profile:
          def currentUser(currentUser: String): LiveView[Nothing, String] = View
          def withAccounts(currentUser: String, accounts: Accounts): LiveView[Nothing, String] = View
          def complete(
            id: Int,
            request: Request,
            currentUser: String,
            accounts: Accounts
          ): LiveView[Nothing, String] = View

        val currentUserOnly: LiveRoute[Any, Unit] { type Input = String } =
          live.context(Profile.currentUser)
        val withService: LiveRoute[Accounts, Unit] { type Input = String } =
          live.context(Profile.withAccounts)
        val complete: LiveRoute[Accounts, Int] { type Input = String } =
          (live / "profiles" / PathCodec.int("id")).context(Profile.complete)
        val inline: LiveRoute[Accounts, Unit] { type Input = String } =
          live.context((user: String, accounts: Accounts) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("routed and post-aspect builders expose the complete context family") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.Request

        trait Accounts

        object RoutedView extends LiveView.Routed.Eventless[String, Unit]:
          def mount(params: Unit, ctx: MountContext) = ZIO.succeed("mounted")
          def view(model: Signal[String]) = div(model)

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          def view(model: Signal[String]) = div(model)

        object RoutedFactory:
          def currentUser(user: String): LiveView.Routed[Nothing, String, Unit] = RoutedView
          def withAccounts(
            user: String,
            accounts: Accounts
          ): LiveView.Routed[Nothing, String, Unit] = RoutedView
          def complete(
            path: Unit,
            request: Request,
            user: String,
            accounts: Accounts
          ): LiveView.Routed[Nothing, String, Unit] = RoutedView

        val aspect = LiveMountAspect.fromRequest[Any, Unit, String, String](
          _ => ZIO.succeed("claim" -> "user"),
          (claim, _) => ZIO.succeed(claim)
        )
        val layout = LiveLayout[Unit, String]([Msg] => (content, context) =>
          if context.context.nonEmpty then content else content
        )

        val routedOne: LiveRoute[Any, Unit] { type Input = String } =
          live.params.context(RoutedFactory.currentUser)
        val routedTwo: LiveRoute[Accounts, Unit] { type Input = String } =
          live.params.context(RoutedFactory.withAccounts)
        val routedComplete: LiveRoute[Accounts, Unit] { type Input = String } =
          live.params.context(RoutedFactory.complete)

        val mounted = live.withMountAspect(aspect).withLayout(layout)
        val mountedOne: LiveRoute[Any, Unit] { type Input = Any } =
          mounted.context((user: String) => View)
        val mountedTwo: LiveRoute[Accounts, Unit] { type Input = Any } =
          mounted.context((user: String, accounts: Accounts) => View)
        val mountedComplete: LiveRoute[Accounts, Unit] { type Input = Any } =
          mounted.context((_: Unit, _: Request, user: String, accounts: Accounts) => View)

        val mountedRouted = mounted.params
        val mountedRoutedOne: LiveRoute[Any, Unit] { type Input = Any } =
          mountedRouted.context(RoutedFactory.currentUser)
        val mountedRoutedTwo: LiveRoute[Accounts, Unit] { type Input = Any } =
          mountedRouted.context(RoutedFactory.withAccounts)
        val mountedRoutedComplete: LiveRoute[Accounts, Unit] { type Input = Any } =
          mountedRouted.context(RoutedFactory.complete)
      """)

      assertTrue(errors.isEmpty)
    },
    test("context requirements remain visible during session and application assembly") {
      val valid = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        trait Accounts
        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val aspect = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("claim" -> "user"),
          (claim, _) => ZIO.succeed(claim)
        )
        val route = live.context((user: String, accounts: Accounts) => View)
        val session: LiveSession[Accounts] =
          Live.session("authenticated").withMountAspect(aspect)(route)
        val application: LiveApplication[Accounts] = Live.router(session)
      """)
      val missingContext = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        val route = live.context((user: String) => View)
        val invalid = Live.session("public")(route)
      """)
      val missingService = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        trait Accounts
        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        val aspect = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("claim" -> "user"),
          (claim, _) => ZIO.succeed(claim)
        )
        val route = live.context((user: String, accounts: Accounts) => View)
        val session = Live.session("authenticated").withMountAspect(aspect)(route)
        val invalid: LiveApplication[Any] = Live.router(session)
      """)

      assertTrue(valid.isEmpty, missingContext.nonEmpty, missingService.nonEmpty)
    },
    test("admitted sessions infer claims, connection IDs, context, and application requirements") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        final case class SessionId(value: String)
        given JsonCodec[SessionId] = JsonCodec.string.transform(SessionId.apply, _.value)
        final case class CurrentUser(name: String)
        trait AuthService
        trait Accounts

        val authentication = LiveMountAspect.fromRequest[AuthService, Any, SessionId, CurrentUser](
          _ => ZIO.succeed(SessionId("session") -> CurrentUser("disconnected")),
          (sessionId, _) => ZIO.succeed(CurrentUser(s"connected:${sessionId.value}"))
        )

        object Profile extends LiveView.Eventless[String]:
          def apply(user: CurrentUser, accounts: Accounts): Profile.type = this
          def mount(ctx: MountContext) = ZIO.succeed("profile")
          def view(model: Signal[String]) = div(model)

        object Status extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val layout = LiveLayout[Any, CurrentUser]([Msg] => (content, _) => content)
        val root = LiveRootLayout[Any, CurrentUser]("authenticated")([Msg] =>
          (content, _, _) => content
        )
        val session = Live.session("authenticated")
          .withAdmission(authentication)(identity)
          .withLayout(layout)
          .withRootLayout(root)
          .withMountAspect(
            LiveMountAspect.make[Any, Any, CurrentUser, Int, Unit](
              (_, _) => ZIO.succeed(1 -> ()),
              (_, _, _) => ZIO.unit
            )
          )(
            live.context((context: (CurrentUser, Unit), accounts: Accounts) =>
              Profile(context._1, accounts)
            ),
            (live / "status")(Status)
          )

        val application: LiveApplication[AuthService & LiveConnections[SessionId] & Accounts] =
          Live.router(session)
      """)

      assertTrue(errors.isEmpty)
    },
    test("a session cannot declare a second admission") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val aspect = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("id" -> "user"),
          (_, _) => ZIO.succeed("user")
        )

        val invalid = Live.session("authenticated")
          .withAdmission(aspect)(identity)
          .withAdmission(aspect)(identity)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("connected-turn guards compose across session admission and route declarations") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        final case class User(name: String)

        val admission = LiveMountAspect.fromRequest[Any, Any, String, User](
          _ => ZIO.succeed("id" -> User("disconnected")),
          (_, _) => ZIO.succeed(User("connected"))
        )
        val routeUser = LiveMountAspect.fromRequest[Any, Unit, String, User](
          _ => ZIO.succeed("route" -> User("disconnected")),
          (_, _) => ZIO.succeed(User("connected"))
        )
        val details = LiveMountAspect.make[Any, Unit, User, Int, Int](
          (_, user) => ZIO.succeed(1 -> user.name.length),
          (_, _, user) => ZIO.succeed(user.name.length)
        )

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val route = live
          .guardConnectedTurns(_ => ZIO.unit)
          .withMountAspect(routeUser)
          .guardConnectedTurns((user: User) => ZIO.unit)
          .withMountAspect(details)
          .guardConnectedTurns((context: (User, Int)) =>
            if context._2 > 0 then ZIO.unit
            else ZIO.fail(LiveConnectedTurnFailure.reload("missing details"))
          )(View)

        val session = Live.session("guarded")
          .guardConnectedTurns(_ => ZIO.unit)
          .withAdmission(admission)(identity)
          .guardConnectedTurns((user: User) => ZIO.unit)(route)
      """)

      assertTrue(errors.isEmpty)
    },
    test("connected-turn guards are available on parameter builder forms") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.codec.PathCodec

        object View extends LiveView.Routed.Eventless[Unit, Int]:
          def mount(params: Int, ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val encodable = (live / "items" / PathCodec.int("id"))
          .params
          .guardConnectedTurns(_ => ZIO.unit)
        val location: LiveLocation = encodable.location(1)
        val route = encodable(View)

        val decodeOnly = (live / "decoded")
          .paramsDecodeOnly(LiveParamsCodec.path[Unit].mapDecodeOnly(_ => 1))
          .guardConnectedTurns(_ => ZIO.unit)(View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("connected-turn guards reject effects with environments and invalid results") {
      val environmentErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        trait Accounts
        val invalid = live.guardConnectedTurns(_ => ZIO.service[Accounts])
      """)
      val resultErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        val invalid = live.guardConnectedTurns(_ => ZIO.succeed(42))
      """)
      val callbackErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        val invalid = live.guardConnectedTurns((value: String) => ZIO.unit)
      """)

      assertTrue(environmentErrors.nonEmpty, resultErrors.nonEmpty, callbackErrors.nonEmpty)
    },
    test("layouts cannot change the LiveView message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val layout = LiveLayout[Unit, Unit]([Msg] =>
          (content, _) => div(on.click("wrong message"), content)
        )
      """)

      assertTrue(errors.nonEmpty)
    },
    test("custom context composition preserves earlier layout context") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        final case class Combined(first: String, second: Int)

        given ContextAppend[String, Int] with
          type Result = Combined
          def append(input: String, output: Int) = Combined(input, output)
          def left(result: Combined) = result.first

        val first = LiveMountAspect.fromRequest[Any, Unit, String, String](
          _ => ZIO.succeed("claim" -> "first"),
          (claim, _) => ZIO.succeed(claim)
        )
        val second = LiveMountAspect.make[Any, Unit, String, Int, Int](
          (_, input) => ZIO.succeed(1 -> input.length),
          (_, _, input) => ZIO.succeed(input.length)
        )
        val layout = LiveLayout[Unit, String]([Msg] => (content, _) => content)

        object View extends LiveView.Eventless[Combined]:
          def mount(ctx: MountContext) = ZIO.succeed(Combined("", 0))
          def view(model: Signal[Combined]) = div()

        val route: LiveRoute[Any, Unit] = live
          .withMountAspect(first)
          .withLayout(layout)
          .withMountAspect(second)
          .apply((_, _, context: Combined) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("session aspect context reaches route factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val sessionAspect = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          def view(model: Signal[String]) = div(model)

        val session: LiveSession[Any] = Live.session("main")
          .withMountAspect(sessionAspect)(
            live.apply((_, _, context: String) => View)
          )
      """)

      assertTrue(errors.isEmpty)
    },
    test("session aspects run before route aspects in the typed pipeline") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        trait SessionEnvironment
        trait RouteEnvironment

        val sessionAspect = LiveMountAspect.fromRequest[SessionEnvironment, Any, String, String](
          _ => ZIO.succeed("session-claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )
        val routeAspect = LiveMountAspect.make[RouteEnvironment, Unit, String, Int, Int](
          (_, session) => ZIO.succeed(1 -> session.length),
          (_, _, session) => ZIO.succeed(session.length)
        )

        object View extends LiveView.Eventless[(String, Int)]:
          def mount(ctx: MountContext) = ZIO.succeed("" -> 0)
          def view(model: Signal[(String, Int)]) = div()

        val route = live
          .withMountAspect(routeAspect)
          .apply((_, _, context: (String, Int)) => View)

        val session: LiveSession[SessionEnvironment & RouteEnvironment] = Live.session("main")
          .withMountAspect(sessionAspect)(route)
      """)

      assertTrue(errors.isEmpty)
    },
    test("session layouts retain their earlier context projection") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val first = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("first-claim" -> "first"),
          (claim, _) => ZIO.succeed(claim)
        )
        val second = LiveMountAspect.make[Any, Any, String, Int, Int](
          (_, first) => ZIO.succeed(1 -> first.length),
          (_, _, first) => ZIO.succeed(first.length)
        )
        val layout = LiveLayout[Any, String]([Msg] => (content, context) =>
          if context.context.nonEmpty then content else content
        )

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val session: LiveSession[Any] = Live.session("main")
          .withMountAspect(first)
          .withLayout(layout)
          .withMountAspect(second)(live(View))
      """)

      assertTrue(errors.isEmpty)
    },
    test("incompatible session and route contexts are rejected") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val sessionAspect = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )

        object View extends LiveView.Eventless[Int]:
          def mount(ctx: MountContext) = ZIO.succeed(0)
          def view(model: Signal[Int]) = div()

        val route = live.apply((_, _, context: Int) => View)
        val session = Live.session("main").withMountAspect(sessionAspect)(route)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("router assembly remains declarative") {
      val applicationErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val session: LiveSession[Any] = Live.session("main")(live(View))
        val application: LiveApplication[Any] =
          Live.router.withRootLayout(LiveRootLayout.identity)(session)
      """)
      val executableErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.Routes

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val application: LiveApplication[Any] = Live.router(live(View))
        val executable: Routes[Any, Nothing] = application
      """)

      assertTrue(applicationErrors.isEmpty, executableErrors.nonEmpty)
    }
  )
