package scalive.docs.trace

import zio.json.*

import scalive.docs.model.*
import scalive.docs.xray.*

private[docs] object CapturedTraceAdapter:
  def adapt(
    example: ExampleDescriptor,
    interaction: CapturedInteraction
  ): TraceDefinition =
    val participants = Vector(
      TraceParticipant("browser", "Browser", "Sends events and applies DOM updates."),
      TraceParticipant(
        "runtime",
        "Scalive runtime",
        "Coordinates protocol, lifecycle, rendering, and effects."
      ),
      TraceParticipant(
        "live-view",
        example.title,
        example.description
      )
    )

    val trigger = Vector(
      step(
        interaction,
        Set("BrowserEvent"),
        _ =>
          TraceStep.Operation(
            "browser",
            interaction.label,
            "The browser emits an event.",
            None
          )
      ),
      step(
        interaction,
        Set("OutboundFrame"),
        records =>
          TraceStep.Message(
            "browser",
            "runtime",
            "Send protocol frame",
            "The browser encodes and sends the operation.",
            protocolEvidence(records, "OutboundFrame", "Protocol frame")
          )
      ),
      step(
        interaction,
        Set("DecodedEvent", "SocketJoin", "Upload"),
        _ =>
          TraceStep.Operation(
            "runtime",
            operationKindLabel(interaction.operationKind),
            "The runtime accepts and decodes the operation.",
            None
          )
      )
    ).flatten

    val dispatch = Vector(
      step(
        interaction,
        Set("BindingResolution"),
        _ =>
          TraceStep.Operation(
            "runtime",
            "Resolve binding",
            "The runtime resolves the typed event binding.",
            None
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
            "The runtime delivers the projected typed message.",
            valueEvidence(records, "TypedMessage", "Resolved message")
          )
      )
    ).flatten

    val lifecycle = Vector(
      step(
        interaction,
        Set("LifecycleStarted", "LifecycleCompleted"),
        record => !record.summary.startsWith("After-render lifecycle"),
        _ =>
          TraceStep.Operation(
            "live-view",
            s"Handle ${typedMessageLabel(interaction.records)}",
            "The LiveView runs the message lifecycle.",
            None
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
            "The handler proposes a new immutable model.",
            valueEvidence(records, "ModelProposed", "Updated model")
          )
      ),
      step(
        interaction,
        Set("RenderStarted"),
        _ =>
          TraceStep.Message(
            "runtime",
            "live-view",
            "Request render",
            "The runtime asks the LiveView to render.",
            None
          )
      ),
      step(
        interaction,
        Set("ModelRendered", "RenderCompleted"),
        _ =>
          TraceStep.Message(
            "live-view",
            "runtime",
            "Return rendered tree",
            "The LiveView returns its rendered tree.",
            None
          )
      ),
      step(
        interaction,
        Set("TreeDiff"),
        records =>
          TraceStep.Operation(
            "runtime",
            "Compute tree diff",
            treeDiffDescription(records),
            None
          )
      ),
      step(
        interaction,
        Set("ModelCommitted"),
        _ =>
          TraceStep.Operation(
            "runtime",
            "Commit model",
            "The rendered model becomes the current socket state.",
            None
          )
      )
    ).flatten

    val response = Vector(
      step(
        interaction,
        Set("FinalPayload", "FinalFrame", "InboundFrame", "InboundProcessed"),
        records =>
          TraceStep.Message(
            "runtime",
            "browser",
            "Publish result",
            "The runtime publishes the result and the browser processes it.",
            protocolEvidence(records, Vector("FinalFrame", "InboundFrame"), "Protocol frame")
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
            protocolEvidence(records, "DomDiff", "DOM changes")
          )
      ),
      step(
        interaction,
        Set("Crash"),
        records =>
          TraceStep.Boundary(
            "Operation failed",
            "The captured runtime operation terminated with a failure.",
            failureEvidence(records)
          )
      )
    ).flatten

    TraceDefinition(
      id = interaction.id,
      title = interaction.label,
      description = s"${interaction.summary} Capture state: ${stateLabel(interaction.state)}.",
      participants = participants,
      phases = Vector(
        phase("trigger", "Trigger", trigger),
        phase("dispatch", "Typed dispatch", dispatch),
        phase("lifecycle", "LiveView lifecycle", lifecycle),
        phase("response", "Result", response)
      ).flatten
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

  private def valueEvidence(
    records: Vector[DocumentationTraceRecord],
    stage: String,
    label: String
  ): Option[TraceEvidence] =
    CapturedTraceValue.select(records, stage).map { value =>
      TraceEvidence(
        label = label,
        summary = Option.when(value.scalaValue.isEmpty)(value.summary).filter(_.nonEmpty),
        facts = Option.when(value.scalaValue.isEmpty)(value.fields).getOrElse(Vector.empty),
        scalaValue = value.scalaValue
      )
    }

  private def protocolEvidence(
    records: Vector[DocumentationTraceRecord],
    stage: String,
    label: String
  ): Option[TraceEvidence] = protocolEvidence(records, Vector(stage), label)

  private def protocolEvidence(
    records: Vector[DocumentationTraceRecord],
    stages: Vector[String],
    label: String
  ): Option[TraceEvidence] =
    stages.iterator
      .flatMap(stage => records.find(record => record.stage == stage && record.protocol.nonEmpty))
      .nextOption()
      .map { record =>
        TraceEvidence(
          label = label,
          facts = record.byteSize.map(value => "size" -> s"$value B").toVector,
          code =
            record.protocol.map(value => DocumentationTraceSanitizer.structure(value).toJsonPretty)
        )
      }

  private def failureEvidence(records: Vector[DocumentationTraceRecord]): Option[TraceEvidence] =
    records
      .find(_.stage == "Crash").map(record =>
        TraceEvidence(label = "Failure", summary = Some(record.summary))
      )

  private def treeDiffDescription(records: Vector[DocumentationTraceRecord]): String =
    records.lastOption.map(_.summary) match
      case Some("Tree diff is empty")         => "The rendered tree matches the previous tree."
      case Some("Tree diff contains changes") => "The rendered tree contains changes."
      case _ => "The runtime compares the previous and rendered trees."

  private def typedMessageLabel(records: Vector[DocumentationTraceRecord]): String =
    CapturedTraceValue
      .select(records, "TypedMessage")
      .map(value => value.typeName.split("[.$]").lastOption.getOrElse(value.typeName))
      .getOrElse("operation")

  private def operationKindLabel(kind: String): String = kind match
    case "Join"            => "Socket join"
    case "ClientEvent"     => "Client event"
    case "ServerMessage"   => "Server message"
    case "AsyncCompletion" => "Async completion"
    case "LivePatch"       => "Live patch"
    case "Upload"          => "Upload"
    case "Leave"           => "Socket leave"
    case "Other"           => "Runtime operation"
    case "Browser"         => "Browser event"
    case other             => other

  private def stateLabel(state: CapturedInteractionState): String = state match
    case CapturedInteractionState.InProgress => "in progress"
    case CapturedInteractionState.Complete   => "complete"
    case CapturedInteractionState.Failed     => "failed"

end CapturedTraceAdapter
