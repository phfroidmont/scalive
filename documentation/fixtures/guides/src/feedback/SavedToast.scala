package feedback

import scalive.*

object SavedToast:
  // docs:start feedback-flash-put-number
  val rendered: Mod.Content[Nothing] =
    flash(DocumentLiveView.Saved) { (message, put) =>
      div(dom.hook("AutoDismiss", DomRef(s"saved-toast-$put")), role := "status", message)
    }
  // docs:end feedback-flash-put-number
