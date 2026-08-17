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
    test("renders authored facts inline without record disclosures") {
      val document = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(TraceCatalog.HttpGet)))
      val evidence = document.select(".docs-trace-evidence-inline").asScala
        .filter(_.select("dl").size() == 1)

      assertTrue(
        evidence.size == 2,
        evidence.forall(_.parent().hasClass("docs-trace-step")),
        evidence.forall(value => value.select("dl > div").asScala.forall(_.select("dt, dd").size() == 2)),
        evidence.forall(_.select("details").isEmpty),
        document.select(".docs-trace-evidence-record").isEmpty,
        evidence.map(_.text()).mkString(" ").contains("connected"),
        evidence.map(_.text()).mkString(" ").contains("fresh connected mount")
      )
    },
    test("renders symbolic Scala values in authored traces") {
      val http = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(TraceCatalog.HttpGet)))
      val join = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(TraceCatalog.LiveSocketJoin)))

      assertTrue(
        http.select("code.docs-trace-evidence-scala-value").eachText().asScala.toVector ==
          Vector("modelA: Model", "render(modelA): HtmlElement[Msg]"),
        join.select("code.docs-trace-evidence-scala-value").eachText().asScala.toVector ==
          Vector("modelB: Model", "render(modelB): HtmlElement[Msg]")
      )
    },
    test("renders code in one direct disclosure") {
      val trace = TraceDefinition(
        "code-detail",
        "Code detail",
        "Evidence rendering fixture",
        Vector(TraceParticipant("runtime", "Runtime", "Runs the operation")),
        Vector(
          TracePhase(
            "phase",
            "Phase",
            Vector(
              TraceStep.Operation(
                "runtime",
                "Send frame",
                "Sends one protocol frame.",
                Some(
                  TraceEvidence(
                    label = "Protocol frame",
                    summary = Some("Encoded event"),
                    facts = Vector("size" -> "12 B"),
                    code = Some("{\n  \"event\": \"event\"\n}")
                  )
                )
              )
            )
          )
        )
      )
      val document = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(trace)))
      val evidence = document.selectFirst("details[data-trace-evidence='Protocol frame']")

      assertTrue(
        evidence.select(".docs-trace-evidence-label, .docs-trace-evidence-summary-fact").text() ==
          "Protocol frame size 12 B",
        evidence.select(".docs-trace-evidence-summary").text() == "Encoded event",
        evidence.children().asScala.count(_.hasClass("docs-trace-evidence-code")) == 1,
        evidence.select("details details").isEmpty,
        evidence.select(".docs-trace-evidence-record").isEmpty,
        !document.text().contains("1 record")
      )
    },
    test("renders semantic fields inline") {
      val trace = TraceDefinition(
        "field-only",
        "Field-only evidence",
        "Evidence rendering fixture",
        Vector(TraceParticipant("runtime", "Runtime", "Runs the operation")),
        Vector(
          TracePhase(
            "phase",
            "Phase",
            Vector(
              TraceStep.Operation(
                "runtime",
                "Return updated model",
                "The handler proposes a new immutable model.",
                Some(
                  TraceEvidence(
                    label = "Updated model",
                    facts = Vector("count" -> "2")
                  )
                )
              )
            )
          )
        )
      )
      val document = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(trace)))
      val detail   = document.selectFirst("[data-trace-evidence='Updated model']")

      assertTrue(
        TraceCatalog.validate(Vector(trace)).isEmpty,
        detail.hasClass("docs-trace-evidence-inline"),
        detail.select("details").isEmpty,
        detail.select("dl").text() == "count 2"
      )
    },
    test("renders an exact Scala value as inline code") {
      val trace = TraceDefinition(
        "scala-value",
        "Scala value",
        "Evidence rendering fixture",
        Vector(TraceParticipant("runtime", "Runtime", "Runs the operation")),
        Vector(
          TracePhase(
            "phase",
            "Phase",
            Vector(
              TraceStep.Operation(
                "runtime",
                "Return updated model",
                "The handler proposes a new immutable model.",
                Some(
                  TraceEvidence(
                    label = "Updated model",
                    scalaValue = Some("CounterExample.Model(count = 1)")
                  )
                )
              )
            )
          )
        )
      )
      val document = Jsoup.parseBodyFragment(HtmlBuilder.build(TraceViewer.render(trace)))
      val detail   = document.selectFirst("[data-trace-evidence='Updated model']")

      assertTrue(
        TraceCatalog.validate(Vector(trace)).isEmpty,
        detail.select("code.docs-trace-evidence-scala-value").text() ==
          "CounterExample.Model(count = 1)",
        detail.select("p, dl").isEmpty
      )
    }
  )
