package scalive.examples.interop

import zio.*
import zio.json.*

import scalive.*

final class BrowserInteropLiveView
    extends LiveView[BrowserInteropLiveView.Msg, BrowserInteropLiveView.Model]:
  import BrowserInteropLiveView.*

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty[Msg, Model].onBrowserEvent(CopyResultEvent) { (model, result, _) =>
      val nextOperation = model.operation match
        case CopyOperation.Pending(requestId) if requestId == result.requestId =>
          if result.ok then CopyOperation.Succeeded else CopyOperation.Failed
        case current => current
      ZIO.succeed(model.copy(operation = nextOperation))
    }

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.CopySample =>
      val requestNumber = model.requestNumber + 1
      val requestId     = s"copy-$requestNumber"
      ctx.client
        .push(CopyRequestEvent, CopyRequest(requestId, SampleText))
        .as(
          model.copy(
            requestNumber = requestNumber,
            operation = CopyOperation.Pending(requestId)
          )
        )

  def render(model: Model) =
    div(
      phx.hook(HookName, id = HookDomId),
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Client interop"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Browser integration"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "Compose browser-only JS commands and exchange typed payloads between Scala and a focused JavaScript hook."
        )
      ),
      div(
        cls := "grid gap-6 lg:grid-cols-2",
        sectionTag(
          cls := "rounded-box border border-base-300 bg-base-100 p-7 shadow-sm",
          div(cls := "badge badge-ghost mb-4", "Client-only command"),
          h2(cls  := "text-2xl font-bold", "Show, hide, and toggle together"),
          p(
            cls := "mt-3 leading-7 text-base-content/70",
            "This click runs one composed JS command entirely in the browser. It sends no LiveView message."
          ),
          button(
            typ := "button",
            cls := "btn btn-primary mt-5",
            phx.onClick(ClientOnlyCommand),
            "Run composed command"
          ),
          p(
            idAttr := CommandPlaceholderId,
            cls := "mt-5 rounded-box border border-dashed border-base-300 p-4 text-sm text-base-content/60",
            "The command will hide this placeholder."
          ),
          div(
            idAttr    := CommandPanelId,
            styleAttr := "display: none;",
            cls       := "mt-5 rounded-box bg-primary/10 p-5",
            p(cls := "font-semibold text-primary", "The command showed this panel."),
            p(
              idAttr    := CommandDetailId,
              styleAttr := "display: none;",
              cls       := "mt-2 text-sm leading-6 text-base-content/70",
              "Run it again to toggle this detail while the panel stays visible."
            )
          )
        ),
        sectionTag(
          cls := "rounded-box border border-base-300 bg-base-100 p-7 shadow-sm",
          div(cls := "badge badge-ghost mb-4", "Hook event"),
          h2(cls  := "text-2xl font-bold", "Request a browser operation"),
          p(
            cls := "mt-3 leading-7 text-base-content/70",
            "A typed LiveView message asks Scala to push ServerToBrowserEvent[CopyRequest]. JavaScript handles it, attempts a clipboard write, and returns BrowserToServerEvent[CopyResult] through hook.pushEvent."
          ),
          div(
            cls := "mt-5 rounded-box bg-base-200 p-4",
            p(
              cls := "text-xs font-semibold uppercase tracking-wide text-base-content/50",
              "Sample"
            ),
            codeTag(cls := "mt-2 block break-words text-sm", SampleText)
          ),
          button(
            typ := "button",
            cls := "btn btn-secondary mt-5",
            phx.onClick(Msg.CopySample),
            if model.operation.isPending then "Retry copy" else "Copy sample text"
          ),
          div(
            idAttr := CopyStatusId,
            cls    := s"alert mt-5 ${model.operation.alertClass}",
            span(model.operation.label)
          )
        )
      )
    )
end BrowserInteropLiveView

object BrowserInteropLiveView:
  final case class CopyRequest(requestId: String, text: String) derives JsonEncoder

  final case class Model(
    requestNumber: Long = 0,
    operation: CopyOperation = CopyOperation.Idle)

  enum Msg:
    case CopySample

  enum CopyOperation(val label: String, val alertClass: String):
    case Idle extends CopyOperation("No browser operation requested yet.", "alert-info")
    case Pending(requestId: String)
        extends CopyOperation("Waiting for the browser result. Retry if needed.", "alert-info")
    case Succeeded extends CopyOperation("Browser operation completed.", "alert-success")
    case Failed    extends CopyOperation("Browser operation could not be completed.", "alert-error")

    def isPending: Boolean = this match
      case Pending(_) => true
      case _          => false

  final private case class CopyResult(requestId: String, ok: Boolean) derives JsonDecoder

  private val HookName             = "BrowserInterop"
  private val HookDomId            = "browser-interop-hook"
  private val CopyResultEvent      = BrowserToServerEvent[CopyResult]("browser-copy-result")
  private val CopyRequestEvent     = ServerToBrowserEvent[CopyRequest]("browser-copy-request")
  private val SampleText           = "Scalive keeps server-to-client event payloads typed."
  private val CommandPanelId       = "browser-command-panel"
  private val CommandPlaceholderId = "browser-command-placeholder"
  private val CommandDetailId      = "browser-command-detail"
  private val CopyStatusId         = "browser-copy-status"
  private val codeTag              = HtmlTag("code")

  private val ClientOnlyCommand =
    JS.show(to = s"#$CommandPanelId")
      .hide(to = s"#$CommandPlaceholderId")
      .toggle(to = s"#$CommandDetailId")

end BrowserInteropLiveView
