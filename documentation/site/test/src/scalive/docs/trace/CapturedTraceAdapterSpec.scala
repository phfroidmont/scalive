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
          record(TraceProducer.Browser, 3, "InboundFrame", protocol = Some(frameProtocol)),
          record(TraceProducer.Browser, 4, "DomDiff", protocol = Some(domProtocol))
        )
      )

      val trace = CapturedTraceAdapter.adapt(ExampleCatalog.Counter, interaction)
      val steps = trace.phases.flatMap(_.steps)

      assertTrue(
        trace.id == interaction.id,
        trace.participants.map(_.id) == Vector("browser", "runtime", "live-view"),
        steps.nonEmpty,
        causalFlowErrors(steps).isEmpty,
        messageRoute(steps, "Request render").contains("runtime" -> "live-view"),
        messageRoute(steps, "Return rendered tree").contains("live-view" -> "runtime"),
        TraceCatalog.validate(Vector(trace)).isEmpty
      )
    },
    test("omits lifecycle markers that add no information to the handler step") {
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
        .flatten

      assertTrue(handlerEvidence.isEmpty)
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
    test("summarizes each step as at most one semantic detail") {
      val interaction = captured(
        Vector(
          record(TraceProducer.Browser, 1, "OutboundFrame", protocol = Some(frameProtocol)),
          record(
            TraceProducer.Server,
            1,
            "DecodedEvent",
            protocol = Some(frameProtocol)
          ),
          record(TraceProducer.Server, 2, "TypedMessage", value = Some(messageWithField)),
          record(TraceProducer.Server, 3, "ModelProposed", value = Some(model)),
          record(
            TraceProducer.Server,
            4,
            "FinalFrame",
            protocol = Some(frameProtocol),
            byteSize = Some(143)
          ),
          record(TraceProducer.Browser, 5, "InboundFrame", protocol = Some(frameProtocol)),
          record(TraceProducer.Browser, 6, "DomDiff", protocol = Some(domProtocol))
        )
      )

      val trace    = CapturedTraceAdapter.adapt(ExampleCatalog.Counter, interaction)
      val steps    = trace.phases.flatMap(_.steps)
      val evidence = steps.flatMap(stepEvidence(_).toVector)
      val code     = evidence.flatMap(_.code).mkString("\n")
      val outbound    = stepEvidence(step(steps, "Send protocol frame")).get
      val clientEvent = stepEvidence(step(steps, "Client event"))
      val typed       = stepEvidence(step(steps, "WithField")).get
      val proposed    = stepEvidence(step(steps, "Return updated model")).get
      val published   = stepEvidence(step(steps, "Publish result")).get

      assertTrue(
        steps.forall(stepEvidence(_).size <= 1),
        outbound.label == "Protocol frame",
        outbound.code.nonEmpty,
        clientEvent.isEmpty,
        typed.facts == Vector("value" -> "42"),
        proposed.facts == Vector("count" -> "1"),
        published.label == "Protocol frame",
        published.facts == Vector("size" -> "143 B"),
        evidence.count(_.label == "Protocol frame") == 2,
        !evidence.map(_.label).contains("Inbound frame"),
        !evidence.map(_.label).contains("Decoded event"),
        code.contains("[redacted]"),
        !code.contains("private-value"),
        !code.contains("server-secret"),
        code.contains("mutations")
      )
    },
    test("omits receiver-side frame copies and summary-only records") {
      val interaction = captured(
        Vector(
          record(TraceProducer.Browser, 1, "BrowserEvent", summary = "Browser event sent"),
          record(
            TraceProducer.Server,
            2,
            "DecodedEvent",
            summary = "Inbound protocol frame decoded",
            protocol = Some(frameProtocol)
          ),
          record(
            TraceProducer.Server,
            3,
            "DecodedEvent",
            summary = "Browser event decoded"
          ),
          record(
            TraceProducer.Browser,
            4,
            "InboundFrame",
            protocol = Some(frameProtocol)
          ),
          record(TraceProducer.Server, 5, "BindingResolution", summary = "Event binding resolved"),
          record(TraceProducer.Server, 6, "RenderStarted", summary = "Render started"),
          record(TraceProducer.Server, 7, "RenderCompleted", summary = "Render completed"),
          record(TraceProducer.Server, 8, "FinalPayload", summary = "Final socket payload published")
        )
      )

      val evidence = CapturedTraceAdapter
        .adapt(ExampleCatalog.Counter, interaction).phases.flatMap(_.steps.flatMap(stepEvidence))

      assertTrue(evidence.isEmpty)
    },
    test("promotes an unchanged tree diff into the timeline description") {
      val trace = CapturedTraceAdapter.adapt(
        ExampleCatalog.Counter,
        captured(
          Vector(
            record(
              TraceProducer.Server,
              1,
              "TreeDiff",
              summary = "Tree diff is empty"
            )
          )
        )
      )
      val step = trace.phases.flatMap(_.steps).collectFirst {
        case operation: TraceStep.Operation => operation
      }.get

      assertTrue(
        step.description == "The rendered tree matches the previous tree.",
        step.evidence.isEmpty
      )
    }
  )

  private val message = DocumentationTraceValue(
    "scalive.docs.examples.CounterExample.Msg.Increment",
    "Increment message",
    Vector.empty
  )
  private val messageWithField = DocumentationTraceValue(
    "scalive.docs.examples.CounterExample.Msg.WithField",
    "Message with one field",
    Vector("value" -> "42")
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

  private def stepEvidence(step: TraceStep): Option[TraceEvidence] = step match
    case TraceStep.Operation(_, _, _, evidence)  => evidence
    case TraceStep.Message(_, _, _, _, evidence) => evidence
    case TraceStep.Boundary(_, _, evidence)      => evidence

  private def step(steps: Vector[TraceStep], label: String): TraceStep =
    steps.find {
      case TraceStep.Operation(_, current, _, _)  => current == label
      case TraceStep.Message(_, _, current, _, _) => current == label
      case TraceStep.Boundary(current, _, _)      => current == label
    }.get

  private def messageRoute(
    steps: Vector[TraceStep],
    label: String
  ): Option[(String, String)] =
    steps.collectFirst {
      case TraceStep.Message(from, to, currentLabel, _, _) if currentLabel == label =>
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
