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

      val accountId: Mod[Nothing]       = idAttr := "account"
      val clickBinding: Mod[Msg]        = on.click(Msg.Clicked)
      val optionalId: Option[Mod[Msg]]  = Some(accountId)
      val bindings: Iterator[Mod[Msg]]  = Iterator(clickBinding)
      val rendered: HtmlElement[Msg]    = panel(optionalId, bindings, "Account")
      val expected: Vector[Mod[Msg]]    =
        Vector(cls := "panel", accountId, clickBinding, Mod.Content.Text("Account"))

      assertTrue(
        rendered.mods == expected,
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
    test("event binding builders cannot be publicly constructed") {
      val bindingConstructorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val binding = HtmlAttrBinding("phx-clik")
      """)
      val keyBindingConstructorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val binding = KeyHtmlAttrBinding("phx-keydon")
      """)
      val rawBindingConstructorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val binding = Mod.Attr.FormBinding("phx-chagne", (_: FormData) => "changed")
      """)
      val rawJsBindingConstructorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val binding = Mod.Attr.JsBinding("phx-clik", JS.push("clicked"))
      """)
      val factoryErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val click: HtmlAttrBinding = on.click
        val keyDown: KeyHtmlAttrBinding = on.keyDown
      """)

      assertTrue(
        bindingConstructorErrors.nonEmpty,
        keyBindingConstructorErrors.nonEmpty,
        rawBindingConstructorErrors.nonEmpty,
        rawJsBindingConstructorErrors.nonEmpty,
        factoryErrors.isEmpty
      )
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
