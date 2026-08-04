package scalive

import zio.test.*

object HtmlMessageTypeSafetySpec extends ZIOSpecDefault:

  override def spec = suite("HtmlMessageTypeSafetySpec")(
    test("live remains exclusively the root route seed") {
      val routeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val route: LiveRouteSeed[Unit] = live
      """)
      val bindingErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val binding = live.onClick("clicked")
      """)

      assertTrue(routeErrors.isEmpty, bindingErrors.nonEmpty)
    },
    test("event bindings must produce the element message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Expected

        enum Other:
          case Unexpected

        val view: HtmlElement[Msg] = button(scalive.on.click(Other.Unexpected))
      """)

      assertTrue(errors.nonEmpty)
    },
    test("parameterized event bindings must produce the element message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Expected(value: String)

        enum Other:
          case Unexpected(value: String)

        val view: HtmlElement[Msg] = input(scalive.on.blur.withValue(Other.Unexpected.apply))
      """)

      assertTrue(errors.nonEmpty)
    },
    test("JS.push bindings must produce the element message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Expected

        enum Other:
          case Unexpected

        val view: HtmlElement[Msg] = button(scalive.on.click(JS.push(Other.Unexpected)))
      """)

      assertTrue(errors.nonEmpty)
    },
    test("matching event bindings compile") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Clicked
          case Changed(value: String)

        val view: HtmlElement[Msg] = div(
          button(scalive.on.click(Msg.Clicked)),
          input(scalive.on.blur.withValue(Msg.Changed.apply)),
          button(scalive.on.click(JS.push(Msg.Clicked)))
        )
      """)

      assertTrue(errors.isEmpty)
    },
    test("withValue uses an empty string when the payload has no value") {
      val attr = scalive.on.blur.withValue(identity)

      val result = attr match
        case Mod.Attr.Binding(_, f) => f(Map.empty)
        case other                  => throw new AssertionError(s"expected binding, got $other")

      assertTrue(result == "")
    },
    test("withValueOption preserves missing and present values") {
      val attr = scalive.on.blur.withValueOption(identity)

      val missing = attr match
        case Mod.Attr.Binding(_, f) => f(Map.empty)
        case other                  => throw new AssertionError(s"expected binding, got $other")

      val present = attr match
        case Mod.Attr.Binding(_, f) => f(Map("value" -> "hello"))
        case other                  => throw new AssertionError(s"expected binding, got $other")

      assertTrue(missing == None, present == Some("hello"))
    },
    test("withBoolValue decodes accepted values and defaults invalid values to false") {
      val attr = scalive.on.blur.withBoolValue(identity)

      def decode(payload: Map[String, String]) =
        attr match
          case Mod.Attr.Binding(_, f) => f(payload)
          case other                  => throw new AssertionError(s"expected binding, got $other")

      assertTrue(
        decode(Map("value" -> "on")),
        decode(Map("value" -> "yes")),
        decode(Map("value" -> "true")),
        !decode(Map("value" -> "off")),
        !decode(Map("value" -> "no")),
        !decode(Map("value" -> "false")),
        !decode(Map("value" -> "unexpected")),
        !decode(Map.empty)
      )
    },
    test("withBoolValueOption preserves invalid and missing values") {
      val attr = scalive.on.blur.withBoolValueOption(identity)

      def decode(payload: Map[String, String]) =
        attr match
          case Mod.Attr.Binding(_, f) => f(payload)
          case other                  => throw new AssertionError(s"expected binding, got $other")

      assertTrue(
        decode(Map("value" -> "true")) == Some(true),
        decode(Map("value" -> "false")) == Some(false),
        decode(Map("value" -> "unexpected")) == None,
        decode(Map.empty) == None
      )
    }
  )
end HtmlMessageTypeSafetySpec
