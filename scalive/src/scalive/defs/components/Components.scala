package scalive.defs.components

import scalive.*
import scalive.codecs.BooleanAsAttrPresenceEncoder

trait Components:
  def focusWrap[Msg](id: String, mods: Mod[Msg]*)(content: Mod[Msg]*): HtmlElement[Msg] =
    val startSentinel = span(idAttr := s"$id-start", tabIndex := 0, aria.hidden := true)
    val endSentinel   = span(idAttr := s"$id-end", tabIndex := 0, aria.hidden := true)

    div(
      phx.hook("Phoenix.FocusWrap", id) ++
        mods ++
        Vector(Mod.Content.Tag(startSentinel)) ++
        content ++
        Vector(Mod.Content.Tag(endSentinel))
    )

  private val dataPhxAutoUpload = htmlAttr("data-phx-auto-upload", BooleanAsAttrPresenceEncoder)

  def liveFileInput[Msg, R](upload: LiveUpload[R], mods: Mod[Msg]*): HtmlElement[Msg] =
    val activeRefs = upload.entries.map(_.ref.value).mkString(",")
    val doneRefs   = upload.entries
      .filter(_.status == LiveUploadEntryStatus.Completed)
      .map(_.ref.value)
      .mkString(",")
    val preflightedRefs = upload.entries
      .filter(entry =>
        entry.status match
          case LiveUploadEntryStatus.Preflighted | LiveUploadEntryStatus.Uploading(_) |
              LiveUploadEntryStatus.Completed =>
            true
          case _ => false
      )
      .map(_.ref.value)
      .mkString(",")

    input(
      phx.hook("Phoenix.LiveFileUpload", upload.ref.value),
      typ                              := "file",
      nameAttr                         := upload.name,
      accept                           := upload.accept.toHtmlValue,
      dataAttr("phx-upload-ref")       := upload.ref.value,
      dataAttr("phx-active-refs")      := activeRefs,
      dataAttr("phx-done-refs")        := doneRefs,
      dataAttr("phx-preflighted-refs") := preflightedRefs,
      dataPhxAutoUpload                := upload.autoUpload,
      multiple                         := upload.maxEntries > 1,
      mods
    )
  end liveFileInput

  def uploadErrors[R](upload: LiveUpload[R]): List[LiveUploadError] = upload.errors

  def uploadErrors[R](upload: LiveUpload[R], entry: LiveUploadEntry[R]): List[LiveUploadError] =
    upload.entries.find(_.ref == entry.ref).map(_.errors).getOrElse(Nil)

  def uploadErrors[R](entry: LiveUploadEntry[R]): List[LiveUploadError] = entry.errors

end Components
