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
          _ <- ctx.subscriptions.start(
                 SubscriptionKey(s"live-trace:$viewerTopic"),
                 SubscriptionDelivery.Latest
               )(
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

  override def view(model: Signal[Model]): HtmlElement[Msg] =
    val interactions = model.map(value => CapturedInteractionGrouper.group(value.records))
    val selectedId   = model.map(_.selectedInteraction)
    sectionTag(
      cls                                   := "docs-live-trace",
      aria.label                            := s"Live ${example.descriptor.title} trace",
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
    val newerCount          = selected.zip(interactions).map { case (value, values) =>
      value.fold(0)(interaction => values.indexWhere(_.id == interaction.id))
    }
    val showDetails = interactions.zip(selectedId).map { case (values, id) =>
      values.nonEmpty || id.nonEmpty
    }
    div(
      cls := "docs-live-trace-live",
      renderCaptureControls(model, interactions.map(_.size)),
      showDetails.when(renderInspection(selectedId, selected, interactionOrdinals, newerCount)),
      showDetails.when(
        renderInteractionList(interactions, interactionOrdinals, selectedId, panelId)
      ),
      showDetails.when(renderSelectedPanel(selected, panelId))
    )

  private def renderCaptureControls(
    model: Signal[Model],
    count: Signal[Int]
  ): HtmlElement[Msg] =
    div(
      cls := model.map(value =>
        s"docs-live-trace-toolbar${if value.enabled then " is-capturing" else " is-paused"}"
      ),
      span(
        cls         := "docs-live-trace-capture-status",
        role        := "status",
        aria.live   := "polite",
        aria.atomic := true,
        model.zip(count).map { case (value, retained) =>
          if value.session.isEmpty then "Unavailable"
          else if value.enabled then "Capturing"
          else if retained == 0 then "Ready"
          else "Paused"
        }
      ),
      span(
        cls         := "docs-live-trace-capture-summary",
        role        := "status",
        aria.live   := "polite",
        aria.atomic := true,
        model.zip(count).map { case (value, retained) =>
          if value.session.isEmpty then "Connect to capture interactions"
          else if retained == 0 && value.enabled then "Use the example controls"
          else if retained == 0 then "No interactions yet"
          else if retained == 1 then "1 interaction retained"
          else s"$retained interactions retained"
        }
      ),
      div(
        cls := "docs-live-trace-actions",
        button(
          typ      := "button",
          disabled := model.map(_.session.isEmpty),
          on.click(Msg.ToggleCapture),
          model.zip(count).map { case (value, retained) =>
            if value.enabled then "Pause capture"
            else if retained == 0 then "Start capture"
            else "Resume capture"
          }
        ),
        button(
          typ      := "button",
          disabled := count.map(_ == 0),
          on.click(Msg.Clear),
          "Clear"
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
