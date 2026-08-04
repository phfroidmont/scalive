package scalive.examples.lifecycle

import zio.*

import scalive.*

final class NotificationsLiveView
    extends LiveView[NotificationsLiveView.Msg, NotificationsLiveView.Model]:
  import NotificationsLiveView.*

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.afterRender[Msg, Model] { (model, ctx) =>
      if ctx.connected then
        ZIO.logDebug(s"Notifications afterRender requestedTitle=${model.currentTitle}")
      else ZIO.unit
    }

  override def pageTitle(model: Model): Option[String] = Some(model.currentTitle)

  def mount(ctx: MountContext) =
    ZIO.succeed(Model(connectedMount = ctx.connected, currentTitle = DefaultTitle))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.PutNotification =>
      ctx.flash
        .put(NotificationFlash, "Your notification is ready.")
        .as(model)
    case Msg.ClearNotification =>
      ctx.flash
        .clear(NotificationFlash)
        .as(model)
    case Msg.RequestAttention =>
      ZIO.succeed(model.copy(currentTitle = AttentionTitle))
    case Msg.RestoreTitle =>
      ZIO.succeed(model.copy(currentTitle = DefaultTitle))

  def render(model: Model) =
    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Lifecycle UX"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Notifications and page state"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "Use phase-specific lifecycle capabilities for connection-aware mounting, keyed flash messages, page titles, and a static after-render side effect."
        )
      ),
      div(
        cls := "mb-6 grid gap-4 md:grid-cols-2",
        sectionTag(
          cls := "rounded-box border border-base-300 bg-base-100 p-6 shadow-sm",
          p(
            cls := "text-sm font-semibold uppercase tracking-[0.14em] text-base-content/50",
            "Mount phase"
          ),
          p(
            cls := "mt-3 text-xl font-bold",
            if model.connectedMount then "Connected mount" else "Disconnected HTTP mount"
          ),
          p(
            cls := "mt-2 text-sm leading-6 text-base-content/65",
            "MountContext.connected is false for static HTML and true when the LiveSocket mounts the interactive view."
          )
        ),
        sectionTag(
          cls := "rounded-box border border-base-300 bg-base-100 p-6 shadow-sm",
          p(
            cls := "text-sm font-semibold uppercase tracking-[0.14em] text-base-content/50",
            "LiveSocket state"
          ),
          div(
            cls := "mt-3",
            span(
              connection.visibleWhenConnected,
              cls := "badge badge-success badge-lg",
              "Connected"
            ),
            span(
              connection.visibleWhenDisconnected,
              cls := "badge badge-error badge-lg",
              "Disconnected"
            )
          ),
          p(
            cls := "mt-3 text-sm leading-6 text-base-content/65",
            "Client connection lifecycle bindings keep this badge current if the socket disconnects or reconnects."
          )
        )
      ),
      div(
        cls := "grid gap-6 lg:grid-cols-2",
        sectionTag(
          cls := "rounded-box border border-base-300 bg-base-100 p-7 shadow-sm",
          div(cls := "badge badge-ghost mb-4", "Keyed flash"),
          h2(cls  := "text-2xl font-bold", "Put and clear one notification key"),
          p(
            cls := "mt-3 leading-7 text-base-content/70",
            "Both actions use the same FlashKind. A static afterRender hook observes connected renders with useful title context as a side effect."
          ),
          div(
            cls := "mt-5 flex flex-wrap gap-3",
            button(
              typ := "button",
              cls := "btn btn-primary",
              on.click(Msg.PutNotification),
              "Put notification"
            ),
            button(
              typ := "button",
              cls := "btn btn-outline",
              on.click(Msg.ClearNotification),
              "Clear notification"
            )
          ),
          flash(NotificationFlash) { message =>
            div(
              idAttr := "notifications-flash",
              cls    := "alert alert-success mt-5",
              span(message)
            )
          }
        ),
        sectionTag(
          cls := "rounded-box border border-base-300 bg-base-100 p-7 shadow-sm",
          div(cls := "badge badge-ghost mb-4", "Document title"),
          h2(cls  := "text-2xl font-bold", "Change and restore the title"),
          p(
            cls := "mt-3 leading-7 text-base-content/70",
            "pageTitle derives the browser title from the model for both static HTML and live updates."
          ),
          div(
            cls := "mt-5 rounded-box bg-base-200 p-4",
            p(
              cls := "text-xs font-semibold uppercase tracking-wide text-base-content/50",
              "Current title"
            ),
            p(cls := "mt-2 font-mono text-sm", model.currentTitle)
          ),
          div(
            cls := "mt-5 flex flex-wrap gap-3",
            button(
              typ := "button",
              cls := "btn btn-secondary",
              on.click(Msg.RequestAttention),
              "Request attention"
            ),
            button(
              typ := "button",
              cls := "btn btn-outline",
              on.click(Msg.RestoreTitle),
              "Restore title"
            )
          )
        )
      )
    )
end NotificationsLiveView

object NotificationsLiveView:
  final case class Model(
    connectedMount: Boolean,
    currentTitle: String)

  enum Msg:
    case PutNotification
    case ClearNotification
    case RequestAttention
    case RestoreTitle

  private val NotificationFlash = FlashKind("notification")
  private val DefaultTitle      = "Notifications"
  private val AttentionTitle    = "Attention needed"
