package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object HtmlVocabularySpec extends ZIOSpecDefault:
  private def render[A](model: A)(view: Signal[A] => HtmlElement[Nothing]) =
    for
      program   <- ZIO.fromEither(RenderProgram.compile[A, Nothing](view))
      candidate <- program.evaluate(model)
    yield HtmlRenderer.render(candidate.tree)

  override def spec = suite("HtmlVocabularySpec")(
    test("renders inline SVG from the svg namespace, with signals in attributes") {
      for html <- render(42.0)(width =>
                    svg(
                      svg.viewBox := "0 0 100 10",
                      svg.linearGradient(idAttr := "fade", svg.stop(svg.offsetAttr := "0")),
                      svg.rect(svg.width := width.map(_.toString), svg.height := "10"),
                      svg.text(svg.x := "1", svg.textAnchor := "start", "42%"),
                      svg.path(svg.d := "M0 0h10", svg.strokeWidth := "2")
                    )
                  )
      yield assertTrue(
        html ==
          "<svg viewBox=\"0 0 100 10\"><linearGradient id=\"fade\"><stop offset=\"0\"></stop></linearGradient>" +
          "<rect width=\"42.0\" height=\"10\"></rect><text x=\"1\" text-anchor=\"start\">42%</text>" +
          "<path d=\"M0 0h10\" stroke-width=\"2\"></path></svg>"
      )
    },
    test("renders the popover, command, inert, scope and datetime attributes") {
      for html <- render(true)(shown =>
                    div(
                      button(popoverTarget := "menu", popoverTargetAction := "toggle", "Menu"),
                      button(commandFor := "dialog", command := "show-modal", "Open"),
                      div(idAttr := "menu", popover := "auto"),
                      sectionTag(inert := shown),
                      sectionTag(inert := false),
                      th(scope := "col"),
                      timeTag(dateTime := "2026-10-21")
                    )
                  )
      yield assertTrue(
        html ==
          "<div><button popovertarget=\"menu\" popovertargetaction=\"toggle\">Menu</button>" +
          "<button commandfor=\"dialog\" command=\"show-modal\">Open</button>" +
          "<div id=\"menu\" popover=\"auto\"></div><section inert></section><section></section>" +
          "<th scope=\"col\"></th><time datetime=\"2026-10-21\"></time></div>"
      )
    },
    test("custom string attributes need no codec, and formTag and labelTag render form and label") {
      val section = htmlAttr("data-section")
      val form    = "a local value named form"
      val label   = "a local value named label"

      for html <- render(())(_ =>
                    formTag(section := "billing", labelTag(forId := "plan", label), form)
                  )
      yield assertTrue(
        html ==
          "<form data-section=\"billing\"><label for=\"plan\">a local value named label</label>" +
          "a local value named form</form>"
      )
    }
  )
end HtmlVocabularySpec
