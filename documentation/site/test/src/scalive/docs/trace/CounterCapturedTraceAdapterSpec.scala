package scalive.docs.trace

import zio.json.ast.Json
import zio.test.*

import scalive.docs.model.*
import scalive.docs.xray.*

object CounterCapturedTraceAdapterSpec extends ZIOSpecDefault:
  override def spec = suite("CounterCapturedTraceAdapterSpec")(
    test("maps only present stages and consolidates runtime records") {
      val interaction = captured(
        Vector(
          record(TraceProducer.Browser, 1, "BrowserEvent"),
          record(TraceProducer.Browser, 2, "OutboundFrame", protocol = Some(frameProtocol)),
          record(TraceProducer.Server, 1, "DecodedEvent"),
          record(TraceProducer.Server, 2, "BindingResolution"),
          record(TraceProducer.Server, 3, "TypedMessage", value = Some(message)),
          record(TraceProducer.Server, 4, "Lifecycle", summary = "Handler started"),
          record(TraceProducer.Server, 5, "Lifecycle", summary = "Handler completed"),
          record(TraceProducer.Server, 6, "ModelProposed", value = Some(model)),
          record(TraceProducer.Server, 7, "TreeDiff", summary = "Tree diff contains changes"),
          record(TraceProducer.Server, 8, "FinalFrame", protocol = Some(frameProtocol), byteSize = Some(143)),
          record(TraceProducer.Browser, 3, "DomDiff", protocol = Some(domProtocol))
        )
      )

      val trace  = CounterCapturedTraceAdapter.adapt(interaction)
      val labels = trace.phases.flatMap(_.steps.map(stepLabel))

      assertTrue(
        trace.id == interaction.id,
        trace.participants.map(_.id) == Vector("browser", "runtime", "live-view"),
        trace.phases.map(_.id) == Vector("event", "lifecycle", "response"),
        labels == Vector(
          "Increase counter",
          "Send event frame",
          "Decode counter event",
          "Increment",
          "Handle Increment",
          "Propose counter model",
          "Compute tree diff",
          "Send rendered diff",
          "Apply DOM patch"
        ),
        !labels.contains("Render counter"),
        !labels.contains("Commit counter model"),
        TraceCatalog.validate(Vector(trace)).isEmpty
      )
    },
    test("attaches projected fields, operation metadata, bytes, and sanitized protocol as evidence") {
      val interaction = captured(
        Vector(
          record(TraceProducer.Server, 1, "TypedMessage", value = Some(message)),
          record(TraceProducer.Server, 2, "ModelProposed", value = Some(model)),
          record(
            TraceProducer.Server,
            3,
            "FinalFrame",
            protocol = Some(frameProtocol),
            byteSize = Some(143)
          ),
          record(TraceProducer.Browser, 4, "DomDiff", protocol = Some(domProtocol))
        )
      )

      val trace    = CounterCapturedTraceAdapter.adapt(interaction)
      val evidence = trace.phases.flatMap(_.steps.flatMap(stepEvidence))
      val facts    = evidence.flatMap(_.facts)
      val code     = evidence.flatMap(_.code).mkString("\n")

      assertTrue(
        evidence.map(_.label).contains("Typed message"),
        evidence.map(_.label).contains("DOM mutations"),
        facts.contains("projected type" -> "scalive.docs.examples.CounterExample.Msg.Increment"),
        facts.contains("count" -> "1"),
        facts.contains("operation kind" -> "ClientEvent"),
        facts.contains("connection epoch" -> "3"),
        facts.contains("socket epoch" -> "2"),
        facts.contains("message reference" -> "7"),
        facts.contains("frame bytes" -> "143"),
        code.contains("[redacted]"),
        !code.contains("private-value"),
        code.contains("mutations")
      )
    }
  )

  private val message = DocumentationTraceValue(
    "scalive.docs.examples.CounterExample.Msg.Increment",
    "Increment message",
    Vector.empty
  )
  private val model = DocumentationTraceValue(
    "scalive.docs.examples.CounterExample.Model",
    "Counter value is 1",
    Vector("count" -> "1")
  )
  private val frameProtocol = Json.Obj(
    "event"   -> Json.Str("phx_reply"),
    "payload" -> Json.Obj("content" -> Json.Str("private-value"))
  )
  private val domProtocol = Json.Obj(
    "mutations" -> Json.Arr(Json.Obj("kind" -> Json.Str("text"), "after" -> Json.Str("1")))
  )

  private def captured(records: Vector[DocumentationTraceRecord]): CapturedInteraction =
    CapturedInteraction(
      "counter-interaction-ref-7-1",
      Some("7"),
      records,
      CapturedInteractionAnchor(Some(1L), Some(1L)),
      CapturedInteractionState.Complete,
      "Increase counter",
      "Increment message"
    )

  private def record(
    producer: TraceProducer,
    sequence: Long,
    stage: String,
    summary: String = "Captured stage",
    value: Option[DocumentationTraceValue] = None,
    protocol: Option[Json] = None,
    byteSize: Option[Int] = None
  ): DocumentationTraceRecord =
    DocumentationTraceRecord(
      producer,
      sequence,
      "trace-session",
      Option.when(producer == TraceProducer.Server)(3L),
      Option.when(producer == TraceProducer.Server)(2L),
      "lv:counter",
      Some("1"),
      Some("7"),
      9L,
      if producer == TraceProducer.Server then "ClientEvent" else "Browser",
      sequence,
      stage,
      summary,
      value,
      protocol,
      byteSize
    )

  private def stepLabel(step: TraceStep): String = step match
    case TraceStep.Operation(_, label, _, _)  => label
    case TraceStep.Message(_, _, label, _, _) => label
    case TraceStep.Boundary(label, _, _)      => label

  private def stepEvidence(step: TraceStep): Vector[TraceEvidence] = step match
    case TraceStep.Operation(_, _, _, evidence)  => evidence
    case TraceStep.Message(_, _, _, _, evidence) => evidence
    case TraceStep.Boundary(_, _, evidence)      => evidence
end CounterCapturedTraceAdapterSpec
