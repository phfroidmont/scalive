package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.docs.SiteLiveViewHarness
import scalive.testing.DisconnectedRender

object ReportsExampleSpec extends ZIOSpecDefault:
  private def state(harness: SiteLiveViewHarness[?, ?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  private def reports(values: Vector[Report]): Reports = new Reports:
    def recent: Task[Vector[Report]] = ZIO.succeed(values)

  override def spec = suite("ReportsExampleSpec")(
    test("loads reports through a constructor-injected service") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(ReportsExamplePreview())
          initial <- state(harness)
          _       <- harness.click("[data-report-id='2']")
          selected <- state(harness)
        yield assertTrue(
          initial.select("[data-report-selected]").text() == "Daily sales",
          initial.select(".docs-reports-toolbar-actions .docs-reports-refresh").size() == 1,
          initial.select(".docs-reports-toolbar-actions .docs-reports-lab-link").size() == 1,
          initial.select(".docs-report-option[data-selected=true]").size() == 1,
          initial.select(".docs-report-option[aria-pressed=true] .docs-report-option-state").text() ==
            "Selected",
          selected.select("[data-report-selected]").text() == "Open incidents",
          selected.select("[data-report-id=2][data-selected=true]").size() == 1,
          selected.select("[data-report-summary]").text().contains("3 incidents")
        )
      }
    },
    test("resets selection without querying the service again") {
      ZIO.scoped {
        for
          calls <- Ref.make(0)
          service = new Reports:
                      def recent = calls.updateAndGet(_ + 1).as(Reports.fixtures)
          harness <- SiteLiveViewHarness.join(new ReportsExample(service))
          _       <- harness.click("[data-report-id='2']")
          _       <- harness.sendServer(ReportsExample.Msg.ResetSelection)
          current <- state(harness)
          count   <- calls.get
        yield assertTrue(
          current.select("[data-report-selected]").text() == "Daily sales",
          count == 1
        )
      }
    },
    test("refreshes from the service and keeps the model valid") {
      ZIO.scoped {
        for
          responses <- Ref.make(
                         List(
                           Reports.fixtures,
                           Vector(Report(3L, "Release readiness", "All checks passed."))
                         )
                       )
          service = new Reports:
                      def recent = responses.modify {
                        case next :: rest => next -> rest
                        case Nil          => Vector.empty -> Nil
                      }
          harness <- SiteLiveViewHarness.join(new ReportsExample(service))
          _       <- harness.clickButton("Refresh reports")
          current <- state(harness)
        yield assertTrue(
          current.select("[data-report-selected]").text() == "Release readiness",
          current.select("[data-report-card]").size() == 1
        )
      }
    },
    test("renders empty and failed service results explicitly") {
      ZIO.scoped {
        for
          emptyHarness <- SiteLiveViewHarness.join(new ReportsExample(reports(Vector.empty)))
          empty        <- state(emptyHarness)
          failedHarness <- SiteLiveViewHarness.join(new ReportsExample(new Reports:
                             def recent = ZIO.fail(new RuntimeException("database password"))
                           ))
          failed <- state(failedHarness)
        yield assertTrue(
          empty.select(".docs-reports-status-empty[data-reports-empty] strong").text() ==
            "No reports are available.",
          failed.select(".docs-reports-status-failed[data-reports-failed] strong").text() ==
            "Reports are temporarily unavailable.",
          !failed.text().contains("database password")
        )
      }
    },
    test("registers and runs a route that requires the Reports layer") {
      val provided = ZLayer.succeed(
        reports(Vector(Report(42L, "Fixture report", "Provided by the route environment.")))
      )
      for
        rendered <- DisconnectedRender
                      .run(
                        scalive.Live.router(ReportsExample.route),
                        Request.get(URL.root / "examples" / "service-injection" / "lab")
                      )
                      .provideLayer(provided)
        document = Jsoup.parse(rendered.html)
      yield assertTrue(
        rendered.response.status == Status.Ok,
        document.select("[data-report-selected]").text() == "Fixture report"
      )
    }
  )
end ReportsExampleSpec
