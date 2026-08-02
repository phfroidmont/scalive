package scalive

import zio.*
import zio.http.codec.PathCodec
import zio.json.*
import zio.test.*

object NavigationApiSpec extends ZIOSpecDefault:
  private val target = (scalive.live / "users" / PathCodec.int("id")).location(42)

  override def spec = suite("NavigationApiSpec")(
    test("safe links preserve all Phoenix navigation attributes") {
      assertTrue(
        HtmlBuilder.build(link.pushNavigate(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"redirect\" data-phx-link-state=\"push\">User</a>",
        HtmlBuilder.build(link.replaceNavigate(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"redirect\" data-phx-link-state=\"replace\">User</a>",
        HtmlBuilder.build(link.pushPatch(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"patch\" data-phx-link-state=\"push\">User</a>",
        HtmlBuilder.build(link.replacePatch(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"patch\" data-phx-link-state=\"replace\">User</a>"
      )
    },
    test("safe JS commands preserve all navigation JSON") {
      import JSCommands.JSCommand.given

      assertTrue(
        JS.pushNavigate(target).toJson == "[[\"navigate\",{\"href\":\"/users/42\"}]]",
        JS.replaceNavigate(target).toJson ==
          "[[\"navigate\",{\"href\":\"/users/42\",\"replace\":true}]]",
        JS.pushPatch(target).toJson == "[[\"patch\",{\"href\":\"/users/42\"}]]",
        JS.replacePatch(target).toJson ==
          "[[\"patch\",{\"href\":\"/users/42\",\"replace\":true}]]"
      )
    },
    test("unsafe links preserve raw destinations for all operations") {
      assertTrue(
        HtmlBuilder.build(link.pushNavigateUnsafe("?page=2", "Next")) ==
          "<a href=\"?page=2\" data-phx-link=\"redirect\" data-phx-link-state=\"push\">Next</a>",
        HtmlBuilder.build(link.replaceNavigateUnsafe("?page=2", "Next")) ==
          "<a href=\"?page=2\" data-phx-link=\"redirect\" data-phx-link-state=\"replace\">Next</a>",
        HtmlBuilder.build(link.pushPatchUnsafe("?page=2", "Next")) ==
          "<a href=\"?page=2\" data-phx-link=\"patch\" data-phx-link-state=\"push\">Next</a>",
        HtmlBuilder.build(link.replacePatchUnsafe("?page=2", "Next")) ==
          "<a href=\"?page=2\" data-phx-link=\"patch\" data-phx-link-state=\"replace\">Next</a>"
      )
    },
    test("unsafe JS commands preserve raw destinations for all operations") {
      import JSCommands.JSCommand.given

      assertTrue(
        JS.pushNavigateUnsafe("?page=2").toJson ==
          "[[\"navigate\",{\"href\":\"?page=2\"}]]",
        JS.replaceNavigateUnsafe("?page=2").toJson ==
          "[[\"navigate\",{\"href\":\"?page=2\",\"replace\":true}]]",
        JS.pushPatchUnsafe("?page=2").toJson == "[[\"patch\",{\"href\":\"?page=2\"}]]",
        JS.replacePatchUnsafe("?page=2").toJson ==
          "[[\"patch\",{\"href\":\"?page=2\",\"replace\":true}]]"
      )
    },
    test("typed lifecycle navigation serializes locations once") {
      for
        commands <- Ref.make(List.empty[LiveNavigationCommand])
        runtime = new LiveNavigationRuntime:
          def request(command: LiveNavigationCommand) = commands.update(_ :+ command)
        ctx = LiveContext(staticChanged = false, navigation = runtime).messageContext[Unit, Unit]
        _      <- ctx.nav.pushNavigate(target)
        _      <- ctx.nav.replaceNavigate(target)
        _      <- ctx.nav.pushPatch(target)
        _      <- ctx.nav.replacePatch(target)
        _      <- ctx.nav.redirect(target)
        result <- commands.get
      yield assertTrue(
        result == List(
          LiveNavigationCommand.PushNavigate("/users/42"),
          LiveNavigationCommand.ReplaceNavigate("/users/42"),
          LiveNavigationCommand.PushPatch("/users/42"),
          LiveNavigationCommand.ReplacePatch("/users/42"),
          LiveNavigationCommand.Redirect("/users/42")
        )
      )
    },
    test("safe navigation APIs reject raw strings") {
      val pushNavigateLinkErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        link.pushNavigate(rawDestination, "User")
      """)
      val replaceNavigateLinkErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        link.replaceNavigate(rawDestination, "User")
      """)
      val pushPatchLinkErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        link.pushPatch(rawDestination, "User")
      """)
      val replacePatchLinkErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        link.replacePatch(rawDestination, "User")
      """)
      val pushNavigateJsErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        JS.pushNavigate(rawDestination)
      """)
      val replaceNavigateJsErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        JS.replaceNavigate(rawDestination)
      """)
      val pushPatchJsErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        JS.pushPatch(rawDestination)
      """)
      val replacePatchJsErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        JS.replacePatch(rawDestination)
      """)
      val contextErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def navigate(ctx: MessageContext[Unit, Unit]) =
          val rawDestination: String = "/users/42"
          ctx.nav.pushNavigate(rawDestination)
      """)
      val urlErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.URL
        link.pushNavigate(URL.decode("/users/42").toOption.get, "User")
      """)

      assertTrue(
        pushNavigateLinkErrors.nonEmpty,
        replaceNavigateLinkErrors.nonEmpty,
        pushPatchLinkErrors.nonEmpty,
        replacePatchLinkErrors.nonEmpty,
        pushNavigateJsErrors.nonEmpty,
        replaceNavigateJsErrors.nonEmpty,
        pushPatchJsErrors.nonEmpty,
        replacePatchJsErrors.nonEmpty,
        contextErrors.nonEmpty,
        urlErrors.nonEmpty
      )
    }
  )
end NavigationApiSpec
