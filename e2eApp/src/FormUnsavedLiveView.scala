import zio.ZIO

import scalive.*

class FormUnsavedLiveView extends LiveView[FormUnsavedLiveView.Msg, FormUnsavedLiveView.Model]:
  import FormUnsavedLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      val note = event.raw.string("note").getOrElse("")
      ZIO.succeed(model.copy(note = note))

  override def view(model: Signal[Model]) =
    val note  = model.map(_.note)
    val dirty = note.map(_.nonEmpty)

    div(
      h1("Unsaved form"),
      link.pushNavigateUnsafe("/form-unsaved/target", "Leave form"),
      form(
        idAttr := "unsaved-form",
        navigation.guardWhen(dirty, "You have unsaved changes. Leave without saving?"),
        on.change.form(FormCodec.formData)(Msg.Validate(_)),
        styleAttr :=
          "margin-top: 1rem; display: flex; flex-direction: column; gap: 0.5rem; max-width: 20rem;",
        label(forId := "unsaved-note", "Unsaved note"),
        input(
          idAttr    := "unsaved-note",
          nameAttr  := "note",
          value     := note,
          styleAttr := "height: 2rem; border: 1px solid #cbd5e1; padding: 0 0.5rem;"
        ),
        p(idAttr := "unsaved-value", "Unsaved value: ", note)
      )
    )
end FormUnsavedLiveView

object FormUnsavedLiveView:
  enum Msg:
    case Validate(event: RawFormEvent[FormData])

  final case class Model(note: String = "")

class FormUnsavedTargetLiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    h1("Unsaved form target")
