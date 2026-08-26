package scalive.docs.examples

import zio.*
import zio.test.*

import scalive.*
import scalive.testing.{ConnectedRender, ConnectedView}

object AsyncReportExampleSpec extends ZIOSpecDefault:
  private def eventuallyText(
    harness: ConnectedView[?],
    selector: String,
    expected: String
  ): Task[String] =
    harness.text(selector).repeatUntil(_ == expected)

  override def spec = suite("AsyncReportExampleSpec")(
    test("renders deterministic success and failure states") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new AsyncReportExample("report-results"))
          _       <- harness.clickButton("Run successful report")
          loading <- harness.text("[data-report-status]")
          _       <- TestClock.adjust(2.seconds)
          _       <- harness.awaitDiff
          success <- harness.text("[data-report-status]")
          title   <- harness.text("[data-report-title]")
          _       <- harness.clickButton("Run failing report")
          _       <- TestClock.adjust(1.second)
          _       <- harness.awaitDiff
          failure <- harness.text("[data-report-status]")
          retained <- harness.text("[data-report-title]")
        yield assertTrue(
          loading == "Loading",
          success == "Succeeded",
          title == "Quarterly activity",
          failure == "Failed",
          retained == "Quarterly activity"
        )
      }
    },
    test("replacement suppresses stale completion") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new AsyncReportExample("report-replacement"))
          _       <- harness.clickButton("Run successful report")
          _       <- harness.clickButton("Replace current work")
          _       <- TestClock.adjust(600.millis)
          _       <- harness.awaitDiff
          title   <- harness.text("[data-report-title]")
          _       <- TestClock.adjust(2.seconds)
          stable  <- harness.text("[data-report-title]")
        yield assertTrue(title == "Replacement report", stable == "Replacement report")
      }
    },
    test("retries after failure and isolates report state between instances") {
      ZIO.scoped {
        for
          first  <- ConnectedRender.join(new AsyncReportExample("report-first"))
          second <- ConnectedRender.join(new AsyncReportExample("report-second"))
          _      <- first.clickButton("Run failing report")
          _      <- TestClock.adjust(1.second)
          _      <- first.awaitDiff
          failed <- first.text("[data-report-status]")
          _      <- first.clickButton("Retry report")
          _      <- TestClock.adjust(800.millis)
          _      <- first.awaitDiff
          retried <- first.text("[data-report-title]")
          untouched <- second.text("[data-report-state]")
        yield assertTrue(
          failed == "Failed",
          retried == "Retried report",
          untouched == "Empty"
        )
      }
    },
    test("distinguishes explicit cancellation from reset") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new AsyncReportExample("report-cancel"))
          _       <- harness.clickButton("Run successful report")
          _       <- harness.clickButton("Cancel report")
          cancelled <- eventuallyText(harness, "[data-report-status]", "Cancelled")
          _         <- harness.clickButton("Run successful report")
          _         <- harness.send(AsyncReportExample.Msg.Reset)
          reset     <- eventuallyText(harness, "[data-report-state]", "Empty")
        yield assertTrue(cancelled == "Cancelled", reset == "Empty")
      }
    }
  )
end AsyncReportExampleSpec
