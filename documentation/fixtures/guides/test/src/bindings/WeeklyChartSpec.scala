package bindings

import zio.*
import zio.test.*

import scalive.testing.ConnectedRender

object WeeklyChartSpec extends ZIOSpecDefault:
  def spec = suite("Inline SVG")(
    test("a chart's bars follow the model") {
      ZIO.scoped {
        for
          view   <- ConnectedRender.join(WeeklyChartLiveView())
          before <- view.html
          _      <- view.click("rect[x=\"31\"]")
          after  <- view.html
        yield assertTrue(
          before.contains("<svg viewBox=\"0 0 70 40\""),
          before.contains("<rect x=\"31\" y=\"20\" width=\"8\" height=\"20\" fill=\"currentColor\""),
          after.contains("<rect x=\"31\" y=\"19\" width=\"8\" height=\"21\" fill=\"currentColor\"")
        )
      }
    }
  )
end WeeklyChartSpec
