package guards

import zio.*

import scalive.*

// docs:start guards-note-form
final case class Note(value: String)

object NoteForm:
  val Root       = FormRoot("note")
  val NoteText   = Root.text("value")
  val Definition = Root.product[Note](NoteText)
// docs:end guards-note-form

trait Notes:
  def save(note: Note): Task[Unit]

// docs:start guards-liveview
final class NoteLiveView(notes: Notes) extends LiveView[NoteLiveView.Msg, NoteLiveView.Model]:
  import NoteLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    val form = NoteForm.Definition.initial()
    ZIO.succeed(Model(form, baseline = form.values))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      ZIO.succeed(model.copy(form = event.form))
    case Msg.Save(event) =>
      event.form.result match
        case Right(note) =>
          notes
            .save(note)
            .as(model.copy(form = event.form, baseline = event.form.values))
        case Left(_) =>
          ZIO.succeed(model.copy(form = event.form))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val noteField = model.map(_.form).field(NoteForm.NoteText)

    form(
      idAttr := "note-form",
      navigation.guardWhen(
        dirty = model.map(_.isDirty),
        message = "Discard unsaved changes?"
      ),
      NoteForm.Definition.onChange(Msg.Validate(_)),
      NoteForm.Definition.onSubmit(Msg.Save(_)),
      label(forId := noteField.id, "Note"),
      noteField.text(noteField.validationAttributes),
      button(typ := "submit", "Save")
    )
end NoteLiveView

object NoteLiveView:
  final case class Model(form: NoteForm.Definition.Form, baseline: NoteForm.Definition.Values):
    def isDirty: Boolean = form.values != baseline

  enum Msg:
    case Validate(event: NoteForm.Definition.Event)
    case Save(event: NoteForm.Definition.Event)
// docs:end guards-liveview
