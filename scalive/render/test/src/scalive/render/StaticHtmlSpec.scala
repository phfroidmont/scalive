package scaliverenderapi

import zio.Task
import zio.ZIO
import zio.test.*

import scalive.*

object StaticHtmlSpec extends ZIOSpecDefault:
  private object StaticComponent extends LiveComponent.Eventless[String, String]:
    def mount(props: String, ctx: MountContext): Task[String] = ZIO.succeed(props)

    def view(
      props: Signal[String],
      model: Signal[String],
      self: ComponentRef[Nothing]
    ): HtmlElement[Nothing] = span(model)

  private object StaticNestedView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext): Task[Unit] = ZIO.unit
    def view(model: Signal[Unit]): HtmlElement[Nothing] = div()

  private object RoutedComponent extends LiveComponent[Unit, String, Unit]:
    def mount(props: Unit, ctx: MountContext): Task[Unit] = ZIO.unit
    def handleMessage(props: Unit, model: Unit, ctx: MessageContext): String => Task[Unit] =
      _ => ZIO.unit
    def view(
      props: Signal[Unit],
      model: Signal[Unit],
      self: ComponentRef[String]
    ): HtmlElement[String] = div()

  override def spec = suite("StaticHtmlSpec")(
    test("renders concrete DSL trees with escaping and an optional doctype") {
      StaticHtml
        .render(
          htmlRootTag(
            lang := "en",
            headTag(titleTag("A & B")),
            bodyTag(
              mainTag(
                cls := "page",
                p("<hello>"),
                rawHtml("<b>trusted</b>")
              )
            )
          ),
          includeDoctype = true
        ).map(html =>
          assertTrue(
            html ==
              "<!doctype html><html lang=\"en\"><head><title>A &amp; B</title></head><body><main class=\"page\"><p>&lt;hello&gt;</p><b>trusted</b></main></body></html>"
          )
        )
    },
    test("reports invalid concrete HTML as a typed render error") {
      StaticHtml.render(input("child")).either.map(result =>
        assertTrue(result.left.exists(_.isInstanceOf[StaticHtmlError.InvalidHtml]))
      )
    },
    test("serializes client-only JS commands") {
      StaticHtml.render(button(on.click(JS.hide()), "Hide")).map(html =>
        assertTrue(html.startsWith("<button phx-click="), html.endsWith(">Hide</button>"))
      )
    },
    test("rejects lifecycle-backed content") {
      for
        binding <- StaticHtml
                     .render(button(on.click.toComponent(RoutedComponent)("clicked"))).either
        component <- StaticHtml
                       .render(div(liveComponent(StaticComponent, "status", "ready"))).either
        nested <- StaticHtml.render(div(liveView("child", StaticNestedView))).either
        flashContent <- StaticHtml
                          .render(div(flash(FlashKind("info"))(message => span(message)))).either
      yield assertTrue(
        binding.left.exists(_.isInstanceOf[StaticHtmlError.Unsupported]),
        component.left.exists(_.isInstanceOf[StaticHtmlError.UnresolvedComponents]),
        nested.left.exists(_.isInstanceOf[StaticHtmlError.UnresolvedNested]),
        flashContent.left.exists(_.isInstanceOf[StaticHtmlError.Unsupported])
      )
    },
    test("requires message-free HTML") {
      val bindingErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        StaticHtml.render(button(on.click("clicked"), "Click"))
      """)
      val publicApiErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.IO

        val rendered: IO[StaticHtmlError, String] = StaticHtml.render(div())
      """)

      assertTrue(bindingErrors.nonEmpty, publicApiErrors.isEmpty)
    }
  )
end StaticHtmlSpec
