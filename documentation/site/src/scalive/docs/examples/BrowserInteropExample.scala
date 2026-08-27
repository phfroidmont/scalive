package scalive.docs.examples

import zio.*
import zio.json.*

import scalive.*

// docs:start browser-integration-example
final class BrowserInteropExample(instanceId: String)
    extends LiveView[BrowserInteropExample.Msg, BrowserInteropExample.Model]:
  import BrowserInteropExample.*

  private val hookRef        = DomRef(s"$instanceId-hook")
  private val panelRef       = DomRef(s"$instanceId-panel")
  private val placeholderRef = DomRef(s"$instanceId-placeholder")
  private val detailRef      = DomRef(s"$instanceId-detail")

  private val clientOnlyCommand =
    JS.show(to = panelRef.selector)
      .hide(to = placeholderRef.selector)
      .toggle(to = detailRef.selector)

  private val resetCommand =
    JS.push(Msg.Reset)
      .hide(to = panelRef.selector)
      .hide(to = detailRef.selector)
      .show(to = placeholderRef.selector)

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty[Msg, Model].onBrowserEvent(CopyResultEvent) { (model, result, _) =>
      ZIO.succeed(applyCopyResult(model, result))
    }

  def mount(ctx: MountContext): Task[Model] = ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.CopySample =>
      val requestNumber = model.requestNumber + 1
      val requestId     = s"copy-$requestNumber"
      ctx.client
        .push(CopyRequestEvent, CopyRequest(requestId, SampleText))
        .as(model.copy(requestNumber = requestNumber, operation = CopyOperation.Pending(requestId)))
    case Msg.Reset => ZIO.succeed(Model())

  def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      dom.hook(HookName, hookRef),
      cls := "docs-browser-integration",
      sectionTag(
        dataAttr("example-controls") := "",
        h3("Compose client-only commands"),
        p(
          "This click shows a panel, hides the placeholder, and toggles a detail without sending a server message."
        ),
        button(typ := "button", on.click(clientOnlyCommand), "Run composed command"),
        p(placeholderRef.attr, "The command will hide this placeholder."),
        div(
          panelRef.attr,
          styleAttr := "display: none;",
          strong("The command showed this panel."),
          p(
            detailRef.attr,
            styleAttr := "display: none;",
            "Run it again to toggle this detail."
          )
        )
      ),
      sectionTag(
        dataAttr("example-controls") := "",
        h3("Exchange typed browser events"),
        p("Scala asks the hook to copy this sample and accepts only the correlated result."),
        codeTag(SampleText),
        div(
          cls := "docs-browser-actions",
          button(
            typ := "button",
            on.click(Msg.CopySample),
            model.map(model =>
              if model.operation.isPending then "Retry copy" else "Copy sample text"
            )
          ),
          button(typ := "button", on.click(resetCommand), "Reset browser integration")
        ),
        p(
          dataAttr("browser-copy-status") := "",
          role                            := "status",
          model.map(_.operation.label)
        )
      )
    )
end BrowserInteropExample

object BrowserInteropExample:
  final case class CopyRequest(requestId: String, text: String) derives JsonEncoder
  final case class CopyResult(requestId: String, ok: Boolean) derives JsonDecoder

  final case class Model(
    requestNumber: Long = 0,
    operation: CopyOperation = CopyOperation.Idle)

  enum Msg:
    case CopySample
    case Reset

  enum CopyOperation(val label: String, val traceLabel: String):
    case Idle extends CopyOperation("No browser operation requested yet.", "idle")
    case Pending(requestId: String)
        extends CopyOperation("Waiting for the browser result. Retry if needed.", "pending")
    case Succeeded extends CopyOperation("Browser operation completed.", "succeeded")
    case Failed    extends CopyOperation("Browser operation could not be completed.", "failed")

    def isPending: Boolean = this match
      case Pending(_) => true
      case _          => false

  val SampleText = "Scalive keeps server-to-browser event payloads typed."

  private val HookName         = "BrowserInterop"
  private val CopyResultEvent  = BrowserToServerEvent[CopyResult]("browser-copy-result")
  private val CopyRequestEvent = ServerToBrowserEvent[CopyRequest]("browser-copy-request")
  private val codeTag          = HtmlTag("code")

  def applyCopyResult(model: Model, result: CopyResult): Model =
    val nextOperation = model.operation match
      case CopyOperation.Pending(requestId) if requestId == result.requestId =>
        if result.ok then CopyOperation.Succeeded else CopyOperation.Failed
      case current => current
    model.copy(operation = nextOperation)
end BrowserInteropExample
// docs:end browser-integration-example
