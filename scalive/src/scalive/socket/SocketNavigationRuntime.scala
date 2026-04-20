package scalive
package socket

import zio.*

import scalive.*

final private[scalive] class SocketNavigationRuntime(
  navigationRef: Ref[Option[LiveNavigationCommand]])
    extends LiveNavigationRuntime:

  def request(command: LiveNavigationCommand): Task[Unit] =
    navigationRef.modify {
      case None           => (ZIO.unit, Some(command))
      case Some(existing) =>
        (
          ZIO.fail(
            new IllegalStateException(
              s"Navigation command is already set to ${show(existing)}"
            )
          ),
          Some(existing)
        )
    }.flatten

  private def show(command: LiveNavigationCommand): String =
    command match
      case LiveNavigationCommand.PushPatch(to)    => s"push_patch($to)"
      case LiveNavigationCommand.ReplacePatch(to) => s"replace_patch($to)"
