package scalive.docs.examples

import zio.{Task, ZIO}

import scalive.*

// docs:start repeated-contacts-form-example
final class RepeatedContactsFormExample
    extends LiveView[RepeatedContactsFormExample.Msg, RepeatedContactsFormExample.Model]:
  import RepeatedContactsFormExample.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model.initial)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      ZIO.succeed(model.copy(form = event.form, saved = None))
    case Msg.Save(event) =>
      ZIO.succeed(model.copy(form = event.form, saved = event.valueOption))
    case Msg.Add =>
      if model.form.rows(Contacts.Rows).size >= MaximumContacts then ZIO.succeed(model)
      else
        val keyNumber = nextAvailableKey(model.form, model.nextKey)
        val key       = Contacts.key(keyNumber)
        ZIO.succeed(
          model.copy(
            form = model.form.added(Contacts.Rows, key)(
              Contacts.Name.initial(""),
              Contacts.Email.initial("")
            ),
            nextKey = keyNumber + 1,
            saved = None
          )
        )
    case Msg.Remove(key) =>
      val rows    = model.form.rows(Contacts.Rows)
      val updated =
        if rows.exists(_.key == key) then model.form.removed(Contacts.Rows, key)
        else model.form
      ZIO.succeed(model.copy(form = updated, saved = None))
    case Msg.MoveUp(key) =>
      val rows  = model.form.rows(Contacts.Rows)
      val index = rows.indexWhere(_.key == key)
      val moved =
        if index > 0 then model.form.movedBefore(Contacts.Rows, key, rows(index - 1).key)
        else model.form
      ZIO.succeed(model.copy(form = moved, saved = None))
    case Msg.MoveDown(key) =>
      val rows  = model.form.rows(Contacts.Rows)
      val index = rows.indexWhere(_.key == key)
      val moved =
        if index >= 0 && index < rows.size - 1 then
          model.form.movedAfter(Contacts.Rows, key, rows(index + 1).key)
        else model.form
      ZIO.succeed(model.copy(form = moved, saved = None))
    case Msg.Reset =>
      ZIO.succeed(Model.initial)
  end handleMessage

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val contactForm = model.map(_.form)
    val rows        = contactForm.rows(Contacts.Rows)

    div(
      cls := "docs-repeated-contacts",
      model.map(_.saved).option { saved =>
        p(
          dataAttr("contacts-saved") := "",
          cls                        := "docs-profile-saved",
          role                       := "status",
          saved.map(value => s"Saved ${value.contacts.size} contacts in the displayed order.")
        )
      },
      form(
        dataAttr("contacts-form") := "",
        idAttr                    := "repeated-contacts-form",
        Contacts.Definition.onChange(Msg.Validate(_)),
        Contacts.Definition.onSubmit(Msg.Save(_)),
        rows.splitBy(_.key.value) { (keyValue, row) =>
          val nameField  = row.map(_.field(Contacts.Name))
          val emailField = row.map(_.field(Contacts.Email))

          fieldSet(
            dataAttr("contact-key") := keyValue,
            cls                     := "docs-contact-row",
            row.presence(),
            legend(row.map(value => s"Contact ${value.key.value}")),
            div(
              cls := "docs-profile-field",
              label(forId := nameField.id, "Name"),
              nameField.text(nameField.validationAttributes),
              nameField.errorFeedback(
                error => error.map(value => messages.getOrElse(value.message, value.message)),
                dataAttr("contact-error") := "name"
              )
            ),
            div(
              cls := "docs-profile-field",
              label(forId := emailField.id, "Email"),
              emailField.email(emailField.validationAttributes),
              emailField.errorFeedback(
                error => error.map(value => messages.getOrElse(value.message, value.message)),
                dataAttr("contact-error") := "email"
              )
            ),
            div(
              cls := "docs-contact-row-actions",
              button(
                typ                 := "button",
                dataAttr("move-up") := "",
                aria.label          := s"Move $keyValue up",
                disabled            := rows.map(_.headOption.exists(_.key.value == keyValue)),
                on.click(row.map(value => Msg.MoveUp(value.key))),
                "Move up"
              ),
              button(
                typ                   := "button",
                dataAttr("move-down") := "",
                aria.label            := s"Move $keyValue down",
                disabled              := rows.map(_.lastOption.exists(_.key.value == keyValue)),
                on.click(row.map(value => Msg.MoveDown(value.key))),
                "Move down"
              ),
              button(
                typ                    := "button",
                dataAttr("remove-row") := "",
                aria.label             := s"Remove $keyValue",
                on.click(row.map(value => Msg.Remove(value.key))),
                "Remove"
              )
            )
          )
        },
        div(
          cls := "docs-profile-actions",
          button(
            typ                     := "button",
            dataAttr("add-contact") := "",
            disabled                := rows.map(_.size >= MaximumContacts),
            on.click(Msg.Add),
            "Add contact"
          ),
          button(typ := "submit", "Save contacts"),
          button(typ := "button", on.click(Msg.Reset), "Reset")
        )
      ),
      sectionTag(
        cls        := "docs-contact-order",
        aria.label := "Current contact identity and order",
        h4("Current order"),
        ol(
          dataAttr("contact-summary") := "",
          rows.splitBy(_.key.value) { (keyValue, row) =>
            li(
              dataAttr("summary-key") := keyValue,
              row.map { value =>
                val name = value.field(Contacts.Name).fieldValue
                s"$keyValue — ${if name.isBlank then "(blank)" else name}"
              }
            )
          }
        )
      )
    )
  end view

