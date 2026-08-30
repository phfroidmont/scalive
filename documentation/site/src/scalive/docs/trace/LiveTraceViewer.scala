package scalive.docs.trace

import zio.*
import zio.json.ast.Json

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

  def mount(ctx: MountContext): Task[Model] =
    ctx.connection match
      case Connection.Connected(capabilities) =>
        capabilities.connectParams.get(TraceSessionParameter) match
          case Some(Json.Str(session)) if ValidSession.matches(session) =>
            for
              owner = s"$viewerTopic:${java.util.UUID.randomUUID()}"
              _ <- capabilities.resources.acquireRelease(
                     store.attach(session, observedTopic, owner)
                   )(_ => store.detach(session, observedTopic, owner))
              _ <- capabilities.subscriptions.start(
                     SubscriptionKey(s"live-trace:$viewerTopic"),
                     SubscriptionDelivery.Latest
                   )(
                     store.updates(session, observedTopic).map(_ => Msg.Refresh)
                   )
              snapshot <- store.snapshot(session, observedTopic)
            yield withSnapshot(
              Model(Some(session), enabled = false, Vector.empty, None, followLatest = true),
              snapshot
            )
          case _ =>
            ZIO.succeed(Model(None, enabled = false, Vector.empty, None, followLatest = true))
      case Connection.Disconnected =>
        ZIO.succeed(Model(None, enabled = false, Vector.empty, None, followLatest = true))

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
              model.copy(
                records = Vector.empty,
                selectedInteraction = None,
                followLatest = true
              )
            )
        case None => ZIO.succeed(model)
    case Msg.Refresh =>
      model.session match
        case Some(session) =>
          store.snapshot(session, observedTopic).map(snapshot => withSnapshot(model, snapshot))
        case None => ZIO.succeed(model)
    case Msg.SelectInteraction(id) =>
      val latest       = CapturedInteractionGrouper.group(model.records).headOption.map(_.id)
      val followLatest = latest.contains(id)
      val updated      = model.copy(
        selectedInteraction = Some(id),
        followLatest = followLatest
      )
      model.session.fold(ZIO.succeed(updated))(session =>
        store
          .selectInteraction(session, observedTopic, Some(id), followLatest).as(updated)
      )
    case Msg.JumpToLatest =>
      val latest  = CapturedInteractionGrouper.group(model.records).headOption.map(_.id)
      val updated = model.copy(selectedInteraction = latest, followLatest = true)
      model.session.fold(ZIO.succeed(updated))(session =>
        store.selectInteraction(session, observedTopic, None, followLatest = true).as(updated)
      )
  end handleMessage

  override def view(model: Signal[Model]): HtmlElement[Msg] =
    val interactions = model.map(value => CapturedInteractionGrouper.group(value.records))
    val selectedId   = model.map(_.selectedInteraction)
    sectionTag(
      cls                                   := "docs-live-trace",
      aria.label                            := s"${example.descriptor.title} interaction inspector",
      dataAttr("live-trace-viewer")         := example.descriptor.id,
      dataAttr("live-trace-observed-topic") := observedTopic,
      dataAttr("live-trace-topic")          := viewerTopic,
      dataAttr("live-trace-enabled")        := model.map(_.enabled.toString),
      dataAttr("live-trace-browser-event")  := BrowserRecordsEvent.value,
      div(
        dom.hook(HookName, DomRef(s"$instanceId-hook")),
        dataAttr("live-trace-hook")           := "",
        dataAttr("live-trace-observed-topic") := observedTopic,
        dataAttr("live-trace-enabled")        := model.map(_.enabled.toString),
        dataAttr("live-trace-browser-event")  := BrowserRecordsEvent.value
      ),
      renderLive(model, interactions, selectedId)
    )

  private def renderLive(
    model: Signal[Model],
    interactions: Signal[Vector[CapturedInteraction]],
    selectedId: Signal[Option[String]]
  ): HtmlElement[Msg] =
    val selected = selectedId.zip(interactions).map { case (id, values) =>
      id.flatMap(selected => values.find(_.id == selected))
    }
    val interactionOrdinals = interactions.map(_.map(value => value.id -> value.ordinal).toMap)
    val panelId             = s"$instanceId-trace-panel"
    val newerCount          = selected.zip(selectedId).zip(interactions).map {
      case ((Some(interaction), _), values) => values.indexWhere(_.id == interaction.id)
      case ((None, Some(_)), values)        => values.size
      case ((None, None), _)                => 0
    }
    val showDetails = interactions.zip(selectedId).map { case (values, id) =>
      values.nonEmpty || id.nonEmpty
    }
    val count = interactions.map(_.size)
    div(
      cls := model.map(value =>
        s"docs-live-trace-live${
            if value.session.isEmpty then " is-unavailable"
            else if value.enabled then " is-tracing"
            else " is-idle"
          }"
      ),
      renderInspectorHeader(model, count),
      renderEmptyState(model, count),
      showDetails.when(renderCaptureControls(model, count)),
      showDetails.when(renderInspection(selectedId, selected, interactionOrdinals, newerCount)),
      showDetails.when(
        renderInteractionList(interactions, interactionOrdinals, selectedId, panelId)
      ),
      showDetails.when(renderSelectedPanel(selected, panelId))
    )
  end renderLive

  private def renderInspectorHeader(
    model: Signal[Model],
    count: Signal[Int]
  ): HtmlElement[Msg] =
    val isSwitch = model.zip(count).map { case (value, retained) =>
      value.session.nonEmpty && (value.enabled || retained > 0)
    }
    headerTag(
      cls := "docs-live-trace-header",
      div(
        cls := "docs-live-trace-heading",
        span(cls := "docs-live-trace-kicker", "LiveView observability"),
        h3("Interaction inspector"),
        p(
          "See how actions in the live result above travel through typed messages, server state, protocol frames, and DOM updates."
        )
      ),
      button(
        typ    := "button",
        idAttr := s"$instanceId-trace-toggle",
        cls    := isSwitch.map(value =>
          s"docs-live-trace-switch${if value then "" else " is-activation"}"
        ),
        role := isSwitch.map(value => if value then "switch" else "button"),
        aria.checked.optional(
          isSwitch.zip(model).map { case (switch, value) =>
            Option.when(switch)(value.enabled.toString)
          }
        ),
        disabled := model.map(_.session.isEmpty),
        on.click(Msg.ToggleCapture),
        span(cls := "docs-live-trace-switch-track", aria.hidden := true),
        span(
          isSwitch.map(value =>
            if value then "Trace new interactions" else "Inspect live interactions"
          )
        )
      )
    )
  end renderInspectorHeader

  private def renderEmptyState(
    model: Signal[Model],
    count: Signal[Int]
  ): HtmlElement[Msg] =
    val unavailable  = model.map(_.session.isEmpty)
    val introduction = model.zip(count).map { case (value, retained) =>
      value.session.nonEmpty && !value.enabled && retained == 0
    }
    val waiting = model.zip(count).map { case (value, retained) =>
      value.enabled && retained == 0
    }
    div(
      cls := "docs-live-trace-empty-states",
      unavailable.when(
        div(
          cls         := "docs-live-trace-prompt is-unavailable",
          role        := "status",
          aria.live   := "polite",
          aria.atomic := true,
          strong("Reconnect to inspect live interactions"),
          p("The live result and its inspector resume when the connection returns.")
        )
      ),
      introduction.when(
        div(
          cls := "docs-live-trace-prompt is-introduction",
          strong("Inspect the result as you use it"),
          p("Choose Inspect live interactions, then perform an action in the live result above.")
        )
      ),
      waiting.when(
        div(
          cls         := "docs-live-trace-prompt is-waiting",
          role        := "status",
          aria.live   := "polite",
          aria.atomic := true,
          span(cls := "docs-live-trace-direction", aria.hidden := true, "↑"),
          div(
            h4("Try the live result above"),
            p(
              "Click a button, type in a field, or perform any action. Its trace will appear here immediately."
            )
          )
        )
      )
    )
  end renderEmptyState

  private def renderCaptureControls(
    model: Signal[Model],
    count: Signal[Int]
  ): HtmlElement[Msg] =
    div(
      cls := model.map(value =>
        s"docs-live-trace-toolbar${if value.enabled then " is-tracing" else " is-idle"}"
      ),
      span(
        cls         := "docs-live-trace-capture-status",
        role        := "status",
        aria.live   := "polite",
        aria.atomic := true,
        model.zip(count).map { case (value, retained) =>
          if value.session.isEmpty then "Unavailable"
          else if value.enabled then "Tracing new interactions"
          else if retained > 0 then
            "Tracing is off. The live result still works, but new actions will not be added here."
          else "Inspector ready"
        }
      ),
      span(
        cls         := "docs-live-trace-capture-summary",
        aria.hidden := true,
        model.zip(count).map { case (value, retained) =>
          if value.session.isEmpty then "Inspector unavailable"
          else if retained == 1 then "1 interaction"
          else s"$retained interactions"
        }
      ),
      div(
        cls := "docs-live-trace-actions",
        button(
          typ      := "button",
          disabled := count.map(_ == 0),
          on.click(Msg.Clear),
          "Clear history"
        )
      )
    )

  private def renderInspection(
    selectedId: Signal[Option[String]],
    selected: Signal[Option[CapturedInteraction]],
    interactionOrdinals: Signal[Map[String, Long]],
    newerCount: Signal[Int]
  ): HtmlElement[Msg] =
    div(
      cls := "docs-live-trace-inspection",
      span(
        cls         := "docs-live-trace-inspection-status",
        role        := "status",
        aria.live   := "polite",
        aria.atomic := true,
        selected.option { interaction =>
          val ordinal = interaction.zip(interactionOrdinals).map { case (value, ordinals) =>
            s"#${ordinals(value.id)}"
          }
          strong(
            cls := "docs-live-trace-inspection-selection",
            span(cls := "docs-visually-hidden", "Inspecting "),
            span(cls := "docs-live-trace-inspection-reference", ordinal),
            " ",
            span(cls := "docs-live-trace-inspection-label", interaction.map(_.label))
          )
        },
        selected.option(interaction =>
          span(
            cls                         := "docs-live-trace-inspection-initiator",
            dataAttr("trace-initiator") := interaction.map(value => initiatorKey(value.initiator)),
            span(cls := "docs-visually-hidden", " Triggered by "),
            interaction.map(value => initiatorLabel(value.initiator))
          )
        ),
        selected.option(interaction =>
          span(
            cls                     := "docs-live-trace-inspection-state",
            dataAttr("trace-state") := interaction.map(value => stateKey(value.state)),
            span(cls := "docs-visually-hidden", " Status "),
            interaction.map(value => stateLabel(value.state))
          )
        ),
        selected.option(_ =>
          span(
            cls := "docs-live-trace-inspection-recency",
            span(cls := "docs-visually-hidden", " "),
            newerCount.map(value => if value == 0 then "Latest" else s"$value newer")
          )
        ),
        selected.zip(selectedId).map {
          case (Some(_), _)    => ""
          case (None, Some(_)) => "Selected interaction expired"
          case (None, None)    => "No interaction selected"
        }
      ),
      newerCount
        .map(_ > 0).when(
          button(
            typ := "button",
            cls := "docs-live-trace-jump",
            on.click(Msg.JumpToLatest),
            "Jump to latest"
          )
        )
    )

  private def renderInteractionList(
    interactions: Signal[Vector[CapturedInteraction]],
    interactionOrdinals: Signal[Map[String, Long]],
    selectedId: Signal[Option[String]],
    panelId: String
  ): HtmlElement[Msg] =
    div(
      cls := "docs-live-trace-event-window",
      interactions
        .map(_.isEmpty).choose(
          p(cls := "docs-live-trace-event-empty", "Captured interactions will appear here."),
          ol(
            cls        := "docs-live-trace-events",
            aria.label := s"Captured ${example.descriptor.title} operations",
            interactions.splitBy(_.id) { (_, interaction) =>
              li(
                button(
                  typ          := "button",
                  idAttr       := interaction.map(interactionRowId),
                  cls          := "docs-live-trace-event",
                  aria.pressed := interaction.zip(selectedId).map { case (value, selected) =>
                    selected.contains(value.id).toString
                  },
                  aria.controls := panelId,
                  aria.busy     := interaction.map(_.state == CapturedInteractionState.InProgress),
                  dataAttr("trace-interaction") := interaction.map(_.id),
                  dataAttr("trace-state")       := interaction.map(value => stateKey(value.state)),
                  dataAttr("trace-initiator")   := interaction
                    .map(value => initiatorKey(value.initiator)),
                  on.click(interaction.map(value => Msg.SelectInteraction(value.id))),
                  span(
                    cls := "docs-live-trace-event-reference",
                    interaction.zip(interactionOrdinals).map { case (value, ordinals) =>
                      s"#${ordinals(value.id)}"
                    }
                  ),
                  strong(interaction.map(_.label)),
                  span(
                    cls := "docs-live-trace-event-initiator",
                    span(cls := "docs-visually-hidden", " Triggered by "),
                    interaction.map(value => initiatorLabel(value.initiator))
                  ),
                  span(
                    cls := "docs-live-trace-event-state",
                    span(cls := "docs-visually-hidden", " Status "),
                    interaction.map(value => stateLabel(value.state))
                  )
                )
              )
            }
          )
        )
    )

  private def renderSelectedPanel(
    selected: Signal[Option[CapturedInteraction]],
    panelId: String
  ): HtmlElement[Msg] =
    div(
      idAttr := panelId,
      cls    := "docs-live-trace-display docs-live-trace-panel",
      role   := "region",
      aria.labelledby.optional(selected.map(_.map(interactionRowId))),
      aria.label.optional(
        selected.map(
          _.fold(Some(s"Displayed ${example.descriptor.title} operation trace"))(_ => None)
        )
      ),
      aria.busy.optional(
        selected.map(_.map(_.state == CapturedInteractionState.InProgress))
      ),
      selected.option(interaction =>
        TraceViewer.renderSignalView(
          interaction.map(value => CapturedTraceAdapter.adapt(example.descriptor, value)),
          provenance = "captured",
          kicker = "Captured operation trace"
        )
      ),
      selected
        .map(_.isEmpty).when(
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

  private def interactionRowId(interaction: CapturedInteraction): String =
    s"$instanceId-${interaction.id}"

  private def withSnapshot(
    model: Model,
    snapshot: DocumentationTraceSnapshot
  ): Model =
    val latest   = CapturedInteractionGrouper.group(snapshot.records).headOption.map(_.id)
    val selected =
      if snapshot.followLatest then latest
      else snapshot.selectedInteraction.orElse(latest)
    model.copy(
      enabled = snapshot.active,
      records = snapshot.records,
      selectedInteraction = selected,
      followLatest = snapshot.followLatest
    )

  private def stateKey(state: CapturedInteractionState): String = state match
    case CapturedInteractionState.InProgress => "in-progress"
    case CapturedInteractionState.Complete   => "complete"
    case CapturedInteractionState.Failed     => "failed"

  private def stateLabel(state: CapturedInteractionState): String = state match
    case CapturedInteractionState.InProgress => "In progress"
    case CapturedInteractionState.Complete   => "Complete"
    case CapturedInteractionState.Failed     => "Failed"

  private def initiatorKey(initiator: CapturedInteractionInitiator): String = initiator match
    case CapturedInteractionInitiator.Browser         => "browser"
    case CapturedInteractionInitiator.Runtime         => "runtime"
    case CapturedInteractionInitiator.Component(_, _) => "component"

  private def initiatorLabel(initiator: CapturedInteractionInitiator): String = initiator match
    case CapturedInteractionInitiator.Browser                 => "Browser"
    case CapturedInteractionInitiator.Runtime                 => "Scalive runtime"
    case CapturedInteractionInitiator.Component(typeName, id) =>
      val componentName = typeName.split("[.$]").lastOption.filter(_.nonEmpty).getOrElse(typeName)
      s"$componentName ($id)"
end LiveTraceViewer

private[docs] object LiveTraceViewer:
  private val TraceSessionParameter = "_scalive_trace_session"
  private val ValidSession          = "[A-Za-z0-9_-]{16,64}".r
  val HookName                      = "LiveTraceViewer"
  val BrowserRecordsEvent           = BrowserToServerEvent[BrowserTraceBatch](
    "docs:live-trace-browser-records"
  )

  enum Msg:
    case ToggleCapture, Clear, Refresh, JumpToLatest
    case SelectInteraction(id: String)

  final case class Model(
    session: Option[String],
    enabled: Boolean,
    records: Vector[DocumentationTraceRecord],
    selectedInteraction: Option[String],
    followLatest: Boolean)

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
end LiveTraceViewer
