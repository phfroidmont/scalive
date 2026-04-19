package scalive.defs.complex

import scalive.*

import zio.test.*

object CompositeKeysSpec extends ZIOSpecDefault:

  override def spec = suite("CompositeKeysSpec")(
    test("aria namespace") {
      val el = div(
        aria.hidden := true,
        aria.label := "Dialog"
      )

      val result = HtmlBuilder.build(el)

      assertTrue(result == "<div aria-hidden=\"true\" aria-label=\"Dialog\"></div>")
    },
    test("xlink namespace") {
      val el = div(
        a(
          xlink.href := "#marker",
          xlink.title := "Marker"
        )
      )

      val result = HtmlBuilder.build(el)

      assertTrue(result == "<div><a xlink-href=\"#marker\" xlink-title=\"Marker\"></a></div>")
    }
  )
