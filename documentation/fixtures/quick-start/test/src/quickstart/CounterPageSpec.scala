// docs:start quick-start-disconnected-test
package quickstart

import java.time.Duration

import zio.*
import zio.http.{Request, Status, URL}
import zio.test.{Live as _, *}

import scalive.*
import scalive.testing.*

object CounterPageSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(error => throw IllegalArgumentException(error.toString), identity)

  private val application = Live.router(Routes.home -> CounterLiveView())
  private val routes      = ZioHttp.routes(application, config)

  def spec = suite("Counter page")(
    test("renders the initial count before the browser connects") {
      for
        page  <- DisconnectedRender.run(routes, Request.get(URL.root))
        count <- ZIO.fromEither(RenderedHtml.parse(page.html).selectOne("output"))
                   .mapError(error => AssertionError(error.toString))
      yield assertTrue(page.response.status == Status.Ok, count.text == "0")
    }
  )
// docs:end quick-start-disconnected-test
