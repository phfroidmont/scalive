package feedback

import zio.*
import zio.json.*

import scalive.*

// docs:start feedback-flash-typed
final case class ArchiveNotice(title: String, undoable: Boolean) derives JsonCodec

object ArchiveNotice:
  val Kind = FlashKind.json[ArchiveNotice]("archive-notice")

  def announce(flash: Flash, title: String): Task[Unit] =
    flash.put(Kind, ArchiveNotice(s"$title archived", undoable = true))

  val rendered: Mod.Content[Nothing] =
    scalive.flash(Kind) { notice =>
      div(
        role := "status",
        p(notice.title),
        Option.when(notice.undoable)(p("Undo is available from the archive."))
      )
    }
// docs:end feedback-flash-typed
