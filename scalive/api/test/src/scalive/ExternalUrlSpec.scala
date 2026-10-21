package scalive

import zio.http.URL
import zio.test.*

object ExternalUrlSpec extends ZIOSpecDefault:
  override def spec = suite("ExternalUrlSpec")(
    test("accepts absolute HTTP and HTTPS URLs with a host") {
      val accepted = Vector(
        "https://checkout.stripe.com/c/pay/cs_test_a1?locale=fr#fid=1",
        "http://example.com",
        "HTTPS://Example.com:8443/path"
      )

      assertTrue(
        accepted.forall(text => ExternalUrl.parse(text).isRight),
        ExternalUrl.parse("https://example.com/a?b=c").map(_.href) ==
          Right("https://example.com/a?b=c"),
        ExternalUrl.from(URL.decode("https://example.com/a").toOption.get).isRight
      )
    },
    test("rejects relative URLs, other schemes, a missing host, and control characters") {
      val rejected = Vector(
        "/settings",
        "settings",
        "//example.com/path",
        "?next=https://example.com",
        "javascript:alert(1)",
        "mailto:billing@example.com",
        "ftp://example.com/file",
        "https://",
        "https:///path",
        "https://example.com/\nLocation: https://evil.example",
        "https://example.com/%0d%0aLocation:%20https://evil.example",
        " https://example.com"
      )

      assertTrue(
        rejected.filter(text => ExternalUrl.parse(text).isRight).isEmpty,
        ExternalUrl.from(URL.decode("/settings").toOption.get).isLeft
      )
    }
  )
end ExternalUrlSpec
