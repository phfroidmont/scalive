package scalive.docs.examples

import zio.*

import scalive.*

// docs:start lifecycle-example
final class LifecycleExample extends LiveView[LifecycleExample.Msg, LifecycleExample.Model]:
  import LifecycleExample.*

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty[Msg, Model].afterRender { (model, ctx) =>
      ZIO
        .when(ctx.connection.isInstanceOf[Connection.Connected[?]]) {
          ZIO.logDebug(s"Lifecycle example rendered with title '${model.currentTitle}'")
        }.unit
    }

  override def pageTitle(model: Model): Option[String] = Some(model.currentTitle)

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(
      Model(
        connectedMount = ctx.connection.isInstanceOf[Connection.Connected[?]],
        currentTitle = DefaultTitle
      )
    )

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.PutNotification =>
      ctx.flash.put(NotificationFlash, "Your notification is ready.").as(model)
    case Msg.ClearNotification =>
      ctx.flash.clear(NotificationFlash).as(model)
    case Msg.RequestAttention =>
      ZIO.succeed(model.copy(currentTitle = AttentionTitle))
    case Msg.Reset =>
      ctx.flash.clear(NotificationFlash).as(model.copy(currentTitle = DefaultTitle))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      cls := "docs-lifecycle",
      div(
        cls := "docs-lifecycle-state",
        sectionTag(
          p(cls := "docs-lifecycle-label", "Mount phase"),
          strong(
            dataAttr("mount-phase") := "",
            model.map(model =>
              if model.connectedMount then "Connected socket mount" else "Disconnected HTTP mount"
            )
          )
        ),
        sectionTag(
          p(cls                              := "docs-lifecycle-label", "Projected page title"),
          strong(dataAttr("lifecycle-title") := "", model.map(_.currentTitle))
        )
      ),
      div(
        cls := "docs-lifecycle-connection",
        span(connection.visibleWhenConnected, "LiveSocket connected"),
        span(connection.visibleWhenDisconnected, "LiveSocket disconnected")
      ),
      flash(NotificationFlash) { message =>
        p(
          dataAttr("lifecycle-flash") := "",
          cls                         := "docs-lifecycle-flash",
          role                        := "status",
          message
        )
      },
      fieldSet(
        dataAttr("example-controls") := "",
        legend(cls := "docs-visually-hidden", "Lifecycle controls"),
        button(typ := "button", on.click(Msg.PutNotification), "Show notification"),
        button(typ := "button", on.click(Msg.ClearNotification), "Clear notification"),
        button(typ := "button", on.click(Msg.RequestAttention), "Request attention"),
        button(typ := "button", on.click(Msg.Reset), "Reset example")
      )
    )
end LifecycleExample

object LifecycleExample:
  final case class Model(connectedMount: Boolean, currentTitle: String)

  enum Msg:
    case PutNotification
    case ClearNotification
    case RequestAttention
    case Reset

  private val NotificationFlash = FlashKind("notification")
  private val DefaultTitle      = "Lifecycle example"
  private val AttentionTitle    = "Attention needed"
// docs:end lifecycle-example
