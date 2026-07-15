package scalive

import zio.*
import zio.http.codec.PathCodec
import zio.json.*
import zio.test.*

object NavigationApiSpec extends ZIOSpecDefault:
  private val target = (scalive.live / "users" / PathCodec.int("id")).location(42)

  override def spec = suite("NavigationApiSpec")(
    test("typed links preserve Phoenix navigation attributes") {
      assertTrue(
        HtmlBuilder.build(link.navigate(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"redirect\" data-phx-link-state=\"push\">User</a>",
        HtmlBuilder.build(link.patch(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"patch\" data-phx-link-state=\"push\">User</a>",
        HtmlBuilder.build(link.patchReplace(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"patch\" data-phx-link-state=\"replace\">User</a>"
      )
    },
    test("typed JS commands preserve navigate and patch JSON") {
      import JSCommands.JSCommand.given

      assertTrue(
        JS.navigate(target).toJson == "[[\"navigate\",{\"href\":\"/users/42\"}]]",
        JS.patch(target, replace = true).toJson ==
          "[[\"patch\",{\"href\":\"/users/42\",\"replace\":true}]]"
      )
    },
    test("unsafe links and JS commands preserve raw destinations") {
      import JSCommands.JSCommand.given

      assertTrue(
        HtmlBuilder.build(link.patchUnsafe("?page=2", "Next")) ==
          "<a href=\"?page=2\" data-phx-link=\"patch\" data-phx-link-state=\"push\">Next</a>",
        JS.patchUnsafe("?page=2").toJson == "[[\"patch\",{\"href\":\"?page=2\"}]]"
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
      val linkErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        link.navigate(rawDestination, "User")
      """)
      val jsErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val rawDestination: String = "/users/42"
        JS.patch(rawDestination)
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
        link.navigate(URL.decode("/users/42").toOption.get, "User")
      """)

      assertTrue(
        linkErrors.nonEmpty,
        jsErrors.nonEmpty,
        contextErrors.nonEmpty,
        urlErrors.nonEmpty
      )
    }
  )
end NavigationApiSpec
