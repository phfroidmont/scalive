package scalive

import zio.*

enum LiveNavigationCommand:
  case PushPatch(to: String)
  case ReplacePatch(to: String)
  case PushNavigate(to: String)
  case ReplaceNavigate(to: String)
  case Redirect(to: String)

trait LiveNavigationRuntime:
  def request(command: LiveNavigationCommand): Task[Unit]

object LiveNavigationRuntime:
  val Disabled: LiveNavigationRuntime = new LiveNavigationRuntime:
    def request(command: LiveNavigationCommand): Task[Unit] =
      ZIO.fail(new IllegalStateException("Navigation runtime is not available"))
