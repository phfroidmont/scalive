package scalive.docs.examples

import zio.*
import zio.json.*

import scalive.*

// docs:start browser-integration-example
final class BrowserInteropExample(instanceId: String)
    extends LiveView[BrowserInteropExample.Msg, BrowserInteropExample.Model]:
  import BrowserInteropExample.*

  // docs:start browser-integration-hook-ref
  private val hookRef = DomRef(s"$instanceId-hook")
  // docs:end browser-integration-hook-ref
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

  // docs:start browser-integration-root-hook
  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.onBrowserEvent(CopyResultEvent) { (model, result, _) =>
      ZIO.succeed(applyCopyResult(model, result))
    }
  // docs:end browser-integration-root-hook

  def mount(ctx: MountContext): Task[Model] = ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    // docs:start browser-integration-push
    case Msg.CopySample =>
      val requestNumber = model.requestNumber + 1
      val requestId     = s"copy-$requestNumber"
      ctx.client
        .push(CopyRequestEvent, CopyRequest(requestId, SampleText))
        .as(model.copy(requestNumber = requestNumber, operation = CopyOperation.Pending(requestId)))
    // docs:end browser-integration-push
    case Msg.Reset => ZIO.succeed(Model())

  def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      // docs:start browser-integration-hook-element
      dom.hook(HookName, hookRef),
      // docs:end browser-integration-hook-element
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
  // docs:start browser-integration-server-event
  final case class CopyRequest(requestId: String, text: String) derives JsonEncoder

  private val CopyRequestEvent = ServerToBrowserEvent[CopyRequest]("browser-copy-request")
  // docs:end browser-integration-server-event

  // docs:start browser-integration-browser-event
  final case class CopyResult(requestId: String, ok: Boolean) derives JsonDecoder

  private val CopyResultEvent = BrowserToServerEvent[CopyResult]("browser-copy-result")
  // docs:end browser-integration-browser-event

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

  private val HookName = "BrowserInterop"
  private val codeTag  = HtmlTag("code")

  def applyCopyResult(model: Model, result: CopyResult): Model =
    val nextOperation = model.operation match
      case CopyOperation.Pending(requestId) if requestId == result.requestId =>
        if result.ok then CopyOperation.Succeeded else CopyOperation.Failed
      case current => current
    model.copy(operation = nextOperation)
end BrowserInteropExample
// docs:end browser-integration-example
