package scalive

import scala.util.Try

import zio.test.*

object FormFeedbackSpec extends ZIOSpecDefault:
  private final case class Profile(name: String)
  private val Root       = FormRoot("profile")
  private val Name       = Root.text("name").required(FieldIssue("Name is required"))
  private val Definition = Root.product[Profile](Tuple1(Name))

  private final case class Item(name: String)
  private final case class Basket(items: Vector[Item])
  private val BasketRoot = FormRoot("basket")
  private val Items      = BasketRoot.rows("items")
  private val ItemName   = Items.text("name").required(FieldIssue("Item name is required"))
  private val ItemRows   = Items.product[Item](Tuple1(ItemName))
  private val BasketDefinition = BasketRoot.product[Basket](Tuple1(ItemRows))
  private val RowA = FormRowKey.from[Items.type]("row_a").toOption.get
  private val RowB = FormRowKey.from[Items.type]("row_b").toOption.get

  private def incoming(
    current: Definition.Form,
    value: String,
    kind: FormEventKind = FormEventKind.Changed,
    blur: Option[String] = None,
    target: Option[String] = None
  ): Definition.AppliedUpdate =
    val event = Definition.event(
      FormData(Vector(Name.name -> value)),
      kind,
      RawFormEvent.Meta(
        target = target.flatMap(FormPath.parse(_).toOption),
        metadata = blur.map(FormUpdate.BlurMetadataKey -> _).toMap
      )
    )
    FormUpdate.fromEvent(event, current.interaction.feedback).applyTo(current)

  private def rowData(keys: Vector[(String, String)]): FormData =
    FormData(keys.flatMap { case (key, value) =>
      Vector(
        s"basket[items][$key][${FormDefinition.RowPresenceName}]" ->
          FormDefinition.RowPresenceValue,
        s"basket[items][$key][name]" -> value
      )
    })

  def spec = suite("FormFeedbackSpec")(
    test("AfterBlur hides changed invalid input until semantic blur") {
      val initial = Definition.initial(Name.initial("valid")).withFeedback(FormFeedback.AfterBlur)
      val changed = incoming(initial, "")
      val blur: Definition.Update = FormUpdate.blurred(Name, FormFeedback.AfterBlur)
      val blurred = blur.applyTo(changed.form)

      assertTrue(
        changed.kind == FormUpdateKind.Changed,
        changed.valuesChanged,
        changed.form.field(Name).visibleErrors.isEmpty,
        blurred.kind == FormUpdateKind.Blurred,
        blurred.target.contains(Name.address),
        blurred.form.field(Name).visibleErrors.nonEmpty,
        blurred.form.result == changed.form.result
      )
    },
    test("incoming changes retain blur history and submit visibility remains sticky") {
      val initial = Definition.initial(Name.initial("valid")).withFeedback(FormFeedback.AfterBlur)
      val blur: Definition.Update = FormUpdate.blurred(Name, FormFeedback.AfterBlur)
      val blurred   = blur.applyTo(initial).form
      val changed   = incoming(blurred, "").form
      val submitted = incoming(changed, "", FormEventKind.Submitted).form
      val recovered = incoming(submitted, "", FormEventKind.Recovered).form
      val changedAfterSubmit = incoming(submitted, "").form

      assertTrue(
        changed.interaction.isBlurred(Name.address),
        changed.field(Name).visibleErrors.nonEmpty,
        submitted.interaction.visibility == ErrorVisibility.All,
        recovered.interaction.visibility == ErrorVisibility.All,
        recovered.field(Name).visibleErrors.nonEmpty,
        changedAfterSubmit.interaction.visibility == ErrorVisibility.All,
        changedAfterSubmit.field(Name).visibleErrors.nonEmpty
      )
    },
    test("recovery preserves blur history for a field absent from the payload") {
      final case class Account(name: String, email: String)
      val root       = FormRoot("account")
      val name       = root.text("name")
      val email      = root.text("email")
      val definition = root.product[Account]((name, email))
      val initial = definition
        .initial(name.initial("Ada"), email.initial("ada@example.com"))
        .withFeedback(FormFeedback.AfterBlur)
      val blurred = FormUpdate
        .blurred(name, FormFeedback.AfterBlur)
        .applyTo(initial)
        .form
      val recovery = definition.event(
        FormData(Vector(email.name -> "new@example.com")),
        FormEventKind.Recovered
      )
      val recovered = FormUpdate
        .fromEvent(recovery, FormFeedback.AfterBlur)
        .applyTo(blurred)
        .form

      assertTrue(recovered.interaction.isBlurred(name.address))
    },
    test("incoming lifecycle updates preserve the ordinary event target") {
      val initial   = Definition.initial(Name.initial("valid"))
      val changed   = incoming(initial, "next", target = Some(Name.name))
      val submitted = incoming(initial, "next", FormEventKind.Submitted, target = Some(Name.name))
      val recovered = incoming(initial, "next", FormEventKind.Recovered, target = Some(Name.name))
      val unknownMarker = incoming(
        initial,
        "next",
        blur = Some("profile[unknown]"),
        target = Some(Name.name)
      )

      assertTrue(
        Vector(changed, submitted, recovered, unknownMarker).forall(
          _.target.contains(Name.address)
        ),
        changed.kind == FormUpdateKind.Changed,
        unknownMarker.kind == FormUpdateKind.Changed
      )
    },
    test("recovery applied to a fresh form starts with fresh feedback history") {
      val fresh = Definition.initial(Name.initial("valid")).withFeedback(FormFeedback.AfterBlur)
      val recovered = incoming(fresh, "", FormEventKind.Recovered)

      assertTrue(
        recovered.form.interaction.visibility == ErrorVisibility.UsedOnly,
        recovered.form.interaction.blurred.isEmpty
      )
    },
    test("pristine and a new mount reset history while programmatic updates retain it") {
      val initial = Definition.initial(Name.initial("valid")).withFeedback(FormFeedback.AfterBlur)
      val blur: Definition.Update = FormUpdate.blurred(Name, FormFeedback.AfterBlur)
      val blurred = blur.applyTo(initial).form
      val updated = blurred.updated(Name, "next")
      val reset   = updated.pristine
      val mounted = Definition.fromValues(updated.values)

      assertTrue(
        updated.interaction.isBlurred(Name.address),
        updated.interaction.feedback == FormFeedback.AfterBlur,
        !reset.interaction.isBlurred(Name.address),
        reset.interaction.feedback == FormFeedback.AfterBlur,
        mounted.interaction == FormInteraction.pristine
      )
    },
    test("browser blur markers are bounded, canonical, existing, and ignored on recovery") {
      val initial = Definition.initial(Name.initial("valid")).withFeedback(FormFeedback.AfterBlur)
      val valid   = incoming(initial, "", blur = Some(Name.name))
      val unknown = incoming(initial, "", blur = Some("profile[unknown]"))
      val malformed = incoming(initial, "", blur = Some("profile[name"))
      val nonCanonical = incoming(initial, "", blur = Some("profile[name][x]"))
      val tooDeep = incoming(initial, "", blur = Some("x" + "[x]" * 40))
      val recovered = incoming(
        initial,
        "",
        FormEventKind.Recovered,
        blur = Some(Name.name)
      )

      assertTrue(
        valid.kind == FormUpdateKind.Blurred,
        valid.target.contains(Name.address),
        Vector(unknown, malformed, nonCanonical, tooDeep).forall { update =>
          update.kind == FormUpdateKind.Changed && update.target.isEmpty
        },
        recovered.kind == FormUpdateKind.Recovered,
        recovered.target.isEmpty
      )
    },
    test("keyed blur follows reordering and is cleared by delete and replacement") {
      val initial = BasketDefinition
        .event(rowData(Vector("row_a" -> "", "row_b" -> "ok")), FormEventKind.Changed).form
        .withFeedback(FormFeedback.AfterBlur)
      val bound = initial.rows(ItemRows).head.bind(ItemName)
      val blur: BasketDefinition.Update =
        FormUpdate.blurred(ItemRows, RowA, ItemName, FormFeedback.AfterBlur)
      val reordered = initial.movedAfter(ItemRows, RowA, RowB)
      val blurred   = blur.applyTo(reordered)
      val removed   = blurred.form.removed(ItemRows, RowA)
      val stale     = blur.applyTo(removed)
      val replaced  = removed.added(ItemRows, RowA)(ItemName.initial(""))

      assertTrue(
        blurred.target.contains(bound.address),
        blurred.form.field(bound).visibleErrors.nonEmpty,
        !removed.interaction.isBlurred(bound.address),
        removed.fieldOption(bound).isEmpty,
        stale.target.isEmpty,
        !replaced.interaction.isBlurred(bound.address)
      )
    },
    test("bound field view factories reject a foreign schema") {
      val OtherRoot       = FormRoot("basket")
      val OtherItems      = OtherRoot.rows("items")
      val OtherItemName   = OtherItems.text("name")
      val OtherItemRows   = OtherItems.product[Item](Tuple1(OtherItemName))
      val OtherDefinition = OtherRoot.product[Basket](Tuple1(OtherItemRows))
      val otherKey        = FormRowKey.from[OtherItems.type]("row_a").toOption.get
      val foreign = OtherDefinition
        .initial(OtherItemRows.initial(OtherItemRows.row(otherKey)(OtherItemName.initial("other"))))
        .rows(OtherItemRows)
        .head
        .bind(OtherItemName)
        .asInstanceOf[BoundFormField[
          BasketRoot.type,
          BasketDefinition.Schema,
          Items.type,
          String,
          String
        ]]
      val current = BasketDefinition.initial(
        ItemRows.initial(ItemRows.row(RowA)(ItemName.initial("current")))
      )

      assertTrue(
        Try(current.field(foreign)).isFailure,
        Try(current.fieldOption(foreign)).isFailure
      )
    },
    test("incoming keyed blur resolves existing rows and row removal clears history") {
      val initialData = rowData(Vector("row_a" -> "", "row_b" -> "ok"))
      val initial = BasketDefinition
        .event(initialData, FormEventKind.Changed).form
        .withFeedback(FormFeedback.AfterBlur)
      val rowAPath = s"basket[items][row_a][name]"
      val existingEvent = BasketDefinition.event(
        initialData,
        FormEventKind.Changed,
        RawFormEvent.Meta(metadata = Map(FormUpdate.BlurMetadataKey -> rowAPath))
      )
      val existing = FormUpdate
        .fromEvent(existingEvent, FormFeedback.AfterBlur)
        .applyTo(initial)
      val withoutRowA = rowData(Vector("row_b" -> "ok"))
      val staleEvent = BasketDefinition.event(
        withoutRowA,
        FormEventKind.Changed,
        RawFormEvent.Meta(metadata = Map(FormUpdate.BlurMetadataKey -> rowAPath))
      )
      val stale = FormUpdate
        .fromEvent(staleEvent, FormFeedback.AfterBlur)
        .applyTo(existing.form)

      assertTrue(
        existing.kind == FormUpdateKind.Blurred,
        existing.target.nonEmpty,
        existing.form.interaction.blurred.contains(existing.target.get),
        stale.kind == FormUpdateKind.Changed,
        stale.target.isEmpty,
        stale.form.interaction.blurred.isEmpty
      )
    },
    test("rejects foreign schemas and preserves structural errors on feedback-only transitions") {
      val OtherRoot       = FormRoot("profile")
      val OtherName       = OtherRoot.text("name")
      val OtherDefinition = OtherRoot.product[Profile](Tuple1(OtherName))
      val current         = Definition.initial(Name.initial("valid"))
      val foreignEvent = OtherDefinition.event(
        FormData(Vector(OtherName.name -> "other")),
        FormEventKind.Changed
      )
      val wrongIncoming = Try {
        FormUpdate
          .fromEvent(foreignEvent, FormFeedback.WhenUsed)
          .asInstanceOf[Definition.Update]
          .applyTo(current)
      }
      val foreignBlur: Definition.Update =
        FormUpdate.blurred(
          OtherName.asInstanceOf[FormField[Root.type, String, String]],
          FormFeedback.AfterBlur
        )
      val wrongBlur = Try(foreignBlur.applyTo(current))

      val malformed = Definition.event(
        FormData(Vector("profile[name" -> "broken")),
        FormEventKind.Changed
      ).form
      val errors = malformed.errors
      val blur: Definition.Update = FormUpdate.blurred(Name, FormFeedback.AfterBlur)
      val transitioned = blur.applyTo(
        malformed.withFeedback(FormFeedback.AfterBlur).withAllErrorsVisible.pristine
      ).form

      assertTrue(
        wrongIncoming.isFailure,
        wrongBlur.isFailure,
        transitioned.errors == errors
      )
    }
  )
end FormFeedbackSpec
