package routing

import java.time.Duration

import zio.*
import zio.http.{Cookie, Header, Request, Status, URL}
import zio.test.{Live as _, live as _, *}

import scalive.*

object HttpRoutesSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(throw _, identity)

  private def get(path: String, reader: Option[String]) =
    val request = Request.get(URL.decode(path).toOption.get)
    for
      routes   <- ZioHttp.routes(HttpRoutes.application, config)
      response <- ZIO.scoped(
                    routes.runZIO(reader.fold(request)(name =>
                      request.addCookie(Cookie.Request("reader", name))
                    ))
                  )
      body <- response.body.asString
    yield response -> body

  def spec = suite("HTTP routes")(
    test("an HTTP route is admitted by its session and builds typed links") {
      for
        pdf       <- get("/invoices/1/invoice.pdf", Some("ada"))
        missing   <- get("/invoices/2/invoice.pdf", Some("ada"))
        anonymous <- get("/invoices/1/invoice.pdf", None)
        page      <- get("/invoices/1", Some("ada"))
      yield assertTrue(
        pdf._1.status == Status.Ok,
        pdf._2 == "%PDF invoice 1 for ada",
        pdf._1.header(Header.ContentType).exists(_.mediaType.fullType == "application/pdf"),
        missing._1.status == Status.NotFound,
        anonymous._1.status == Status.SeeOther,
        page._2.contains("href=\"/invoices/1/invoice.pdf\"")
      )
    }.provide(ZLayer.succeed(Invoices()))
  )
end HttpRoutesSpec
