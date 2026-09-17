package scalive.docs.examples

import java.time.Duration

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.testing.ConnectedRender

object LayoutContextExampleSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("scalive.test"))
  ).toOption.get

  private def run(routes: Routes[Any, Nothing], request: Request): UIO[Response] =
    ZIO.scoped(routes.runZIO(request))

  def spec = suite("Layout context example")(
    test("shared document layouts select typed session and mounted route contexts") {
      val application = LayoutContextExample.application
      val routes      = ZioHttp.routes(application, config)
      val publicUrl   = URL.decode("/public?source=guide").toOption.get
      val accountUrl  = URL.decode("/account?source=guide").toOption.get
      val variantUrl  = URL.decode("/account/variant?source=guide").toOption.get

      ZIO.scoped {
        for
          defaultResponse <- run(routes, Request.get(URL.decode("/default").toOption.get))
          publicResponse  <- run(routes, Request.get(publicUrl))
          accountResponse <- run(routes, Request.get(accountUrl))
          variantResponse <- run(routes, Request.get(variantUrl))
          defaultBody     <- defaultResponse.body.asString.orDie
          publicBody      <- publicResponse.body.asString.orDie
          accountBody     <- accountResponse.body.asString.orDie
          variantBody     <- variantResponse.body.asString.orDie
          publicClaims    <- ZioHttpSecurity.verifySession(
                            config,
                            attribute(publicBody, "data-phx-session").get
                          )
          accountClaims <- ZioHttpSecurity.verifySession(
                             config,
                             attribute(accountBody, "data-phx-session").get
                           )
          variantClaims <- ZioHttpSecurity.verifySession(
                             config,
                             attribute(variantBody, "data-phx-session").get
                           )
          publicCompiled = ZioHttp.validate(application).find(_.matches(publicUrl)).get
          publicLifecycle <- publicCompiled.prepareConnected(
                               publicUrl,
                               Request.get(publicUrl),
                               publicClaims
                             )
          connectedModel <- publicLifecycle
                              .mount(
                                scalive.runtime.connection.RootMountContext.disconnected
                              ).orDie
          connected     <- ConnectedRender.join(application, config, Request.get(publicUrl))
          connectedBody <- connected.html
        yield assertTrue(
          defaultBody.startsWith("<!doctype html><html lang=\"en\">"),
          defaultBody.contains("<title>Default title</title>"),
          defaultBody.contains("<main id=\"application\""),
          defaultBody.contains("<div id=\"page\">default</div>"),
          publicClaims.rootLayoutKey == "document:fr",
          publicClaims.sessionMountClaims == Vector("\"fr\""),
          publicBody.startsWith("<!doctype html><html lang=\"fr\">"),
          publicBody.contains("<title>Public title</title>"),
          publicBody.contains("data-params=\"()\""),
          publicBody.contains("data-request-url=\"/public?source=guide\""),
          publicBody.contains("data-current-url=\"/public?source=guide\""),
          publicBody.contains(
            "<section id=\"document-fr\" data-params=\"()\" data-request-url=\"/public?source=guide\" data-current-url=\"/public?source=guide\"><div id=\"page\">public-fr</div></section>"
          ),
          connectedModel == "public-fr",
          connectedBody.contains("<section id=\"document-fr\""),
          connectedBody.contains("<div id=\"page\">public-fr</div>"),
          accountClaims.rootLayoutKey == "document:de",
          accountClaims.sessionMountClaims.size == 2,
          accountClaims.sessionMountClaims.headOption.contains("\"de\""),
          accountBody.contains(
            "<section id=\"document-de\" data-params=\"()\" data-request-url=\"/account?source=guide\" data-current-url=\"/account?source=guide\"><div id=\"page\">account-de-audited</div></section>"
          ),
          variantClaims.rootLayoutKey == "document:de-CH",
          variantBody.startsWith("<!doctype html><html lang=\"de-CH\">"),
          variantBody.contains("<title>Variant title</title>"),
          variantBody.contains(
            "<section id=\"document-de\" data-params=\"()\" data-request-url=\"/account/variant?source=guide\" data-current-url=\"/account/variant?source=guide\"><section id=\"document-de-CH\" data-params=\"()\" data-request-url=\"/account/variant?source=guide\" data-current-url=\"/account/variant?source=guide\"><div id=\"page\">account-variant</div></section></section>"
          )
        )
        end for
      }
    },
    test("adapted identity roots preserve default documents through session composition") {
      object Page extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext)  = ZIO.unit
        def view(model: Signal[Unit]) = div(idAttr := "identity-content", "Page")

      val root         = LiveRootLayout.identity.forContext[Any](identity)
      val applications = Vector(
        scalive.Live.router.withRootLayout(root)(scalive.live(Page)),
        scalive.Live.router(
          scalive.Live.session("identity").withRootLayout(root)(scalive.live(Page))
        )
      )

      for bodies <- ZIO.foreach(applications) { application =>
                      run(ZioHttp.routes(application, config), Request.get(URL.root))
                        .flatMap(_.body.asString.orDie)
                    }
      yield assertTrue(
        bodies.forall(body =>
          body.startsWith("<!doctype html><html>") &&
            body.contains("<head><meta charset=\"utf-8\">") &&
            body.contains("<meta name=\"csrf-token\"") &&
            body.contains("<body>") &&
            body.contains("id=\"identity-content\"")
        )
      )
    }
  )

  private def attribute(html: String, name: String): Option[String] =
    val pattern = (java.util.regex.Pattern.quote(name) + "=\"([^\"]+)\"").r
    pattern.findFirstMatchIn(html).map(_.group(1))
end LayoutContextExampleSpec
