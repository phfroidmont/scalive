package scalive.runtime.connection

import zio.*
import zio.http.URL
import zio.test.*

import scalive.*
import scalive.runtime.contracts.*
import scalive.runtime.kernel.NavigationRequest
import scalive.runtime.resources.*

object RootNavigationSpec extends ZIOSpecDefault:
  private val metadata = RootConnectionMetadata(staticChanged = false, connectParams = Map.empty)

  private def attempt(
    destination: String
  )(operation: Navigation => Task[Unit]): Task[(Either[Throwable, Unit], Option[NavigationRequest])] =
    for
      lifecycle <- ZIO.fromEither(LifecycleId.fresh()).mapError(error => Exception(error.toString))
      journal <- RootTurnJournal.make(
                   OwnerId.Root(lifecycle),
                   RootHookRegistry.fromStatic(LiveHooks.empty[Unit, Unit])
                 )
      context = RootMessageContext[Unit, Unit](
                  metadata,
                  URL.decode("/current?old=yes").toOption.get,
                  journal
                )
      result     <- operation(context.nav).either
      navigation <- journal.navigationWithFlash
    yield result -> navigation

  private val liveOperations = Vector[(String, (Navigation, String) => Task[Unit])](
    "push navigate"    -> ((nav, to) => nav.pushNavigateUnsafe(to)),
    "replace navigate" -> ((nav, to) => nav.replaceNavigateUnsafe(to)),
    "push patch"       -> ((nav, to) => nav.pushPatchUnsafe(to)),
    "replace patch"    -> ((nav, to) => nav.replacePatchUnsafe(to))
  )

  private val allOperations = liveOperations :+
    ("redirect" -> ((nav: Navigation, to: String) => nav.redirectUnsafe(to)))

  override def spec = suite("RootNavigationSpec")(
    test("live navigation accepts paths and HTTP destinations without misreading later colons") {
      val destinations = Vector(
        "/foo",
        "http://example.com/foo",
        "https://example.com/foo",
        "/items/a:b",
        "?return=https://example.com/a:b",
        "#section:a"
      )

      ZIO.foreach(destinations)(destination =>
        attempt(destination)(_.pushNavigateUnsafe(destination))
      ).map(results => assertTrue(results.forall((result, recorded) => result.isRight && recorded.nonEmpty)))
    },
    test("live navigation rejects unsupported and obscured schemes") {
      val destinations = Vector(
        "javascript:alert('hi')",
        "JaVaScRiPt:alert('hi')",
        "    javascript:alert('hi')",
        "javascript:alert('hi')   ",
        "    javascript:alert('hi')   ",
        "mailto:foo@example.com",
        "custom:destination"
      )

      ZIO.foreach(liveOperations) { case (name, operation) =>
        ZIO.foreach(destinations)(destination =>
          attempt(destination)(nav => operation(nav, destination)).map((name, destination, _))
        )
      }.map(results =>
        assertTrue(results.flatten.forall { case (_, _, (result, recorded)) =>
          result.isLeft && recorded.isEmpty
        })
      )
    },
    test("navigation rejects encoded tabs and literal tab, LF, and CR characters") {
      val destinations = Vector(
        "/%09/example.com",
        "/\t/example.com",
        "/example.com/\n",
        "/example.com\r",
        "/example.com\r\nLocation: https://evil.example"
      )

      ZIO.foreach(allOperations) { case (name, operation) =>
        ZIO.foreach(destinations)(destination =>
          attempt(destination)(nav => operation(nav, destination)).map((name, destination, _))
        )
      }.map(results =>
        assertTrue(results.flatten.forall { case (_, _, (result, recorded)) =>
          result.isLeft && recorded.isEmpty
        })
      )
    },
    test("redirect rejects an implicit unsafe scheme") {
      attempt("javascript:alert('hi')")(_.redirectUnsafe("javascript:alert('hi')")).map {
        case (result, recorded) => assertTrue(result.isLeft, recorded.isEmpty)
      }
    }
  )
end RootNavigationSpec
