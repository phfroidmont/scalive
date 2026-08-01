package scaliveapi

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import scalive.*

object SecurityApiSpec extends ZIOSpecDefault:
  trait Auth:
    def authenticate(token: String): UIO[Option[User]]
    def resume(id: String): UIO[Option[User]]

  final case class User(id: String)
  final case class Claims(id: String) derives JsonCodec

  final class LoginView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext) = ZIO.unit
    def render(model: Unit)     = div("Login")

  final class ProtectedView(user: User) extends LiveView.Eventless[User]:
    def mount(ctx: MountContext) = ZIO.succeed(user)
    def render(model: User)      = div(model.id)

  private val Login = _root_.scalive.live / "login"
  private val Protected = _root_.scalive.live / "protected"

  def spec = suite("SecurityApiSpec")(
    test("shared cookies and authenticated aspects are usable from the public API") {
      val security = LiveSecurity(
        TokenConfig("public-security-api-secret", 1.hour),
        CookiePolicy(secure = true)
      )
      val aspect = LiveMountAspect.authenticated(
        "session",
        Login.location
      )(
        token =>
          ZIO
            .serviceWithZIO[Auth](_.authenticate(token))
            .map(_.map(user => Claims(user.id) -> user)),
        claims => ZIO.serviceWithZIO[Auth](_.resume(claims.id))
      )
      val expected: LiveMountAspect[Auth, Any, Any, Claims, User] = aspect
      val session = security.cookies.make("session", "opaque")
      val expired = security.cookies.expire("session")
      val redirect = Login.location.seeOther
      val formRoot = FormRoot("login")
      val email    = formRoot.requiredString("email")
      val decoder = HttpFormDecoder.urlEncoded(
        formRoot.form(email.codec).codec,
        maxBytes = 4096,
        security.csrf
      )
      val routes: Routes[Auth, Nothing] = scalive.Live.router.withSecurity(security)(
        Login(LoginView()),
        scalive.Live.session("authenticated").withMountAspect(aspect)(
          Protected.context(ProtectedView.apply)
        )
      )

      assertTrue(
        expected eq aspect,
        session.isSecure,
        session.isHttpOnly,
        expired.maxAge.contains(zio.Duration.Zero),
        redirect.status == Status.SeeOther,
        redirect.header(Header.Location).exists(_.url.encode == Login.location.href),
        decoder != null,
        routes != null
      )
    }
  )
