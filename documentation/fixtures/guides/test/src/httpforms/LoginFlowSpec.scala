package httpforms

import java.time.Duration

import zio.*
import zio.http.{Method, Request, URL}
import zio.test.*

import scalive.*
import scalive.testing.*

object LoginFlowSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(error => throw IllegalArgumentException(error.toString), identity)

  private val routes = LoginApp.routes(config)

  private val sessions: ULayer[Sessions] = ZLayer.succeed(
    new Sessions:
      def create(credentials: Credentials): IO[Sessions.Error, String] =
        if credentials.password == "correct horse" then ZIO.succeed("session-token")
        else ZIO.fail(Sessions.Error.InvalidCredentials)
      def revoke(token: String): UIO[Unit] = ZIO.unit
  )

  def spec = suite("Login flow")(
    test("signing in redirects to the dashboard") {
      val login = Request.get(URL.decode("/login").toOption.get)
      // docs:start testing-submit-form
      val signIn =
        for
          page <- DisconnectedRender.run(routes, login)
          form <- ZIO
                    .fromEither(page.form(FormQuery(method = Some(Method.POST))))
                    .mapError(error => AssertionError(error.toString))
          csrf <- ZIO
                    .fromOption(form.values(CsrfProtection.ParamName).headOption)
                    .orElseFail(AssertionError("The form has no CSRF field."))
          redirect <- form.submit(
                        routes,
                        FormData(
                          Vector(
                            CsrfProtection.ParamName -> csrf,
                            LoginForm.Email.name     -> "ada@example.test",
                            LoginForm.Password.name  -> "correct horse"
                          )
                        )
                      )
          dashboard <- redirect.followSeeOther(routes)
        yield assertTrue(dashboard.text.contains("Dashboard"))
      // docs:end testing-submit-form
      signIn.provide(sessions)
    }
  )
