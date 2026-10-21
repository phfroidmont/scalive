package scaliveapi

import zio.test.*

import scalive.*

object PerRecordFormSpec extends ZIOSpecDefault:
  final case class Row(title: String)
  final case class Values(entries: Vector[Row])
  val Root       = FormRoot("history")
  val Group      = Root.rows("entries")
  val Title      = Group.text("title")
  val Entries    = Group.product[Row]((title = Title))
  val Definition = Root.product[Values]((entries = Entries))
  val RowA       = FormRowKey.from[Group.type]("a").toOption.get

  // A definition built for one record has its own schema, so the model keeps it with its form.
  final class Record(val definition: FormDefinition[Root.type, Values])(val form: definition.Form)

  def spec = suite("PerRecordFormSpec")(
    test("a form signal renders controls whatever its definition's schema") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        val records: Signal[Record] = ???
        val forms = records.map(_.form)
        val presence: HtmlElement[Nothing] = forms.presence(Entries, RowA)
        val title = forms.field(Entries, RowA, Title)
      """)

      assertTrue(errors.isEmpty)
    },
    test("a row-bound field still needs the form's own schema") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        val records: Signal[Record] = ???
        val other = Definition.initial(Entries.initial(Entries.row(RowA)(Title.initial("x"))))
        val bound = other.rows(Entries).head.bind(Title)
        records.map(_.form).field(bound)
      """)

      assertTrue(errors.nonEmpty)
    }
  )
end PerRecordFormSpec
