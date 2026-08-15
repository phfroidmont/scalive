package scalive.docs.trace

import zio.json.*

import scalive.docs.model.*
import scalive.docs.xray.*

private[docs] object CounterCapturedTraceAdapter:
  private val participants = Vector(
    TraceParticipant("browser", "Browser", "Sends the counter event and applies the DOM patch."),
    TraceParticipant(
      "runtime",
      "Scalive runtime",
      "Decodes protocol, coordinates rendering, and sends the diff."
    ),
    TraceParticipant(
      "live-view",
      "Counter LiveView",
      "Handles the typed message and renders counter state."
    )
  )

  def adapt(interaction: CapturedInteraction): TraceDefinition =
    val input = Vector(
      step(
        interaction,
        Set("BrowserEvent"),
        records =>
          TraceStep.Operation(
            "browser",
            interaction.label,
            "The browser control emits a counter event.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("OutboundFrame"),
        records =>
          TraceStep.Message(
            "browser",
            "runtime",
            "Send event frame",
            "The browser encodes and sends the event.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("DecodedEvent", "BindingResolution"),
        records =>
          TraceStep.Operation(
            "runtime",
            "Decode counter event",
            "The runtime decodes the event and resolves its binding.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("TypedMessage"),
        records =>
          TraceStep.Message(
            "runtime",
            "live-view",
            typedMessageLabel(records),
            "The resolved binding produces the projected counter message.",
            evidence(records)
          )
      )
    ).flatten

    val lifecycle = Vector(
      step(
        interaction,
        Set("LifecycleStarted", "LifecycleCompleted"),
        record => record.summary.startsWith("Event lifecycle"),
        records =>
          TraceStep.Operation(
            "live-view",
            s"Handle ${typedMessageLabel(interaction.records)}",
            "The Counter LiveView runs the message lifecycle.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("ModelProposed"),
        records =>
          TraceStep.Message(
            "live-view",
            "runtime",
            "Return updated model",
            "The handler returns a new immutable counter model.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("RenderStarted"),
        records =>
          TraceStep.Message(
            "runtime",
            "live-view",
            "Request counter render",
            "The runtime asks the LiveView to render the updated model.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("ModelRendered", "RenderCompleted"),
        records =>
          TraceStep.Message(
            "live-view",
            "runtime",
            "Return rendered tree",
            "The LiveView returns the typed tree produced from the updated model.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("TreeDiff"),
        records =>
          TraceStep.Operation(
            "runtime",
            "Compute tree diff",
            "The runtime compares the previous and rendered trees.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("ModelCommitted"),
        records =>
          TraceStep.Operation(
            "runtime",
            "Commit counter model",
            "The rendered model becomes the current socket state.",
            evidence(records)
          )
      )
    ).flatten

    val output = Vector(
      step(
        interaction,
        Set("FinalPayload", "FinalFrame", "InboundFrame", "InboundProcessed"),
        records =>
          TraceStep.Message(
            "runtime",
            "browser",
            "Send rendered diff",
            "The runtime publishes the response and the browser decodes it.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("DomPatch", "DomDiff"),
        records =>
          TraceStep.Operation(
            "browser",
            "Apply DOM patch",
            "The browser applies and observes the returned DOM mutations.",
            evidence(records)
          )
      ),
      step(
        interaction,
        Set("Crash"),
        records =>
          TraceStep.Boundary(
            "Counter interaction failed",
            "The captured runtime operation terminated with a failure.",
            evidence(records)
          )
      )
    ).flatten

    val phases = Vector(
      phase("event", "Counter event", input),
      phase("lifecycle", "LiveView lifecycle", lifecycle),
      phase("response", "Diff and DOM", output)
    ).flatten

    TraceDefinition(
      id = interaction.id,
      title = interaction.label,
      description = s"${interaction.summary} Capture state: ${stateLabel(interaction.state)}.",
      participants = participants,
      phases = phases
    )
  end adapt

  private def step(
    interaction: CapturedInteraction,
    stages: Set[String],
    build: Vector[DocumentationTraceRecord] => TraceStep
  ): Option[TraceStep] =
    step(interaction, stages, _ => true, build)

  private def step(
    interaction: CapturedInteraction,
    stages: Set[String],
    include: DocumentationTraceRecord => Boolean,
    build: Vector[DocumentationTraceRecord] => TraceStep
  ): Option[TraceStep] =
    val records = interaction.records.filter(record => stages(record.stage) && include(record))
    Option.when(records.nonEmpty)(build(records))

  private def phase(id: String, title: String, steps: Vector[TraceStep]): Option[TracePhase] =
    Option.when(steps.nonEmpty)(TracePhase(id, title, steps))

  private def evidence(records: Vector[DocumentationTraceRecord]): Vector[TraceEvidence] =
    records.map { record =>
      TraceEvidence(
        label = recordLabel(record),
        summary = recordSummary(record),
        code =
          record.protocol.map(value => DocumentationTraceSanitizer.structure(value).toJsonPretty),
        producer = Some(producerLabel(record.producer)),
        highlights = recordHighlights(record),
        correlation = recordCorrelation(record),
        metadata = recordMetadata(record),
        projection = record.value.map(value =>
          TraceEvidenceProjection(value.typeName, value.summary, value.fields)
        )
      )
    }

  private def recordHighlights(record: DocumentationTraceRecord): Vector[String] =
    record.byteSize.map(value => s"$value B").toVector ++
      record.value.toVector.flatMap(_.fields.take(2).map { case (name, value) => s"$name: $value" })

  private def recordCorrelation(record: DocumentationTraceRecord): Vector[(String, String)] =
    Vector(
      record.messageReference.map(value => "message" -> s"#$value"),
      record.joinReference.map(value => "join" -> s"#$value")
    ).flatten

  private def recordMetadata(record: DocumentationTraceRecord): Vector[(String, String)] =
    Vector(
      Some("operation" -> operationKindLabel(record.operationKind)),
      Some("sequence"  -> record.operationSequence.toString),
      record.connectionEpoch.map(value => "connection" -> value.toString),
      record.socketEpoch.map(value => "socket" -> value.toString)
    ).flatten

  private def recordSummary(record: DocumentationTraceRecord): String = record.stage match
    case "FinalPayload" => "Socket payload prepared for encoding"
    case _              => record.summary

  private def typedMessageLabel(records: Vector[DocumentationTraceRecord]): String =
    records
      .find(_.stage == "TypedMessage")
      .flatMap(_.value)
      .map(value => value.typeName.split("[.$]").lastOption.getOrElse(value.typeName))
      .getOrElse("counter message")

  private def producerLabel(producer: TraceProducer): String = producer match
    case TraceProducer.Browser => "Browser"
    case TraceProducer.Server  => "Runtime"

  private def operationKindLabel(kind: String): String = kind match
    case "ClientEvent"     => "Client event"
    case "ServerMessage"   => "Server message"
    case "AsyncCompletion" => "Async completion"
    case "LivePatch"       => "Live patch"
    case other             => other

  private def stateLabel(state: CapturedInteractionState): String = state match
    case CapturedInteractionState.InProgress => "in progress"
    case CapturedInteractionState.Complete   => "complete"
    case CapturedInteractionState.Failed     => "failed"

  private def stageLabel(stage: String): String = stage match
    case "BrowserEvent"       => "Browser event"
    case "OutboundFrame"      => "Outbound frame"
    case "InboundFrame"       => "Inbound frame"
    case "InboundProcessed"   => "Response processed"
    case "DecodedEvent"       => "Decoded event"
    case "BindingResolution"  => "Binding resolution"
    case "TypedMessage"       => "Typed message"
    case "LifecycleStarted"   => "Lifecycle started"
    case "LifecycleCompleted" => "Lifecycle completed"
    case "ModelProposed"      => "Proposed model"
    case "RenderStarted"      => "Render started"
    case "ModelRendered"      => "Rendered model"
    case "RenderCompleted"    => "Render completed"
    case "TreeDiff"           => "Tree diff"
    case "ModelCommitted"     => "Committed model"
    case "FinalPayload"       => "Payload assembled"
    case "FinalFrame"         => "Final frame"
    case "DomPatch"           => "DOM patch"
    case "DomDiff"            => "DOM mutations"
    case "Crash"              => "Runtime failure"
    case other                => other

  private def recordLabel(record: DocumentationTraceRecord): String = record.stage match
    case "LifecycleStarted"   => "Handler started"
    case "LifecycleCompleted" => "Handler completed"
    case _                    => stageLabel(record.stage)
end CounterCapturedTraceAdapter
