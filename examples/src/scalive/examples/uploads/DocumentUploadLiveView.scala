package scalive.examples.uploads

import zio.*

import scalive.*

final class DocumentUploadLiveView(store: UploadStore)
    extends LiveView[DocumentUploadLiveView.Msg, DocumentUploadLiveView.Model]:
  import DocumentUploadLiveView.*

  def mount(ctx: MountContext) =
    for
      upload  <- ctx.uploads.allow(Upload)
      entries <- store.entries
    yield Model(upload = upload, stored = entries)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate      => refreshUpload(model, ctx.uploads)
    case Msg.Progress      => refreshUpload(model, ctx.uploads)
    case Msg.Cancel(entry) =>
      ctx.uploads.cancel(entry).map(upload => model.copy(upload = upload))
    case Msg.Save              => saveCompleted(model, ctx.uploads)
    case Msg.RetryStore        => saveCompleted(model, ctx.uploads)
    case Msg.Delete(storageId) =>
      store
        .delete(storageId)
        .foldZIO(
          error =>
            ZIO
              .logErrorCause(
                s"UploadStore operation=delete storageId=$storageId",
                Cause.fail(error)
              ).as(model.copy(storeFailure = Some(StoreFailure.Delete))),
          _ => store.entries.map(entries => model.copy(stored = entries, storeFailure = None))
        )
    case Msg.DismissStoreFailure => ZIO.succeed(model.copy(storeFailure = None))

  def render(model: Model) =
    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Uploads"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Document uploader"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "Upload at most two small text documents, follow server-reported progress, consume completed entries, and manage application-owned files."
        )
      ),
      model.storeFailure.map(storeFailureAlert),
      uploader(model.upload),
      storedEntries(model.stored)
    )

  private def uploader(upload: LiveUpload[Chunk[Byte]]): HtmlElement[Msg] =
    form(
      cls := "space-y-6",
      on.change(_ => Msg.Validate),
      on.submit(Msg.Save),
      div(
        cls := "rounded-box border-2 border-dashed border-base-300 bg-base-100 p-6",
        upload.dropTarget,
        liveFileInput(
          upload,
          aria.label := "Documents to upload",
          cls        := "file-input file-input-bordered w-full",
          upload.onProgress(_ => Msg.Progress)
        ),
        p(
          cls := "mt-3 text-sm text-base-content/60",
          "Accepted: .txt and .md. Maximum: 2 files, 1 MiB each."
        ),
        errorList(uploadErrors(upload)),
        div(
          cls := "mt-5 space-y-3",
          upload.entries.splitBy(_.ref) { (_, entry) =>
            articleTag(
              cls := "rounded-box border border-base-300 p-4",
              div(
                cls := "flex flex-wrap items-start justify-between gap-3",
                div(
                  p(cls := "font-medium", entry.client.fileName),
                  p(cls := "text-xs text-base-content/55", formatBytes(entry.client.sizeBytes))
                ),
                button(
                  typ := "button",
                  cls := "btn btn-ghost btn-sm text-error",
                  on.click(Msg.Cancel(entry)),
                  "Cancel"
                )
              ),
              progressTag(
                cls     := "progress progress-primary mt-3 w-full",
                value   := entry.progress.toString,
                maxAttr := "100",
                s"${entry.progress}%"
              ),
              p(
                cls := "mt-1 text-xs text-base-content/55",
                if entry.status == LiveUploadEntryStatus.Completed then "Ready to save"
                else s"${entry.progress}% uploaded"
              ),
              errorList(uploadErrors(entry))
            )
          }
        )
      ),
      button(
        typ := "submit",
        cls := "btn btn-primary",
        submission.replaceTextWith("Saving..."),
        "Upload and save documents"
      )
    )

  private def storeFailureAlert(failure: StoreFailure): HtmlElement[Msg] =
    div(
      cls := "alert alert-error mb-6",
      span("The document store could not complete that operation. Please try again."),
      div(
        cls := "flex gap-2",
        Option.when(failure == StoreFailure.Save)(
          button(
            typ := "button",
            cls := "btn btn-sm",
            on.click(Msg.RetryStore),
            "Retry storage"
          )
        ),
        button(
          typ := "button",
          cls := "btn btn-ghost btn-sm",
          on.click(Msg.DismissStoreFailure),
          "Dismiss"
        )
      )
    )

  private def storedEntries(entries: Vector[UploadStore.Entry]): HtmlElement[Msg] =
    sectionTag(
      cls := "mt-10",
      h2(cls := "text-2xl font-bold tracking-tight", "Stored documents"),
      if entries.isEmpty then
        p(cls := "mt-4 text-base-content/60", "No documents have been stored yet.")
      else
        div(
          cls := "mt-4 space-y-3",
          entries.splitBy(_.storageId) { (_, entry) =>
            articleTag(
              cls := "rounded-box border border-base-300 bg-base-100 p-5 shadow-sm",
              div(
                cls := "flex flex-wrap items-center justify-between gap-4",
                div(
                  h3(cls := "font-semibold", entry.clientName),
                  p(
                    cls := "mt-1 text-sm text-base-content/60",
                    s"${entry.contentType} - ${formatBytes(entry.size)}"
                  ),
                  p(cls := "mt-1 font-mono text-xs text-base-content/45", entry.storageId)
                ),
                button(
                  typ := "button",
                  cls := "btn btn-outline btn-error btn-sm",
                  on.click(Msg.Delete(entry.storageId)),
                  "Delete"
                )
              )
            )
          }
        )
    )

  private def errorList(errors: List[LiveUploadError]): HtmlElement[Nothing] =
    div(
      errors.distinct.map(error => p(cls := "mt-2 text-sm text-error", errorMessage(error)))
    )

  private def refreshUpload(model: Model, uploads: Uploads): LiveIO[Model] =
    uploads.get(Upload).map(_.fold(model)(upload => model.copy(upload = upload)))

  private def saveCompleted(model: Model, uploads: Uploads): LiveIO[Model] =
    uploads
      .consumeCompleted(Upload) { upload =>
        store
          .save(upload).foldZIO(
            error =>
              ZIO
                .logErrorCause(
                  s"UploadStore operation=save uploadRef=${upload.ref.value}",
                  Cause.fail(error)
                ).as(ConsumeDecision.Postpone(false)),
            _ => ZIO.succeed(ConsumeDecision.Consume(true))
          )
      }.flatMap { case (attempts, upload) =>
        store.entries.map(entries =>
          model.copy(
            upload = upload,
            stored = entries,
            storeFailure = Option.when(attempts.contains(false))(StoreFailure.Save)
          )
        )
      }
