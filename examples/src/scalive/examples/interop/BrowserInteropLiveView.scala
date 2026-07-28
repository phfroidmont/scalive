package scalive.examples.interop

import zio.*
import zio.json.*
import zio.json.ast.Json

import scalive.*

final class BrowserInteropLiveView
    extends LiveView[BrowserInteropLiveView.Msg, BrowserInteropLiveView.Model]:
  import BrowserInteropLiveView.*

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty[Msg, Model].rawEvent(CopyResultEvent) { (model, event, _) =>
      if event.bindingId != CopyResultEvent || event.cid.nonEmpty then
        ZIO.succeed(LiveEventHookResult.cont(model))
      else
        decodeCopyResult(event.value, model.pendingRequestId) match
          case Some(result) =>
            val nextModel = model.copy(
              pendingRequestId = None,
              copyStatus = if result.ok then CopyStatus.Succeeded else CopyStatus.Failed
            )
            ZIO.succeed(LiveEventHookResult.halt(nextModel))
          case None =>
            ZIO.succeed(LiveEventHookResult.halt(model))
    }

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.CopySample if model.pendingRequestId.nonEmpty =>
      ZIO.succeed(model)
    case Msg.CopySample =>
      val requestNumber = model.requestNumber + 1
      val requestId     = s"copy-$requestNumber"
      ctx.client
        .push(CopyRequestEvent, CopyRequest(requestId, SampleText))
        .as(
          model.copy(
            requestNumber = requestNumber,
            pendingRequestId = Some(requestId),
            copyStatus = CopyStatus.Pending
          )
        )

  def render(model: Model) =
    div(
      idAttr   := HookDomId,
      phx.hook := HookName,
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Client interop"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Browser integration"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "Compose browser-only JS commands and send one typed payload from Scala to a focused JavaScript hook. The hook reply is raw JSON, not a typed client-to-server channel, so Scala validates it at runtime."
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
            "A typed LiveView message asks Scala to push ClientEvent[CopyRequest]. JavaScript handles it, attempts a clipboard write, and returns only a request ID and success flag through hook.pushEvent."
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
            typ      := "button",
            cls      := "btn btn-secondary mt-5",
            disabled := model.pendingRequestId.nonEmpty,
            phx.onClick(Msg.CopySample),
            "Copy sample text"
          ),
          div(
            idAttr := CopyStatusId,
            cls    := s"alert mt-5 ${model.copyStatus.alertClass}",
            span(model.copyStatus.label)
          )
        )
      )
    )
end BrowserInteropLiveView

object BrowserInteropLiveView:
  final case class CopyRequest(requestId: String, text: String) derives JsonEncoder

  final case class Model(
    requestNumber: Long = 0,
    pendingRequestId: Option[String] = None,
    copyStatus: CopyStatus = CopyStatus.Idle)

  enum Msg:
    case CopySample

  enum CopyStatus(val label: String, val alertClass: String):
    case Idle      extends CopyStatus("No browser operation requested yet.", "alert-info")
    case Pending   extends CopyStatus("Waiting for the browser result...", "alert-info")
    case Succeeded extends CopyStatus("Browser operation completed.", "alert-success")
    case Failed    extends CopyStatus("Browser operation could not be completed.", "alert-error")

  final private case class CopyResult(requestId: String, ok: Boolean)

  private val HookName             = "BrowserInterop"
  private val HookDomId            = "browser-interop-hook"
  private val CopyResultEvent      = "browser-copy-result"
  private val CopyRequestEvent     = ClientEvent[CopyRequest]("browser-copy-request")
  private val SampleText           = "Scalive keeps server-to-client event payloads typed."
  private val CommandPanelId       = "browser-command-panel"
  private val CommandPlaceholderId = "browser-command-placeholder"
  private val CommandDetailId      = "browser-command-detail"
  private val CopyStatusId         = "browser-copy-status"
  private val CopyResultFields     = Set("requestId", "ok")
  private val codeTag              = HtmlTag("code")

  private val ClientOnlyCommand =
    JS.show(to = s"#$CommandPanelId")
      .hide(to = s"#$CommandPlaceholderId")
      .toggle(to = s"#$CommandDetailId")

  private def decodeCopyResult(value: Json, expectedRequestId: Option[String]): Option[CopyResult] =
    value match
      case Json.Obj(fields)
          if fields.length == CopyResultFields.size && fields.map(_._1).toSet == CopyResultFields =>
        for
          requestId <- fields.collectFirst { case ("requestId", Json.Str(value)) => value }
          ok        <- fields.collectFirst { case ("ok", Json.Bool(value)) => value }
          if requestId.length <= 64
          if expectedRequestId.contains(requestId)
        yield CopyResult(requestId, ok)
      case _ => None
end BrowserInteropLiveView
