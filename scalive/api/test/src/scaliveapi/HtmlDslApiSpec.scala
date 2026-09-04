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
        case Mod.Attr.CompositeStatic(name, _) => name
        case Mod.Attr.Static(name, _)          => name
        case _: Mod.Attr.Binding[?]            => "binding"
        case Mod.Content.Text(text, _)         => text
        case _                                 => "other"
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

        def passthrough[Message](mods: Mod.Input[Message]*): HtmlElement[Message] =
          articleTag(mods*)

        def card[Message](mods: Mod.Input[Message]*): HtmlElement[Message] =
          articleTag(cls := "card", Mod.flatten(mods))

        val optionalBinding = Option(on.click(Msg.Clicked))
        val forwarded: HtmlElement[Msg] = passthrough(optionalBinding, "Open")
        val rendered: HtmlElement[Msg] = card(optionalBinding, "Open")
      """)

      assertTrue(errors.isEmpty)
    },
    test("composite attributes preserve their behavior through HtmlAttr references") {
      val custom                            = CompositeHtmlAttr("data-tags")
      val asHtmlAttr: HtmlAttr[String]      = custom
      val static: Mod.Attr[Nothing]         = asHtmlAttr := "one two"
      val signal: Signal[String]            = null.asInstanceOf[Signal[String]]
      val optional: Signal[Option[String]]  = null.asInstanceOf[Signal[Option[String]]]
      val dynamic: Mod.Attr[Nothing]        = asHtmlAttr := signal
      val dynamicOptional: Mod.Attr[Nothing] = asHtmlAttr.optional(optional)
      val builtIns: Vector[CompositeHtmlAttr] = Vector(className, cls, rel, role)

      assertTrue(
        builtIns.size == 4,
        static == Mod.Attr.CompositeStatic("data-tags", "one two"),
        dynamic == Mod.Attr.CompositeSignalValue("data-tags", signal),
        dynamicOptional == Mod.Attr.CompositeSignalOptionalValue("data-tags", optional)
      )
    },
    test("inert is a typed boolean presence attribute") {
      val typed: HtmlAttr[Boolean]       = inert
      val present: Mod.Attr[Nothing]     = inert := true
      val absent: Mod.Attr[Nothing]      = inert := false

      assertTrue(
        typed.name == "inert",
        present == Mod.Attr.StaticValueAsPresence("inert", true),
        absent == Mod.Attr.StaticValueAsPresence("inert", false)
      )
    }
  )
end HtmlDslApiSpec
