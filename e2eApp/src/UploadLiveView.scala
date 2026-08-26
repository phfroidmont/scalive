import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

import UploadLiveView.*
import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.codecs.StringAsIsEncoder

class UploadLiveView() extends LiveView.Routed[Msg, Model, QueryParams]:

  private val ariaLabel = htmlAttr("aria-label", StringAsIsEncoder)

  def mount(_params: QueryParams, ctx: MountContext) =
    ctx.uploads.allow(uploadDefinition(UploadMode.Manual)).map(upload => Model(upload = upload))

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    val mode =
      if params.external_upload.isDefined then UploadMode.External
      else if params.auto_upload.isDefined then UploadMode.Auto
      else UploadMode.Manual

    if model.mode == mode then ZIO.succeed(model)
    else
      ctx.uploads.disallow(model.upload.definition) *>
        ctx.uploads
          .allow(uploadDefinition(mode)).map(upload => model.copy(upload = upload, mode = mode))

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
      styleTag(
        """
          |#drop-target {
          |  box-sizing: border-box;
          |  min-height: 260px;
          |  margin: 1rem 0;
          |  padding: 2rem;
          |  border: 4px dashed #475569;
          |  background: #e2e8f0;
          |  transition: background 150ms ease, border-color 150ms ease;
          |}
          |
          |#drop-target.phx-drop-target-active {
          |  border-color: #15803d;
          |  background: #bbf7d0;
          |}
          |
          |#drop-target-inner {
          |  min-height: 140px;
          |  display: flex;
          |  flex-direction: column;
          |  align-items: center;
          |  justify-content: center;
          |  gap: 0.5rem;
          |  border: 2px solid #94a3b8;
          |  background: white;
          |}
          |
          |#drop-target.phx-drop-target-active #drop-target-inner {
          |  border-color: #16a34a;
          |  background: #f0fdf4;
          |}
          |""".stripMargin
      ),
      h1("Uploads"),
      link.pushNavigateUnsafe("/upload?replaced=1", "Replace view"),
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
          idAttr := "drop-target",
          upload.dropTarget,
          div(
            idAttr := "drop-target-inner",
            span("Inner drop target child"),
            span("Drag a file across the outer padding and this inner element.")
          ),
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

  private def uploadDefinition(mode: UploadMode): LiveUploadDef[Chunk[Byte]] =
    mode match
      case UploadMode.External =>
        LiveUploadDef.external(
          name = "avatar",
          accept = LiveUploadAccept.only(AcceptedExtensions.head, AcceptedExtensions.tail*),
          uploader = TestExternalUploader,
          maxEntries = MaxEntries,
          maxFileSize = MaxFileSize,
          autoUpload = true
        )
      case _ =>
        LiveUploadDef.inMemory(
          name = "avatar",
          accept = LiveUploadAccept.only(AcceptedExtensions.head, AcceptedExtensions.tail*),
          maxEntries = MaxEntries,
          maxFileSize = MaxFileSize,
          autoUpload = mode == UploadMode.Auto
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

  private val TestExternalUploader = new LiveUploadExternalUploader[Chunk[Byte]]:
    def preflight(_client: UploadClientMetadata) =
      ZIO.succeed(
        LiveExternalUploadResult.Ready(
          ExternalUploadClientConfig(Json.Obj("uploader" -> Json.Str("TestExternal"))),
          Chunk.empty
        )
      )

  final case class QueryParams(
    auto_upload: Option[String] = None,
    external_upload: Option[String] = None)
      derives Schema

  enum UploadMode:
    case Manual, Auto, External

  enum Msg:
    case Validate
    case Progress
    case CancelUpload(entry: LiveUploadEntry[Chunk[Byte]])
    case Save

  final case class UploadedFile(name: String, storedName: String)

  final case class Model(
    upload: LiveUpload[Chunk[Byte]],
    mode: UploadMode = UploadMode.Manual,
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
