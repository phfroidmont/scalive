package scalive

import utest.*

object CompositeKeysSpec extends TestSuite:

  val tests = Tests {
    test("aria namespace") {
      val el = div(
        aria.hidden := true,
        aria.label := "Dialog"
      )

      val result = HtmlBuilder.build(el)

      assert(result == "<div aria-hidden=\"true\" aria-label=\"Dialog\"></div>")
    }

    test("xlink namespace") {
      val el = div(
        a(
          xlink.href := "#marker",
          xlink.title := "Marker"
        )
      )

      val result = HtmlBuilder.build(el)

      assert(result == "<div><a xlink-href=\"#marker\" xlink-title=\"Marker\"></a></div>")
    }
  }
