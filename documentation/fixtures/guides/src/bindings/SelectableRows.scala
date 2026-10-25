package bindings

import scalive.*

object SelectableRows:
  final case class Contact(id: Int, name: String)

  final case class Model(contacts: Vector[Contact], selected: Option[Int], unread: Int)

  // docs:start bindings-row-derived-state
  def derivedRows(model: Signal[Model]): HtmlElement[Nothing] =
    val selected = model.map(_.selected)
    ul(
      model.map(_.contacts).splitBy(_.id) { (id, contact) =>
        li(
          cls := selected.map(current => if current.contains(id) then "selected" else ""),
          contact.map(_.name)
        )
      }
    )
  // docs:end bindings-row-derived-state

  // docs:start bindings-row-folded-state
  final case class Entry(contact: Contact, selected: Boolean)

  def foldedRows(model: Signal[Model]): HtmlElement[Nothing] =
    val entries = model.map(page =>
      page.contacts.map(contact => Entry(contact, page.selected.contains(contact.id)))
    )
    ul(
      entries.splitBy(_.contact.id) { (_, entry) =>
        li(
          cls := entry.map(current => if current.selected then "selected" else ""),
          entry.map(_.contact.name)
        )
      }
    )
  // docs:end bindings-row-folded-state
end SelectableRows
