package scalive.docs.trace

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

import scalive.*
import scalive.docs.model.*
import scalive.testing.ConnectedRender

object TraceViewerSpec extends ZIOSpecDefault:
  private def render(trace: TraceDefinition): RIO[Scope, Document] =
    val view = new LiveView.Eventless[Unit]:
      def mount(ctx: MountContext) = ZIO.unit
      override def view(model: Signal[Unit]) = TraceViewer.render(trace)
    ConnectedRender.join(view).flatMap(_.html).map(Jsoup.parseBodyFragment)

  override def spec = suite("TraceViewerSpec")(
    test("renders trace structure in source order") {
      ZIO.scoped {
        render(TraceCatalog.HttpGet).map { document =>
          val figure = document.selectFirst("figure[data-trace-viewer=http-get]")
          val steps  = document.select("[data-trace-step]")
          val phases = document.select(".docs-trace-phase-group")
          val trace   = TraceCatalog.HttpGet
          val expectedSteps = trace.phases.flatMap(_.steps)

          assertTrue(
            figure.attr("data-trace-provenance") == "authored",
            figure.attr("aria-labelledby") == "docs-trace-http-get-title",
            document.selectFirst("#docs-trace-http-get-title").text() == trace.title,
            steps.eachAttr("data-trace-step").asScala.toVector ==
              expectedSteps.indices.map(index => (index + 1).toString).toVector,
            phases.eachAttr("data-trace-phase").asScala.toVector == trace.phases.map(_.id),
            phases.asScala.toVector.map(_.selectFirst(".docs-trace-phase-events").attr("start")) ==
              Vector("1", "4", "9"),
            steps.last().attr("data-trace-step-kind") == "boundary",
            !document.html().contains("phx-")
          )
        }
      }
    },
    test("keeps authored lifecycle traces concise") {
      ZIO.scoped {
        for
          http <- render(TraceCatalog.HttpGet)
          join <- render(TraceCatalog.LiveSocketJoin)
        yield assertTrue(
          http.select(".docs-trace-evidence").isEmpty,
          join.select(".docs-trace-evidence").isEmpty,
          http.text().contains("temporary model for this HTTP request"),
          http.text().contains("not carried into the socket lifecycle"),
          join.text().contains("fresh lifecycle"),
          join.text().contains("become current only after the initial render and its after-render hooks succeed")
        )
      }
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
      ZIO.scoped {
        render(trace).map { document =>
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
        }
      }
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
      ZIO.scoped {
        render(trace).map { document =>
          val detail = document.selectFirst("[data-trace-evidence='Updated model']")
          assertTrue(
            TraceCatalog.validate(Vector(trace)).isEmpty,
            detail.hasClass("docs-trace-evidence-inline"),
            detail.select("details").isEmpty,
            detail.select("dl").text() == "count 2"
          )
        }
      }
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
      ZIO.scoped {
        render(trace).map { document =>
          val detail = document.selectFirst("[data-trace-evidence='Updated model']")
          assertTrue(
            TraceCatalog.validate(Vector(trace)).isEmpty,
            detail.select("code.docs-trace-evidence-scala-value").text() ==
              "CounterExample.Model(count = 1)",
            detail.select("p, dl").isEmpty
          )
        }
      }
    }
  )
