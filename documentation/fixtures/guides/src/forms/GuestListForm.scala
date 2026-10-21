package forms

import zio.*

import scalive.*

final case class Guest(name: String)
final case class GuestList(guests: Vector[Guest])

object GuestList:
  val Root       = FormRoot("guestList")
  val Guests     = Root.rows("guests")
  val GuestName  = Guests.text("name").map(_.trim)
  val GuestRows  = Guests.product[Guest]((name = GuestName))
  val Definition = Root.product[GuestList]((guests = GuestRows))

  // docs:start forms-per-record-definition
  // The capacity comes from the event being edited, so each event builds its own definition.
  def forEvent(capacity: Int): FormDefinition[Root.type, GuestList] =
    Definition.emap(list =>
      if list.guests.size <= capacity then Right(list)
      else Left(Definition.errors(FieldIssue(s"At most $capacity guests")))
    )

  // Its types belong to that one value, so the model keeps the definition with its form.
  final class Editor(
    val definition: FormDefinition[Root.type, GuestList]
  )(
    val form: definition.Form):
    def changed(data: FormData): Editor =
      Editor(definition)(definition.event(data, FormEventKind.Changed).form)
  // docs:end forms-per-record-definition

final class GuestListLiveView(capacity: Int)
    extends LiveView[GuestListLiveView.Msg, GuestList.Editor]:
  import GuestListLiveView.*

  def mount(ctx: MountContext): Task[GuestList.Editor] =
    val definition = GuestList.forEvent(capacity)
    ZIO.succeed(
      GuestList.Editor(definition)(
        definition.initial(GuestList.GuestRows.initial(GuestList.GuestRows.row(guest("ada"))()))
      )
    )

  def handleMessage(editor: GuestList.Editor, ctx: MessageContext) =
    case Msg.Changed(data) => ZIO.succeed(editor.changed(data))

  // docs:start forms-per-record-render
  def view(model: Signal[GuestList.Editor]): HtmlElement[Msg] =
    val guests = model.map(_.form)
    form(
      idAttr := "guest-list",
      // The binding is built once, so the handler decodes with the event's own definition.
      GuestList.Definition.onChange(event => Msg.Changed(event.data)),
      model.map(_.form.rows(GuestList.GuestRows).map(_.key)).splitBy(identity) { (key, _) =>
        val name = guests.field(GuestList.GuestRows, key, GuestList.GuestName)
        div(guests.presence(GuestList.GuestRows, key), name.text())
      },
      p(idAttr := "guest-errors", model.map(_.form.errors.all.map(_.issue.message).mkString))
    )
  // docs:end forms-per-record-render

object GuestListLiveView:
  def guest(value: String) = FormRowKey.from[GuestList.Guests.type](value).toOption.get

  enum Msg:
    case Changed(data: FormData)
