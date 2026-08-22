package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object HtmlRendererSpec extends ZIOSpecDefault:
  final case class Model(
    className: String,
    disabled: Boolean,
    note: Option[String],
    text: String,
    raw: String)

  override def spec = suite("HtmlRendererSpec")(
    test("renders structured static and dynamic HTML with exact escaping") {
      val compiled = RenderProgram.compile[Model, Nothing] { model =>
        div(
          cls := model.map(_.className),
          disabled := model.map(_.disabled),
          dataAttr("note").optional(model.map(_.note)),
          title := "<unsafe>",
          span("<safe & \"quoted\">"),
          model.map(_.text),
          rawHtml(model.map(_.raw))
        )
      }
      val input = Model("box &", disabled = true, Some("\"note\""), "dynamic &", "<b>raw</b>")

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(input)
      yield assertTrue(
        HtmlRenderer.render(candidate.tree) ==
          "<div class=\"box &amp;\" disabled data-note=\"&quot;note&quot;\" title=\"&lt;unsafe&gt;\"><span>&lt;safe &amp; &quot;quoted&quot;&gt;</span>dynamic &amp;<b>raw</b></div>"
      )
    },
    test("omits absent attributes and keeps source order") {
      val compiled = RenderProgram.compile[Unit, Nothing] { _ =>
        div(
          dataAttr("first") := "1",
          disabled          := false,
          dataAttr("second") := "2"
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
      yield assertTrue(
        HtmlRenderer.render(candidate.tree) == "<div data-first=\"1\" data-second=\"2\"></div>"
      )
    },
    test("renders HTML5 void elements and optional doctype") {
      for
        inputProgram <- ZIO.fromEither(
          RenderProgram.compile[Unit, Nothing](_ => input(disabled := true, value := "x"))
        )
        documentProgram <- ZIO.fromEither(
          RenderProgram.compile[Unit, Nothing](_ => htmlRootTag(bodyTag("content")))
        )
        inputCandidate    <- inputProgram.evaluate(())
        documentCandidate <- documentProgram.evaluate(())
      yield assertTrue(
        HtmlRenderer.render(inputCandidate.tree) == "<input disabled value=\"x\">",
        HtmlRenderer.render(documentCandidate.tree, includeDoctype = true) ==
          "<!doctype html><html><body>content</body></html>"
      )
    },
    test("renders slot elements with fallback content") {
      for
        program   <- ZIO.fromEither(RenderProgram.compile[Unit, Nothing](_ => slotTag("fallback")))
        candidate <- program.evaluate(())
      yield assertTrue(HtmlRenderer.render(candidate.tree) == "<slot>fallback</slot>")
    },
    test("renders present and absent flash as transparent content") {
      val info = FlashKind("info")
      val compiled = RenderProgram.compile[Map[FlashKind, String], Nothing](
        _ => mainTag("before", flash(info)(message => span(message)), "after"),
        identity
      )

      for
        program <- ZIO.fromEither(compiled)
        absent  <- program.evaluate(Map.empty)
        present <- program.evaluate(Map(info -> "hello & goodbye"), Some(absent.commit))
      yield assertTrue(
        HtmlRenderer.render(absent.tree) == "<main>beforeafter</main>",
        HtmlRenderer.render(present.tree) ==
          "<main>before<span>hello &amp; goodbye</span>after</main>"
      )
    }
  )
