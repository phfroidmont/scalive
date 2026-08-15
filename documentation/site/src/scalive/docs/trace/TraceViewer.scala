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
          renderEvidenceGroup(stepEvidence(step), label)
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
          renderEvidenceGroup(stepEvidence(step), label)
        )
      case TraceStep.Boundary(label, description, _) =>
        li(
          cls                         := "docs-trace-step docs-trace-boundary",
          dataAttr("trace-step")      := order.toString,
          dataAttr("trace-step-kind") := "boundary",
          eventCopy(order, label, description, boundaryContext),
          renderEvidenceGroup(stepEvidence(step), label)
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

  private def renderEvidenceGroup(
    evidence: Vector[TraceEvidence],
    stepLabel: String
  ): Vector[Mod[Nothing]] =
    Option
      .when(evidence.nonEmpty) {
        def common(values: TraceEvidence => Vector[(String, String)]): Set[(String, String)] =
          if evidence.size == 1 then Set.empty[(String, String)]
          else evidence.map(value => values(value).toSet).reduce(_ intersect _)

        val commonFactSet        = common(_.facts)
        val commonMetadataSet    = common(_.metadata)
        val commonCorrelationSet = common(_.correlation)
        val commonFacts          = evidence.head.facts.filter(commonFactSet)
        val commonMetadata       = evidence.head.metadata.filter(commonMetadataSet)
        val commonCorrelation    = evidence.head.correlation.filter(commonCorrelationSet)
        val recordCount = if evidence.size == 1 then "1 record" else s"${evidence.size} records"

        detailsTag(
          cls                              := "docs-trace-evidence",
          dataAttr("trace-evidence-count") := evidence.size.toString,
          summaryTag(
            span(cls := "docs-visually-hidden", "Show technical "),
            span(cls := "docs-trace-evidence-count", recordCount),
            span(cls := "docs-visually-hidden", s" for $stepLabel")
          ),
          div(
            cls := "docs-trace-evidence-content",
            Option
              .when(commonFacts.nonEmpty)(
                renderFactGroup("Details", commonFacts, "docs-trace-evidence-common")
              )
              .map(value => value: Mod[Nothing]).toVector,
            Option
              .when(commonMetadata.nonEmpty)(
                renderFactGroup("Execution", commonMetadata, "docs-trace-evidence-common")
              )
              .map(value => value: Mod[Nothing]).toVector,
            Option
              .when(commonCorrelation.nonEmpty)(
                renderFactGroup(
                  "Correlation",
                  commonCorrelation,
                  "docs-trace-evidence-common"
                )
              )
              .map(value => value: Mod[Nothing]).toVector,
            ol(
              cls := "docs-trace-evidence-records",
              evidence.map(value =>
                renderEvidenceRecord(
                  value,
                  commonFactSet,
                  commonMetadataSet,
                  commonCorrelationSet
                ): Mod[Nothing]
              )
            )
          )
        )
      }.map(value => value: Mod[Nothing]).toVector

  private def renderEvidenceRecord(
    evidence: TraceEvidence,
    commonFacts: Set[(String, String)],
    commonMetadata: Set[(String, String)],
    commonCorrelation: Set[(String, String)]
  ): HtmlElement[Nothing] =
    val specificFacts       = evidence.facts.filterNot(commonFacts)
    val specificMetadata    = evidence.metadata.filterNot(commonMetadata)
    val specificCorrelation = evidence.correlation.filterNot(commonCorrelation)
    li(
      cls                        := "docs-trace-evidence-record",
      dataAttr("trace-evidence") := evidence.label,
      evidence.producer.map(value => dataAttr("trace-producer") := value.toLowerCase).toVector,
      detailsTag(
        summaryTag(
          evidence.producer
            .map(value =>
              span(cls := "docs-trace-evidence-producer", value): Mod[Nothing]
            ).toVector,
          span(cls := "docs-trace-evidence-record-label", evidence.label),
          Option
            .when(evidence.producer.isEmpty)(
              span(cls := "docs-trace-evidence-record-summary", evidence.summary)
            )
            .map(value => value: Mod[Nothing]).toVector,
          Option
            .when(evidence.highlights.nonEmpty)(
              span(
                cls := "docs-trace-evidence-highlights",
                evidence.highlights.map(value => span(value): Mod[Nothing])
              )
            )
            .map(value => value: Mod[Nothing]).toVector
        ),
        div(
          cls := "docs-trace-evidence-record-content",
          p(cls := "docs-trace-evidence-description", evidence.summary),
          evidence.projection.map(value => renderProjection(value): Mod[Nothing]).toVector,
          Option
            .when(specificFacts.nonEmpty)(
              renderFactGroup("Details", specificFacts, "docs-trace-evidence-specific")
            )
            .map(value => value: Mod[Nothing]).toVector,
          Option
            .when(specificMetadata.nonEmpty)(
              renderFactGroup("Execution", specificMetadata, "docs-trace-evidence-metadata")
            )
            .map(value => value: Mod[Nothing]).toVector,
          Option
            .when(specificCorrelation.nonEmpty)(
              renderFactGroup(
                "Correlation",
                specificCorrelation,
                "docs-trace-evidence-correlation"
              )
            )
            .map(value => value: Mod[Nothing]).toVector,
          evidence.code.map(value => renderProtocolCode(value): Mod[Nothing]).toVector
        )
      )
    )
  end renderEvidenceRecord

  private def renderProjection(projection: TraceEvidenceProjection): HtmlElement[Nothing] =
    div(
      cls := "docs-trace-evidence-projection",
      code(projection.typeName),
      p(projection.summary),
      Option
        .when(projection.fields.nonEmpty)(renderFacts(projection.fields, ""))
        .map(value => value: Mod[Nothing]).toVector
    )

  private def renderFactGroup(
    title: String,
    facts: Vector[(String, String)],
    className: String
  ): HtmlElement[Nothing] =
    sectionTag(
      cls := className,
      h5(title),
      renderFacts(facts, "")
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

  private def stepEvidence(step: TraceStep): Vector[TraceEvidence] = step match
    case TraceStep.Operation(_, _, _, evidence)  => evidence
    case TraceStep.Message(_, _, _, _, evidence) => evidence
    case TraceStep.Boundary(_, _, evidence)      => evidence

  private def laneCountStyle(count: Int): String = s"--docs-trace-lanes: $count"

  private def laneCenter(index: Int, count: Int): Double =
    (index + 0.5d) / count * 100d
end TraceViewer
