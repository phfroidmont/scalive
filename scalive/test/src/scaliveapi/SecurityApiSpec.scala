package scaliveapi

import scala.concurrent.duration.*

import zio.*
import zio.json.*
import zio.test.*

import scalive.*

object SecurityApiSpec extends ZIOSpecDefault:
  trait Auth:
    def authenticate(token: String): UIO[Option[User]]
    def resume(id: String): UIO[Option[User]]

  final case class User(id: String)
  final case class Claims(id: String) derives JsonCodec

  private val Login = _root_.scalive.live / "login"

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

      assertTrue(
        expected eq aspect,
        session.isSecure,
        session.isHttpOnly,
        expired.maxAge.contains(zio.Duration.Zero)
      )
    }
  )