end RepeatedContactsFormExample

object RepeatedContactsFormExample:
  final case class Contact(name: String, email: String)
  final case class ContactBook(contacts: Vector[Contact])

  object Contacts:
    val Root  = FormRoot("contact_book")
    val Group = Root.rows("contacts")
    val Name  = Group.text("name").map(_.trim).required(FieldIssue("contact.name.required"))
    val Email = Group
      .text("email")
      .map(_.trim)
      .required(FieldIssue("contact.email.required"))
      .validate(FieldIssue("contact.email.invalid"))(_.contains('@'))
    val Rows       = Group.product[Contact]((Name, Email))
    val Definition = Root.product[ContactBook](Tuple1(Rows))

    def key(number: Long): Group.Key =
      keyFromValue(s"contact-$number")

    def keyFromValue(value: String): Group.Key =
      FormRowKey
        .from[Group.type](value).fold(
          error => throw new IllegalArgumentException(error.code),
          identity
        )

  private val messages = Map(
    "contact.name.required"  -> "Name is required.",
    "contact.email.required" -> "Email is required.",
    "contact.email.invalid"  -> "Enter a valid email address."
  )

  private val MaximumContacts = 20

  private def nextAvailableKey(form: Contacts.Definition.Form, first: Long): Long =
    val used = form.rows(Contacts.Rows).iterator.map(_.key.value).toSet
    Iterator.iterate(first)(_ + 1).find(number => !used(s"contact-$number")).get

  final case class Model(
    form: Contacts.Definition.Form,
    nextKey: Long,
    saved: Option[ContactBook] = None)

  object Model:
    def initial: Model =
      Model(
        Contacts.Definition.initial(
          Contacts.Rows.initial(
            Contacts.Rows.row(Contacts.key(1))(
              Contacts.Name.initial("Ada Lovelace"),
              Contacts.Email.initial("ada@example.com")
            ),
            Contacts.Rows.row(Contacts.key(2))(
              Contacts.Name.initial("Grace Hopper"),
              Contacts.Email.initial("grace@example.com")
            )
          )
        ),
        nextKey = 3
      )

  enum Msg:
    case Validate(event: Contacts.Definition.Event)
    case Save(event: Contacts.Definition.Event)
    case Add
    case Remove(key: Contacts.Group.Key)
    case MoveUp(key: Contacts.Group.Key)
    case MoveDown(key: Contacts.Group.Key)
    case Reset
end RepeatedContactsFormExample
// docs:end repeated-contacts-form-example
