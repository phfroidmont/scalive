package feedback

import zio.*
import zio.json.*

import scalive.*

final case class Rendered(title: String) derives JsonEncoder

val RenderedEvent = ServerToBrowserEvent[Rendered]("document:rendered")

trait Documents:
  def save(title: String): Task[Unit]

// docs:start feedback-liveview
final class DocumentLiveView(documents: Documents)
    extends LiveView[DocumentLiveView.Msg, DocumentLiveView.Model]:
  import DocumentLiveView.*

  // docs:start feedback-after-render
  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.afterRender { (model, ctx) =>
      ctx.connection match
        case Connection.Connected(capabilities) =>
          capabilities.client.push(RenderedEvent, Rendered(model.title))
        case Connection.Disconnected => ZIO.unit
    }
  // docs:end feedback-after-render

  // docs:start feedback-page-title
  override def pageTitle(model: Model): Option[String] =
    Some(model.title)
  // docs:end feedback-page-title

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model("Untitled document"))

  def handleMessage(model: Model, ctx: MessageContext) =
    // docs:start feedback-flash-put
    case Msg.Save =>
      documents.save(model.title) *> ctx.flash.put(Saved, "Changes saved.").as(model)
    // docs:end feedback-flash-put

  def view(model: Signal[Model]): HtmlElement[Msg] =
    mainTag(
      // docs:start feedback-flash-render
      flash(Saved) { message =>
        p(role := "status", aria.live := "polite", message)
      },
      // docs:end feedback-flash-render
      // docs:start feedback-connection
      div(
        span(connection.visibleWhenConnected, "Connected"),
        span(connection.visibleWhenDisconnected, "Offline")
      ),
      // docs:end feedback-connection
      h1(model.map(_.title)),
      button(typ := "button", on.click(Msg.Save), "Save")
    )
end DocumentLiveView

object DocumentLiveView:
  val Saved = FlashKind("saved")

  final case class Model(title: String)

  enum Msg:
    case Save
// docs:end feedback-liveview
