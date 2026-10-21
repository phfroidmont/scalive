import zio.ZIO

import scalive.*

/** Puts the same flash message again while it is shown. */
class FlashRepeatLiveView extends LiveView[Unit, Unit]:
  import FlashRepeatLiveView.*

  def mount(ctx: MountContext) = ZIO.unit

  def handleMessage(model: Unit, ctx: MessageContext) =
    _ => ctx.flash.put(Saved, "Saved")

  override def view(model: Signal[Unit]) =
    div(
      button(idAttr := "save", on.click(()), "Save"),
      flash(Saved)((message, put) => p(dom.hook("FlashMounts", DomRef(s"saved-$put")), message)),
      flash(Saved)(message => p(dom.hook("FixedFlashMounts", DomRef("saved-fixed")), message))
    )

object FlashRepeatLiveView:
  val Saved = FlashKind("saved")
