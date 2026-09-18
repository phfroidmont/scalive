package scalivetesting

import zio.*
import zio.http.*
import zio.test.*

import scalive.testing.{DisconnectedRender, HtmlQueryError, RenderedHtml}

object RenderedHtmlSpec extends ZIOSpecDefault:
  private def isInvalidSelector(result: Either[HtmlQueryError, ?], selector: String): Boolean =
    result match
      case Left(HtmlQueryError.InvalidSelector(actual, message)) =>
        actual == selector && message.nonEmpty
      case _ => false

  def spec = suite("RenderedHtmlSpec")(
    test("selectOne distinguishes zero, one, and multiple matches") {
      val rendered = RenderedHtml.parse(
        """<section><p id="first" class="item">First</p><p class="item">Second</p></section>"""
      )

      assertTrue(
        rendered.selectOne(".missing") == Left(HtmlQueryError.NotFound(".missing")),
        rendered.selectOne("#first").map(element => (element.tagName, element.text)) ==
          Right(("p", "First")),
        rendered.selectOne(".item") == Left(HtmlQueryError.MultipleMatches(".item", 2))
      )
    },
    test("selectAll keeps DOM order and distinct controls with repeated names and values") {
      val rendered = RenderedHtml.parse(
        """<input id="first" name="tags[]" value="same">
          |<input id="second" name="tags[]" value="same">
          |<input id="third" name="tags[]" value="last">""".stripMargin
      )

      assertTrue(
        rendered.selectAll(".missing") == Right(Vector.empty),
        rendered
          .selectAll("input").map(
            _.map(element => (element.attribute("id"), element.attribute("name"), element.value))
          ) == Right(
          Vector(
            (Some("first"), Some("tags[]"), "same"),
            (Some("second"), Some("tags[]"), "same"),
            (Some("third"), Some("tags[]"), "last")
          )
        )
      )
    },
    test("both query methods return typed errors for empty, blank, and malformed CSS") {
      val rendered  = RenderedHtml.parse("<p>Text</p>")
      val selectors = Vector("", " \t\n ", "[")

      assertTrue(
        selectors.forall(selector => isInvalidSelector(rendered.selectOne(selector), selector)),
        selectors.forall(selector => isInvalidSelector(rendered.selectAll(selector), selector))
      )
    },
    test(
      "attributes are case-insensitive, decoded, unresolved, and distinguish absent from empty"
    ) {
      val rendered = RenderedHtml.parse(
        """<html><head><base href="https://example.test/base/"></head><body>
          |<A ID="link" HREF="../next?one=1&amp;two=2#part" DATA-LABEL="A &amp; B" DATA-EMPTY="">Go</A>
          |<input disabled>
          |</body></html>""".stripMargin
      )
      val link     = rendered.selectOne("#link")
      val disabled = rendered.selectOne("input")

      assertTrue(
        link.map(_.tagName) == Right("a"),
        link.map(_.attribute("Id")) == Right(Some("link")),
        link.map(_.attribute("HrEf")) == Right(Some("../next?one=1&two=2#part")),
        link.map(_.attribute("Data-Label")) == Right(Some("A & B")),
        link.map(_.attribute("DATA-EMPTY")) == Right(Some("")),
        link.map(_.attribute("missing")) == Right(None),
        disabled.map(_.attribute("DiSaBlEd")) == Right(Some("")),
        disabled.map(_.attribute("value")) == Right(None),
        disabled.map(_.value) == Right("")
      )
    },
    test("raw control values match disconnected fields without browser value computation") {
      val html =
        """<!doctype html><html><head><title>Fields</title></head><body><form>
          |<input name="date" type="date" value="2026-99-88">
          |<input name="padded" value="  keep &amp; spaces  ">
          |<input name="enabled" type="checkbox" checked disabled>
          |<textarea name="notes" value="ignored">  First
          |  &amp; second  </textarea>
          |<select name="choice"><option value="selected" selected>Selected</option></select>
          |<select name="explicit" value="raw"><option value="other" selected>Other</option></select>
          |</form></body></html>""".stripMargin
      val routes = Routes(
        Method.GET / "fields" -> handler(Response(body = Body.fromString(html)))
      )

      for
        page <- DisconnectedRender.run(
                  routes,
                  Request.get(URL.decode("/fields").fold(throw _, identity))
                )
        form <- ZIO.fromEither(page.form()).orDieWith(error => new AssertionError(error.toString))
        rendered = RenderedHtml.parse(page.html)
        fields   = rendered.selectAll("input, textarea, select")
      yield assertTrue(
        rendered.html == html,
        fields.map(_.map(_.value)) ==
          Right(Vector("2026-99-88", "  keep & spaces  ", "", "First\n  & second", "", "raw")),
        fields.map(_.map(element => (element.tagName, element.attribute("name"), element.value))) ==
          Right(form.fields.map(field => (field.tagName, Some(field.name), field.value))),
        rendered.selectOne("textarea").map(_.text) == Right("First\n  & second"),
        rendered.selectOne("[name=enabled]").map(_.attribute("checked")) == Right(Some("")),
        rendered.selectOne("[name=enabled]").map(_.attribute("disabled")) == Right(Some(""))
      )
    },
    test("full documents and ordinary fragments preserve source HTML and expose document text") {
      val documentHtml =
        """<!doctype html><html><head><title>Snapshot</title></head><body>
          |<p>Hello
          |  <strong>world</strong> &amp; friends</p>
          |</body></html>""".stripMargin
      val fragmentHtml = "<p> Hello\n <b>world</b> </p><p>Next&nbsp;line</p>"
      val document     = RenderedHtml.parse(documentHtml)
      val fragment     = RenderedHtml.parse(fragmentHtml)

      assertTrue(
        document.html == documentHtml,
        document.text == "Snapshot Hello world & friends",
        document.selectOne("head > title").map(_.text) == Right("Snapshot"),
        fragment.html == fragmentHtml,
        fragment.text == "Hello world Next line",
        fragment.selectAll("html > body > p").map(_.map(_.text)) ==
          Right(Vector("Hello world", "Next line"))
      )
    },
    test("mutating matchText queries cannot change other queries or retained element data") {
      // Jsoup 1.23.1 supports this deprecated mutating selector. Revisit this regression when
      // upgrading past its removal, without weakening query isolation.
      val html =
        """<div id="content" data-state="kept">Before <span data-label="inner">inside</span> after</div>"""
      val rendered    = RenderedHtml.parse(html)
      val retained    = rendered.selectOne("#content")
      val descendants = rendered.selectAll("#content *")
      val textMatches = rendered.selectAll("#content :matchText")
      val afterAll    = rendered.selectAll("#content *")
      val textMatch   = rendered.selectOne("#content span:matchText")
      val afterOne    = rendered.selectAll("#content *")

      assertTrue(
        textMatches.exists(_.nonEmpty),
        textMatch.map(_.text) == Right("inside"),
        descendants.map(_.size) == Right(1),
        afterAll.map(_.size) == Right(1),
        afterOne.map(_.size) == Right(1),
        descendants.map(
          _.map(element => (element.tagName, element.text, element.attribute("data-label")))
        ) ==
          Right(Vector(("span", "inside", Some("inner")))),
        afterOne.map(
          _.map(element => (element.tagName, element.text, element.attribute("data-label")))
        ) ==
          Right(Vector(("span", "inside", Some("inner")))),
        retained.map(element =>
          (element.tagName, element.text, element.value, element.attribute("data-state"))
        ) ==
          Right(("div", "Before inside after", "", Some("kept"))),
        rendered.selectOne("#content").map(_.text) == Right("Before inside after"),
        rendered.text == "Before inside after",
        rendered.html == html
      )
    }
  )
end RenderedHtmlSpec
