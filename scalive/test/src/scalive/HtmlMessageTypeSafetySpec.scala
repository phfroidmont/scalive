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
    }
  )
end HtmlMessageTypeSafetySpec
