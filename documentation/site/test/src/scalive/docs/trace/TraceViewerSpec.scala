package scalive.docs.trace

import org.jsoup.Jsoup
import scala.jdk.CollectionConverters.*
import zio.test.*

import scalive.HtmlBuilder
import scalive.docs.model.*

object TraceViewerSpec extends ZIOSpecDefault:
  override def spec = suite("TraceViewerSpec")(
    test("renders the authored HTTP lifecycle in causal order") {
      val document = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(TraceCatalog.HttpGet)))
      val steps    = document.select("[data-trace-step]")
      val messages = document.select("[data-trace-step-kind=message]")
      val phases   = document.select(".docs-trace-phase-group")

      assertTrue(
        document.select("figure[data-trace-viewer=http-get][data-trace-provenance=authored]").size() == 1,
        document.select(".docs-trace-actors > li").eachText().asScala.toVector == Vector(
          "Browser",
          "Scalive runtime",
          "Your LiveView"
        ),
        steps.size() == 10,
        steps.eachAttr("data-trace-step").asScala.toVector == (1 to 10).map(_.toString).toVector,
        phases.size() == 3,
        phases.asScala.toVector.map(_.selectFirst(".docs-trace-phase-index").text()) ==
          Vector("Phase 1", "Phase 2", "Phase 3"),
        phases.asScala.toVector.map(_.selectFirst(".docs-trace-phase").ownText()) ==
          Vector("Request", "Disconnected lifecycle", "Response and teardown"),
        phases.asScala.toVector.map(_.selectFirst(".docs-trace-phase-events").attr("start")) == Vector(
          "1",
          "4",
          "9"
        ),
        phases.asScala.toVector.map(_.select("[data-trace-step]").size()) == Vector(3, 5, 2),
        messages.eachAttr("data-trace-from").asScala.toVector == Vector(
          "browser",
          "runtime",
          "live-view",
          "runtime",
          "live-view",
          "runtime"
        ),
        messages.eachAttr("data-trace-to").asScala.toVector == Vector(
          "runtime",
          "live-view",
          "runtime",
          "live-view",
          "runtime",
          "browser"
        ),
        document.select(".docs-trace-event-heading > .docs-trace-order").eachText().asScala.toVector ==
          (1 to 10).map(value => f"$value%02d").toVector,
        document.select(".docs-trace-route [data-trace-participant]").eachText().asScala.toVector ==
          Vector(
            "Browser",
            "Scalive runtime",
            "Scalive runtime",
            "Your LiveView",
            "Your LiveView",
            "Scalive runtime",
            "Scalive runtime",
            "Your LiveView",
            "Your LiveView",
            "Scalive runtime",
            "Scalive runtime",
            "Browser"
          ),
        document.select(".docs-trace-route-arrow").eachText().asScala.toVector == Vector.fill(6)("->"),
        document.select("figure.docs-trace").attr("aria-labelledby") == "docs-trace-http-get-title",
        !document.html().contains("phx-")
      )
    },
    test("groups technical records by causal step") {
      val document = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(TraceCatalog.HttpGet)))
      val evidence = document.select("details.docs-trace-evidence")

      assertTrue(
        evidence.size() == 2,
        evidence.asScala.forall(_.parent().hasClass("docs-trace-step")),
        evidence.asScala.forall(_.attr("data-trace-evidence-count") == "1"),
        evidence.asScala.forall(_.select(".docs-trace-evidence-record").size() == 1),
        evidence.asScala.forall(value => value.select("dl > div").asScala.forall(_.select("dt, dd").size() == 2)),
        evidence.select("summary .docs-trace-evidence-record-summary").size() == 2,
        evidence.text().contains("connected"),
        evidence.text().contains("fresh connected mount")
      )
    },
    test("labels shared context and renders records as ordered disclosures") {
      val trace = TraceDefinition(
        "shared-context",
        "Shared context",
        "Evidence rendering fixture",
        Vector(TraceParticipant("runtime", "Runtime", "Runs the operation")),
        Vector(
          TracePhase(
            "phase",
            "Phase",
            Vector(
              TraceStep.Operation(
                "runtime",
                "Operation",
                "Runs once",
                Vector(
                  TraceEvidence(
                    label = "Started",
                    summary = "Operation started",
                    producer = Some("Runtime"),
                    highlights = Vector("12 B"),
                    correlation = Vector("message" -> "#7"),
                    metadata = Vector("operation" -> "Client event")
                  ),
                  TraceEvidence(
                    label = "Completed",
                    summary = "Operation completed",
                    producer = Some("Browser"),
                    correlation = Vector("message" -> "#7")
                  )
                )
              )
            )
          )
        )
      )
      val document = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(trace)))
      val evidence = document.selectFirst("details.docs-trace-evidence")

      assertTrue(
        evidence.select(".docs-trace-evidence-common h5").text() == "Correlation",
        evidence.select(".docs-trace-evidence-common").text().contains("message #7"),
        evidence.select("ol.docs-trace-evidence-records").size() == 1,
        evidence.select("li.docs-trace-evidence-record").size() == 2,
        evidence.select(".docs-trace-evidence-record > details").size() == 2,
        evidence.select(".docs-trace-evidence-producer").eachText().asScala.toVector ==
          Vector("Runtime", "Browser"),
        evidence.select(".docs-trace-evidence-highlights").text() == "12 B",
        evidence.select("summary .docs-trace-evidence-description").isEmpty,
        evidence.select(".docs-trace-evidence-metadata").text().contains("Client event")
      )
    }
  )
