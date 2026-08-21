package scaliveapi

import zio.http.{Header, Status}
import zio.test.*

import scalive.*

object LiveLocationSpec extends ZIOSpecDefault:
  def spec = suite("LiveLocationSpec")(
    test("creates an HTTP see-other response") {
      val response = (scalive.live / "home").location.seeOther

      assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url.encode == "/home")
      )
    }
  )
end LiveLocationSpec
