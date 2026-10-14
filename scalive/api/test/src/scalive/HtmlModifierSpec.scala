package scalive

import zio.test.*

object HtmlModifierSpec extends ZIOSpecDefault:
  final private case class Profile(name: String)

  private enum Intent:
    case Save

  def spec = suite("HtmlModifierSpec")(
    test("composite attributes preserve their behavior through HtmlAttr references") {
      val asHtmlAttr: HtmlAttr[String]       = CompositeHtmlAttr("data-tags")
      val signal: Signal[String]             = null.asInstanceOf[Signal[String]]
      val optional: Signal[Option[String]]   = null.asInstanceOf[Signal[Option[String]]]
      val static: Mod.Attr[Nothing]          = asHtmlAttr := "one two"
      val dynamic: Mod.Attr[Nothing]         = asHtmlAttr := signal
      val dynamicOptional: Mod.Attr[Nothing] = asHtmlAttr.optional(optional)

      assertTrue(
        static == Mod.Attr.CompositeStatic("data-tags", "one two"),
        dynamic == Mod.Attr.CompositeSignalValue("data-tags", signal),
        dynamicOptional == Mod.Attr.CompositeSignalOptionalValue("data-tags", optional)
      )
    },
    test("inert is a boolean presence attribute") {
      assertTrue(
        (inert := true) == Mod.Attr.StaticValueAsPresence("inert", true),
        (inert := false) == Mod.Attr.StaticValueAsPresence("inert", false)
      )
    },
    test("Mod.flatten wraps elements and collections of elements as child content") {
      val first  = li("first")
      val second = li("second")
      val id     = idAttr := "list"

      assertTrue(
        Mod.flatten(Vector(id, first, Vector(second))) ==
          Vector(id, Mod.Content.Tag(first), Mod.Content.Tag(second))
      )
    },
    test("form submitter attributes render a named submit button") {
      val root       = FormRoot("profile")
      val definition = root.product[Profile]((name = root.text("name")))
      val submitter  = definition.submitter(Intent.values)(_ => "save")
      val button     = submitter.button(Intent.Save)("Save")

      assertTrue(
        button.attrMods == Vector(
          Mod.Attr.Static("type", "submit"),
          Mod.Attr.Static("name", submitter.name),
          Mod.Attr.Static("value", "save")
        )
      )
    }
  )
end HtmlModifierSpec
