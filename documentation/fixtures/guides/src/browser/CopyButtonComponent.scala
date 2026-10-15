package browser

import zio.*
import zio.json.*

import scalive.*

object CopyButtonComponent
    extends LiveComponent[
      CopyButtonComponent.Props,
      CopyButtonComponent.Msg,
      CopyButtonComponent.Model
    ]:
  final case class Props(id: String, text: String)

  final case class Model(requestNumber: Long, pending: Option[String], copied: Option[String])

  enum Msg:
    case Copy
    case Copied(result: CopyResult)

  final case class CopyRequest(requestId: String, text: String) derives JsonEncoder
  final case class CopyResult(requestId: String, ok: Boolean) derives JsonDecoder

  private val CopyRequestEvent = ServerToBrowserEvent[CopyRequest]("browser-copy-request")
  private val CopyResultEvent  = BrowserToServerEvent[CopyResult]("browser-copy-result")

  // docs:start browser-component-events
  override def browserEvents = BrowserEvents.on(CopyResultEvent)(Msg.Copied(_))
  // docs:end browser-component-events

  def mount(props: Props, ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(requestNumber = 0, pending = None, copied = None))

  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => Task[Model] =
    case Msg.Copy =>
      val requestNumber = model.requestNumber + 1
      val requestId     = s"${props.id}-copy-$requestNumber"
      ctx.client
        .push(CopyRequestEvent, CopyRequest(requestId, props.text))
        .as(model.copy(requestNumber = requestNumber, pending = Some(requestId), copied = None))
    case Msg.Copied(result) => ZIO.succeed(applyCopyResult(props, model, result))

  def view(props: Signal[Props], model: Signal[Model]) =
    button(
      typ := "button",
      on.click(Msg.Copy),
      model.map(model => if model.copied.nonEmpty then "Copied" else "Copy")
    )

  private def applyCopyResult(props: Props, model: Model, result: CopyResult): Model =
    if model.pending.contains(result.requestId) then
      model.copy(pending = None, copied = Option.when(result.ok)(props.text))
    else model
end CopyButtonComponent
