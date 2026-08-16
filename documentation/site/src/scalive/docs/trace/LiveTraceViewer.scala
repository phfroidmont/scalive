package scalive.docs.trace

import zio.*

import scalive.*
import scalive.docs.examples.RegisteredExample
import scalive.docs.xray.*

final private[docs] class LiveTraceViewer(
  instanceId: String,
  observedTopic: String,
  viewerTopic: String,
  example: RegisteredExample,
  store: DocumentationTraceStore)
    extends LiveView[LiveTraceViewer.Msg, LiveTraceViewer.Model]:

  import LiveTraceViewer.*

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty[Msg, Model].onBrowserEvent(BrowserRecordsEvent) { (model, batch, _) =>
      model.session match
        case Some(session) =>
          store.appendBrowser(session, observedTopic, batch) *>
            store.snapshot(session, observedTopic).map(snapshot => withSnapshot(model, snapshot))
        case None => ZIO.succeed(model)
    }

  def mount(ctx: MountContext): LiveIO[Model] =
    ctx.runtimeTraceSession match
      case Some(session) =>
        for
          owner = s"$viewerTopic:${java.util.UUID.randomUUID()}"
          _ <- ctx.subscriptions.start(SubscriptionKey(s"live-trace:$viewerTopic"))(
                 zio.stream.ZStream
                   .acquireReleaseWith(store.attach(session, observedTopic, owner))(_ =>
                     store.detach(session, observedTopic, owner)
                   ).flatMap(_ => store.updates(session, observedTopic)).map(_ => Msg.Refresh)
               )
          snapshot <- store.snapshot(session, observedTopic)
        yield withSnapshot(
          Model(Some(session), enabled = false, Vector.empty, None),
          snapshot
        )
      case None => ZIO.succeed(Model(None, enabled = false, Vector.empty, None))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ToggleCapture =>
      model.session match
        case Some(session) if model.enabled =>
          store.deactivate(session, observedTopic).as(model.copy(enabled = false))
        case Some(session) =>
          store.activate(session, observedTopic, example).as(model.copy(enabled = true))
        case None => ZIO.succeed(model)
    case Msg.Clear =>
      model.session match
        case Some(session) =>
          store
            .reset(session, observedTopic).as(
              model.copy(records = Vector.empty, selectedInteraction = None)
            )
        case None => ZIO.succeed(model)
    case Msg.Refresh =>
      model.session match
        case Some(session) =>
          store.snapshot(session, observedTopic).map(snapshot => withSnapshot(model, snapshot))
        case None => ZIO.succeed(model)
    case Msg.SelectInteraction(id) =>
      ZIO.succeed(model.copy(selectedInteraction = Some(id)))
    case Msg.JumpToLatest =>
      val latest = CapturedInteractionGrouper.group(model.records).headOption.map(_.id)
      ZIO.succeed(model.copy(selectedInteraction = latest))

  def render(model: Model): HtmlElement[Msg] =
    val interactions = CapturedInteractionGrouper.group(model.records)
    sectionTag(
      cls                                   := "docs-live-trace",
      aria.label                            := s"Live ${example.descriptor.title} trace",
      dataAttr("live-trace-viewer")         := example.descriptor.id,
      dataAttr("live-trace-observed-topic") := observedTopic,
      dataAttr("live-trace-topic")          := viewerTopic,
      dataAttr("live-trace-enabled")        := model.enabled.toString,
      dataAttr("live-trace-browser-event")  := BrowserRecordsEvent.value,
      div(
        dom.hook(HookName, DomRef(s"$instanceId-hook")),
        dataAttr("live-trace-hook")           := "",
        dataAttr("live-trace-observed-topic") := observedTopic,
        dataAttr("live-trace-enabled")        := model.enabled.toString,
        dataAttr("live-trace-browser-event")  := BrowserRecordsEvent.value
      ),
      renderLive(model, interactions, model.selectedInteraction)
    )

  private def renderLive(
    model: Model,
    interactions: Vector[CapturedInteraction],
    selectedId: Option[String]
  ): HtmlElement[Msg] =
    val selected            = selectedId.flatMap(id => interactions.find(_.id == id))
    val interactionOrdinals =
      interactions.map(interaction => interaction.id -> interaction.ordinal).toMap
    val panelId    = s"$instanceId-trace-panel"
    val newerCount =
      selected.fold(0)(interaction => interactions.indexWhere(_.id == interaction.id))
    div(
      cls := "docs-live-trace-live",
      renderCaptureControls(model, interactions.size),
      Option
        .when(interactions.nonEmpty || selectedId.nonEmpty)(
          Vector[Mod[Msg]](
            renderInspection(
              selectedId,
              selected,
              interactionOrdinals,
              newerCount
            ),
            renderInteractionList(interactions, interactionOrdinals, selectedId, panelId),
            selected match
              case Some(interaction) =>
                div(
                  idAttr          := panelId,
                  cls             := "docs-live-trace-display docs-live-trace-panel",
                  role            := "region",
                  aria.labelledby := interactionRowId(interaction),
                  aria.busy       := (interaction.state == CapturedInteractionState.InProgress),
                  TraceViewer.render(
                    CapturedTraceAdapter.adapt(example.descriptor, interaction),
                    provenance = "captured",
                    kicker = "Captured operation trace"
                  )
                )
              case None =>
                div(
                  idAttr     := panelId,
                  cls        := "docs-live-trace-display docs-live-trace-panel",
                  role       := "region",
                  aria.label := s"Displayed ${example.descriptor.title} operation trace",
                  div(
                    cls         := "docs-live-trace-empty",
                    role        := "status",
                    aria.live   := "polite",
                    aria.atomic := true,
                    strong("Selected interaction expired"),
                    p("The selected interaction is no longer retained.")
                  )
                )
          )
        ).getOrElse(Vector.empty)
    )
  end renderLive

  private def renderCaptureControls(model: Model, count: Int): HtmlElement[Msg] =
    div(
      cls := s"docs-live-trace-toolbar${if model.enabled then " is-capturing" else " is-paused"}",
      span(
        cls         := "docs-live-trace-capture-status",
        role        := "status",
        aria.live   := "polite",
        aria.atomic := true,
        if model.session.isEmpty then "Unavailable"
        else if model.enabled then "Capturing"
        else if count == 0 then "Ready"
        else "Paused"
      ),
      span(
        cls         := "docs-live-trace-capture-summary",
        role        := "status",
        aria.live   := "polite",
        aria.atomic := true,
        if model.session.isEmpty then "Connect to capture interactions"
        else if count == 0 && model.enabled then "Use the example controls"
        else if count == 0 then "No interactions yet"
        else if count == 1 then "1 interaction retained"
        else s"$count interactions retained"
      ),
      div(
        cls := "docs-live-trace-actions",
        button(
          typ      := "button",
          disabled := model.session.isEmpty,
          on.click(Msg.ToggleCapture),
          if model.enabled then "Pause capture"
          else if count == 0 then "Start capture"
          else "Resume capture"
        ),
        button(
          typ      := "button",
          disabled := count == 0,
          on.click(Msg.Clear),
          "Clear"
        )
      )
    )

  private def renderInspection(
    selectedId: Option[String],
    selected: Option[CapturedInteraction],
    interactionOrdinals: Map[String, Long],
    newerCount: Int
  ): HtmlElement[Msg] =
    div(
      cls := "docs-live-trace-inspection",
      span(
        cls         := "docs-live-trace-inspection-status",
        role        := "status",
        aria.live   := "polite",
        aria.atomic := true,
        selected match
          case Some(interaction) =>
            Vector[Mod[Msg]](
              Mod.Content.Text("Inspecting "),
              Mod.Content.Tag(
                strong(
                  cls := "docs-live-trace-inspection-reference",
                  s"#${interactionOrdinals(interaction.id)}"
                )
              ),
              Mod.Content.Text(" / "),
              Mod.Content.Tag(
                span(cls := "docs-live-trace-inspection-label", interaction.label)
              ),
              Mod.Content.Text(" / "),
              Mod.Content.Tag(
                span(cls := "docs-live-trace-inspection-state", stateLabel(interaction.state))
              ),
              Mod.Content.Text(if newerCount == 0 then " / latest" else s" / $newerCount newer")
            )
          case None if selectedId.nonEmpty =>
            Vector(Mod.Content.Text("Selected interaction expired"))
          case None => Vector(Mod.Content.Text("No interaction selected"))
      ),
      Option
        .when(newerCount > 0)(
          Mod.Content.Tag(
            button(
              typ := "button",
              cls := "docs-live-trace-jump",
              on.click(Msg.JumpToLatest),
              "Jump to latest"
            )
          ): Mod[Msg]
        ).toVector
    )

  private def renderInteractionList(
    interactions: Vector[CapturedInteraction],
    interactionOrdinals: Map[String, Long],
    selectedId: Option[String],
    panelId: String
  ): HtmlElement[Msg] =
    div(
      cls := "docs-live-trace-event-window",
      if interactions.isEmpty then
        p(cls := "docs-live-trace-event-empty", "Captured interactions will appear here.")
      else
        ol(
          cls        := "docs-live-trace-events",
          aria.label := s"Captured ${example.descriptor.title} operations",
          interactions.map { interaction =>
            li(
              button(
                typ           := "button",
                idAttr        := interactionRowId(interaction),
                cls           := "docs-live-trace-event",
                aria.pressed  := selectedId.contains(interaction.id).toString,
                aria.controls := panelId,
                aria.busy     := (interaction.state == CapturedInteractionState.InProgress),
                dataAttr("trace-interaction") := interaction.id,
                dataAttr("trace-state")       := stateKey(interaction.state),
                on.click(Msg.SelectInteraction(interaction.id)),
                span(
                  cls := "docs-live-trace-event-reference",
                  s"#${interactionOrdinals(interaction.id)}"
                ),
                strong(interaction.label),
                span(cls := "docs-live-trace-event-state", stateLabel(interaction.state))
              )
            )
          }
        )
    )

  private def interactionRowId(interaction: CapturedInteraction): String =
    s"$instanceId-${interaction.id}"

  private def withSnapshot(
    model: Model,
    snapshot: DocumentationTraceSnapshot
  ): Model =
    val selected = model.selectedInteraction.orElse(
      CapturedInteractionGrouper.group(snapshot.records).headOption.map(_.id)
    )
    model.copy(
      enabled = snapshot.active,
      records = snapshot.records,
      selectedInteraction = selected
    )

  private def stateKey(state: CapturedInteractionState): String = state match
    case CapturedInteractionState.InProgress => "in-progress"
    case CapturedInteractionState.Complete   => "complete"
    case CapturedInteractionState.Failed     => "failed"

  private def stateLabel(state: CapturedInteractionState): String = state match
    case CapturedInteractionState.InProgress => "In progress"
    case CapturedInteractionState.Complete   => "Complete"
    case CapturedInteractionState.Failed     => "Failed"
end LiveTraceViewer

private[docs] object LiveTraceViewer:
  val HookName            = "LiveTraceViewer"
  val BrowserRecordsEvent = BrowserToServerEvent[BrowserTraceBatch](
    "docs:live-trace-browser-records"
  )

  enum Msg:
    case ToggleCapture, Clear, Refresh, JumpToLatest
    case SelectInteraction(id: String)

  final case class Model(
    session: Option[String],
    enabled: Boolean,
    records: Vector[DocumentationTraceRecord],
    selectedInteraction: Option[String])

  def nested(
    instanceId: String,
    observedTopic: String,
    viewerTopic: String,
    example: RegisteredExample,
    store: DocumentationTraceStore
  ): Mod[Nothing] =
    liveView(
      instanceId,
      LiveTraceViewer(instanceId, observedTopic, viewerTopic, example, store),
      sticky = false
    )
