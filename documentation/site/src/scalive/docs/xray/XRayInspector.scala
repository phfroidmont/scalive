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
        p(
          if model.session.isEmpty then
            "Connect to enable the runtime trace. The example remains readable without it."
          else if model.enabled then
            "Capturing this example. Browser and server records keep independent ordering."
          else "Enable X-ray to capture this example's next interactions."
        ),
        button(
          typ := "button",
          on.click(Msg.Toggle),
          if model.enabled then "Disable X-ray" else "Enable X-ray"
        ),
        button(
          typ      := "button",
          disabled := !model.enabled,
          on.click(Msg.Reset),
          "Clear trace"
        )
      ),
      div(
        cls       := "docs-xray-records",
        role      := "log",
        aria.live := "polite",
        renderLane("Server", TraceProducer.Server, model.records),
        renderLane("Browser", TraceProducer.Browser, model.records)
      )
    )

  private def renderLane(
    label: String,
    producer: TraceProducer,
    records: Vector[DocumentationTraceRecord]
  ): HtmlElement[Msg] =
    val matching = records.filter(_.producer == producer).sortBy(_.producerSequence)
    sectionTag(
      cls := "docs-xray-lane",
      h4(label),
      if matching.isEmpty then p(cls := "docs-xray-empty", "No records yet.")
      else
        ol(
          matching.map(record =>
            li(
              dataAttr("xray-stage")             := record.stage,
              dataAttr("xray-operation")         := record.operationSequence.toString,
              dataAttr("xray-operation-kind")    := record.operationKind,
              dataAttr("xray-connection-epoch")  := record.connectionEpoch.fold("")(_.toString),
              dataAttr("xray-socket-epoch")      := record.socketEpoch.fold("")(_.toString),
              dataAttr("xray-join-reference")    := record.joinReference.getOrElse(""),
              dataAttr("xray-message-reference") := record.messageReference.getOrElse(""),
              p(
                strong(stageLabel(record.stage)),
                " ",
                record.summary
              ),
              record.value.toVector.map(value => renderValue(value): Mod[Msg]),
              record.protocol.toVector.map(value => renderProtocol(value): Mod[Msg])
            )
          )
        )
    )
  end renderLane

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
      summaryTag("Sanitized protocol structure"),
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
