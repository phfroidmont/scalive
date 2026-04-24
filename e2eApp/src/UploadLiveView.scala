import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

import UploadLiveView.*
import zio.*
import zio.http.URL
import zio.http.codec.HttpCodec
import zio.stream.ZStream

import scalive.*
import scalive.codecs.StringAsIsEncoder

class UploadLiveView() extends LiveView[Msg, Model]:

  override val queryCodec: LiveQueryCodec[Option[String]] = ParamsCodec

  private val ariaLabel = htmlAttr("aria-label", StringAsIsEncoder)

  def init =
    LiveContext
      .allowUpload(UploadName, uploadOptions(autoUpload = false))
      .map(upload => Model(upload = upload))
      .catchAll(_ => ZIO.succeed(Model(upload = disconnectedUpload(autoUpload = false))))

  override def handleParams(model: Model, params: Option[String], _url: URL) =
    val autoUpload = params.contains("1")
    if model.upload.autoUpload == autoUpload then ZIO.succeed(model)
    else
      (LiveContext.disallowUpload(UploadName).ignore *>
        LiveContext
          .allowUpload(UploadName, uploadOptions(autoUpload))
          .map(upload => model.copy(upload = upload))).catchAll(_ =>
        ZIO.succeed(model.copy(upload = disconnectedUpload(autoUpload)))
      )

  def update(model: Model) =
    case Msg.Validate =>
      refreshUpload(model)
    case Msg.Progress =>
      refreshUpload(model)
    case Msg.CancelUpload(ref) =>
      LiveContext.cancelUpload(UploadName, ref) *> refreshUpload(model)
    case Msg.Save =>
      saveCompletedEntries(model)

  def view(model: Model) =
    div(
      styleAttr := "padding: 1rem;",
      h1("Uploads"),
      form(
        idAttr := "upload-form",
        phx.onSubmit(Msg.Save),
        phx.onChange(_ => Msg.Validate),
        liveFileInput(
          model.upload,
          phx.onProgress(_ => Msg.Progress)
        ),
        button(
          typ := "submit",
          "Upload"
        ),
        sectionTag(
          phx.dropTarget := model.upload.ref,
          model.upload.entries.splitBy(_.ref) { (_, entry) =>
            articleTag(
              cls := "upload-entry",
              figure(
                figCaption(entry.clientName)
              ),
              progressTag(
                value   := entry.progress.toString,
                maxAttr := "100",
                s"${entry.progress}%"
              ),
              button(
                typ := "button",
                phx.onClick(params => Msg.CancelUpload(params.getOrElse("ref", ""))),
                phx.value("ref") := entry.ref,
                ariaLabel        := "cancel",
                "x"
              ),
              uploadErrors(entry)
                .filterNot(_ == LiveUploadError.TooManyFiles)
                .splitBy(_.toString) { (_, error) =>
                  p(
                    cls := "alert alert-danger",
                    errorToString(error)
                  )
                }
            )
          },
          uploadErrors(model.upload).splitBy(_.toString) { (_, error) =>
            p(
              cls := "alert alert-danger",
              errorToString(error)
            )
          }
        ),
        ul(
          model.uploadedFiles.splitBy(_.storedName) { (_, file) =>
            li(
              a(
                href := downloadUrl(file.storedName),
                file.name
              )
            )
          }
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty

  private def refreshUpload(model: Model): RIO[LiveContext.HasUploads, Model] =
    LiveContext.upload(UploadName).map {
      case Some(upload) => model.copy(upload = upload)
      case None         => model
    }

  private def uploadOptions(autoUpload: Boolean): LiveUploadOptions =
    LiveUploadOptions(
      accept = LiveUploadAccept.Exactly(AcceptedExtensions),
      maxEntries = MaxEntries,
      maxFileSize = MaxFileSize,
      autoUpload = autoUpload
    )

  private def disconnectedUpload(autoUpload: Boolean): LiveUpload =
    val options = uploadOptions(autoUpload)
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

  private def saveCompletedEntries(model: Model): RIO[LiveContext.HasUploads, Model] =
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

  val ParamsCodec: LiveQueryCodec[Option[String]] =
    LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("auto_upload").optional)

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
