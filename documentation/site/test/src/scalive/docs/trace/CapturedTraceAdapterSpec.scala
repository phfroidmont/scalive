package scalive.docs.trace

import zio.json.ast.Json
import zio.test.*

import scalive.docs.model.*
import scalive.docs.xray.*

object CapturedTraceAdapterSpec extends ZIOSpecDefault:
  override def spec = suite("CapturedTraceAdapterSpec")(
    test("represents every lane transition in the captured causal flow") {
      val interaction = captured(
        Vector(
          record(TraceProducer.Browser, 1, "BrowserEvent"),
          record(TraceProducer.Browser, 2, "OutboundFrame", protocol = Some(frameProtocol)),
          record(TraceProducer.Server, 1, "DecodedEvent"),
          record(TraceProducer.Server, 2, "BindingResolution"),
          record(TraceProducer.Server, 3, "TypedMessage", value = Some(message)),
          record(
            TraceProducer.Server,
            4,
            "LifecycleStarted",
            summary = "Event lifecycle and message handler started"
          ),
          record(
            TraceProducer.Server,
            5,
            "LifecycleCompleted",
            summary = "Event lifecycle and message handler completed"
          ),
          record(TraceProducer.Server, 6, "ModelProposed", value = Some(model)),
          record(TraceProducer.Server, 7, "RenderStarted"),
          record(TraceProducer.Server, 8, "ModelRendered", value = Some(model)),
          record(TraceProducer.Server, 9, "RenderCompleted"),
          record(TraceProducer.Server, 10, "TreeDiff", summary = "Tree diff contains changes"),
          record(TraceProducer.Server, 11, "ModelCommitted", value = Some(model)),
          record(TraceProducer.Server, 12, "FinalPayload"),
          record(
            TraceProducer.Server,
            13,
            "FinalFrame",
            protocol = Some(frameProtocol),
            byteSize = Some(143)
          ),
          record(TraceProducer.Browser, 3, "DomDiff", protocol = Some(domProtocol))
        )
      )

      val trace = CapturedTraceAdapter.adapt(ExampleCatalog.Counter, interaction)
      val steps = trace.phases.flatMap(_.steps)

      assertTrue(
        trace.id == interaction.id,
        trace.participants.map(_.id) == Vector("browser", "runtime", "live-view"),
        steps.nonEmpty,
        causalFlowErrors(steps).isEmpty,
        messageWithEvidence(steps, "Render started").contains("runtime" -> "live-view"),
        messageWithEvidence(steps, "Render completed").contains("live-view" -> "runtime"),
        TraceCatalog.validate(Vector(trace)).isEmpty
      )
    },
    test("groups lifecycle evidence with semantic start and completion labels") {
      val interaction = captured(
        Vector(
          record(
            TraceProducer.Server,
            1,
            "LifecycleStarted",
            summary = "Event lifecycle and message handler started"
          ),
          record(
            TraceProducer.Server,
            2,
            "LifecycleCompleted",
            summary = "Event lifecycle and message handler completed"
          ),
          record(
            TraceProducer.Server,
            3,
            "LifecycleStarted",
            summary = "After-render lifecycle started"
          )
        )
      )

      val trace           = CapturedTraceAdapter.adapt(ExampleCatalog.Counter, interaction)
      val handlerEvidence = trace.phases
        .flatMap(_.steps)
        .collectFirst { case TraceStep.Operation("live-view", _, _, evidence) =>
          evidence
        }
        .getOrElse(Vector.empty)

      assertTrue(
        handlerEvidence.map(_.label) == Vector("Handler started", "Handler completed"),
        handlerEvidence.map(_.label).distinct.size == 2,
        handlerEvidence.forall(evidence => !evidence.label.matches(".* \\d+$"))
      )
    },
    test("omits phases whose captured stages are absent") {
      val trace = CapturedTraceAdapter.adapt(
        ExampleCatalog.Counter,
        captured(Vector(record(TraceProducer.Browser, 1, "BrowserEvent")))
      )

      assertTrue(
        trace.phases.map(_.id) == Vector("trigger"),
        trace.phases.forall(_.steps.nonEmpty)
      )
    },
    test(
      "attaches projected fields, operation metadata, bytes, and sanitized protocol as evidence"
    ) {
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

      val trace    = CapturedTraceAdapter.adapt(ExampleCatalog.Counter, interaction)
      val evidence = trace.phases.flatMap(_.steps.flatMap(stepEvidence))
      val code     = evidence.flatMap(_.code).mkString("\n")
      val finalFrame = evidence.find(_.label == "Final frame").get
      val proposed   = evidence.find(_.label == "Proposed model").get

      assertTrue(
        evidence.map(_.label).contains("Typed message"),
        evidence.map(_.label).contains("DOM mutations"),
        evidence.flatMap(_.producer).toSet == Set("Runtime", "Browser"),
        finalFrame.highlights == Vector("143 B"),
        finalFrame.metadata.contains("operation" -> "Client event"),
        finalFrame.metadata.contains("connection" -> "3"),
        finalFrame.metadata.contains("socket" -> "2"),
        finalFrame.correlation.contains("message" -> "#7"),
        proposed.projection.exists(_.typeName == "scalive.docs.examples.CounterExample.Model"),
        proposed.projection.exists(_.fields.contains("count" -> "1")),
        evidence.forall(_.facts.forall(_._1 != "stage")),
        code.contains("[redacted]"),
        !code.contains("private-value"),
        !code.contains("server-secret"),
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
    "payload" -> Json.Obj(
      "content"  -> Json.Str("private-value"),
      "csrfToken" -> Json.Str("server-secret")
    )
  )
  private val domProtocol = Json.Obj(
    "mutations" -> Json.Arr(Json.Obj("kind" -> Json.Str("text"), "after" -> Json.Str("1")))
  )

  private def captured(records: Vector[DocumentationTraceRecord]): CapturedInteraction =
    CapturedInteraction(
      id = "captured-operation-1",
      ordinal = 1L,
      operationKind = "ClientEvent",
      reference = Some("7"),
      records = records,
      orderingAnchor = CapturedInteractionAnchor(Some(1L), Some(1L)),
      state = CapturedInteractionState.Complete,
      label = "Increment",
      summary = "Increment message"
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
      byteSize,
      interactionOrdinal = Some(1L)
    )

  private def stepEvidence(step: TraceStep): Vector[TraceEvidence] = step match
    case TraceStep.Operation(_, _, _, evidence)  => evidence
    case TraceStep.Message(_, _, _, _, evidence) => evidence
    case TraceStep.Boundary(_, _, evidence)      => evidence

  private def messageWithEvidence(
    steps: Vector[TraceStep],
    evidenceLabel: String
  ): Option[(String, String)] =
    steps.collectFirst {
      case TraceStep.Message(from, to, _, _, evidence) if evidence.exists(_.label == evidenceLabel) =>
        from -> to
    }

  private def causalFlowErrors(steps: Vector[TraceStep]): Vector[String] =
    steps
      .foldLeft((Option.empty[String], Vector.empty[String])) {
        case ((current, errors), TraceStep.Operation(participant, label, _, _)) =>
          val nextErrors = current
            .filter(_ != participant).fold(errors)(lane =>
              errors :+ s"$label starts on $participant after the flow ended on $lane"
            )
          Some(participant) -> nextErrors
        case ((current, errors), TraceStep.Message(from, to, label, _, _)) =>
          val nextErrors = current
            .filter(_ != from).fold(errors)(lane =>
              errors :+ s"$label leaves $from after the flow ended on $lane"
            )
          Some(to) -> nextErrors
        case (state, _: TraceStep.Boundary) => state
      }._2
end CapturedTraceAdapterSpec
