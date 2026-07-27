package scalive

import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

object LiveRoutesTypeSafetySpec extends ZIOSpecDefault:

  private final case class TypeClaims(value: String) derives JsonCodec
  private final case class TypeRouteClaims(value: String) derives JsonCodec
  private final case class TypeUser(name: String)
  private final case class TypeOrg(name: String)
  private final case class TypeSection(name: String)

  private def view(text: String): LiveView[Unit, Unit] = new LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ZIO.unit
    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.unit
    def render(model: Unit): HtmlElement[Unit] = div(text)

  private def url(path: String): URL =
    URL.decode(path).toOption.get

  override def spec = suite("LiveRoutesTypeSafetySpec")(
    test("contextual route without provider does not compile") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.*

        final case class User(name: String)

        def view: LiveView[Unit, Unit] = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
          def render(model: Unit): HtmlElement[Unit] = div()

        val routes = scalive.Live.router(
          scalive.live { (_: Unit, _: Request, user: User) => view }
        )
      """)

      assertTrue(errors.nonEmpty)
    },
    test("wrong session context does not compile") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.*
        import zio.json.*

        final case class Claims(value: String) derives JsonCodec
        final case class User(name: String)
        final case class Role(name: String)

        def view: LiveView[Unit, Unit] = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
          def render(model: Unit): HtmlElement[Unit] = div()

        val aspect = LiveMountAspect.fromRequest[Any, Any, Claims, User](
          _ => ZIO.succeed(Claims("signed") -> User("disconnected")),
          (_, _) => ZIO.succeed(User("connected"))
        )

        val routes = scalive.Live.router(
          scalive.Live.session("admin").withMountAspect(aspect)(
            scalive.live { (_: Unit, _: Request, role: Role) => view }
          )
        )
      """)

      assertTrue(errors.nonEmpty)
    },
    test("routes with different environments infer an intersection environment") {
      trait Users:
        def name: String

      trait Orgs:
        def name: String

      val userAspect = LiveMountAspect.fromRequest[Users, Unit, TypeClaims, TypeUser](
        _ => ZIO.serviceWith[Users](users => TypeClaims(users.name) -> TypeUser(users.name)),
        (_, _) => ZIO.serviceWith[Users](users => TypeUser(users.name))
      )

      val orgAspect = LiveMountAspect.fromRequest[Orgs, Unit, TypeClaims, TypeOrg](
        _ => ZIO.serviceWith[Orgs](orgs => TypeClaims(orgs.name) -> TypeOrg(orgs.name)),
        (_, _) => ZIO.serviceWith[Orgs](orgs => TypeOrg(orgs.name))
      )

      val routes = scalive.Live.router(
        (scalive.live / "users").withMountAspect(userAspect) { (_, _, user: TypeUser) =>
          view(user.name)
        },
        (scalive.live / "orgs").withMountAspect(orgAspect) { (_, _, org: TypeOrg) =>
          view(org.name)
        }
      )

      val usersLayer: ULayer[Users] = ZLayer.succeed(new Users:
        def name = "alice"
      )
      val orgsLayer: ULayer[Orgs] = ZLayer.succeed(new Orgs:
        def name = "acme"
      )

      val response: UIO[Response] =
        ZIO.scoped(routes.runZIO(Request.get(url("/users")))).provideLayer(usersLayer ++ orgsLayer)

      for
        rendered <- response
        body     <- rendered.body.asString
      yield assertTrue(rendered.status == Status.Ok, body.contains("alice"))
    },
    test("session and route environments infer an intersection environment") {
      trait Auth:
        def user: String

      trait Catalog:
        def section: String

      val authAspect = LiveMountAspect.fromRequest[Auth, Any, TypeClaims, TypeUser](
        _ => ZIO.serviceWith[Auth](auth => TypeClaims(auth.user) -> TypeUser(auth.user)),
        (_, _) => ZIO.serviceWith[Auth](auth => TypeUser(auth.user))
      )

      val catalogAspect = LiveMountAspect.make[Catalog, Unit, TypeUser, TypeRouteClaims, TypeSection](
        (_, user) =>
          ZIO.serviceWith[Catalog](catalog => TypeRouteClaims(user.name) -> TypeSection(catalog.section)),
        (_, _, _) => ZIO.serviceWith[Catalog](catalog => TypeSection(catalog.section))
      )

      val routes = scalive.Live.router(
        scalive.Live.session("admin").withMountAspect(authAspect)(
          (scalive.live / "catalog").withMountAspect(catalogAspect) {
            (_, _, user: TypeUser, section: TypeSection) =>
              view(s"${user.name}:${section.name}")
          }
        )
      )

      val authLayer: ULayer[Auth] = ZLayer.succeed(new Auth:
        def user = "alice"
      )
      val catalogLayer: ULayer[Catalog] = ZLayer.succeed(new Catalog:
        def section = "hardware"
      )

      val response: UIO[Response] =
        ZIO.scoped(routes.runZIO(Request.get(url("/catalog")))).provideLayer(authLayer ++ catalogLayer)

      for
        rendered <- response
        body     <- rendered.body.asString
      yield assertTrue(rendered.status == Status.Ok, body.contains("alice:hardware"))
    },
    test("encodable route params expose final-domain location methods") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.codec.PathCodec

        final case class UserLocation(id: Int, tab: Option[String])

        val route =
          (live / "users" / PathCodec.int("id"))
            .queryOptional[String]("tab")
            .mapParams { case (id, tab) => UserLocation(id, tab) }(
              location => location.id -> location.tab
            )

        val location: LiveLocation = route.location(UserLocation(42, Some("settings")))
      """)

      assertTrue(errors.isEmpty)
    },
    test("decode-only route params do not expose location methods") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val route = live.paramsDecodeOnly(
          LiveParamsDecoder.custom[Unit, String]((_, url) => Right(url.path.encode))
        )

        route.location("/")
      """)

      assertTrue(errors.nonEmpty)
    },
    test("decode-only route params do not expose checked location methods") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val route = live.paramsDecodeOnly(
          LiveParamsDecoder.custom[Unit, String]((_, url) => Right(url.path.encode))
        )

        val location: Either[LiveLocation.EncodeError, LiveLocation] =
          route.locationEither("/")
      """)

      assertTrue(errors.exists(_.message.contains(
        "LiveRouteParamsCapability.DecodeOnly =:= scalive.LiveRouteParamsCapability.Encodable"
      )))
    },
    test("decode-only route params do not expose bidirectional mappings") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val route = live.paramsDecodeOnly(
          LiveParamsDecoder.custom[Unit, String]((_, url) => Right(url.path.encode))
        )

        val mapped: LiveRouteParamsBuilder[
          Any,
          Unit,
          Any,
          Any,
          String,
          LiveRouteParamsCapability.Encodable
        ] = route.mapParams(identity[String])(identity[String])
      """)

      assertTrue(errors.exists(_.message.contains(
        "LiveRouteParamsCapability.DecodeOnly =:= scalive.LiveRouteParamsCapability.Encodable"
      )))
    },
    test("decode-only route params can still be mounted") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        val route = live.paramsDecodeOnly(
          LiveParamsDecoder.custom[Unit, String]((_, url) => Right(url.path.encode))
        )

        val view = new LiveView.Routed[Unit, Unit, String]:
          def mount(ctx: MountContext) = ZIO.unit
          override def handleParams(
            model: Unit,
            params: String,
            url: zio.http.URL,
            ctx: ParamsContext
          ) = ZIO.succeed(model)
          def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
          def render(model: Unit): HtmlElement[Unit] = div()

        val mounted = route -> view
      """)

      assertTrue(errors.isEmpty)
    },
    test("named modifiers compile across supported builder stages") {
      val routeErrors: List[scala.compiletime.testing.Error] =
        scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        final case class Claims(value: String) derives JsonCodec
        final case class User(name: String)
        final case class Section(name: String)

        val routeUserAspect = LiveMountAspect.fromRequest[Any, Unit, Claims, User](
          _ => ZIO.succeed(Claims("user") -> User("disconnected")),
          (_, _) => ZIO.succeed(User("connected"))
        )
        val routeSectionAspect = LiveMountAspect.make[Any, Unit, User, Claims, Section](
          (_, user) => ZIO.succeed(Claims(user.name) -> Section("disconnected")),
          (_, _, _) => ZIO.succeed(Section("connected"))
        )
        val anyLayout = LiveLayout.identity
        val anyRoot = LiveRootLayout.identity
        val routeUserLayout = LiveLayout[Unit, User]((content, _) => content)
        val routeUserRoot = LiveRootLayout[Unit, User]("route-user-root")((content, _) => content)

        val seedWithAspect = live.withMountAspect(routeUserAspect)
        val seedWithLayout = live.withLayout(anyLayout)
        val seedWithRoot = live.withRootLayout(anyRoot)

        val routeBuilder = seedWithAspect
          .withLayout(routeUserLayout)
          .withRootLayout(routeUserRoot)
          .withMountAspect(routeSectionAspect)

        val paramsBuilder = live.params
          .withMountAspect(routeUserAspect)
          .withLayout(routeUserLayout)
          .withRootLayout(routeUserRoot)
      """)
      val sessionErrors: List[scala.compiletime.testing.Error] =
        scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        final case class Claims(value: String) derives JsonCodec
        final case class User(name: String)
        final case class Section(name: String)

        val sessionUserAspect = LiveMountAspect.fromRequest[Any, Any, Claims, User](
          _ => ZIO.succeed(Claims("user") -> User("disconnected")),
          (_, _) => ZIO.succeed(User("connected"))
        )
        val sessionSectionAspect = LiveMountAspect.make[Any, Any, User, Claims, Section](
          (_, user) => ZIO.succeed(Claims(user.name) -> Section("disconnected")),
          (_, _, _) => ZIO.succeed(Section("connected"))
        )

        val anyLayout = LiveLayout.identity
        val anyRoot = LiveRootLayout.identity
        val sessionUserLayout = LiveLayout[Any, User]((content, _) => content)
        val sessionUserRoot = LiveRootLayout[Any, User]("session-user-root")((content, _) => content)

        val sessionSeedWithAspect = Live.session("admin").withMountAspect(sessionUserAspect)
        val sessionSeedWithLayout = Live.session("layout").withLayout(anyLayout)
        val sessionSeedWithRoot = Live.session("root").withRootLayout(anyRoot)
        val sessionBuilder = sessionSeedWithAspect
          .withLayout(sessionUserLayout)
          .withRootLayout(sessionUserRoot)
          .withMountAspect(sessionSectionAspect)
      """)
      val routerErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.codec.PathCodec

        val anyLayout = LiveLayout.identity
        val anyRoot = LiveRootLayout.identity
        val router = Live.router
          .withLayout(anyLayout)
          .withRootLayout(anyRoot)
          .withSocketPath(PathCodec.empty / "socket")
          .withTokenConfig(TokenConfig.default)
      """)

      assertTrue(routeErrors.isEmpty, sessionErrors.isEmpty, routerErrors.isEmpty)
    },
    test("router-only modifiers are unavailable on routes and sessions") {
      val routeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.codec.PathCodec
        val route = live.withSocketPath(PathCodec.empty / "socket")
      """)
      val sessionErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val session = Live.session("admin").withTokenConfig(TokenConfig.default)
      """)

      assertTrue(routeErrors.nonEmpty, sessionErrors.nonEmpty)
    },
    test("symbolic and wrapper route modifiers are unavailable") {
      val operatorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val route = live @@ LiveLayout.identity
      """)
      val socketWrapperErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.codec.PathCodec
        val mount = Live.socketAt(PathCodec.empty / "socket")
      """)
      val tokenWrapperErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val config = Live.tokenConfig(TokenConfig.default)
      """)

      assertTrue(
        operatorErrors.nonEmpty,
        socketWrapperErrors.nonEmpty,
        tokenWrapperErrors.nonEmpty
      )
    }
  )
end LiveRoutesTypeSafetySpec
