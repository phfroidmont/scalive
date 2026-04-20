package scalive.defs.components

import scala.annotation.targetName

import scalive.*
import scalive.codecs.BooleanAsAttrPresenceEncoder

trait Components:
  def focusWrap(id: String, mods: Mod*)(content: Mod*): HtmlElement =
    val startSentinel = span(idAttr := s"$id-start", tabIndex := 0, aria.hidden := true)
    val endSentinel   = span(idAttr := s"$id-end", tabIndex := 0, aria.hidden := true)

    div(
      Vector(idAttr := id, phx.hook := "Phoenix.FocusWrap") ++
        mods ++
        Vector(Mod.Content.Tag(startSentinel)) ++
        content ++
        Vector(Mod.Content.Tag(endSentinel))
    )

  private val dataPhxAutoUpload = htmlAttr("data-phx-auto-upload", BooleanAsAttrPresenceEncoder)

  def liveFileInput(upload: Dyn[LiveUpload], mods: Mod*): HtmlElement =
    input(
      idAttr                      := upload(_.ref),
      typ                         := "file",
      nameAttr                    := upload(_.name),
      accept                      := upload(_.accept.toHtmlValue),
      dataAttr("phx-hook")        := "Phoenix.LiveFileUpload",
      dataAttr("phx-update")      := "ignore",
      dataAttr("phx-upload-ref")  := upload(_.ref),
      dataAttr("phx-active-refs") := upload(u =>
        u.entries.filterNot(_.cancelled).map(_.ref).mkString(",")
      ),
      dataAttr("phx-done-refs") := upload(u => u.entries.filter(_.done).map(_.ref).mkString(",")),
      dataAttr("phx-preflighted-refs") := upload(u =>
        u.entries.filter(entry => entry.preflighted || entry.done).map(_.ref).mkString(",")
      ),
      dataPhxAutoUpload := upload(_.autoUpload),
      multiple          := upload(_.maxEntries > 1),
      mods
    )

  def uploadErrors(upload: LiveUpload): List[LiveUploadError] = upload.errors

  def uploadErrors(upload: LiveUpload, entry: LiveUploadEntry): List[LiveUploadError] =
    val _ = upload
    entry.errors

  @targetName("uploadErrorsDynUpload")
  def uploadErrors(upload: Dyn[LiveUpload]): Dyn[List[LiveUploadError]] =
    upload(_.errors)

  @targetName("uploadErrorsDynEntry")
  def uploadErrors(entry: Dyn[LiveUploadEntry]): Dyn[List[LiveUploadError]] =
    entry(_.errors)
end Components
