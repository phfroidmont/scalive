package scaliveapi

import zio.test.*

import scalive.*

object HtmlDslApiSpec extends ZIOSpecDefault:

  override def spec = suite("HtmlDslApiSpec")(
    test("function components accept mixed modifier inputs") {
      enum Msg:
        case Clicked

      def panel[Message](mods: Mod.Input[Message]*): HtmlElement[Message] =
        sectionTag(cls := "panel", Mod.flatten(mods))

      val optionalId: Option[Mod[Msg]] = Some(idAttr := "account")
      val bindings: Iterator[Mod[Msg]] = Iterator(on.click(Msg.Clicked))
      val rendered: HtmlElement[Msg]   = panel(optionalId, bindings, "Account")
      val modifierKinds: Vector[String] = rendered.mods.map {
        case Mod.Attr.Static(name, _)  => name
        case _: Mod.Attr.Binding[?]    => "binding"
        case Mod.Content.Text(text, _) => text
        case _                         => "other"
      }

      assertTrue(
        modifierKinds == Vector("class", "id", "binding", "Account"),
        !bindings.hasNext
      )
    },
    test("Mod.flatten preserves order and consumes collections once") {
      val first: Mod[Nothing]  = idAttr := "first"
      val second: Mod[Nothing] = cls    := "second"
      val third: Mod[Nothing]  = title  := "third"
      val fourth: Mod[Nothing] = "fourth"
      val collection           = Iterator(second, third)
      val inputs = Iterator[Mod.Input[Nothing]](
        first,
        collection,
        fourth
      )

      val flattened = Mod.flatten(inputs)

      assertTrue(
        flattened == Vector(first, second, third, fourth),
        !inputs.hasNext,
        !collection.hasNext
      )
    },
    test("generic function component syntax is available through the public API") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Clicked

        def card[Message](mods: Mod.Input[Message]*): HtmlElement[Message] =
          articleTag(cls := "card", Mod.flatten(mods))

        val optionalBinding = Option(on.click(Msg.Clicked))
        val rendered: HtmlElement[Msg] = card(optionalBinding, "Open")
      """)

      assertTrue(errors.isEmpty)
    }
  )
end HtmlDslApiSpec
