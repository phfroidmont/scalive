package scalive.docs.examples

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}

import zio.*

import scalive.*

// docs:start text-upload-example
final class TextUploadExample extends LiveView[TextUploadExample.Msg, TextUploadExample.Model]:
  import TextUploadExample.*

  def mount(ctx: MountContext): Task[Model] =
    ctx.uploads.allow(TextFiles).map(Model(_))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate | Msg.Progress => refresh(model, ctx.uploads)
    case Msg.Cancel(entry)           =>
      ctx.uploads.cancel(entry).map(upload => model.copy(upload = upload, notice = None))
    case Msg.Summarize => summarizeCompleted(model, ctx.uploads)
    case Msg.Reset     =>
      ctx.uploads.disallow(TextFiles) *>
        ctx.uploads.allow(TextFiles).map(Model(_))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val upload = model.map(_.upload)
    div(
      cls := "docs-text-upload",
      p(
        cls := "docs-example-lede",
        "Choose one small text file. The server validates UTF-8, keeps only aggregate facts, and immediately releases the uploaded bytes."
      ),
      model
        .map(_.notice).option(notice => p(role := "status", cls := "docs-upload-notice", notice)),
      form(
        dataAttr("text-upload-form") := "",
        on.change(_ => Msg.Validate),
        on.submit(Msg.Summarize),
        div(
          cls := "docs-upload-dropzone",
          upload.dropTarget,
          label(forId := upload.map(_.ref.value), "Text file"),
          liveFileInput(
            upload,
            dataAttr("text-upload-input") := "",
            upload.onProgress(Msg.Progress)
          ),
          p(cls := "docs-upload-help", "Accepted: .txt and .md. Maximum: one file, 64 KiB."),
          errorList(uploadErrors(upload)),
          div(
            cls := "docs-upload-entries",
            upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
              articleTag(
                cls := "docs-upload-entry",
                div(
                  strong(entry.map(_.client.fileName)),
                  span(entry.map(entry => formatBytes(entry.client.sizeBytes)))
                ),
                progressTag(
                  value      := entry.map(_.progress.toString),
                  maxAttr    := "100",
                  aria.label := entry.map(entry => s"Upload progress for ${entry.client.fileName}")
                ),
                span(
                  entry.map(entry =>
                    if entry.status == LiveUploadEntryStatus.Completed then "Ready"
                    else s"${entry.progress}%"
                  )
                ),
                button(typ := "button", on.click(entry.map(Msg.Cancel(_))), "Cancel"),
                errorList(uploadErrors(entry))
              )
            }
          )
        ),
        div(
          cls := "docs-upload-actions",
          button(
            typ := "submit",
            submission.replaceTextWith("Summarizing..."),
            "Summarize completed file"
          ),
          button(typ := "button", on.click(Msg.Reset), "Reset upload")
        )
      ),
      div(
        cls := "docs-upload-summaries",
        model.map(_.summaries).splitBy(_.id) { (_, summary) =>
          articleTag(
            dataAttr("upload-summary") := "",
            cls                        := "docs-upload-summary",
            h3(dataAttr("summary-name") := "", summary.map(_.fileName)),
            dl(
              div(
                dt("Size"),
                dd(dataAttr("summary-bytes") := "", summary.map(value => formatBytes(value.bytes)))
              ),
              div(
                dt("Lines"),
                dd(
                  dataAttr("summary-lines") := "",
                  summary.map(value => plural(value.lines, "line"))
                )
              ),
              div(
                dt("Words"),
                dd(
                  dataAttr("summary-words") := "",
                  summary.map(value => plural(value.words, "word"))
                )
              )
            )
          )
        }
      )
    )
  end view

  private def refresh(model: Model, uploads: Uploads): Task[Model] =
    uploads.get(TextFiles).map(_.fold(model)(upload => model.copy(upload = upload, notice = None)))

  private def summarizeCompleted(model: Model, uploads: Uploads): Task[Model] =
    uploads
      .consumeCompleted(TextFiles) { completed =>
        ZIO.succeed(ConsumeDecision.Consume(summarize(completed)))
      }.map { case (results, upload) =>
        val summaries = results.collect { case Right(summary) => summary }
        val rejected  = results.count(_.isLeft)
        val notice    =
          if rejected > 0 then Some("The file was discarded because it was not valid UTF-8 text.")
          else if summaries.isEmpty then
            Some("Finish uploading a valid file before summarizing it.")
          else Some("Summary created. The uploaded bytes were discarded.")
        model.copy(upload = upload, summaries = model.summaries ++ summaries, notice = notice)
      }

  private def summarize(completed: CompletedUpload[Chunk[Byte]]): Either[Unit, Summary] =
    decodeUtf8(completed.result).map { text =>
      val lines = if text.isEmpty then 0 else text.linesIterator.size
      val words = Word.findAllIn(text).size
      Summary(
        id = completed.ref.value,
        fileName = completed.client.fileName,
        bytes = completed.result.length.toLong,
        lines = lines,
        words = words
      )
    }

  private def decodeUtf8(bytes: Chunk[Byte]): Either[Unit, String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(decoder.decode(ByteBuffer.wrap(bytes.toArray)).toString)
    catch case _: java.nio.charset.CharacterCodingException => Left(())

  private def errorList(errors: Signal[List[LiveUploadError]]): HtmlElement[Nothing] =
    div(
      errors.map(_.distinct).splitBy(identity) { (_, error) =>
        p(role := "alert", error.map(uploadErrorMessage))
      }
    )
end TextUploadExample

object TextUploadExample:
  private val MaxFileSize = 64L * 1024L
  private val Word        = raw"\S+".r

  private val TextFiles = LiveUploadDef.inMemory(
    name = "text-file",
    accept = LiveUploadAccept.only(".txt", ".md"),
    maxEntries = 1,
    maxFileSize = MaxFileSize
  )

  final case class Summary(id: String, fileName: String, bytes: Long, lines: Int, words: Int)

  final case class Model(
    upload: LiveUpload[Chunk[Byte]],
    summaries: Vector[Summary] = Vector.empty,
    notice: Option[String] = None)

  enum Msg:
    case Validate
    case Progress
    case Cancel(entry: LiveUploadEntry[Chunk[Byte]])
    case Summarize
    case Reset

  private def uploadErrorMessage(error: LiveUploadError): String = error match
    case LiveUploadError.TooManyFiles => "Choose one file at a time."
    case LiveUploadError.NotAccepted  => "Choose a .txt or .md file."
    case LiveUploadError.TooLarge     => "Choose a file no larger than 64 KiB."
    case _                            => "The upload failed. Remove the file and try again."

  private def formatBytes(bytes: Long): String =
    if bytes < 1024L then s"$bytes B" else f"${bytes.toDouble / 1024.0}%.1f KiB"

  private def plural(value: Int, unit: String): String =
    s"$value $unit${if value == 1 then "" else "s"}"
end TextUploadExample
// docs:end text-upload-example
