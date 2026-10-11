package browser

import zio.*

import scalive.*

final class ConfirmDialogLiveView
    extends LiveView[ConfirmDialogLiveView.Msg, ConfirmDialogLiveView.Model]:
  import ConfirmDialogLiveView.*

  private val dialogRef = DomRef("confirm-dialog")

  // docs:start browser-dialog-commands
  private val openDialog =
    JS.pushFocus()
      .show(to = dialogRef.selector)
      .addClass("is-open", to = dialogRef.selector)
      .setAttribute("data-state" -> "open", to = dialogRef.selector)
      .transition(("ease-out", "opacity-0", "opacity-100"), to = dialogRef.selector)
      .focusFirst(to = dialogRef.selector)
      .dispatch("dialog:opened", to = dialogRef.selector)

  private val confirm =
    JS.push(Msg.ConfirmDialog).toggleClass("is-pending", to = dialogRef.selector)

  private val closeDialog =
    JS.hide(to = dialogRef.selector)
      .removeClass("is-open", to = dialogRef.selector)
      .removeAttribute("data-state", to = dialogRef.selector)
      .popFocus()
  // docs:end browser-dialog-commands

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(confirmed = false))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ConfirmDialog => ZIO.succeed(model.copy(confirmed = true))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      // docs:start browser-dialog-buttons
      button(typ := "button", on.click(openDialog), "Open"),
      div(
        dialogRef.attr,
        role      := "dialog",
        styleAttr := "display: none;",
        p("Delete this draft?"),
        button(typ := "button", on.click(confirm), "Confirm"),
        button(typ := "button", on.click(closeDialog), "Close")
      ),
      // docs:end browser-dialog-buttons
      p(model.map(model => if model.confirmed then "Draft deleted." else ""))
    )
end ConfirmDialogLiveView

object ConfirmDialogLiveView:
  final case class Model(confirmed: Boolean)

  enum Msg:
    case ConfirmDialog
