import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

import zio.*
import zio.stream.ZStream

import scalive.*
import scalive.codecs.StringAsIsEncoder

import UploadLiveView.*

class UploadLiveView(initialAutoUpload: Boolean) extends LiveView[Msg, Model]:

  private val ariaLabel = htmlAttr("aria-label", StringAsIsEncoder)

  def init: RIO[LiveContext, Model] =
    LiveContext
      .allowUpload(UploadName, uploadOptions)
      .map(upload => Model(upload = upload))
      .catchAll(_ => ZIO.succeed(Model(upload = disconnectedUpload)))

  def update(model: Model) =
    case Msg.Validate =>
      refreshUpload(model)
    case Msg.Progress =>
      refreshUpload(model)
    case Msg.CancelUpload(ref) =>
      LiveContext.cancelUpload(UploadName, ref) *> refreshUpload(model)
    case Msg.Save =>
      saveCompletedEntries(model)

  def view(model: Dyn[Model]) =
    div(
      styleAttr := "padding: 1rem;",
      h1("Uploads"),
      form(
        idAttr := "upload-form",
        phx.onSubmit(Msg.Save),
        phx.onChange(_ => Msg.Validate),
        upload.liveFileInput(
          model(_.upload),
          phx.onProgress(_ => Msg.Progress)
        ),
        button(
          typ := "submit",
          "Upload"
        ),
        sectionTag(
          phx.dropTarget := model(_.upload.ref),
          model(_.upload.entries).splitBy(_.ref) { (_, entry) =>
            articleTag(
              cls := "upload-entry",
              figure(
                figCaption(entry(_.clientName))
              ),
              progressTag(
                value   := entry(e => e.progress.toString),
                maxAttr := "100",
                entry(e => s"${e.progress}%")
              ),
              button(
                typ := "button",
                phx.onClick(params => Msg.CancelUpload(params.getOrElse("ref", ""))),
                phx.value("ref") := entry(_.ref),
                ariaLabel        := "cancel",
                "x"
              ),
              upload
                .errors(entry)(_.filterNot(_ == LiveUploadError.TooManyFiles))
                .splitBy(_.toString) { (_, error) =>
                  p(
                    cls := "alert alert-danger",
                    error(errorToString)
                  )
                }
            )
          },
          upload.errors(model(_.upload)).splitBy(_.toString) { (_, error) =>
            p(
              cls := "alert alert-danger",
              error(errorToString)
            )
          }
        ),
        ul(
          model(_.uploadedFiles).splitBy(_.storedName) { (_, file) =>
            li(
              a(
                href := file(f => downloadUrl(f.storedName)),
                file(_.name)
              )
            )
          }
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty

  private def refreshUpload(model: Model): RIO[LiveContext, Model] =
    LiveContext.upload(UploadName).map {
      case Some(upload) => model.copy(upload = upload)
      case None         => model
    }

  private def uploadOptions: LiveUploadOptions =
    LiveUploadOptions(
      accept = LiveUploadAccept.Exactly(AcceptedExtensions),
      maxEntries = MaxEntries,
      maxFileSize = MaxFileSize,
      autoUpload = initialAutoUpload
    )

  private def disconnectedUpload: LiveUpload =
    val options = uploadOptions
    LiveUpload(
      name = UploadName,
      ref = s"$UploadName-upload",
      accept = options.accept,
      maxEntries = options.maxEntries,
      maxFileSize = options.maxFileSize,
      chunkSize = options.chunkSize,
      chunkTimeout = options.chunkTimeout,
      autoUpload = options.autoUpload,
      external = options.external.nonEmpty,
      entries = Nil,
      errors = Nil
    )

  private def saveCompletedEntries(model: Model): RIO[LiveContext, Model] =
    for
      consumed  <- LiveContext.consumeUploadedEntries(UploadName)
      persisted <- ZIO.foreach(consumed) { entry =>
                     persistUploadedFile(entry.name, entry.bytes).map(storedName =>
                       UploadedFile(name = entry.name, storedName = storedName)
                     )
                   }
      refreshed <- LiveContext.upload(UploadName)
    yield model.copy(
      upload = refreshed.getOrElse(model.upload),
      uploadedFiles = model.uploadedFiles ++ persisted
    )
end UploadLiveView

object UploadLiveView:

  private val UploadName                       = "avatar"
  private val MaxEntries                       = 2
  private val MaxFileSize: Long                = 8_000_000L
  private val AcceptedExtensions: List[String] = List(".txt", ".md")
  private val UploadDir: Path                  = Paths.get(sys.props("java.io.tmpdir"), "lvupload")

  enum Msg:
    case Validate
    case Progress
    case CancelUpload(ref: String)
    case Save

  final case class UploadedFile(name: String, storedName: String)

  final case class Model(
    upload: LiveUpload,
    uploadedFiles: List[UploadedFile] = Nil)

  def errorToString(error: LiveUploadError): String =
    error match
      case LiveUploadError.TooManyFiles => "You have selected too many files"
      case LiveUploadError.NotAccepted  => "You have selected an unacceptable file type"
      case LiveUploadError.TooLarge     => "Too large"
      case _                            => "Upload failed"

  def downloadUrl(storedName: String): String =
    val encoded = URLEncoder.encode(storedName, StandardCharsets.UTF_8)
    s"/download?file=$encoded"

  def resolveUploadPath(storedName: String): Option[Path] =
    if storedName.isEmpty || storedName.contains("/") || storedName.contains("\\") || storedName
        .contains("..")
    then None
    else
      val resolved = UploadDir.resolve(storedName).normalize()
      Option.when(resolved.startsWith(UploadDir))(resolved)

  def persistUploadedFile(name: String, bytes: Chunk[Byte]): Task[String] =
    ZIO.attemptBlocking {
      Files.createDirectories(UploadDir)
      val sanitized  = sanitizeFileName(name)
      val storedName = s"${UUID.randomUUID().toString}-$sanitized"
      Files.write(UploadDir.resolve(storedName), bytes.toArray)
      storedName
    }

  private def sanitizeFileName(fileName: String): String =
    val base = fileName.replaceAll("[^A-Za-z0-9._-]", "_")
    if base.isEmpty then "upload.bin" else base
end UploadLiveView
