package scaliveapi

import zio.test.*

object RoutedConstructionSpec extends ZIOSpecDefault:
  override def spec = suite("RoutedConstructionSpec")(
    test("route values build links and register LiveViews") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.codec.PathCodec
        import zio.schema.{Schema, derived}

        final case class Search(q: Option[String]) derives Schema
        final case class Page(value: Option[Int])

        object Home extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        object Item extends LiveView.Routed.Eventless[Int, Int]:
          def mount(params: Int, ctx: MountContext) = ZIO.succeed(params)
          def view(model: Signal[Int]) = div()
        object SearchView extends LiveView.Routed.Eventless[String, Search]:
          def mount(params: Search, ctx: MountContext) = ZIO.succeed("")
          def view(model: Signal[String]) = div(model)
        object PageView extends LiveView.Routed.Eventless[String, Page]:
          def mount(params: Page, ctx: MountContext) = ZIO.succeed("")
          def view(model: Signal[String]) = div(model)

        object Routes:
          val home   = live
          val item   = live / "items" / PathCodec.int("id")
          val search = (live / "search").query[Search]
          val page   = (live / "page").queryOptional[Int]("page").mapParams(Page.apply)(_.value)

        val homeLink: LiveLocation   = Routes.home.location
        val itemLink: LiveLocation   = Routes.item.location(1)
        val searchLink: LiveLocation = Routes.search.location(Search(Some("typed")))
        val pageLink: LiveLocation   = Routes.page.location(Page(Some(2)))

        val application: LiveApplication[Any] = Live.router(
          Routes.home -> Home,
          Routes.item -> Item,
          Routes.item -> Home,
          Routes.search -> SearchView,
          Routes.page -> PageView,
          Live.route(PathCodec.empty / "explicit") -> Home
        )
      """)

      assertTrue(errors.isEmpty)
    },
    test("setup accepts layouts, guards, resources, and aspects in any order") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        object Layout extends LiveLayout[Any, Any]:
          def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, Any]) = div(content)
        object Search extends LiveView.Routed.Eventless[Unit, Option[String]]:
          def mount(params: Option[String], ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val aspect = LiveRouteMountAspect.fromRequest[Any, Unit, Int](_ => ZIO.succeed(1))
        val search = (live / "search").queryOptional[String]("q")

        val layoutAfterQuery = search.withLayout(Layout) -> Search
        val guardsFirst = search
          .guardConnectedTurns(_ => ZIO.unit)
          .withConnectedResources((_, _) => ZIO.unit)
          .withLayout(Layout)
          .withMountAspect(aspect)
          .withLayout(Layout)
          .guardConnectedTurns((count: Int) => ZIO.unit)
          .withConnectedResources((count: Int, _) => ZIO.unit) -> Search
      """)
      val setupHasNoLinks = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        object Layout extends LiveLayout[Any, Any]:
          def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, Any]) = div(content)
        val link = (live / "search").withLayout(Layout).location
      """)
      val setupHasNoPath = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        object Layout extends LiveLayout[Any, Any]:
          def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, Any]) = div(content)
        val longer = (live / "items").withLayout(Layout) / "more"
      """)

      assertTrue(errors.isEmpty, setupHasNoLinks.nonEmpty, setupHasNoPath.nonEmpty)
    },
    test("a routed LiveView must take the route's parameters") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        object Item extends LiveView.Routed.Eventless[Int, Int]:
          def mount(params: Int, ctx: MountContext) = ZIO.succeed(params)
          def view(model: Signal[Int]) = div()

        val search = (live / "search").queryOptional[String]("q") -> Item
      """)

      assertTrue(
        errors.exists(_.message.contains(
          "Item.type cannot be mounted on a route whose parameters are Option[String]"
        ))
      )
    },
    test("decode-only routes and request factories are gone") {
      val decodeOnly = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val route = live.paramsDecodeOnly(LiveParamsCodec.path[Unit])
      """)
      val requestFactory = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.Request
        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        val route = live((_: Request) => View)
      """)

      assertTrue(decodeOnly.nonEmpty, requestFactory.nonEmpty)
    },
    test("contexts accumulate as flat tuples") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        final case class User(name: String)
        final case class Access(canEdit: Boolean)
        final case class Locale(tag: String)

        val user = LiveSessionMountAspect.fromRequest[Any, String, User](
          _ => ZIO.succeed("ada" -> User("ada")),
          (claim, _) => ZIO.succeed(User(claim))
        )
        val access = LiveRouteMountAspect.make[Any, Unit, User, Access]((_, _) => ZIO.succeed(Access(true)))
        val locale = LiveRouteMountAspect.make[Any, Unit, (User, Access), Locale]((_, _) => ZIO.succeed(Locale("en")))
        val userLayout = new LiveLayout[Any, User] { def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, User]) = content }
        val accessLayout = new LiveLayout[Any, (User, Access)] { def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, (User, Access)]) = content }

        final class Workspace(user: User, access: Access, locale: Locale) extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val session: LiveSession[Any] = Live.session("account").withMountAspect(user)(
          live
            .withMountAspect(access)
            .withLayout(accessLayout)
            .withMountAspect(locale)
            .guardConnectedTurns((context: (User, Access, Locale)) => ZIO.unit)
            .context((user, access, locale) => Workspace(user, access, locale))
        )
      """)

      assertTrue(errors.isEmpty)
    },
    test("routes read session context only inside a session that produces it") {
      val sessionScoped = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        final case class User(name: String)
        final case class Org(id: String)
        final class Account(user: User) extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        final class Billing(user: User, org: Org) extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val user = LiveSessionMountAspect.fromRequest[Any, String, User](
          _ => ZIO.succeed("ada" -> User("ada")),
          (claim, _) => ZIO.succeed(User(claim))
        )
        val org = LiveSessionMountAspect.make[Any, User, String, Org](
          (_, _) => ZIO.succeed("acme" -> Org("acme")),
          (claim, _, _) => ZIO.succeed(Org(claim))
        )

        val single: LiveSession[Any] =
          Live.session("account").withMountAspect(user)(
            (live / "account").context((user: User) => Account(user))
          )
        val both: LiveSession[Any] =
          Live.session("billing").withMountAspect(user).withMountAspect(org)(
            (live / "billing").context((user, org) => Billing(user, org))
          )
      """)
      val wrongSession = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*
        final case class User(name: String)
        final case class Org(id: String)
        final class Account(user: User) extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        val org = LiveSessionMountAspect.fromRequest[Any, String, Org](
          _ => ZIO.succeed("acme" -> Org("acme")),
          (claim, _) => ZIO.succeed(Org(claim))
        )
        val session = Live.session("s").withMountAspect(org)(
          (live / "account").context((user: User) => Account(user))
        )
      """)

      assertTrue(
        sessionScoped.isEmpty,
        wrongSession.exists(_.message.contains("Required: Org => Account"))
      )
    },
    test("the router explains routes that read session context") {
      inline val definitions = """
        import scalive.*
        import zio.*
        final case class User(name: String)
        final case class Access(canEdit: Boolean)
        final class Account(user: User) extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        object Home extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        val access = LiveRouteMountAspect.make[Any, Unit, User, Access]((_, _) => ZIO.succeed(Access(true)))
      """
      val direct = scala.compiletime.testing.typeCheckErrors(definitions + """
        val application = Live.router(
          live -> Home,
          (live / "account").context((user: User) => Account(user))
        )
      """)
      val declared = scala.compiletime.testing.typeCheckErrors(definitions + """
        val account     = (live / "account").context((user: User) => Account(user))
        val application = Live.router(account)
      """)
      val aspect = scala.compiletime.testing.typeCheckErrors(definitions + """
        val application = Live.router((live / "workspace").withMountAspect(access) -> Home)
      """)
      val spliced = scala.compiletime.testing.typeCheckErrors(definitions + """
        val routes      = Vector((live / "account").context((user: User) => Account(user)))
        val application = Live.router(routes*)
      """)
      val unknown = scala.compiletime.testing.typeCheckErrors(definitions + """
        val account: LiveRegistration[Any] = (live / "account").context((user: User) => Account(user))
        val application = Live.router(account)
      """)
      val accepted = scala.compiletime.testing.typeCheckErrors(definitions + """
        val routes: Vector[LiveRegistration[Any] { type Input = Any }] = Vector(live -> Home)
        val application = Live.router((routes :+ ((live / "x") -> Home))*)
      """)
      val message = "This route reads User from a named live session"

      assertTrue(
        direct.map(_.message).exists(_.startsWith(message)),
        declared.map(_.message).exists(_.startsWith(message)),
        aspect.map(_.message).exists(_.startsWith(message)),
        spliced.map(_.message).exists(_.startsWith(message)),
        unknown.map(_.message).exists(_.contains("does not say which session context")),
        accepted.isEmpty
      )
    },
    test("services come from an effectful factory, with or without context") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        trait Reports
        trait Clock2
        final case class User(name: String)
        final class ReportsView(reports: Reports, clock: Clock2) extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()
        final class Account(user: User, reports: Reports) extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val user = LiveSessionMountAspect.fromRequest[Any, String, User](
          _ => ZIO.succeed("ada" -> User("ada")),
          (claim, _) => ZIO.succeed(User(claim))
        )

        val reports: LiveRegistration[Reports & Clock2] { type Input = Any } =
          (live / "reports").from(
            for
              reports <- ZIO.service[Reports]
              clock   <- ZIO.service[Clock2]
            yield ReportsView(reports, clock)
          )
        val account: LiveRegistration[Reports] { type Input = User } =
          (live / "account").from((user: User) => ZIO.serviceWith[Reports](Account(user, _)))
        val application: LiveApplication[Reports & Clock2] =
          Live.router(reports, Live.session("account").withMountAspect(user)(account))
      """)

      assertTrue(errors.isEmpty)
    },
    test("routed views patch by their own parameters") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        enum Msg:
          case Next

        object Search extends LiveView.Routed[Msg, Int, Option[String]]:
          def mount(params: Option[String], ctx: MountContext) = ZIO.succeed(0)
          def handleMessage(model: Int, ctx: MessageContext) =
            case Msg.Next => ctx.nav.pushPatch(Some("next")).as(model + 1)
          override def handleParamsDecodeError(
            model: Int,
            error: LiveParamsCodec.DecodeError,
            url: zio.http.URL,
            ctx: ParamsContext
          ) = ctx.nav.replacePatch(None).as(model)
          def view(model: Signal[Int]) = div()
      """)
      val wrongParams = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        enum Msg:
          case Next

        object Search extends LiveView.Routed[Msg, Int, Option[String]]:
          def mount(params: Option[String], ctx: MountContext) = ZIO.succeed(0)
          def handleMessage(model: Int, ctx: MessageContext) =
            case Msg.Next => ctx.nav.pushPatch(42).as(model + 1)
          def view(model: Signal[Int]) = div()
      """)

      assertTrue(errors.isEmpty, wrongParams.nonEmpty)
    },
    test("live sessions cannot be nested") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val inner = Live.session("inner")(live -> View)
        val outer = Live.session("outer")(inner)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("a session cannot declare a second admission") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        final case class Id(value: String) derives JsonCodec
        val aspect = LiveSessionMountAspect.fromRequest[Any, Id, Id](
          _ => ZIO.succeed(Id("a") -> Id("a")),
          (claim, _) => ZIO.succeed(claim)
        )
        val twice = Live.session("s").withAdmission(aspect)(identity).withAdmission(aspect)(identity)
      """)

      assertTrue(errors.exists(_.message.contains("at most one admission")))
    },
    test("guards and connected resources reject environments and non-Unit results") {
      val guardEnvironment = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        trait Service
        val route = live.guardConnectedTurns(_ => ZIO.service[Service].unit)
      """)
      val guardResult = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        val route = live.guardConnectedTurns(_ => ZIO.succeed(1))
      """)
      val resourceEnvironment = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        trait Service
        val route = live.withConnectedResources((_, _) => ZIO.service[Service].unit)
      """)
      val resourceResult = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        val route = live.withConnectedResources((_, _) => ZIO.succeed(1))
      """)

      assertTrue(
        guardEnvironment.nonEmpty,
        guardResult.nonEmpty,
        resourceEnvironment.nonEmpty,
        resourceResult.nonEmpty
      )
    },
    test("layouts cannot change the LiveView message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object Layout extends LiveLayout[Unit, Unit]:
          def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Unit, Unit]): HtmlElement[Msg] =
            div(on.click("wrong message"), content)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("layout context adapters are public, reusable, and preserve variance") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        final case class RouteContext(name: String)
        final case class SessionContext(name: String)

        val layout = new LiveLayout[Any, String] { def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, String]) = content }
        val routeLayout: LiveLayout[String, RouteContext] =
          layout.forContext(_.name)
        val sessionLayout: LiveLayout[Int, SessionContext] =
          layout.forContext(_.name)

        val root = new LiveRootLayout[Any, String] { def view[Msg](content: HtmlElement[Msg], title: Option[String], context: LiveRootLayoutContext[Any, String]) = content }
        val routeRoot: LiveRootLayout[String, RouteContext] =
          root.forContext(_.name)
        val sessionRoot: LiveRootLayout[Int, SessionContext] =
          root.forContext(_.name)

        val layoutVariance: LiveLayout[String, String] = LiveLayout.identity
        val rootVariance: LiveRootLayout[String, String] = LiveRootLayout.default
      """)

      assertTrue(errors.isEmpty)
    },
    test("layout implementations cannot override framework context adaptation") {
      val ordinary = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        object Layout extends LiveLayout[Any, String]:
          def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, String]) = content
          override def forContext[C](select: C => String): LiveLayout[Any, C] = LiveLayout.identity
      """)
      val root = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        object Root extends LiveRootLayout[Any, String]:
          override def key(context: LiveRootLayoutContext[Any, String]) = context.context
          def view[Msg](content: HtmlElement[Msg], title: Option[String], context: LiveRootLayoutContext[Any, String]) = content
          override def forContext[C](select: C => String): LiveRootLayout[Any, C] = LiveRootLayout.default
      """)

      assertTrue(
        ordinary.exists(_.message.contains("final")),
        root.exists(_.message.contains("final"))
      )
    },
    test("layout context adapters reject selectors with the wrong input or result") {
      val wrongInput = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val layout = new LiveLayout[Any, String] { def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, String]) = content }
        val invalid = layout.forContext[Int](_.name)
      """)
      val wrongResult = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val root = new LiveRootLayout[Any, String] { def view[Msg](content: HtmlElement[Msg], title: Option[String], context: LiveRootLayoutContext[Any, String]) = content }
        val invalid = root.forContext[Int](_ + 1)
      """)

      assertTrue(wrongInput.nonEmpty, wrongResult.nonEmpty)
    },
    test("typed layout builders reject incompatible selectors") {
      val wrongInput = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        val aspect = LiveRouteMountAspect.fromRequest[Any, Unit, String](_ => ZIO.succeed("ctx"))
        val layout = new LiveLayout[Unit, Int] { def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Unit, Int]) = content }
        val invalid = live.withMountAspect(aspect).withLayout(layout, (value: Boolean) => 1)
      """)
      val wrongResult = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        val aspect = LiveSessionMountAspect.fromRequest[Any, String, String](
          _ => ZIO.succeed("claim" -> "ctx"),
          (_, _) => ZIO.succeed("ctx")
        )
        val root = new LiveRootLayout[Any, Int] { def view[Msg](content: HtmlElement[Msg], title: Option[String], context: LiveRootLayoutContext[Any, Int]) = content }
        val invalid = Live.session("main").withMountAspect(aspect).withRootLayout(root, identity)
      """)

      assertTrue(wrongInput.nonEmpty, wrongResult.nonEmpty)
    },
    test("context-free route and router builders do not expose selector overloads") {
      val routeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val layout = new LiveLayout[Any, Any] { def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, Any]) = content }
        val invalid = live.withLayout(layout, identity)
      """)
      val routerErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val root = new LiveRootLayout[Any, Any] { def view[Msg](content: HtmlElement[Msg], title: Option[String], context: LiveRootLayoutContext[Any, Any]) = content }
        val invalid = Live.router.withRootLayout(root, identity)
      """)

      assertTrue(routeErrors.nonEmpty, routerErrors.nonEmpty)
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

        val first = LiveRouteMountAspect.fromRequest[Any, Unit, String](
          request =>
            if request.params == () then ZIO.succeed("first")
            else ZIO.fail(LiveRouteMountFailure.notFound)
        )
        val second = LiveRouteMountAspect.make[Any, Unit, String, Int](
          (request, input) =>
            if request.params == () then ZIO.succeed(input.length)
            else ZIO.fail(LiveRouteMountFailure.notFound)
        )
        val layout = new LiveLayout[Unit, Int] { def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Unit, Int]) = content }
        val root = new LiveRootLayout[Unit, Int] { def view[Msg](content: HtmlElement[Msg], title: Option[String], context: LiveRootLayoutContext[Unit, Int]) = content }

        object View extends LiveView.Eventless[Combined]:
          def mount(ctx: MountContext) = ZIO.succeed(Combined("", 0))
          def view(model: Signal[Combined]) = div()

        val route: LiveRegistration[Any] { type Input = Any } = live
          .withMountAspect(first)
          .withLayout(layout, _.length)
          .withRootLayout(root, _.length)
          .withMountAspect(second)
          .context((context: Combined) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("session aspect context reaches route factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val sessionAspect = LiveSessionMountAspect.fromRequest[Any, String, String](
          _ => ZIO.succeed("claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          def view(model: Signal[String]) = div(model)

        val root = new LiveRootLayout[Any, String] { def view[Msg](content: HtmlElement[Msg], title: Option[String], context: LiveRootLayoutContext[Any, String]) = content }
        val session: LiveSession[Any] = Live.session("main")
          .withMountAspect(sessionAspect)(
            live.context((context: String) => View)
          )

        val sessionWithRoot: LiveSession[Any] = Live.session("main-root")
          .withMountAspect(sessionAspect)
          .withRootLayout(root)(live.context((context: String) => View))
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

        val sessionAspect = LiveSessionMountAspect.fromRequest[SessionEnvironment, String, String](
          _ => ZIO.succeed("session-claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )
        val routeAspect = LiveRouteMountAspect.make[RouteEnvironment, Unit, String, Int](
          (request, session) =>
            if request.params == () then ZIO.succeed(session.length)
            else ZIO.fail(LiveRouteMountFailure.notFound)
        )

        object View extends LiveView.Eventless[(String, Int)]:
          def mount(ctx: MountContext) = ZIO.succeed("" -> 0)
          def view(model: Signal[(String, Int)]) = div()

        val route = live
          .withMountAspect(routeAspect)
          .context(_ => View)

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

        val first = LiveSessionMountAspect.fromRequest[Any, String, String](
          _ => ZIO.succeed("first-claim" -> "first"),
          (claim, _) => ZIO.succeed(claim)
        )
        val second = LiveSessionMountAspect.make[Any, String, Int, Int](
          (_, first) => ZIO.succeed(1 -> first.length),
          (_, _, first) => ZIO.succeed(first.length)
        )
        val layout = new LiveLayout[Any, Int]:
          def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, Int]) =
            if context.context > 0 then content else content
        val root = new LiveRootLayout[Any, Int] { def view[Msg](content: HtmlElement[Msg], title: Option[String], context: LiveRootLayoutContext[Any, Int]) = content }

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val session: LiveSession[Any] = Live.session("main")
          .withMountAspect(first)
          .withLayout(layout, _.length)
          .withRootLayout(root, _.length)
          .withMountAspect(second)(live -> View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("incompatible session and route contexts are rejected") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val sessionAspect = LiveSessionMountAspect.fromRequest[Any, String, String](
          _ => ZIO.succeed("claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )

        object View extends LiveView.Eventless[Int]:
          def mount(ctx: MountContext) = ZIO.succeed(0)
          def view(model: Signal[Int]) = div()

        val route = live.context((context: Int) => View)
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

        val session: LiveSession[Any] = Live.session("main")(live -> View)
        val application: LiveApplication[Any] =
          Live.router.withRootLayout(LiveRootLayout.default)(session)
      """)
      val executableErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.Routes

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val application: LiveApplication[Any] = Live.router(live -> View)
        val executable: Routes[Any, Nothing] = application
      """)

      assertTrue(applicationErrors.isEmpty, executableErrors.nonEmpty)
    }
  )
end RoutedConstructionSpec
