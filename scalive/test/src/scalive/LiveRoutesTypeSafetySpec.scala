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
          (scalive.Live.session("admin") @@ aspect)(
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
        ((scalive.live / "users") @@ userAspect) { (_, _, user: TypeUser) =>
          view(user.name)
        },
        ((scalive.live / "orgs") @@ orgAspect) { (_, _, org: TypeOrg) =>
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
        (scalive.Live.session("admin") @@ authAspect)(
          ((scalive.live / "catalog") @@ catalogAspect) {
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
    }
  )
end LiveRoutesTypeSafetySpec
