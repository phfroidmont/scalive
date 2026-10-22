package scalive

/** Which navigation a [[navigation.guardWhen]] guard asks the visitor to confirm. */
enum NavigationGuardScope derives CanEqual:
  /** Navigation that leaves the LiveView and loses its state: live links to any view, including the
    * same one, LiveView history traversal, reload, and tab close. Live patches pass.
    */
  case LeavingView

  /** Every navigation [[LeavingView]] guards, and live patches within the LiveView too. */
  case AnyNavigation
