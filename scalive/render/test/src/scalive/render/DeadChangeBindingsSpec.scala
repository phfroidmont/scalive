package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object DeadChangeBindingsSpec extends ZIOSpecDefault:
  private def deadIn(view: HtmlElement[Int]) =
    for
      program   <- ZIO.fromEither(RenderProgram.compile[Unit, Int](_ => view))
      candidate <- program.evaluate(())
    yield DeadChangeBindings.find(candidate.tree)

  override def spec = suite("DeadChangeBindingsSpec")(
    test("a change binding on a form, or on a control that has a form, is sent") {
      for dead <- deadIn(
                    div(
                      form(on.change(_ => 1), input(nameAttr := "a")),
                      form(div(input(nameAttr := "b", on.change(_ => 2)))),
                      form(select(nameAttr := "c", on.change(_ => 3))),
                      input(nameAttr := "d", formId := "elsewhere", on.change(_ => 4)),
                      form(HtmlTag("color-picker")(on.change(_ => 5)))
                    )
                  )
      yield assertTrue(dead.isEmpty)
    },
    test("a change binding on a control without a form, or on another element, is never sent") {
      for dead <- deadIn(
                    div(
                      input(idAttr := "search", nameAttr := "q", on.change(_ => 1)),
                      textArea(nameAttr := "notes", on.change(_ => 2)),
                      form(div(cls := "group", on.change(_ => 3), input(nameAttr := "a"))),
                      div(contentEditable := true, on.change(_ => 4))
                    )
                  )
      yield assertTrue(
        dead == Vector(
          "<input id=\"search\" name=\"q\">",
          "<textarea name=\"notes\">",
          "<div class=\"group\">",
          "<div>"
        )
      )
    }
  )
end DeadChangeBindingsSpec
