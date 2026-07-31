package scalive

import zio.Duration
import zio.http.{Cookie, Path}
import zio.test.*

object CookiePolicySpec extends ZIOSpecDefault:
  def spec = suite("CookiePolicySpec")(
    test("creates and expires cookies with one hardened policy") {
      val policy = CookiePolicy(secure = true)
      val created = policy.make("session", "token", maxAge = Some(Duration.fromSeconds(60)))
      val expired = policy.expire("session")

      assertTrue(
        created.path.contains(Path.root),
        created.isSecure,
        created.isHttpOnly,
        created.sameSite.contains(Cookie.SameSite.Lax),
        created.maxAge.contains(Duration.fromSeconds(60)),
        expired.path == created.path,
        expired.isSecure == created.isSecure,
        expired.isHttpOnly == created.isHttpOnly,
        expired.sameSite == created.sameSite,
        expired.content.isEmpty,
        expired.maxAge.contains(Duration.Zero)
      )
    }
  )
