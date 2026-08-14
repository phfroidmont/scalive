package scalive.docs.trace

import org.jsoup.Jsoup
import scala.jdk.CollectionConverters.*
import zio.test.*

import scalive.HtmlBuilder
import scalive.docs.model.TraceCatalog

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
    test("uses native disclosure for authored lifecycle evidence") {
      val document = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(TraceCatalog.HttpGet)))
      val evidence = document.select("details.docs-trace-evidence")

      assertTrue(
        evidence.size() == 2,
        evidence.select("summary").eachText().asScala.toVector == Vector(
          "Mount context",
          "Lifecycle boundary"
        ),
        evidence.asScala.forall(_.parent().hasClass("docs-trace-event-copy")),
        evidence.asScala.forall(value => value.select("dl > div").asScala.forall(_.select("dt, dd").size() == 2)),
        evidence.text().contains("connected"),
        evidence.text().contains("fresh connected mount")
      )
    }
  )
