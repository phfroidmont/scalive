package scalive.defs.components

import scala.annotation.targetName

import scalive.*
import scalive.codecs.BooleanAsAttrPresenceEncoder

/** Manually implemented HTML helpers whose structure participates in the Phoenix client protocol.
  */
trait Components:
  /** Renders a keyboard-focus boundary using Phoenix's `Phoenix.FocusWrap` hook.
    *
    * The returned wrapper receives `id` and the supplied `mods`, and surrounds `content` with
    * focusable, assistive-technology-hidden sentinels named `${id}-start` and `${id}-end`. Keep
    * `id` stable and unique in the document so the hook and sentinels retain their DOM identity. Do
    * not supply another `id` or `phx-hook` in `mods`; those attributes belong to this helper's
    * protocol structure. Supply only attributes and bindings in `mods`: content there is placed
    * before the start sentinel and breaks the hook's expected structure.
    *
    * @param id
    *   stable DOM ID used by the wrapper and its two sentinels
    * @param mods
    *   additional wrapper attributes and bindings, not child content
    * @param content
    *   content whose focus should wrap at the boundaries
    */
  def focusWrap[Msg](
    id: DomRef,
    mods: Mod.Input[Msg]*
  )(
    content: Mod.Input[Msg]*
  ): HtmlElement[Msg] =
    val startSentinel = span(idAttr := s"${id.value}-start", tabIndex := 0, aria.hidden := true)
    val endSentinel   = span(idAttr := s"${id.value}-end", tabIndex := 0, aria.hidden := true)

    div(
      Vector(id.attr, phx.hook := "Phoenix.FocusWrap") ++
        Mod.flatten(mods) ++
        Vector(Mod.Content.Tag(startSentinel)) ++
        Mod.flatten(content) ++
        Vector(Mod.Content.Tag(endSentinel))
    )

  private val dataPhxAutoUpload = htmlAttr("data-phx-auto-upload", BooleanAsAttrPresenceEncoder)

  /** Renders the file input required by the LiveView upload protocol.
    *
    * The helper derives the input `id`, `name`, `accept`, and `multiple` state from the current
    * [[LiveUpload]] snapshot. It also owns the `Phoenix.LiveFileUpload` hook and the
    * `data-phx-upload-ref`, `data-phx-active-refs`, `data-phx-done-refs`,
    * `data-phx-preflighted-refs`, and `data-phx-error-refs` protocol attributes.
    * `data-phx-auto-upload` is present only for automatic uploads. These attributes allow the
    * browser client to correlate selections and progress with server-side entries; do not remove or
    * hand-edit them. Refresh the upload snapshot after upload events before rendering it again.
    * Supply only attributes and bindings in `mods`; an `input` is a void element and cannot contain
    * child content.
    *
    * Validation and transfer failures are not rendered by the input. Read them with
    * [[uploadErrors]] and present them explicitly.
    *
    * @param upload
    *   the currently allowed upload snapshot to bind to this input
    * @param mods
    *   additional input attributes and bindings; do not pass content or duplicate the protocol
    *   attributes owned by this helper
    */
  def liveFileInput[Msg, R](upload: LiveUpload[R], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    fileInput(upload, idAttr := upload.ref.value, Mod.flatten(mods))

  /** Renders the file input required by the LiveView upload protocol.
    *
    * This form also reports the upload's transfer progress with `progress`, from
    * `upload.onProgress`. It throws `IllegalArgumentException` when `progress` belongs to another
    * upload.
    */
  def liveFileInput[Msg, R](
    upload: LiveUpload[R],
    progress: UploadProgress[Msg],
    mods: Mod.Input[Msg]*
  ): HtmlElement[Msg] =
    val id = progress.upload match
      case Left(ref) =>
        requireOwnProgress(upload, ref)
        idAttr := upload.ref.value
      case Right(refs) =>
        idAttr := refs.map { ref =>
          requireOwnProgress(upload, ref)
          upload.ref.value
        }
    fileInput(upload, id, progress.binding +: Mod.flatten(mods))

  /** Renders the file input required by the LiveView upload protocol.
    *
    * This form follows a signal of the upload, such as `model.map(_.upload)`, so the input's
    * protocol attributes follow the latest snapshot in the model.
    */
  def liveFileInput[Msg, R](
    upload: Signal[LiveUpload[R]],
    mods: Mod.Input[Msg]*
  ): HtmlElement[Msg] =
    signalFileInput(upload, idAttr := upload.map(_.ref.value), Mod.flatten(mods))

  /** Renders the file input required by the LiveView upload protocol.
    *
    * This form follows a signal of the upload and also reports its transfer progress with
    * `progress`. Rendering fails with `IllegalArgumentException` when `progress` belongs to another
    * upload.
    */
  def liveFileInput[Msg, R](
    upload: Signal[LiveUpload[R]],
    progress: UploadProgress[Msg],
    mods: Mod.Input[Msg]*
  ): HtmlElement[Msg] =
    val id = progress.upload match
      case Left(ref) =>
        idAttr := upload.map { current =>
          requireOwnProgress(current, ref)
          current.ref.value
        }
      case Right(refs) =>
        idAttr := upload.combineWithFn(refs) { (current, ref) =>
          requireOwnProgress(current, ref)
          current.ref.value
        }
    signalFileInput(upload, id, progress.binding +: Mod.flatten(mods))

  private def requireOwnProgress[R](upload: LiveUpload[R], progress: UploadRef): Unit =
    require(
      progress == upload.ref,
      s"the progress binding of upload '${progress.value}' was passed to the file input " +
        s"of upload '${upload.name}'"
    )

  private def fileInput[Msg, R](
    upload: LiveUpload[R],
    id: Mod.Attr[Nothing],
    mods: Seq[Mod[Msg]]
  ): HtmlElement[Msg] =
    val activeRefs = upload.entries.map(_.ref.value).mkString(",")
    val doneRefs   = upload.entries
      .filter(_.status == LiveUploadEntryStatus.Completed)
      .map(_.ref.value)
      .mkString(",")
    val preflightedRefs = upload.entries
      .filter(isPreflighted)
      .map(_.ref.value)
      .mkString(",")

    input(
      id,
      phx.hook                         := "Phoenix.LiveFileUpload",
      typ                              := "file",
      nameAttr                         := upload.name,
      accept                           := upload.accept.toHtmlValue,
      dataAttr("phx-upload-ref")       := upload.ref.value,
      dataAttr("phx-active-refs")      := activeRefs,
      dataAttr("phx-done-refs")        := doneRefs,
      dataAttr("phx-preflighted-refs") := preflightedRefs,
      dataAttr("phx-error-refs")       := errorRefs(upload),
      dataPhxAutoUpload                := upload.autoUpload,
      multiple                         := upload.maxEntries > 1,
      mods
    )
  end fileInput

  private def signalFileInput[Msg, R](
    upload: Signal[LiveUpload[R]],
    id: Mod.Attr[Nothing],
    mods: Seq[Mod[Msg]]
  ): HtmlElement[Msg] =
    input(
      id,
      phx.hook                    := "Phoenix.LiveFileUpload",
      typ                         := "file",
      nameAttr                    := upload.map(_.name),
      accept                      := upload.map(_.accept.toHtmlValue),
      dataAttr("phx-upload-ref")  := upload.map(_.ref.value),
      dataAttr("phx-active-refs") := upload.map(
        _.entries.map(_.ref.value).mkString(",")
      ),
      dataAttr("phx-done-refs") := upload.map(
        _.entries
          .filter(_.status == LiveUploadEntryStatus.Completed)
          .map(_.ref.value)
          .mkString(",")
      ),
      dataAttr("phx-preflighted-refs") := upload.map(
        _.entries
          .filter(isPreflighted)
          .map(_.ref.value)
          .mkString(",")
      ),
      dataAttr("phx-error-refs") := upload.map(errorRefs),
      dataPhxAutoUpload          := upload.map(_.autoUpload),
      multiple                   := upload.map(_.maxEntries > 1),
      mods
    )

  /** Returns the errors to show for an upload or one of its entries.
    *
    * This form returns upload-wide errors, such as selecting more files than the definition
    * permits. Entry-specific validation or transfer errors are available from an entry overload.
    */
  def uploadErrors[R](upload: LiveUpload[R]): List[LiveUploadError] = upload.errors

  /** Returns the errors to show for an upload or one of its entries.
    *
    * This form returns the upload-wide errors of a signal of the upload.
    */
  def uploadErrors[R](upload: Signal[LiveUpload[R]]): Signal[List[LiveUploadError]] =
    upload.map(_.errors)

  private def isPreflighted[R](entry: LiveUploadEntry[R]): Boolean = entry.status match
    case LiveUploadEntryStatus.Preflighted | LiveUploadEntryStatus.Uploading(_) |
        LiveUploadEntryStatus.Completed =>
      true
    case LiveUploadEntryStatus.Invalid(errors) =>
      errors.exists {
        case LiveUploadError.WriterFailure(_) => true
        case _                                => false
      }
    case LiveUploadEntryStatus.Selected => false

  private def errorRefs[R](upload: LiveUpload[R]): String =
    val uploadRef = Option.when(upload.errors.nonEmpty)(upload.ref.value).toList
    val entryRefs = upload.entries.filter(_.errors.nonEmpty).map(_.ref.value)
    (uploadRef ++ entryRefs).distinct.mkString(",")

  /** Returns the errors to show for an upload or one of its entries.
    *
    * This form returns the current errors for `entry` as recorded in `upload`, matched by upload
    * entry reference. `Nil` is returned when the reference is absent from this upload snapshot,
    * including after that entry has been removed.
    */
  def uploadErrors[R](upload: LiveUpload[R], entry: LiveUploadEntry[R]): List[LiveUploadError] =
    upload.entries.find(_.ref == entry.ref).map(_.errors).getOrElse(Nil)

  /** Returns the errors to show for an upload or one of its entries.
    *
    * This form returns the validation or transfer errors attached to `entry`. An entry that is not
    * in the invalid state has no errors and returns `Nil`.
    */
  def uploadErrors[R](entry: LiveUploadEntry[R]): List[LiveUploadError] = entry.errors

  /** Returns the errors to show for an upload or one of its entries.
    *
    * This form returns the errors of a signal of an upload entry.
    */
  @targetName("uploadEntryErrorsSignal")
  def uploadErrors[R](entry: Signal[LiveUploadEntry[R]]): Signal[List[LiveUploadError]] =
    entry.map(_.errors)

end Components