end DocumentUploadLiveView

object DocumentUploadLiveView:
  val layer = ZLayer.fromFunction(DocumentUploadLiveView.apply)

  private val Upload: LiveUploadDef[Chunk[Byte]] = LiveUploadDef.inMemory(
    name = "documents",
    accept = LiveUploadAccept.only(".txt", ".md"),
    maxEntries = 2,
    maxFileSize = 1024L * 1024L
  )

  enum Msg:
    case Validate
    case Progress
    case Cancel(entry: LiveUploadEntry[Chunk[Byte]])
    case Save
    case RetryStore
    case Delete(storageId: String)
    case DismissStoreFailure

  enum StoreFailure:
    case Save
    case Delete

  final case class Model(
    upload: LiveUpload[Chunk[Byte]],
    stored: Vector[UploadStore.Entry],
    storeFailure: Option[StoreFailure] = None)

  private def errorMessage(error: LiveUploadError): String =
    error match
      case LiveUploadError.TooManyFiles => "Choose at most 2 files."
      case LiveUploadError.NotAccepted  => "Only .txt and .md files are accepted."
      case LiveUploadError.TooLarge     => "Each file must be 1 MiB or smaller."
      case _                            =>
        "The upload could not be completed. Remove the file and try again."

  private def formatBytes(bytes: Long): String =
    if bytes < 1024L then s"$bytes B"
    else f"${bytes.toDouble / 1024.0}%.1f KiB"
end DocumentUploadLiveView
