package scalive

import zio.test.*

object HtmlMessageTypeSafetySpec extends ZIOSpecDefault:

  override def spec = suite("HtmlMessageTypeSafetySpec")(
    test("event bindings must produce the element message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Expected

        enum Other:
          case Unexpected

        val view: HtmlElement[Msg] = button(phx.onClick(Other.Unexpected))
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

        val view: HtmlElement[Msg] = input(phx.onBlur.withValue(Other.Unexpected.apply))
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

        val view: HtmlElement[Msg] = button(phx.onClick(JS.push(Other.Unexpected)))
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
          button(phx.onClick(Msg.Clicked)),
          input(phx.onBlur.withValue(Msg.Changed.apply)),
          button(phx.onClick(JS.push(Msg.Clicked)))
        )
      """)

      assertTrue(errors.isEmpty)
    },
    test("withValue uses an empty string when the payload has no value") {
      val attr = phx.onBlur.withValue(identity)

      val result = attr match
        case Mod.Attr.Binding(_, f) => f(Map.empty)
        case other                  => throw new AssertionError(s"expected binding, got $other")

      assertTrue(result == "")
    },
    test("withValueOption preserves missing and present values") {
      val attr = phx.onBlur.withValueOption(identity)

      val missing = attr match
        case Mod.Attr.Binding(_, f) => f(Map.empty)
        case other                  => throw new AssertionError(s"expected binding, got $other")

      val present = attr match
        case Mod.Attr.Binding(_, f) => f(Map("value" -> "hello"))
        case other                  => throw new AssertionError(s"expected binding, got $other")

      assertTrue(missing == None, present == Some("hello"))
    },
    test("withBoolValue decodes accepted values and defaults invalid values to false") {
      val attr = phx.onBlur.withBoolValue(identity)

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
      val attr = phx.onBlur.withBoolValueOption(identity)

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
