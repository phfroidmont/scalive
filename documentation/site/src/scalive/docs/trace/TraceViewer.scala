package scalive.docs.trace

import scalive.*
import scalive.docs.model.*

private[docs] object TraceViewer:
  private val ariaLabelledBy = htmlAttr("aria-labelledby", scalive.codecs.StringAsIsEncoder)

  def render(
    trace: TraceDefinition,
    provenance: String = "authored",
    kicker: String = "Lifecycle trace"
  ): HtmlElement[Nothing] =
    val participants = trace.participants.map(_.id)
    val indices      = participants.zipWithIndex.toMap
    val labels = trace.participants.map(participant => participant.id -> participant.label).toMap

    figure(
      cls                          := "docs-trace",
      dataAttr("trace-viewer")     := trace.id,
      dataAttr("trace-provenance") := provenance,
      ariaLabelledBy               := s"docs-trace-${trace.id}-title",
      HtmlTag("figcaption")(
        cls := s"docs-trace-header${
            if provenance == "captured" then " docs-visually-hidden" else ""
          }",
        p(cls     := "docs-trace-kicker", kicker),
        h3(idAttr := s"docs-trace-${trace.id}-title", trace.title),
        p(trace.description)
      ),
      ul(
        cls       := "docs-trace-actors",
        styleAttr := laneCountStyle(participants.size),
        trace.participants.map(participant =>
          li(
            dataAttr("trace-participant") := participant.id,
            span(cls := "docs-trace-actor-dot", aria.hidden := true),
            participant.label
          )
        )
      ),
      ol(
        cls       := "docs-trace-sequence",
        styleAttr := laneCountStyle(participants.size),
        trace.phases.zipWithIndex.map { case (phase, phaseIndex) =>
          val start = trace.phases.take(phaseIndex).map(_.steps.size).sum + 1
          renderPhase(phase, phaseIndex, start, indices, labels, participants.size)
        }
      )
    )
  end render

  private def renderPhase(
    phase: TracePhase,
    phaseIndex: Int,
    start: Int,
    indices: Map[String, Int],
    labels: Map[String, String],
    laneCount: Int
  ): HtmlElement[Nothing] =
    li(
      cls                     := "docs-trace-phase-group",
      dataAttr("trace-phase") := phase.id,
      h4(
        cls := "docs-trace-phase",
        span(cls := "docs-trace-phase-index", s"Phase ${phaseIndex + 1}"),
        phase.title
      ),
      ol(
        cls                                                  := "docs-trace-phase-events",
        styleAttr                                            := laneCountStyle(laneCount),
        htmlAttr("start", scalive.codecs.IntAsStringEncoder) := start,
        phase.steps.zipWithIndex.map { case (step, stepIndex) =>
          renderStep(step, start + stepIndex, indices, labels, laneCount)
        }
      )
    )

  private def renderStep(
    step: TraceStep,
    order: Int,
    indices: Map[String, Int],
    labels: Map[String, String],
    laneCount: Int
  ): HtmlElement[Nothing] =
    step match
      case TraceStep.Operation(participant, label, description, _) =>
        li(
          cls                           := "docs-trace-step docs-trace-operation",
          dataAttr("trace-step")        := order.toString,
          dataAttr("trace-step-kind")   := "operation",
          dataAttr("trace-participant") := participant,
          styleAttr                     :=
            f"--docs-trace-lane: ${indices(participant) + 1}; --docs-trace-detail-position: ${laneCenter(indices(participant), laneCount)}%.4f%%;",
          eventCopy(
            order,
            label,
            description,
            operationContext(participant, labels)
          ),
          renderEvidence(stepEvidence(step), label)
        )
      case TraceStep.Message(from, to, label, description, _) =>
        val fromPosition  = laneCenter(indices(from), laneCount)
        val toPosition    = laneCenter(indices(to), laneCount)
        val startPosition = Math.min(fromPosition, toPosition)
        val endPosition   = Math.max(fromPosition, toPosition)
        val direction     = if indices(from) < indices(to) then "forward" else "reverse"
        li(
          cls := s"docs-trace-step docs-trace-message docs-trace-message-$direction",
          dataAttr("trace-step")      := order.toString,
          dataAttr("trace-step-kind") := "message",
          dataAttr("trace-from")      := from,
          dataAttr("trace-to")        := to,
          styleAttr                   :=
            f"--docs-trace-start: $startPosition%.4f%%; --docs-trace-end: $endPosition%.4f%%; --docs-trace-midpoint: ${(fromPosition + toPosition) / 2}%.4f%%; --docs-trace-detail-position: ${(fromPosition + toPosition) / 2}%.4f%%;",
          div(
            cls         := "docs-trace-message-route",
            aria.hidden := true,
            span(cls := "docs-trace-message-line")
          ),
          eventCopy(
            order,
            label,
            description,
            messageContext(from, to, labels)
          ),
          renderEvidence(stepEvidence(step), label)
        )
      case TraceStep.Boundary(label, description, _) =>
        li(
          cls                         := "docs-trace-step docs-trace-boundary",
          dataAttr("trace-step")      := order.toString,
          dataAttr("trace-step-kind") := "boundary",
          eventCopy(order, label, description, boundaryContext),
          renderEvidence(stepEvidence(step), label)
        )
  end renderStep

  private def eventCopy(
    order: Int,
    label: String,
    description: String,
    context: HtmlElement[Nothing]
  ): HtmlElement[Nothing] =
    div(
      cls := "docs-trace-event-copy",
      context,
      div(
        cls := "docs-trace-event-heading",
        span(cls := "docs-trace-order", f"$order%02d"),
        strong(label)
      ),
      p(description)
    )

  private def operationContext(
    participant: String,
    labels: Map[String, String]
  ): HtmlElement[Nothing] =
    p(
      cls                           := "docs-trace-event-context docs-visually-hidden",
      dataAttr("trace-participant") := participant,
      labels(participant)
    )

  private def messageContext(
    from: String,
    to: String,
    labels: Map[String, String]
  ): HtmlElement[Nothing] =
    p(
      cls := "docs-trace-event-context docs-trace-route docs-visually-hidden",
      span(dataAttr("trace-participant") := from, labels(from)),
      span(cls                           := "docs-trace-route-arrow", aria.hidden := true, "->"),
      span(dataAttr("trace-participant") := to, labels(to))
    )

  private val boundaryContext =
    p(cls := "docs-trace-event-context docs-visually-hidden", "Lifecycle boundary")

  private def renderEvidence(
    evidence: Option[TraceEvidence],
    stepLabel: String
  ): Vector[Mod[Nothing]] =
    evidence
      .map(value =>
        if value.scalaValue.nonEmpty then renderInlineEvidence(value, stepLabel): Mod[Nothing]
        else if value.code.nonEmpty then renderCodeEvidence(value, stepLabel): Mod[Nothing]
        else renderInlineEvidence(value, stepLabel): Mod[Nothing]
      ).toVector

  private def renderInlineEvidence(
    evidence: TraceEvidence,
    stepLabel: String
  ): HtmlElement[Nothing] =
    div(
      cls                        := "docs-trace-evidence docs-trace-evidence-inline",
      dataAttr("trace-evidence") := evidence.label,
      span(cls := "docs-visually-hidden", s"${evidence.label} for $stepLabel: "),
      evidence.scalaValue
        .map(value => code(cls := "docs-trace-evidence-scala-value", value): Mod[Nothing]).toVector,
      evidence.summary
        .filter(_ => evidence.scalaValue.isEmpty)
        .map(value => p(value): Mod[Nothing]).toVector,
      Option
        .when(evidence.scalaValue.isEmpty && evidence.facts.nonEmpty)(
          renderFacts(evidence.facts, "")
        )
        .map(value => value: Mod[Nothing]).toVector
    )

  private def renderCodeEvidence(
    evidence: TraceEvidence,
    stepLabel: String
  ): HtmlElement[Nothing] =
    detailsTag(
      cls                        := "docs-trace-evidence docs-trace-evidence-code-detail",
      dataAttr("trace-evidence") := evidence.label,
      summaryTag(
        span(cls := "docs-trace-evidence-marker", aria.hidden := true),
        span(cls := "docs-visually-hidden", "Show "),
        span(cls := "docs-trace-evidence-label", evidence.label),
        evidence.facts.map { case (name, value) =>
          span(
            cls := "docs-trace-evidence-summary-fact",
            span(cls := "docs-trace-evidence-summary-name", name),
            " ",
            value
          ): Mod[Nothing]
        },
        evidence.summary
          .map(value => span(cls := "docs-trace-evidence-summary", value): Mod[Nothing]).toVector,
        span(cls := "docs-visually-hidden", s" for $stepLabel")
      ),
      evidence.code.map(value => renderProtocolCode(value): Mod[Nothing]).toVector
    )

  private def renderProtocolCode(value: String): HtmlElement[Nothing] =
    div(
      cls := "docs-trace-evidence-code",
      div(
        cls := "docs-trace-evidence-code-toolbar",
        span(
          cls                           := "docs-visually-hidden",
          aria.live                     := "polite",
          aria.atomic                   := true,
          dataAttr("trace-code-status") := ""
        ),
        button(
          typ                         := "button",
          aria.pressed                := "false",
          dataAttr("trace-code-wrap") := "",
          "Wrap lines"
        ),
        button(
          typ                           := "button",
          aria.expanded                 := false,
          dataAttr("trace-code-expand") := "",
          "Show all"
        ),
        button(
          typ                         := "button",
          dataAttr("trace-code-copy") := "",
          "Copy JSON"
        )
      ),
      pre(code(value))
    )

  private def renderFacts(
    facts: Vector[(String, String)],
    className: String
  ): HtmlElement[Nothing] =
    dl(
      cls := className,
      facts.map { case (name, value) =>
        div(dt(name), dd(value))
      }
    )

  private def stepEvidence(step: TraceStep): Option[TraceEvidence] = step match
    case TraceStep.Operation(_, _, _, evidence)  => evidence
    case TraceStep.Message(_, _, _, _, evidence) => evidence
    case TraceStep.Boundary(_, _, evidence)      => evidence

  private def laneCountStyle(count: Int): String = s"--docs-trace-lanes: $count"

  private def laneCenter(index: Int, count: Int): Double =
    (index + 0.5d) / count * 100d

  /** Signal-backed counterpart used by LiveViews. */
  def renderSignalView(
    trace: Signal[TraceDefinition],
    provenance: String = "authored",
    kicker: String = "Lifecycle trace"
  ): HtmlElement[Nothing] =
    val view         = trace.map(toTraceView)
    val participants = view.map(_.participants)
    figure(
      cls                          := "docs-trace",
      dataAttr("trace-viewer")     := view.map(_.id),
      dataAttr("trace-provenance") := provenance,
      ariaLabelledBy               := view.map(value => s"docs-trace-${value.id}-title"),
      HtmlTag("figcaption")(
        cls := s"docs-trace-header${
            if provenance == "captured" then " docs-visually-hidden" else ""
          }",
        p(cls     := "docs-trace-kicker", kicker),
        h3(idAttr := view.map(value => s"docs-trace-${value.id}-title"), view.map(_.title)),
        p(view.map(_.description))
      ),
      ul(
        cls       := "docs-trace-actors",
        styleAttr := participants.map(values => laneCountStyle(values.size)),
        participants.splitBy(_.id) { (_, participant) =>
          li(
            dataAttr("trace-participant") := participant.map(_.id),
            span(cls := "docs-trace-actor-dot", aria.hidden := true),
            participant.map(_.label)
          )
        }
      ),
      ol(
        cls       := "docs-trace-sequence",
        styleAttr := participants.map(values => laneCountStyle(values.size)),
        view.map(_.phases).splitBy(_.id)((_, phase) => renderTracePhase(phase))
      )
    )
  end renderSignalView

  private def renderTracePhase(phase: Signal[TracePhaseModel]): HtmlElement[Nothing] =
    li(
      cls                     := "docs-trace-phase-group",
      dataAttr("trace-phase") := phase.map(_.id),
      h4(
        cls := "docs-trace-phase",
        span(cls := "docs-trace-phase-index", phase.map(value => s"Phase ${value.index + 1}")),
        phase.map(_.title)
      ),
      ol(
        cls       := "docs-trace-phase-events",
        styleAttr := phase.map(value => laneCountStyle(value.laneCount)),
        htmlAttr("start", scalive.codecs.IntAsStringEncoder) := phase.map(_.start),
        phase.map(_.steps).splitByIndex((_, step) => renderTraceStep(step))
      )
    )

  private def renderTraceStep(step: Signal[TraceStepModel]): HtmlElement[Nothing] =
    li(
      cls := step.map {
        case value if value.kind == "message" =>
          s"docs-trace-step docs-trace-message docs-trace-message-${value.direction}"
        case value => s"docs-trace-step docs-trace-${value.kind}"
      },
      dataAttr("trace-step")      := step.map(_.order.toString),
      dataAttr("trace-step-kind") := step.map(_.kind),
      dataAttr("trace-participant").optional(
        step.map(value => Option.when(value.kind == "operation")(value.participant))
      ),
      dataAttr("trace-from").optional(
        step.map(value => Option.when(value.kind == "message")(value.from))
      ),
      dataAttr("trace-to").optional(
        step.map(value => Option.when(value.kind == "message")(value.to))
      ),
      styleAttr.optional(step.map(value => Option.when(value.style.nonEmpty)(value.style))),
      step
        .map(_.kind == "message").when(
          div(
            cls         := "docs-trace-message-route",
            aria.hidden := true,
            span(cls := "docs-trace-message-line")
          )
        ),
      traceEventCopy(
        step,
        step
          .map(_.kind).chooseMod(
            "operation" -> Mod.Content.Tag(traceOperationContext(step)),
            "message"   -> Mod.Content.Tag(traceMessageContext(step)),
            "boundary"  -> Mod.Content.Tag(boundaryContext)
          )
      ),
      traceEvidence(step.map(_.evidence), step.map(_.label))
    )

  private def traceEventCopy(
    step: Signal[TraceStepModel],
    context: Mod[Nothing]
  ): HtmlElement[Nothing] =
    div(
      cls := "docs-trace-event-copy",
      context,
      div(
        cls := "docs-trace-event-heading",
        span(cls := "docs-trace-order", step.map(value => f"${value.order}%02d")),
        strong(step.map(_.label))
      ),
      p(step.map(_.description))
    )

  private def traceOperationContext(step: Signal[TraceStepModel]): HtmlElement[Nothing] =
    p(
      cls                           := "docs-trace-event-context docs-visually-hidden",
      dataAttr("trace-participant") := step.map(_.participant),
      step.map(_.participantLabel)
    )

  private def traceMessageContext(step: Signal[TraceStepModel]): HtmlElement[Nothing] =
    p(
      cls := "docs-trace-event-context docs-trace-route docs-visually-hidden",
      span(dataAttr("trace-participant") := step.map(_.from), step.map(_.fromLabel)),
      span(cls                           := "docs-trace-route-arrow", aria.hidden := true, "->"),
      span(dataAttr("trace-participant") := step.map(_.to), step.map(_.toLabel))
    )

  private def traceEvidence(
    evidence: Signal[Option[TraceEvidence]],
    stepLabel: Signal[String]
  ): Vector[Mod[Nothing]] =
    Vector(
      evidence.map(_.filter(traceEvidenceKind(_) == "inline")).option { value =>
        traceInlineEvidence(value, stepLabel)
      },
      evidence.map(_.filter(traceEvidenceKind(_) == "code")).option { value =>
        traceCodeEvidence(value, stepLabel)
      }
    )

  private def traceInlineEvidence(
    evidence: Signal[TraceEvidence],
    stepLabel: Signal[String]
  ): HtmlElement[Nothing] =
    div(
      cls                        := "docs-trace-evidence docs-trace-evidence-inline",
      dataAttr("trace-evidence") := evidence.map(_.label),
      span(
        cls := "docs-visually-hidden",
        evidence.zip(stepLabel).map { case (value, label) => s"${value.label} for $label: " }
      ),
      evidence
        .map(_.scalaValue).option(value => code(cls := "docs-trace-evidence-scala-value", value)),
      evidence.map(value => value.summary.filter(_ => value.scalaValue.isEmpty)).option(p(_)),
      evidence
        .map(value => value.scalaValue.isEmpty && value.facts.nonEmpty).when(
          traceFacts(evidence.map(_.facts), "")
        )
    )

  private def traceCodeEvidence(
    evidence: Signal[TraceEvidence],
    stepLabel: Signal[String]
  ): HtmlElement[Nothing] =
    detailsTag(
      cls                        := "docs-trace-evidence docs-trace-evidence-code-detail",
      dataAttr("trace-evidence") := evidence.map(_.label),
      summaryTag(
        span(cls := "docs-trace-evidence-marker", aria.hidden := true),
        span(cls := "docs-visually-hidden", "Show "),
        span(cls := "docs-trace-evidence-label", evidence.map(_.label)),
        evidence.map(_.facts).splitByIndex { (_, fact) =>
          span(
            cls := "docs-trace-evidence-summary-fact",
            span(cls := "docs-trace-evidence-summary-name", fact.map(_._1)),
            " ",
            fact.map(_._2)
          )
        },
        evidence.map(_.summary).option(value => span(cls := "docs-trace-evidence-summary", value)),
        span(cls := "docs-visually-hidden", stepLabel.map(label => s" for $label"))
      ),
      evidence.map(_.code).option(traceProtocolCode)
    )

  private def traceProtocolCode(value: Signal[String]): HtmlElement[Nothing] =
    div(
      cls := "docs-trace-evidence-code",
      div(
        cls := "docs-trace-evidence-code-toolbar",
        span(
          cls                           := "docs-visually-hidden",
          aria.live                     := "polite",
          aria.atomic                   := true,
          dataAttr("trace-code-status") := ""
        ),
        button(
          typ                         := "button",
          aria.pressed                := "false",
          dataAttr("trace-code-wrap") := "",
          "Wrap lines"
        ),
        button(
          typ                           := "button",
          aria.expanded                 := false,
          dataAttr("trace-code-expand") := "",
          "Show all"
        ),
        button(typ := "button", dataAttr("trace-code-copy") := "", "Copy JSON")
      ),
      pre(code(value))
    )

  private def traceFacts(
    facts: Signal[Vector[(String, String)]],
    className: String
  ): HtmlElement[Nothing] =
    dl(
      cls := className,
      facts.splitByIndex((_, fact) => div(dt(fact.map(_._1)), dd(fact.map(_._2))))
    )

  private def traceEvidenceKind(evidence: TraceEvidence): String =
    if evidence.scalaValue.nonEmpty then "inline"
    else if evidence.code.nonEmpty then "code"
    else "inline"

  private def toTraceView(trace: TraceDefinition): TraceViewModel =
    val indices      = trace.participants.map(_.id).zipWithIndex.toMap
    val labels       = trace.participants.map(value => value.id -> value.label).toMap
    val laneCount    = trace.participants.size
    var currentOrder = 1
    val phases       = trace.phases.zipWithIndex.map { case (phase, phaseIndex) =>
      val start = currentOrder
      val steps = phase.steps.map { step =>
        val value = toTraceStep(step, currentOrder, indices, labels, laneCount)
        currentOrder += 1
        value
      }
      TracePhaseModel(phase.id, phase.title, phaseIndex, start, laneCount, steps)
    }
    TraceViewModel(trace.id, trace.title, trace.description, trace.participants, phases)

  private def toTraceStep(
    step: TraceStep,
    order: Int,
    indices: Map[String, Int],
    labels: Map[String, String],
    laneCount: Int
  ): TraceStepModel = step match
    case TraceStep.Operation(participant, label, description, evidence) =>
      TraceStepModel(
        "operation",
        order,
        label,
        description,
        evidence,
        participant = participant,
        participantLabel = labels(participant),
        style =
          f"--docs-trace-lane: ${indices(participant) + 1}; --docs-trace-detail-position: ${laneCenter(indices(participant), laneCount)}%.4f%%;"
      )
    case TraceStep.Message(from, to, label, description, evidence) =>
      val fromPosition  = laneCenter(indices(from), laneCount)
      val toPosition    = laneCenter(indices(to), laneCount)
      val startPosition = Math.min(fromPosition, toPosition)
      val endPosition   = Math.max(fromPosition, toPosition)
      TraceStepModel(
        "message",
        order,
        label,
        description,
        evidence,
        from = from,
        to = to,
        fromLabel = labels(from),
        toLabel = labels(to),
        direction = if indices(from) < indices(to) then "forward" else "reverse",
        style =
          f"--docs-trace-start: $startPosition%.4f%%; --docs-trace-end: $endPosition%.4f%%; --docs-trace-midpoint: ${(fromPosition + toPosition) / 2}%.4f%%; --docs-trace-detail-position: ${(fromPosition + toPosition) / 2}%.4f%%;"
      )
    case TraceStep.Boundary(label, description, evidence) =>
      TraceStepModel("boundary", order, label, description, evidence)

  final private case class TraceViewModel(
    id: String,
    title: String,
    description: String,
    participants: Vector[TraceParticipant],
    phases: Vector[TracePhaseModel])

  final private case class TracePhaseModel(
    id: String,
    title: String,
    index: Int,
    start: Int,
    laneCount: Int,
    steps: Vector[TraceStepModel])

  final private case class TraceStepModel(
    kind: String,
    order: Int,
    label: String,
    description: String,
    evidence: Option[TraceEvidence],
    participant: String = "",
    participantLabel: String = "",
    from: String = "",
    to: String = "",
    fromLabel: String = "",
    toLabel: String = "",
    direction: String = "",
    style: String = "")
end TraceViewer
