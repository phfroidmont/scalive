package scalive.docs

import zio.*
import zio.http.*
import zio.test.*

object DocumentationSiteSpec extends ZIOSpecDefault:
  private val revision = "0123456789abcdef0123456789abcdef01234567"

  override def spec = suite("DocumentationSiteSpec")(
    test("reports the exact running documentation revision") {
      for
        response <- ZIO.scoped(
                      DocumentationSite.healthRoutes(revision).runZIO(
                        Request.get(URL.decode("/health").fold(throw _, identity))
                      )
                    )
        body <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        response.header(Header.CacheControl).contains(Header.CacheControl.NoStore),
        body == revision
      )
    }
  )
end DocumentationSiteSpec
