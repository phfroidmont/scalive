package routing

import java.time.Duration

import zio.*
import zio.http.{Request, Status, URL}
import zio.test.{Live as _, live as _, *}

import scalive.*
import scalive.testing.*

object ErrorPagesSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(throw _, identity)

  private def get(path: String) =
    for
      routes <- ZioHttp.routes(ErrorPages.application, config)
      page   <- DisconnectedRender.run(routes, Request.get(URL.decode(path).toOption.get))
    yield page

  def spec = suite("Error pages")(
    test("a missing patient and an unknown URL answer the error page with 404") {
      for
        known     <- get("/patients/1")
        missing   <- get("/patients/2")
        unmatched <- get("/no/such/page")
      yield assertTrue(
        known.response.status == Status.Ok,
        known.text.contains("Ada Lovelace"),
        missing.response.status == Status.NotFound,
        missing.text.contains("Page not found"),
        !missing.text.contains("patient 2"),
        unmatched.response.status == Status.NotFound,
        unmatched.text.contains("Page not found")
      )
    }
  )
end ErrorPagesSpec
