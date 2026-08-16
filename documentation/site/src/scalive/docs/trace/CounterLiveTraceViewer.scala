package scalive.docs.trace

import zio.*

import scalive.*
import scalive.docs.examples.RegisteredExample
import scalive.docs.xray.*

final private[docs] class CounterLiveTraceViewer(
  instanceId: String,
  observedTopic: String,
  inspectorTopic: String,
  example: RegisteredExample,
  store: DocumentationTraceStore)
    extends LiveView[CounterLiveTraceViewer.Msg, CounterLiveTraceViewer.Model]:

  import CounterLiveTraceViewer.*

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty[Msg, Model].onBrowserEvent(XRayInspector.BrowserRecordsEvent) {
      (model, batch, _) =>
        model.session match
          case Some(session) =>
            store.appendBrowser(session, observedTopic, batch) *>
              store.records(session, observedTopic).map(records => withRecords(model, records))
          case None => ZIO.succeed(model)
    }

  def mount(ctx: MountContext): LiveIO[Model] =
    ctx.runtimeTraceSession match
      case Some(session) =>
        for
          _ <- ctx.subscriptions.start(SubscriptionKey(s"counter-trace:$inspectorTopic"))(
                 store.updates(session, observedTopic).map(_ => Msg.Refresh)
               )
          records <- store.records(session, observedTopic)
        yield withRecords(
          Model(Some(session), store.isActive(session, observedTopic), Vector.empty, None),
          records
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
          store.records(session, observedTopic).map(records => withRecords(model, records))
        case None => ZIO.succeed(model)
    case Msg.SelectInteraction(id) =>
      ZIO.succeed(model.copy(selectedInteraction = Some(id)))
    case Msg.JumpToLatest =>
      val latest = CapturedInteractionGrouper.group(model.records).headOption.map(_.id)
      ZIO.succeed(model.copy(selectedInteraction = latest))

  def render(model: Model): HtmlElement[Msg] =
    val interactions = CapturedInteractionGrouper.group(model.records)
    sectionTag(
      cls                             := "docs-live-trace",
      aria.label                      := "Live counter trace",
      dataAttr("live-trace-viewer")   := "counter",
      dataAttr("xray-observed-topic") := observedTopic,
      dataAttr("xray-topic")          := inspectorTopic,
      dataAttr("xray-enabled")        := model.enabled.toString,
      dataAttr("xray-browser-event")  := XRayInspector.BrowserRecordsEvent.value,
      div(
        dom.hook(XRayInspector.HookName, DomRef(s"$instanceId-hook")),
        dataAttr("xray-hook")           := "",
        dataAttr("xray-observed-topic") := observedTopic,
        dataAttr("xray-enabled")        := model.enabled.toString,
        dataAttr("xray-browser-event")  := XRayInspector.BrowserRecordsEvent.value
      ),
      renderLive(model, interactions, model.selectedInteraction)
    )

  private def renderLive(
    model: Model,
    interactions: Vector[CapturedInteraction],
    selectedId: Option[String]
  ): HtmlElement[Msg] =
    val selected            = selectedId.flatMap(id => interactions.find(_.id == id))
    val interactionOrdinals = interactions.reverse.zipWithIndex.map { case (interaction, index) =>
      val ordinal =
        interaction.records.flatMap(_.interactionOrdinal).headOption.getOrElse(index + 1L)
      interaction.id -> ordinal
    }.toMap
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
                    CounterCapturedTraceAdapter.adapt(interaction),
                    provenance = "captured",
                    kicker = "Browser event trace"
                  )
                )
              case None =>
                div(
                  idAttr     := panelId,
                  cls        := "docs-live-trace-display docs-live-trace-panel",
                  role       := "region",
                  aria.label := "Displayed counter interaction trace",
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
        else if count == 0 && model.enabled then "Use a counter control"
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
          aria.live  := "polite",
          aria.label := "Captured counter interactions",
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

  private def withRecords(model: Model, records: Vector[DocumentationTraceRecord]): Model =
    val selected = model.selectedInteraction.orElse(
      CapturedInteractionGrouper.group(records).headOption.map(_.id)
    )
    model.copy(records = records, selectedInteraction = selected)

  private def stateKey(state: CapturedInteractionState): String = state match
    case CapturedInteractionState.InProgress => "in-progress"
    case CapturedInteractionState.Complete   => "complete"
    case CapturedInteractionState.Failed     => "failed"

  private def stateLabel(state: CapturedInteractionState): String = state match
    case CapturedInteractionState.InProgress => "In progress"
    case CapturedInteractionState.Complete   => "Complete"
    case CapturedInteractionState.Failed     => "Failed"
end CounterLiveTraceViewer

private[docs] object CounterLiveTraceViewer:
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
    inspectorTopic: String,
    example: RegisteredExample,
    store: DocumentationTraceStore
  ): Mod[Nothing] =
    liveView(
      instanceId,
      CounterLiveTraceViewer(instanceId, observedTopic, inspectorTopic, example, store),
      sticky = false
    )
