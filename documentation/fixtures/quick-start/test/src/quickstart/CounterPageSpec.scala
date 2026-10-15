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
  ).fold(throw _, identity)

  private val application = Live.router(Routes.home -> CounterLiveView())

  def spec = suite("Counter page")(
    test("renders the initial count before the browser connects") {
      for
        routes <- ZioHttp.routes(application, config)
        page   <- DisconnectedRender.run(routes, Request.get(URL.root))
        count  <- ZIO.fromEither(RenderedHtml.parse(page.html).selectOne("output"))
                   .mapError(error => AssertionError(error.toString))
      yield assertTrue(page.response.status == Status.Ok, count.text == "0")
    }
  )
// docs:end quick-start-disconnected-test
