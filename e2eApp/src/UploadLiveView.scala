import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

import UploadLiveView.*
import zio.*
import zio.http.URL

import scalive.*
import scalive.codecs.StringAsIsEncoder

class UploadLiveView() extends LiveView.Routed[Msg, Model, Option[String]]:

  private val ariaLabel = htmlAttr("aria-label", StringAsIsEncoder)

  def mount(_params: Option[String], ctx: MountContext) =
    ctx.uploads.allow(uploadDefinition(autoUpload = false)).map(upload => Model(upload = upload))

  override def handleParams(model: Model, params: Option[String], _url: URL, ctx: ParamsContext) =
    val autoUpload = params.contains("1")
    if model.upload.autoUpload == autoUpload then ZIO.succeed(model)
    else
      ctx.uploads.disallow(model.upload.definition) *>
        ctx.uploads.allow(uploadDefinition(autoUpload)).map(upload => model.copy(upload = upload))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate =>
      refreshUpload(model, ctx.uploads)
    case Msg.Progress =>
      refreshUpload(model, ctx.uploads)
    case Msg.CancelUpload(entry) =>
      ctx.uploads.cancel(entry).map(upload => model.copy(upload = upload))
    case Msg.Save =>
      saveCompletedEntries(model, ctx.uploads)

  override def view(model: Signal[Model]) =
    val upload = model.map(_.upload)

    div(
      styleAttr := "padding: 1rem;",
      h1("Uploads"),
      form(
        idAttr := "upload-form",
        on.submit(Msg.Save),
        on.change(_ => Msg.Validate),
        liveFileInput(
          upload,
          upload.onProgress(_ => Msg.Progress)
        ),
        button(
          typ := "submit",
          "Upload"
        ),
        sectionTag(
          upload.dropTarget,
          upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
            articleTag(
              cls := "upload-entry",
              figure(
                figCaption(entry.map(_.client.fileName))
              ),
              progressTag(
                value   := entry.map(_.progress.toString),
                maxAttr := "100",
                entry.map(current => s"${current.progress}%")
              ),
              button(
                typ := "button",
                on.click(entry.map(Msg.CancelUpload.apply)),
                phx.value("ref") := entry.map(_.ref.value),
                ariaLabel        := "cancel",
                "x"
              ),
              uploadErrors(entry)
                .map(_.filterNot(_ == LiveUploadError.TooManyFiles))
                .splitBy(_.toString) { (_, error) =>
                  p(
                    cls := "alert alert-danger",
                    error.map(errorToString)
                  )
                }
            )
          },
          uploadErrors(upload).splitBy(_.toString) { (_, error) =>
            p(
              cls := "alert alert-danger",
              error.map(errorToString)
            )
          }
        ),
        ul(
          model.map(_.uploadedFiles).splitBy(_.storedName) { (_, file) =>
            li(
              a(
                href := file.map(current => downloadUrl(current.storedName)),
                file.map(_.name)
              )
            )
          }
        )
      )
    )
  end view

  private def refreshUpload(model: Model, uploads: Uploads): Task[Model] =
    uploads.get(model.upload.definition).map {
      case Some(upload) => model.copy(upload = upload)
      case None         => model
    }

  private def uploadDefinition(autoUpload: Boolean): LiveUploadDef[Chunk[Byte]] =
    LiveUploadDef.inMemory(
      name = "avatar",
      accept = LiveUploadAccept.only(AcceptedExtensions.head, AcceptedExtensions.tail*),
      maxEntries = MaxEntries,
      maxFileSize = MaxFileSize,
      autoUpload = autoUpload
    )

  private def saveCompletedEntries(model: Model, uploads: Uploads): Task[Model] =
    uploads
      .consumeCompleted(model.upload.definition) { entry =>
        persistUploadedFile(entry.client.fileName, entry.result)
          .map(storedName => UploadedFile(entry.client.fileName, storedName))
          .map(ConsumeDecision.Consume(_))
      }.map { case (persisted, upload) =>
        model.copy(upload = upload, uploadedFiles = model.uploadedFiles ++ persisted)
      }
end UploadLiveView

object UploadLiveView:
  private val MaxEntries                       = 2
  private val MaxFileSize: Long                = 8_000_000L
  private val AcceptedExtensions: List[String] = List(".txt", ".md")
  private val UploadDir: Path                  = Paths.get(sys.props("java.io.tmpdir"), "lvupload")

  enum Msg:
    case Validate
    case Progress
    case CancelUpload(entry: LiveUploadEntry[Chunk[Byte]])
    case Save

  final case class UploadedFile(name: String, storedName: String)

  final case class Model(
    upload: LiveUpload[Chunk[Byte]],
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
