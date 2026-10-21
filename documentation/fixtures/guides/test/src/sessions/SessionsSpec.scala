package sessions

import java.time.Duration

import zio.*
import zio.http.{Cookie, Request, Response, Status, URL}
import zio.test.*

import scalive.*
import scalive.testing.*

object SessionsSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(throw _, identity)

  private val ada = CurrentUser(1L, "Ada")

  private val services =
    ZLayer.succeed[Authentication](
      new Authentication:
        def authenticate(request: Request) =
          request.cookie("session").map(_.content) match
            case Some("ada-session") =>
              ZIO.succeed(AuthenticatedSession(SessionId("ada-session"), ada))
            case _ => ZIO.fail(Response.status(Status.Unauthorized))
        def resume(sessionId: SessionId) =
          if sessionId.value == "ada-session" then ZIO.succeed(ada)
          else ZIO.fail(LiveSessionMountFailure.unauthorized)
    ) ++ ZLayer.succeed[Workspaces](
      new Workspaces:
        def authorize(user: CurrentUser, workspaceId: String) =
          if workspaceId == "docs" then ZIO.succeed(WorkspaceAccess(workspaceId, canEdit = true))
          else ZIO.fail(LiveRouteMountFailure.notFound)
    )

  private def request(path: String, signedIn: Boolean = true) =
    val request = Request.get(URL.decode(path).toOption.get)
    if signedIn then request.addCookie(Cookie.Request("session", "ada-session")) else request

  def spec = suite("Live sessions")(
    test("named session and route contexts reach the page by name") {
      ZIO.scoped {
        for
          client <- ConnectedRender.open(
                      NamedContexts.application,
                      config,
                      request("/workspaces/docs")
                    )
          view    <- client.join
          heading <- view.text("h1")
        yield assertTrue(heading == "Ada: docs")
      }
    },
    test("session and route context reach the connected page and the session layout") {
      ZIO.scoped {
        for
          client  <- ConnectedRender.open(Application.application, config, request("/workspaces/docs"))
          view    <- client.join
          heading <- view.text("h1")
          header  <- view.text("header p")
        yield assertTrue(heading == "Ada: docs", header == "Signed in as Ada")
      }
    },
    test("mount aspects annotate the logs of the pages they admit") {
      ZIO.scoped {
        for
          client <- ConnectedRender.open(LogAnnotations.application, config, request("/workspaces/docs"))
          _      <- client.join
          logs   <- ZTestLogger.logOutput
          opened = logs.filter(_.message() == "Workspace opened")
        yield assertTrue(
          opened.size == 2,
          opened.forall(entry =>
            entry.annotations.get("user").contains("1") &&
              entry.annotations.get("workspace").contains("docs")
          )
        )
      }
    },
    test("live navigation stays inside the session") {
      ZIO.scoped {
        // docs:start testing-follow-navigation
        for
          client   <- ConnectedRender.open(Application.application, config, request("/account"))
          account  <- client.join
          action   <- account.clickButton("Open settings")
          settings <- action match
                        case ConnectedAction.LiveNavigation(navigation) => navigation.follow
                        case other => ZIO.fail(AssertionError(s"Expected live navigation, got $other"))
          heading <- settings.text("h1")
        yield assertTrue(heading == "Settings for Ada")
        // docs:end testing-follow-navigation
      }
    },
    test("the session aspect rejects a visitor without a session") {
      for
        routes <- ZioHttp.routes(Application.application, config)
        page   <- DisconnectedRender.run(routes, request("/account", signedIn = false))
      yield assertTrue(page.response.status == Status.Unauthorized)
    },
    test("the route aspect hides an unknown workspace") {
      for
        routes <- ZioHttp.routes(Application.application, config)
        page   <- DisconnectedRender.run(routes, request("/workspaces/secret"))
      yield assertTrue(page.response.status == Status.NotFound)
    }
  ).provide(services)
end SessionsSpec
