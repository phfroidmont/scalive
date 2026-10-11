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
      val account: Mod[Nothing]         = "Account"
      val expected: Vector[Mod[Msg]]    = Vector(cls := "panel", accountId, clickBinding, account)

      assertTrue(
        rendered.mods == expected,
        !bindings.hasNext
      )
    },
    test("elements and collections of elements are children") {
      val first                       = li("first")
      val second                      = li("second")
      val note                        = p("note")
      val list                        = ul(Vector(first, second))
      val panel                       = div(Option(note), Option.empty[HtmlElement[Nothing]], first)
      def child(element: HtmlElement[Nothing]): Mod[Nothing] = element

      assertTrue(
        list.mods == Vector(child(first), child(second)),
        panel.mods == Vector(child(note), child(first))
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
    test("client lifecycle bindings accept only JS commands") {
      // The Phoenix client runs only JS commands for these attributes; a message would render as a
      // bare binding ID, which `JS.exec` cannot run.
      val messageErrors = Vector(
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          val binding = dom.onMount("mounted")
        """),
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          val binding = dom.onRemove("removed")
        """),
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          val binding = connection.onConnect("connected")
        """),
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          val binding = connection.onDisconnect("disconnected")
        """)
      )
      val commandErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val mounted: Mod.Attr[String] = dom.onMount(JS.push("mounted"))
        val removed: Mod.Attr[Nothing] = dom.onRemove(JS.hide())
        val connected: Mod.Attr[Nothing] = connection.onConnect(JS.removeClass("offline"))
        val disconnected: Mod.Attr[Nothing] = connection.onDisconnect(JS.addClass("offline"))
      """)

      assertTrue(messageErrors.forall(_.nonEmpty), commandErrors.isEmpty)
    },
    test("upload progress binds only to its upload's file input") {
      // The browser reads phx-progress only from the upload's own file input.
      val elsewhere = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        def page(upload: LiveUpload[zio.Chunk[Byte]]) = div(upload.onProgress("progress"))
      """)
      val onInput = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        def page(upload: LiveUpload[zio.Chunk[Byte]]) =
          liveFileInput(upload, upload.onProgress("progress"), cls := "hidden")
        def live(upload: Signal[LiveUpload[zio.Chunk[Byte]]]) =
          liveFileInput(upload, upload.onProgress(_ => "progress"))
      """)

      assertTrue(elsewhere.nonEmpty, onInput.isEmpty)
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
    test("modifier representations are not public") {
      val errors = Vector(
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          val attr = Mod.Attr.Static("id", "account")
        """),
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          val text = Mod.Content.Text("Account")
        """),
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          val child = Mod.Content.Tag(div())
        """),
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          def attrs(element: HtmlElement[Nothing]) = element.attrMods
        """),
        scala.compiletime.testing.typeCheckErrors("""
          import scalive.*
          def spec(spec: NestedViewSpec) = spec
        """)
      )

      assertTrue(errors.forall(_.nonEmpty))
    },
    test("typed attributes keep their public types") {
      val typed: HtmlAttr[Boolean]            = inert
      val asHtmlAttr: HtmlAttr[String]        = CompositeHtmlAttr("data-tags")
      val builtIns: Vector[CompositeHtmlAttr] = Vector(className, cls, rel, role)

      assertTrue(typed.name == "inert", asHtmlAttr.name == "data-tags", builtIns.size == 4)
    }
  )
end HtmlDslApiSpec
