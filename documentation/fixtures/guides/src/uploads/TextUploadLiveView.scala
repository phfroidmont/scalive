// docs:start uploads-liveview
package uploads

import zio.*

import scalive.*

// docs:start uploads-definition
val TextFiles = LiveUploadDef.inMemory(
  name = "text-file",
  accept = LiveUploadAccept.only(".txt", ".md"),
  maxEntries = 1,
  maxFileSize = 64L * 1024L
)
// docs:end uploads-definition

final case class Model(savedSizes: Vector[Int] = Vector.empty)

enum Msg:
  case Validate
  case Save
  case Cancel(entry: LiveUploadEntry[Chunk[Byte]])
  case Reset

final class TextUploadLiveView extends LiveView[Msg, Model]:
  // docs:start uploads-mount
  def mount(ctx: MountContext): Task[Model] =
    ctx.uploads.allow(TextFiles).as(Model())
  // docs:end uploads-mount

  def handleMessage(model: Model, ctx: MessageContext) =
    // docs:start uploads-validate
    case Msg.Validate => ZIO.succeed(model)
    // docs:end uploads-validate
    // docs:start uploads-consume
    case Msg.Save =>
      ctx.uploads
        .consumeCompleted(TextFiles) { completed =>
          ZIO.succeed(ConsumeDecision.Consume(completed.result.length))
        }
        .map(sizes => model.copy(savedSizes = model.savedSizes ++ sizes))
    // docs:end uploads-consume
    // docs:start uploads-cancel
    case Msg.Cancel(entry) =>
      ctx.uploads.cancel(entry).as(model)
    // docs:end uploads-cancel
    // docs:start uploads-reset
    case Msg.Reset =>
      (ctx.uploads.disallow(TextFiles) *> ctx.uploads.allow(TextFiles)).as(Model())
    // docs:end uploads-reset

  // docs:start uploads-render
  def view(model: Signal[Model]): HtmlElement[Msg] =
    form(
      TextFiles.dropTarget,
      on.change(_ => Msg.Validate),
      on.submit(Msg.Save),
      TextFiles.render { upload =>
        div(
          div(
            label(forId := upload.map(_.ref.value), "Text file"),
            liveFileInput(upload),
            errorMessages(uploadErrors(upload))
          ),
          upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
            div(
              span(entry.map(_.client.fileName)),
              progressTag(value := entry.map(_.progress.toString), maxAttr := "100"),
              button(typ        := "button", on.click(entry.map(Msg.Cancel(_))), "Cancel"),
              errorMessages(uploadErrors(entry))
            )
          }
        )
      },
      button(typ := "submit", "Upload and save"),
      button(typ := "button", on.click(Msg.Reset), "Reset")
    )

  private def errorMessages(errors: Signal[List[LiveUploadError]]): HtmlElement[Msg] =
    div(
      errors.map(_.distinct).splitBy(identity) { (_, error) =>
        p(role := "alert", error.map(message))
      }
    )

  private def message(error: LiveUploadError): String = error match
    case LiveUploadError.TooManyFiles => "Choose one file at a time."
    case LiveUploadError.NotAccepted  => "Choose a .txt or .md file."
    case LiveUploadError.TooLarge     => "Choose a file no larger than 64 KiB."
    case _                            => "The upload failed. Remove the file and try again."
  // docs:end uploads-render
end TextUploadLiveView
// docs:end uploads-liveview
