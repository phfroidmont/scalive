package routing

import java.time.Duration
import java.util.UUID

import zio.*
import zio.http.{Request, Status, URL}
import zio.test.{Live as _, live as _, *}

import scalive.*
import scalive.testing.*

object QueryValuesSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(throw _, identity)

  private def get(path: String) =
    for
      routes <- ZioHttp.routes(QueryValues.application, config)
      page   <- DisconnectedRender.run(routes, Request.get(URL.decode(path).toOption.get))
    yield page

  def spec = suite("Optional and untrusted query values")(
    test("the plain path and the query route build the documented links") {
      assertTrue(
        LoginLinks.plain.href == "/login",
        LoginLinks.returning.href == "/login?returnTo=%2Faccount",
        LoginRoutes.loginWithReturn.location(None).href == "/login"
      )
    },
    test("registering the plain path beside the query route fails as a duplicate path") {
      object Plain extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext)  = ZIO.unit
        def view(model: Signal[Unit]) = div()
      val both = Live.router(
        LoginRoutes.login           -> Plain,
        LoginRoutes.loginWithReturn -> LoginLiveView()
      )
      for error <- ZioHttp.routes(both, config).flip
      yield assertTrue(error.getMessage.startsWith("duplicate rendered route path"))
    },
    test("returnTo accepts only paths on this site") {
      assertTrue(
        ReturnTo.from("/account?tab=1").map(_.path).contains("/account?tab=1"),
        ReturnTo.from("https://evil.example").isEmpty,
        ReturnTo.from("//evil.example").isEmpty,
        ReturnTo.from("/\\evil.example").isEmpty
      )
    },
    test("the login page seeds its hidden field from a safe returnTo only") {
      for
        safe   <- get("/login?returnTo=%2Faccount")
        unsafe <- get("/login?returnTo=https://evil.example")
        bare   <- get("/login")
      yield assertTrue(
        safe.html.contains("value=\"/account\""),
        unsafe.response.status == Status.Ok,
        !unsafe.html.contains("evil.example"),
        bare.response.status == Status.Ok
      )
    },
    test("a reset link decodes every missing or malformed value to its own view") {
      val id   = UUID.randomUUID()
      val code = UUID.randomUUID()
      for
        bare      <- get("/reset-password")
        malformed <- get("/reset-password?id=garbage&code=garbage")
        partial   <- get(s"/reset-password?id=$id")
        usable    <- get(s"/reset-password?id=$id&code=$code")
        repeated  <- get(s"/reset-password?id=$id&id=$id&code=$code")
      yield assertTrue(
        Vector(bare, malformed, partial).forall(page =>
          page.response.status == Status.Ok && page.text.contains("This link cannot be used.")
        ),
        usable.text.contains("Choose a new password."),
        repeated.response.status == Status.BadRequest,
        ResetRoutes.resetPassword.location(ResetLink.Unusable).href == "/reset-password",
        ResetRoutes.resetPassword.location(ResetLink.Usable(id, code)).href ==
          s"/reset-password?id=$id&code=$code"
      )
    }
  )
end QueryValuesSpec
