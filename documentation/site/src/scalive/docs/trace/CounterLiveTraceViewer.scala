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
    val selected   = selectedId.flatMap(id => interactions.find(_.id == id))
    val newerCount =
      selected.fold(0)(interaction => interactions.indexWhere(_.id == interaction.id))
    div(
      cls := "docs-live-trace-live",
      renderCaptureControls(model, interactions.size),
      renderInspection(selectedId, selected, newerCount),
      renderInteractionList(interactions, selectedId),
      selected match
        case Some(interaction) =>
          div(
            cls := "docs-live-trace-display",
            TraceViewer.render(
              CounterCapturedTraceAdapter.adapt(interaction),
              provenance = "captured",
              kicker = "Browser event trace"
            )
          )
        case None =>
          div(
            cls       := "docs-live-trace-empty",
            role      := "status",
            aria.live := "polite",
            strong(
              if model.session.isEmpty then "Capture unavailable" else "No captured interaction"
            ),
            p(
              if model.session.isEmpty then "Connect to inspect a live counter interaction."
              else if selectedId.nonEmpty then "The selected interaction is no longer retained."
              else if model.enabled then "Use a counter control to capture an interaction."
              else "Start capture, then use a counter control."
            )
          )
    )
  end renderLive

  private def renderCaptureControls(model: Model, count: Int): HtmlElement[Msg] =
    div(
      cls := s"docs-live-trace-toolbar${if model.enabled then " is-capturing" else " is-paused"}",
      span(
        cls := "docs-live-trace-capture-status",
        if model.enabled then "Capturing" else if count == 0 then "Ready" else "Paused"
      ),
      span(cls := "docs-live-trace-capture-summary", s"$count interactions retained"),
      div(
        cls := "docs-live-trace-actions",
        button(
          typ := "button",
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
    newerCount: Int
  ): HtmlElement[Msg] =
    div(
      cls := "docs-live-trace-inspection",
      span(
        selected match
          case Some(interaction) =>
            Vector[Mod[Msg]](
              Mod.Content.Text("Inspecting "),
              Mod.Content.Tag(strong(interactionReference(interaction))),
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
    selectedId: Option[String]
  ): HtmlElement[Msg] =
    div(
      cls := "docs-live-trace-event-window",
      if interactions.isEmpty then
        p(cls := "docs-live-trace-event-empty", "Captured interactions will appear here.")
      else
        ol(
          cls        := "docs-live-trace-events",
          aria.label := "Captured counter interactions",
          interactions.map { interaction =>
            li(
              button(
                typ                           := "button",
                cls                           := "docs-live-trace-event",
                aria.pressed                  := selectedId.contains(interaction.id).toString,
                dataAttr("trace-interaction") := interaction.id,
                dataAttr("trace-state")       := stateKey(interaction.state),
                on.click(Msg.SelectInteraction(interaction.id)),
                span(cls := "docs-live-trace-event-reference", interactionReference(interaction)),
                span(cls := "docs-live-trace-event-kind", "Click"),
                strong(interaction.label),
                span(cls := "docs-live-trace-event-state", stateLabel(interaction.state))
              )
            )
          }
        )
    )

  private def interactionReference(interaction: CapturedInteraction): String =
    interaction.reference.fold(interaction.id)(reference => s"#$reference")

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
