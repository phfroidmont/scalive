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
        cls := "docs-trace-header",
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
        cls := "docs-trace-sequence",
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
          styleAttr                     := s"--docs-trace-lane: ${indices(participant) + 1}",
          eventCopy(
            order,
            label,
            description,
            stepEvidence(step),
            operationContext(participant, labels)
          )
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
            f"--docs-trace-start: $startPosition%.4f%%; --docs-trace-end: $endPosition%.4f%%; --docs-trace-midpoint: ${(fromPosition + toPosition) / 2}%.4f%%;",
          div(
            cls         := "docs-trace-message-route",
            aria.hidden := true,
            span(cls := "docs-trace-message-line")
          ),
          eventCopy(
            order,
            label,
            description,
            stepEvidence(step),
            messageContext(from, to, labels)
          )
        )
      case TraceStep.Boundary(label, description, _) =>
        li(
          cls                         := "docs-trace-step docs-trace-boundary",
          dataAttr("trace-step")      := order.toString,
          dataAttr("trace-step-kind") := "boundary",
          eventCopy(order, label, description, stepEvidence(step), boundaryContext)
        )
  end renderStep

  private def eventCopy(
    order: Int,
    label: String,
    description: String,
    evidence: Vector[TraceEvidence],
    context: HtmlElement[Nothing]
  ): HtmlElement[Nothing] =
    div(
      cls := "docs-trace-event-copy",
      context,
      div(
        cls := "docs-trace-event-heading",
        span(cls := "docs-trace-order", aria.hidden := true, f"$order%02d"),
        strong(label)
      ),
      p(description),
      evidence.map(value => renderEvidence(value): Mod[Nothing])
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

  private def renderEvidence(evidence: TraceEvidence): HtmlElement[Nothing] =
    detailsTag(
      cls                        := "docs-trace-evidence",
      dataAttr("trace-evidence") := evidence.label,
      summaryTag(evidence.label),
      p(evidence.summary),
      Option
        .when(evidence.facts.nonEmpty)(
          dl(
            evidence.facts.map { case (name, value) =>
              div(dt(name), dd(value))
            }
          )
        ).map(value => value: Mod[Nothing]).toVector,
      evidence.code.map(value => pre(code(value)): Mod[Nothing]).toVector
    )

  private def stepEvidence(step: TraceStep): Vector[TraceEvidence] = step match
    case TraceStep.Operation(_, _, _, evidence)  => evidence
    case TraceStep.Message(_, _, _, _, evidence) => evidence
    case TraceStep.Boundary(_, _, evidence)      => evidence

  private def laneCountStyle(count: Int): String = s"--docs-trace-lanes: $count"

  private def laneCenter(index: Int, count: Int): Double =
    (index + 0.5d) / count * 100d
end TraceViewer
