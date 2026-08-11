package scalive.docs.xray

import zio.*
import zio.json.*

import scalive.*
import scalive.docs.examples.RegisteredExample

final private[docs] class XRayInspector(
  instanceId: String,
  observedTopic: String,
  inspectorTopic: String,
  example: RegisteredExample,
  store: DocumentationTraceStore)
    extends LiveView[XRayInspector.Msg, XRayInspector.Model]:

  import XRayInspector.*

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty[Msg, Model].onBrowserEvent(BrowserRecordsEvent) { (model, batch, _) =>
      model.session match
        case Some(session) =>
          store.appendBrowser(session, observedTopic, batch) *>
            store.records(session, observedTopic).map(records => model.copy(records = records))
        case None => ZIO.succeed(model)
    }

  def mount(ctx: MountContext): LiveIO[Model] =
    ctx.runtimeTraceSession match
      case Some(session) =>
        for
          _ <- ctx.subscriptions.start(SubscriptionKey(s"xray:$inspectorTopic"))(
                 store.updates(session, observedTopic).map(_ => Msg.Refresh)
               )
          records <- store.records(session, observedTopic)
        yield Model(Some(session), store.isActive(session, observedTopic), records)
      case None => ZIO.succeed(Model(None, enabled = false, Vector.empty))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Toggle =>
      model.session match
        case Some(session) if model.enabled =>
          store.deactivate(session, observedTopic).as(model.copy(enabled = false))
        case Some(session) =>
          store.activate(session, observedTopic, example).as(model.copy(enabled = true))
        case None => ZIO.succeed(model)
    case Msg.Reset =>
      model.session match
        case Some(session) =>
          store.reset(session, observedTopic).as(model.copy(records = Vector.empty))
        case None => ZIO.succeed(model)
    case Msg.Refresh =>
      model.session match
        case Some(session) =>
          store.records(session, observedTopic).map(records => model.copy(records = records))
        case None => ZIO.succeed(model)

  def render(model: Model): HtmlElement[Msg] =
    sectionTag(
      cls                             := "docs-xray",
      dataAttr("xray-observed-topic") := observedTopic,
      dataAttr("xray-topic")          := inspectorTopic,
      dataAttr("xray-enabled")        := model.enabled.toString,
      dataAttr("xray-browser-event")  := BrowserRecordsEvent.value,
      div(
        dom.hook(HookName, DomRef(s"$instanceId-hook")),
        dataAttr("xray-hook")           := "",
        dataAttr("xray-observed-topic") := observedTopic,
        dataAttr("xray-enabled")        := model.enabled.toString,
        dataAttr("xray-browser-event")  := BrowserRecordsEvent.value
      ),
      headerTag(
        dataAttr("example-controls") := "",
        h3("Runtime X-ray"),
        div(
          cls := "docs-xray-actions",
          button(
            typ := "button",
            on.click(Msg.Toggle),
            if model.enabled then "Stop tracing" else "Start tracing"
          ),
          button(
            typ      := "button",
            disabled := !model.enabled,
            on.click(Msg.Reset),
            "Clear trace"
          )
        )
      ),
      renderSummary(model.records, model.session.nonEmpty),
      renderRawTrace(model.records)
    )

  private def renderRawTrace(records: Vector[DocumentationTraceRecord]): HtmlElement[Msg] =
    latestInteraction(records) match
      case Some(interaction) =>
        val current = interaction.records.toSet
        val earlier = records.filterNot(current)
        detailsTag(
          cls := "docs-xray-raw",
          summaryTag(s"Raw trace (${interaction.records.size} records)"),
          p(
            cls := "docs-xray-raw-note",
            "Ordered by protocol handoff; browser and server clocks are independent."
          ),
          renderCausalRaw(interaction.records),
          if earlier.isEmpty then Vector.empty
          else
            Vector(
              detailsTag(
                cls := "docs-xray-history",
                summaryTag(s"Earlier records (${earlier.size})"),
                p("Producer order only."),
                renderIndependentLanes(earlier)
              )
            )
        )
      case None =>
        detailsTag(
          cls := "docs-xray-raw",
          summaryTag(s"Raw trace (${records.size} records)"),
          p(
            cls := "docs-xray-raw-note",
            "No correlated interaction. Records are ordered per producer."
          ),
          renderIndependentLanes(records)
        )

  private def renderCausalRaw(records: Vector[DocumentationTraceRecord]): HtmlElement[Msg] =
    val ordered = causalOrder(records)
    val entries = ordered.zipWithIndex.flatMap { case (record, index) =>
      val handoff = ordered
        .lift(index - 1).filter(_.producer != record.producer).map(previous =>
          renderHandoff(previous.producer, record.producer, records): Mod[Msg]
        )
      handoff.toVector :+ (renderRawRecord(record, Some(index + 1)): Mod[Msg])
    }
    sectionTag(
      cls        := "docs-xray-causal",
      aria.label := "Latest interaction raw records in causal order",
      div(
        cls := "docs-xray-causal-headings",
        span(cls := "docs-xray-causal-browser", "Browser"),
        span(cls := "docs-xray-causal-server", "Server")
      ),
      ol(
        cls        := "docs-xray-causal-list",
        tabIndex   := 0,
        aria.label := "Scrollable causal record sequence",
        entries
      )
    )

  private def causalOrder(
    records: Vector[DocumentationTraceRecord]
  ): Vector[DocumentationTraceRecord] =
    val browser = producerRecords(records, TraceProducer.Browser)
    val server  = producerRecords(records, TraceProducer.Server)
    val inbound = browser.indexWhere(_.stage == "InboundFrame")
    if inbound < 0 then browser ++ server
    else browser.take(inbound) ++ server ++ browser.drop(inbound)

  private def renderHandoff(
    from: TraceProducer,
    to: TraceProducer,
    records: Vector[DocumentationTraceRecord]
  ): HtmlElement[Msg] =
    val responseBytes = Option
      .when(from == TraceProducer.Server)(
        latestStage(records, TraceProducer.Server, "FinalFrame").flatMap(_.byteSize)
      ).flatten
    li(
      cls := s"docs-xray-handoff docs-xray-handoff-${producerName(from)}-${producerName(to)}",
      span(
        strong(
          if from == TraceProducer.Browser then "Request to server" else "Response to browser"
        ),
        responseBytes.map(bytes => s" / $bytes bytes").getOrElse("")
      )
    )

  private def renderSummary(
    records: Vector[DocumentationTraceRecord],
    connected: Boolean
  ): HtmlElement[Msg] =
    latestInteraction(records) match
      case None =>
        div(
          cls       := "docs-xray-empty-state",
          role      := "status",
          aria.live := "polite",
          strong(if connected then "No capture" else "Unavailable"),
          p(
            if connected then "Start tracing, then use a counter control."
            else "Connect to capture an interaction."
          )
        )
      case Some(interaction) =>
        div(
          cls                          := "docs-xray-summary",
          dataAttr("xray-interaction") := interaction.reference,
          role                         := "log",
          aria.live                    := "polite",
          aria.label                   := "Latest captured interaction",
          headerTag(
            h4("Latest interaction"),
            span(s"${interaction.records.size} records")
          ),
          div(
            cls := "docs-xray-sequence",
            div(
              cls := "docs-xray-sequence-headings",
              span(cls := "docs-xray-sequence-browser", "Browser"),
              span(cls := "docs-xray-sequence-server", "Server")
            ),
            ol(summarySteps(interaction.records).map(step => renderSummaryStep(step): Mod[Msg]))
          )
        )

  private def renderSummaryStep(step: SummaryStep): HtmlElement[Msg] =
    li(
      cls := s"docs-xray-sequence-step docs-xray-sequence-${producerName(step.producer)}",
      dataAttr("xray-summary-order")    := step.order.toString,
      dataAttr("xray-summary-producer") := producerName(step.producer),
      dataAttr("xray-summary-stage")    := step.stage,
      span(cls := "docs-xray-sequence-number", f"${step.order}%02d"),
      div(
        cls := "docs-xray-sequence-card",
        p(cls := "docs-xray-sequence-producer", producerLabel(step.producer)),
        p(cls := "docs-xray-summary-stage", step.label),
        strong(step.title): Mod[Msg],
        step.detail.map(value => p(value): Mod[Msg]).toVector
      )
    )

  private def summarySteps(records: Vector[DocumentationTraceRecord]): Vector[SummaryStep] =
    Vector(
      latestStage(records, TraceProducer.Browser, "BrowserEvent").map(record =>
        SummaryStep(
          1,
          TraceProducer.Browser,
          record.stage,
          "Event",
          "Click sent",
          None
        )
      ),
      latestStage(records, TraceProducer.Server, "TypedMessage").map(record =>
        SummaryStep(
          2,
          TraceProducer.Server,
          record.stage,
          "Typed message",
          record.value.fold(record.summary)(_.typeName),
          record.value.map(_.summary)
        )
      ),
      latestStage(records, TraceProducer.Server, "ModelProposed").map(record =>
        SummaryStep(
          3,
          TraceProducer.Server,
          record.stage,
          "Proposed model",
          modelSummary(record),
          None
        )
      ),
      latestStage(records, TraceProducer.Server, "TreeDiff").map(record =>
        val bytes = latestStage(records, TraceProducer.Server, "FinalFrame").flatMap(_.byteSize)
        SummaryStep(
          4,
          TraceProducer.Server,
          record.stage,
          "Wire diff",
          if record.summary.contains("empty") then "No changes" else "Changes detected",
          bytes.map(value => s"$value-byte frame")
        )
      ),
      latestStage(records, TraceProducer.Browser, "DomDiff").map(record =>
        SummaryStep(5, TraceProducer.Browser, record.stage, "DOM patch", "DOM updated", None)
      )
    ).flatten

  private def modelSummary(record: DocumentationTraceRecord): String =
    record.value.flatMap(_.fields.headOption).fold(record.summary) { case (name, value) =>
      s"$name = $value"
    }

  private def producerName(producer: TraceProducer): String = producer match
    case TraceProducer.Browser => "browser"
    case TraceProducer.Server  => "server"

  private def producerLabel(producer: TraceProducer): String = producer match
    case TraceProducer.Browser => "Browser"
    case TraceProducer.Server  => "Server"

  private def latestStage(
    records: Vector[DocumentationTraceRecord],
    producer: TraceProducer,
    stage: String
  ): Option[DocumentationTraceRecord] =
    records
      .filter(record => record.producer == producer && record.stage == stage).maxByOption(
        _.producerSequence
      )

  private def latestInteraction(
    records: Vector[DocumentationTraceRecord]
  ): Option[CapturedInteraction] =
    val serverAnchor = records
      .filter(record => record.stage == "TypedMessage" && record.messageReference.nonEmpty)
      .maxByOption(_.producerSequence)
    val browserAnchor = records
      .filter(record => record.stage == "BrowserEvent" && record.messageReference.nonEmpty)
      .maxByOption(_.producerSequence)
    serverAnchor.orElse(browserAnchor).flatMap(_.messageReference).map { reference =>
      val matchingServer = serverAnchor.toVector.flatMap { anchor =>
        records.filter(record =>
          record.producer == TraceProducer.Server &&
            record.messageReference.contains(reference) &&
            record.connectionEpoch == anchor.connectionEpoch &&
            record.socketEpoch == anchor.socketEpoch &&
            record.operationSequence == anchor.operationSequence
        )
      }
      val matchingBrowser = browserAnchor.toVector.flatMap { anchor =>
        records.filter(record =>
          record.producer == TraceProducer.Browser &&
            record.messageReference.contains(reference) &&
            record.producerSequence >= anchor.producerSequence
        )
      }
      CapturedInteraction(reference, (matchingBrowser ++ matchingServer).toVector)
    }

  private def renderIndependentLanes(
    records: Vector[DocumentationTraceRecord]
  ): HtmlElement[Msg] =
    div(
      cls := "docs-xray-records",
      renderLane("Browser", TraceProducer.Browser, records),
      renderLane("Server", TraceProducer.Server, records)
    )

  private def producerRecords(
    records: Vector[DocumentationTraceRecord],
    producer: TraceProducer
  ): Vector[DocumentationTraceRecord] =
    records.filter(_.producer == producer).sortBy(_.producerSequence)

  private def renderLane(
    label: String,
    producer: TraceProducer,
    records: Vector[DocumentationTraceRecord]
  ): HtmlElement[Msg] =
    val matching = producerRecords(records, producer)
    sectionTag(
      cls                       := s"docs-xray-lane docs-xray-lane-${producerName(producer)}",
      dataAttr("xray-producer") := producerName(producer),
      headerTag(h4(label), span(s"${matching.size} records")),
      if matching.isEmpty then p(cls := "docs-xray-empty", "No records yet.")
      else
        ol(
          cls        := "docs-xray-lane-list",
          tabIndex   := 0,
          aria.label := s"Scrollable $label raw records",
          matching.map(record => renderRawRecord(record, None): Mod[Msg])
        )
    )

  private def renderRawRecord(
    record: DocumentationTraceRecord,
    globalOrder: Option[Int]
  ): HtmlElement[Msg] =
    val localOrder = f"${record.producerSequence}%02d"
    val metadata   = Vector[Mod[Msg]](
      li(s"${record.operationKind} operation ${record.operationSequence}")
    ) ++
      record.messageReference.map(reference => li(s"message ref $reference"): Mod[Msg]) ++
      record.socketEpoch.map(epoch => li(s"socket epoch $epoch"): Mod[Msg])
    li(
      cls := s"docs-xray-raw-record docs-xray-raw-${producerName(record.producer)}",
      dataAttr("xray-stage")             := record.stage,
      dataAttr("xray-operation")         := record.operationSequence.toString,
      dataAttr("xray-operation-kind")    := record.operationKind,
      dataAttr("xray-connection-epoch")  := record.connectionEpoch.fold("")(_.toString),
      dataAttr("xray-socket-epoch")      := record.socketEpoch.fold("")(_.toString),
      dataAttr("xray-join-reference")    := record.joinReference.getOrElse(""),
      dataAttr("xray-message-reference") := record.messageReference.getOrElse(""),
      globalOrder
        .map(order => span(cls := "docs-xray-raw-order", f"$order%02d"): Mod[Msg]).toVector,
      articleTag(
        headerTag(
          span(
            cls := "docs-xray-local-order",
            s"${producerLabel(record.producer).head}$localOrder"
          ),
          strong(stageLabel(record.stage))
        ),
        p(cls := "docs-xray-record-summary", record.summary),
        ul(
          cls := "docs-xray-record-meta",
          metadata
        ),
        record.value.toVector.map(value => renderValue(value): Mod[Msg]),
        record.protocol.toVector.map(value => renderProtocol(value): Mod[Msg])
      )
    )
  end renderRawRecord

  private def renderValue(value: DocumentationTraceValue): HtmlElement[Msg] =
    div(
      cls := "docs-xray-value",
      code(value.typeName),
      p(value.summary),
      value.fields.map { case (name, fieldValue) =>
        p(strong(s"$name: "), code(fieldValue))
      }
    )

  private def renderProtocol(value: zio.json.ast.Json): HtmlElement[Msg] =
    detailsTag(
      summaryTag("Protocol payload (sanitized)"),
      pre(code(value.toJsonPretty))
    )

  private def stageLabel(stage: String): String =
    stage match
      case "ModelProposed"  => "Proposed model"
      case "ModelRendered"  => "Rendered model"
      case "ModelCommitted" => "Committed model"
      case "TreeDiff"       => "Wire diff"
      case "FinalFrame"     => "Protocol frame"
      case other            => other.replaceAll("([a-z])([A-Z])", "$1 $2")
end XRayInspector

private[docs] object XRayInspector:
  val HookName            = "XRayInspector"
  val BrowserRecordsEvent = BrowserToServerEvent[BrowserTraceBatch]("docs:xray-browser-records")

  enum Msg:
    case Toggle, Reset, Refresh

  final case class Model(
    session: Option[String],
    enabled: Boolean,
    records: Vector[DocumentationTraceRecord])

  final private[docs] case class CapturedInteraction(
    reference: String,
    records: Vector[DocumentationTraceRecord])

  final private[docs] case class SummaryStep(
    order: Int,
    producer: TraceProducer,
    stage: String,
    label: String,
    title: String,
    detail: Option[String])

  def nested(
    instanceId: String,
    observedTopic: String,
    inspectorTopic: String,
    example: RegisteredExample,
    store: DocumentationTraceStore
  ): Mod[Nothing] =
    liveView(
      instanceId,
      new XRayInspector(instanceId, observedTopic, inspectorTopic, example, store),
      sticky = false
    )
end XRayInspector
