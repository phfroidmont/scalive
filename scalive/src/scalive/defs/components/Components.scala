package scalive.defs.components

import scalive.*
import scalive.codecs.BooleanAsAttrPresenceEncoder

trait Components:
  def focusWrap[Msg](id: String, mods: Mod[Msg]*)(content: Mod[Msg]*): HtmlElement[Msg] =
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

  def liveFileInput[Msg](upload: LiveUpload, mods: Mod[Msg]*): HtmlElement[Msg] =
    val activeRefs      = upload.entries.map(_.ref).mkString(",")
    val doneRefs        = upload.entries.filter(_.done).map(_.ref).mkString(",")
    val preflightedRefs = upload.entries
      .filter(entry => entry.preflighted || entry.done)
      .map(_.ref)
      .mkString(",")

    input(
      idAttr                           := upload.ref,
      typ                              := "file",
      nameAttr                         := upload.name,
      accept                           := upload.accept.toHtmlValue,
      dataAttr("phx-hook")             := "Phoenix.LiveFileUpload",
      dataAttr("phx-update")           := "ignore",
      dataAttr("phx-upload-ref")       := upload.ref,
      dataAttr("phx-active-refs")      := activeRefs,
      dataAttr("phx-done-refs")        := doneRefs,
      dataAttr("phx-preflighted-refs") := preflightedRefs,
      dataPhxAutoUpload                := upload.autoUpload,
      multiple                         := upload.maxEntries > 1,
      mods
    )

  def uploadErrors(upload: LiveUpload): List[LiveUploadError] = upload.errors

  def uploadErrors(upload: LiveUpload, entry: LiveUploadEntry): List[LiveUploadError] =
    upload.entries.find(_.ref == entry.ref).map(_.errors).getOrElse(Nil)

  def uploadErrors(entry: LiveUploadEntry): List[LiveUploadError] = entry.errors

end Components
