package scaliveapi

import zio.test.*

import scalive.*

object PhoenixNestedParamsAdapterSpec extends ZIOSpecDefault:
  private final case class Item(name: String)
  private final case class Basket(items: Vector[Item])
  private val Root       = FormRoot("basket")
  private val Items      = Root.rows("items")
  private val Name       = Items.text("name").required(FieldIssue("Name is required"))
  private val ItemRows   = Items.product[Item](Tuple1(Name))
  private val Definition = Root.product[Basket](Tuple1(ItemRows))
  private val Adapter    = PhoenixNestedParamsAdapter(Definition, ItemRows)

  def spec = suite("PhoenixNestedParamsAdapterSpec")(
    test("translates indexed rows, persistent ids, sort, drop, and deterministic new rows") {
      val data = FormData(
        Vector(
          "basket[items][0][_persistent_id]" -> "key_a",
          "basket[items][0][name]"           -> "Alpha",
          "basket[items][1][_persistent_id]" -> "key_b",
          "basket[items][1][name]"           -> "Beta",
          Adapter.sortName                    -> "1",
          Adapter.sortName                    -> "new",
          Adapter.sortName                    -> "0",
          Adapter.dropName                    -> "0"
        )
      )
      val first  = Adapter.event(data, FormEventKind.Changed)
      val replay = Adapter.event(data, FormEventKind.Changed)
      val rows   = first.form.rows(ItemRows)

      assertTrue(
        rows.map(_.key.value) == Vector("key_b", "new_0"),
        rows.head.field(Name).rawValues == Vector("Beta"),
        rows(1).field(Name).rawValues.isEmpty,
        first.form.values == replay.form.values,
        first.errors.all.map(_.message) == Vector("Name is required")
      )
    },
    test("rejects missing, malformed, and duplicate persistent ids") {
      val event = Adapter.event(
        FormData(
          Vector(
            "basket[items][0][name]"           -> "Missing",
            "basket[items][1][_persistent_id]" -> "bad key",
            "basket[items][1][name]"           -> "Malformed",
            "basket[items][2][_persistent_id]" -> "same",
            "basket[items][2][_persistent_id]" -> "same",
            Adapter.sortName                    -> "0",
            Adapter.sortName                    -> "1",
            Adapter.sortName                    -> "2"
          )
        ),
        FormEventKind.Changed
      )

      assertTrue(
        event.errors.all.map(_.code).toSet.contains(Some("missing_persistent_id")),
        event.errors.all.map(_.code).toSet.contains(Some("invalid_row_key_character")),
        event.errors.all.map(_.code).toSet.contains(Some("duplicate_persistent_id")),
        event.form.rows(ItemRows).isEmpty
      )
    },
    test("provides compatibility rendering names without changing stable core keys") {
      val key  = FormRowKey.from[Items.type]("stable").toOption.get
      val form = Definition.initial(ItemRows.initial(ItemRows.row(key)(Name.initial("Value"))))
      val row  = form.rows(ItemRows).head

      assertTrue(
        Adapter.persistentIdName(3) == "basket[items][3][_persistent_id]",
        Adapter.fieldName(3, Name) == "basket[items][3][name]",
        Adapter.sortName == "basket[items_sort][]",
        Adapter.dropName == "basket[items_drop][]",
        Adapter.fieldId("basket-form", 3, Name) == "basket-form_items_3_name",
        row.key == key
      )
    },
    test("ignores unknown indexed controls without manufacturing rows") {
      val event = Adapter.event(
        FormData(Vector("basket[items][0][custom]" -> "ignored")),
        FormEventKind.Changed
      )

      assertTrue(event.form.rows(ItemRows).isEmpty, event.form.isValid)
    },
    test("supports explicit deterministic allocation and typed blank-row values") {
      val configured = PhoenixNestedParamsAdapter.configured(Definition, ItemRows) {
        (ordinal, _) =>
          FormRowKey.from[Items.type](s"custom_$ordinal").left.map { error =>
            FieldIssue("allocation failed", Some(error.code))
          }
      } { key =>
        ItemRows.row(key)(Name.initial("Blank"))
      }
      val event = configured.event(
        FormData(Vector(configured.sortName -> "new")),
        FormEventKind.Changed
      )
      val row = event.form.rows(ItemRows).head

      assertTrue(row.key.value == "custom_0", row.field(Name).rawValues == Vector("Blank"))
    },
    test("bounds indexed Phoenix rows, values, and errors") {
      val limitedDefinition = Definition.withLimits(
        FormLimits(maxValuesPerField = 1, maxRowsPerGroup = 1, maxErrors = 2)
      )
      val limited = PhoenixNestedParamsAdapter(limitedDefinition, ItemRows)
      val event = limited.event(
        FormData(
          Vector(
            "basket[items][0][_persistent_id]" -> "key_a",
            "basket[items][0][name]"           -> "first",
            "basket[items][0][name]"           -> "second",
            "basket[items][1][_persistent_id]" -> "key_b",
            "basket[items][1][name]"           -> "ignored",
            limited.sortName                    -> "0",
            limited.sortName                    -> "1"
          )
        ),
        FormEventKind.Changed
      )

      assertTrue(
        event.form.rows(ItemRows).size == 1,
        event.form.rows(ItemRows).head.field(Name).rawValues == Vector("first"),
        event.errors.all.size <= 2,
        event.errors.all.exists(_.code.contains("too_many_values")),
        event.errors.all.exists(_.code.contains("too_many_rows"))
      )
    },
    test("translates indexed recovery targets and unused markers to stable addresses") {
      val target = FormPath("basket", "items", "0", "name")
      val event = Adapter.event(
        FormData(
          Vector(
            "basket[items][0][_persistent_id]" -> "stable",
            "basket[items][0][name]"           -> "Draft",
            "basket[items][0][_unused_name]"   -> "",
            Adapter.sortName                    -> "0"
          )
        ),
        FormEventKind.Recovered,
        Some(target)
      )
      val field = event.form.rows(ItemRows).head.field(Name)

      assertTrue(
        event.kind == FormEventKind.Recovered,
        event.meta.target.contains(field.address),
        event.meta.browserTarget.contains(target),
        !field.isUsed,
        field.visibleErrors.isEmpty
      )
    }
  )
end PhoenixNestedParamsAdapterSpec
