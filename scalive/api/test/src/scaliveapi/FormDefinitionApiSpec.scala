package scaliveapi

import zio.test.*

import scalive.*

object FormDefinitionApiSpec extends ZIOSpecDefault:
  final private case class Profile(name: String, email: Email, tags: Vector[String])
  final private case class Email(value: String)

  private val ProfileRoot = FormRoot("profile")
  private val Name = ProfileRoot.text("name").map(_.trim).required(FieldIssue("Name is required"))
  private val EmailField = ProfileRoot.text("email").emap { value =>
    if value.contains('@') then Right(Email(value))
    else Left(FieldIssues.one(FieldIssue("Email is invalid", Some("invalid_email"))))
  }
  private val Tags              = ProfileRoot.texts("tags")
  private val ProfileDefinition =
    ProfileRoot.product[Profile]((name = Name, email = EmailField, tags = Tags))
  private enum ProfileIntent(val wireValue: String):
    case Preview extends ProfileIntent("preview")
    case Save    extends ProfileIntent("save")
  private val ProfileSubmitter =
    ProfileDefinition.submitter(ProfileIntent.values)(_.wireValue)

  final private case class Settings(enabled: Boolean)
  final private case class SettingsRows(options: Vector[Settings])

  final private case class Qualification(title: String, year: String)
  final private case class Application(name: String, qualifications: Vector[Qualification])
  private val ApplicationRoot = FormRoot("application")
  private val ApplicantName   = ApplicationRoot.text("name")
  private val Qualifications  = ApplicationRoot.rows("qualifications")
  private val Title = Qualifications.text("title").required(FieldIssue("Title is required"))
  private val Year  = Qualifications.text("year")
  private val QualificationRows     = Qualifications.product[Qualification]((title = Title, year = Year))
  private val ApplicationDefinition =
    ApplicationRoot.product[Application]((name = ApplicantName, qualifications = QualificationRows))
  private val RowA = FormRowKey.from[Qualifications.type]("row_a").toOption.get
  private val RowB = FormRowKey.from[Qualifications.type]("row_b").toOption.get

  def spec = suite("FormDefinitionApiSpec")(
    test("strictly decodes checkbox values and prioritizes duplicate issues") {
      val codec   = FieldInput.checkbox()
      val invalid = FieldIssues.one(
        FieldIssue("has an invalid checkbox value", Some("invalid_checkbox"))
      )
      val duplicate = FieldIssues.one(
        FieldIssue("must be submitted at most once", Some("duplicate_value"))
      )
      val malformed  = Vector("false", "on", "1", "", "TRUE", " ", " true", "true ")
      val duplicates = Vector(
        Vector("true", "true"),
        Vector("true", "false"),
        Vector("false", "true"),
        Vector("false", "on"),
        Vector("", "", "")
      )

      assertTrue(
        codec.decode(Vector.empty) == Right(false),
        codec.decode(Vector("true")) == Right(true),
        malformed.forall(value => codec.decode(Vector(value)) == Left(invalid)),
        duplicates.forall(raw => codec.decode(raw) == Left(duplicate))
      )
    },
    test("round trips default, custom, and empty checkbox tokens and preserves custom issues") {
      val codecs = Vector(
        "true" -> FieldInput.checkbox(),
        "yes"  -> FieldInput.checkbox("yes"),
        ""     -> FieldInput.checkbox("")
      )
      val invalidIssue   = FieldIssue("Expected yes", Some("expected_yes"))
      val duplicateIssue = FieldIssue("Only one checkbox value", Some("repeated_checkbox"))
      val custom         = FieldInput.checkbox("yes", invalidIssue, duplicateIssue)

      assertTrue(
        codecs.forall { (token, codec) =>
          codec.encode(false) == Vector.empty &&
          codec.encode(true) == Vector(token) &&
          Vector(false, true).forall(value => codec.decode(codec.encode(value)) == Right(value))
        },
        Vector("true", "YES", " yes ", "").forall { value =>
          custom.decode(Vector(value)) == Left(FieldIssues.one(invalidIssue))
        },
        custom.decode(Vector("yes", "no")) == Left(FieldIssues.one(duplicateIssue)),
        FieldInput.checkbox("").decode(Vector("", "")) == Left(
          FieldIssues.one(FieldIssue("must be submitted at most once", Some("duplicate_value")))
        ),
        FieldInput.checkbox("").decode(Vector("true")) == Left(
          FieldIssues.one(FieldIssue("has an invalid checkbox value", Some("invalid_checkbox")))
        )
      )
    },
    test("initializes and updates typed checkbox fields with canonical absent false values") {
      val root       = FormRoot("settings")
      val enabled    = root.field("enabled", FieldInput.checkbox())
      val definition = root.product[Settings]((enabled = enabled))
      val initial    = definition.initial(enabled.initial(false))
      val checked    = initial.updated(enabled, true)
      val unchecked  = checked.updated(enabled, false)
      val absent     = definition.event(FormData.empty, FormEventKind.Submitted).form

      assertTrue(
        initial.valueOption.contains(Settings(false)),
        initial.fieldView(enabled).input == Right(false),
        initial.fieldView(enabled).rawValues.isEmpty,
        checked.valueOption.contains(Settings(true)),
        checked.fieldView(enabled).input == Right(true),
        checked.fieldView(enabled).rawValues == Vector("true"),
        checked.values == definition.initial(enabled.initial(true)).values,
        unchecked.valueOption.contains(Settings(false)),
        unchecked.fieldView(enabled).rawValues.isEmpty,
        unchecked.values == initial.values,
        absent.valueOption.contains(Settings(false)),
        absent.fieldView(enabled).rawValues.isEmpty,
        absent.values == initial.values
      )
    },
    test("retains malformed submitted checkbox values and visible structural errors") {
      val root       = FormRoot("settings")
      val enabled    = root.field("enabled", FieldInput.checkbox())
      val definition = root.product[Settings]((enabled = enabled))
      val cases      = Vector(
        Vector("false") -> FieldIssue("has an invalid checkbox value", Some("invalid_checkbox")),
        Vector("true", "false") -> FieldIssue(
          "must be submitted at most once",
          Some("duplicate_value")
        )
      )

      assertTrue(cases.forall { (raw, issue) =>
        val data  = FormData(raw.map(enabled.name -> _))
        val event = definition.event(data, FormEventKind.Submitted)
        val field = event.form.fieldView(enabled)

        event.data.raw == data.raw &&
        field.rawValues == raw &&
        field.input == Left(FieldIssues.one(issue)) &&
        field.visibleErrors.map(_.issue) == Vector(issue) &&
        event.errors.all == Vector(FormError(enabled.address, issue)) &&
        event.form.valueOption.isEmpty
      })
    },
    test("retains checkbox-only rows with presence markers and typed true-to-false updates") {
      val root       = FormRoot("settings")
      val group      = root.rows("options")
      val enabled    = group.field("enabled", FieldInput.checkbox())
      val rows       = group.product[Settings]((enabled = enabled))
      val definition = root.product[SettingsRows]((options = rows))
      val key        = FormRowKey.from[group.type]("row_a").toOption.get
      val initial    = definition.initial(rows.initial(rows.row(key)(enabled.initial(false))))
      val event      = definition.event(
        FormData(Vector("settings[options][row_a][_scalive_row]" -> "1")),
        FormEventKind.Submitted
      )
      val row       = event.form.rows(rows).head
      val checked   = event.form.updated(row.bind(enabled), true)
      val unchecked = checked.updated(row.bind(enabled), false)

      assertTrue(
        event.errors.all.isEmpty,
        event.form.rows(rows).map(_.key) == Vector(key),
        row.result.contains(Settings(false)),
        row.fieldView(enabled).input == Right(false),
        row.fieldView(enabled).rawValues.isEmpty,
        event.form.values == initial.values,
        checked.rows(rows).head.result.contains(Settings(true)),
        checked.rows(rows).head.fieldView(enabled).rawValues == Vector("true"),
        unchecked.rows(rows).map(_.key) == Vector(key),
        unchecked.rows(rows).head.result.contains(Settings(false)),
        unchecked.rows(rows).head.fieldView(enabled).rawValues.isEmpty,
        unchecked.values == initial.values
      )
    },
    test("retains editable input after domain refinement and preserves duplicate raw input") {
      val initial = ProfileDefinition.initial(
        Name.initial(" Ada "),
        EmailField.initial("ada@example.com"),
        Tags.initial(Vector("scala", "liveview"))
      )
      val updated   = initial.updated(EmailField, "grace@example.com")
      val duplicate = updated.updatedRaw(EmailField, Vector("first", "second"))

      assertTrue(
        initial.valueOption.contains(
          Profile("Ada", Email("ada@example.com"), Vector("scala", "liveview"))
        ),
        updated.valueOption.exists(_.email == Email("grace@example.com")),
        duplicate.fieldView(EmailField).rawValues == Vector("first", "second"),
        duplicate.fieldView(EmailField).fieldValue == "second",
        duplicate.fieldView(EmailField).input.isLeft,
        duplicate.errors.all.map(_.code) == Vector(Some("duplicate_value"))
      )
    },
    test("projects metadata-free values and retains malformed payload diagnostics") {
      val ordinary = FormData(
        Vector(
          Name.name          -> "Ada",
          EmailField.name    -> "ada@example.com",
          "profile[unknown]" -> "ignored"
        )
      )
      val metadata = FormData(
        ordinary.raw ++ Vector(
          "profile[_unused_name]" -> "",
          "unrelated"             -> "ignored"
        )
      )
      val first     = ProfileDefinition.event(ordinary, FormEventKind.Changed)
      val second    = ProfileDefinition.event(metadata, FormEventKind.Changed)
      val malformed = ProfileDefinition.event(
        FormData(ordinary.raw :+ ("profile[name" -> "broken")),
        FormEventKind.Changed
      )

      assertTrue(
        first.form.values == second.form.values,
        first.form.interaction != second.form.interaction,
        malformed.data.raw.last == "profile[name" -> "broken",
        malformed.errors.all.exists(_.code.contains("unterminated_bracket")),
        malformed.form.values == first.form.values
      )
    },
    test("decodes, renders, reorders, and updates stable repeated rows") {
      val form = ApplicationDefinition.initial(
        ApplicantName.initial("Ada"),
        QualificationRows.initial(
          QualificationRows.row(RowA)(Title.initial("Mathematics"), Year.initial("1835")),
          QualificationRows.row(RowB)(Title.initial("Logic"), Year.initial("1843"))
        )
      )
      val reordered        = form.movedBefore(QualificationRows, RowB, RowA)
      val rowB             = reordered.rows(QualificationRows).head
      val changed          = reordered.updated(rowB.bind(Title), "Symbolic logic")
      val boundBeforeMove  = form.rows(QualificationRows).head.bind(Title)
      val changedAfterMove = reordered.updated(boundBeforeMove, "Analysis")
      val replaced         = changed
        .removed(QualificationRows, RowA)
        .added(QualificationRows, RowA)(Title.initial("Replacement"), Year.initial("1850"))

      assertTrue(
        form.valueOption.exists(_.qualifications.map(_.title) == Vector("Mathematics", "Logic")),
        reordered.rows(QualificationRows).map(_.key.value) == Vector("row_b", "row_a"),
        rowB.fieldView(Title).name == "application[qualifications][row_b][title]",
        changed.valueOption.exists(_.qualifications.head.title == "Symbolic logic"),
        changedAfterMove.valueOption.exists(_.qualifications(1).title == "Analysis"),
        replaced.values != form.values
      )
    },
    test("exposes signal-backed field and row rendering outside the scalive package") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        final case class Row(name: String)
        final case class Application(rows: Vector[Row])

        val root = FormRoot("application")
        val group = root.rows("rows")
        val name = group.text("name")
        val rowSchema = group.product[Row]((name = name))
        val definition = root.product[Application]((rows = rowSchema))

        def render(form: Signal[definition.Form]): HtmlElement[Nothing] =
          div(
            form.rows(rowSchema).splitBy(_.key) { (key, row) =>
              val field = form.field(rowSchema, key, name)
              div(
                row.presence(),
                label(forId := field.id, "Name"),
                dataAttr("field-name") := field.name,
                dataAttr("field-error-id") := field.errorId,
                dataAttr("field-invalid") := field.hasVisibleErrors.map(_.toString),
                field.text(field.validationAttributes),
                field.email(),
                field.password(),
                field.hidden(),
                field.checkbox(),
                field.checkbox("yes"),
                field.textarea(),
                field.select(Vector("first" -> "First")),
                field.item("first").checkbox("first"),
                field.errorFeedback(error => error.map(_.message))
              )
            }
          )
      """)

      assertTrue(errors.isEmpty)
    },
    test("exposes bound checkbox items with generic fields, signal tokens, and row bindings") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Focused(value: String)
          case Updated, Submitted

        def render[Owner, Input, Value](
          control: FormControl[Owner, Input, Value, Msg],
          token: Signal[String]
        ): HtmlElement[Msg] =
          val first: FormControlItem[Msg] = control.item("stable-key")
          val second: FormControlItem[Msg] = control.item("")
          val id: Signal[String] = first.id
          val name: Signal[String] = first.name
          div(
            label(forId := id, "First"),
            dataAttr("field-name") := name,
            first.checkbox("submitted-token",
              control.validationAttributes,
              Vector(cls := "choice"),
              Option(title := "First choice"),
              on.focus.withValue(Msg.Focused.apply)
            ),
            second.checkbox(token,
              control.validationAttributes,
              Vector(dataAttr("choice") := "second"),
              Option(title := "Second choice"),
              on.focus.withValue(Msg.Focused.apply)
            ),
            control.errorFeedback(_.map(_.message))
          )

        val root = FormRoot("choices")
        val group = root.rows("rows")
        val values = group.texts("values")
        final case class Choice(values: Vector[String])
        final case class Choices(rows: Vector[Choice])
        val rows = group.product[Choice]((values = values))
        val definition = root.product[Choices]((rows = rows))

        def repeated(source: Signal[definition.Form]): HtmlElement[Msg] =
          val binding = source.bind[Msg](DomRef("choices"), _ => Msg.Updated, _ => Msg.Submitted)
          binding.render(
            binding.rows(rows).splitBy(_.key) { (key, row) =>
              val control = binding.field(rows, key, values)
              div(row.presence(), render(control, row.map(_.key.value)))
            }
          )
      """)

      assertTrue(errors.isEmpty)
    },
    test("checkbox items require an explicit token") {
      val missingToken = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def render(item: FormControlItem[Nothing]) = item.checkbox()
      """)
      assertTrue(missingToken.nonEmpty)
    },
    test("exposes tel, date, and search helpers with generic input and refined values") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Focused(value: String)
          case Updated, Submitted

        def render[Owner, Input, Value](
          unbound: FormControl[Owner, Input, Value, Nothing],
          control: FormControl[Owner, Input, Value, Nothing]
        ): Vector[HtmlElement[Msg]] =
          val unboundWithMessage = unbound.tel(
            unbound.validationAttributes,
            placeholder := "Telephone",
            on.focus.withValue(Msg.Focused.apply)
          )
          val blurWithMessage: FormControl[Owner, Input, Value, Msg] =
            unbound.onBlur(Msg.Focused(_))
          val controlWithMessage = control.search(
            Option(placeholder := "Search"),
            on.focus.withValue(Msg.Focused.apply)
          )
          Vector(
            unbound.tel(),
            unbound.date(),
            unbound.search(),
            blurWithMessage.date(Vector(cls := "date")),
            control.tel(),
            control.date(),
            control.search(),
            input(typ := "number", control.inputAttributes, control.validationAttributes),
            unboundWithMessage,
            controlWithMessage
          )

        final case class Refined(value: String)
        val root = FormRoot("contact")
        val field = root.text("value").emap { raw =>
          if raw.nonEmpty then Right(Refined(raw))
          else Left(FieldIssues.one(FieldIssue("Required")))
        }
        final case class Contact(value: Refined)
        val definition = root.product[Contact]((value = field))

        def refined(form: Signal[definition.Form]): Vector[HtmlElement[Msg]] =
          val binding = form.bind[Msg](
            DomRef("contact-form"),
            _ => Msg.Updated,
            _ => Msg.Submitted
          )
          render(form.field(field), binding.field(field))
      """)

      assertTrue(errors.isEmpty)
    },
    test("field returns a control on a form value and a form signal, and fieldView the data") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        final case class Phone(number: String)
        final case class Contact(name: String, phones: Vector[Phone])
        val root = FormRoot("contact")
        val name = root.text("name")
        val group = root.rows("phones")
        val number = group.text("number")
        val rows = group.product[Phone]((number = number))
        val definition = root.product[Contact]((name = name, phones = rows))

        def render[Owner](control: FormControl[Owner, String, String, Nothing]): HtmlElement[Nothing] =
          div(control.text(control.validationAttributes), control.errorFeedback(_.map(_.message)))

        def static(form: definition.Form, key: FormRowKey[group.type]): Vector[HtmlElement[Nothing]] =
          val bound = form.rows(rows).head.bind(number)
          Vector(
            render(form.field(name)),
            render(form.field(bound)),
            render(form.field(rows, key, number))
          )

        def live(
          form: Signal[definition.Form],
          bound: BoundFormField[root.type, definition.Schema, group.type, String, String],
          key: FormRowKey[group.type]
        ): Vector[HtmlElement[Nothing]] =
          Vector(
            render(form.field(name)),
            render(form.field(bound)),
            render(form.field(rows, key, number))
          )

        def views(form: definition.Form): Vector[FormFieldView[root.type, String, String]] =
          val row = form.rows(rows).head
          Vector(form.fieldView(name), form.fieldView(row.bind(number)), row.fieldView(number)) ++
            form.fieldViewOption(row.bind(number))
      """)
      val readOnlyField = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        final case class Profile(name: String)
        val root = FormRoot("profile")
        val name = root.text("name")
        val definition = root.product[Profile]((name = name))

        def view(form: definition.Form): FormFieldView[root.type, String, String] =
          form.field(name)
      """)

      assertTrue(errors.isEmpty, readOnlyField.nonEmpty)
    },
    test("projects core row-presence controls and attaches row errors to stable addresses") {
      val data = FormData(
        Vector(
          "application[name]"                                -> "Ada",
          "application[qualifications][row_b][_scalive_row]" -> "1",
          "application[qualifications][row_b][title]"        -> "",
          "application[qualifications][row_b][year]"         -> "1843",
          "application[qualifications][row_a][_scalive_row]" -> "1",
          "application[qualifications][row_a][title]"        -> "Mathematics",
          "application[qualifications][row_a][year]"         -> "1835"
        )
      )
      val event = ApplicationDefinition.event(data, FormEventKind.Submitted)
      val rows  = event.form.rows(QualificationRows)

      assertTrue(
        rows.map(_.key.value) == Vector("row_b", "row_a"),
        rows.head.fieldView(Title).rawValues == Vector(""),
        rows.head.fieldView(Title).visibleErrors.map(_.message) == Vector("Title is required"),
        rows(1).result.contains(Qualification("Mathematics", "1835")),
        event.form.valueOption.isEmpty
      )
    },
    test("field issues cannot be built empty") {
      val first  = FieldIssue("Too short", Some("too_short"))
      val second = FieldIssue("Needs a digit", Some("digit"))
      val emptyCollection = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val issues = FieldIssues(Vector.empty[FieldIssue])
      """)

      assertTrue(
        FieldIssues(first, second).all == Vector(first, second),
        FieldIssues.from(Vector(first, second)).map(_.all) == Some(Vector(first, second)),
        FieldIssues.from(Vector.empty).isEmpty,
        emptyCollection.nonEmpty
      )
    },
    test("row keys use an ASCII letter, digit, underscore, and hyphen grammar") {
      val accepted = FormRowKey.from[Unit]("Row_42-a")
      val rejected = Vector("é", "名", "٣", "a b").map(FormRowKey.from[Unit](_))

      assertTrue(
        accepted.map(_.value) == Right("Row_42-a"),
        rejected.forall(_.left.exists(_.isInstanceOf[FormRowKey.Error.InvalidCharacter]))
      )
    },
    test("rejects invalid row structure, duplicate schema paths, and reserved names") {
      val invalidRows = ApplicationDefinition.event(
        FormData(
          Vector(
            "application[qualifications][bad key][_scalive_row]" -> "0",
            "application[qualifications][orphan][title]"         -> "orphan"
          )
        ),
        FormEventKind.Changed
      )
      val duplicate = scala.util.Try {
        val root  = FormRoot("duplicate")
        val first = root.text("name")
        val again = root.text("name")
        final case class Names(first: String, again: String)
        root.product[Names]((first = first, again = again))
      }
      val reserved = scala.util.Try {
        val root = FormRoot("reserved")
        final case class Reserved(value: String)
        root.product[Reserved]((value = root.text("_scalive_private")))
      }

      assertTrue(
        invalidRows.errors.all.map(_.code).toSet.contains(Some("invalid_row_key_character")),
        invalidRows.errors.all.map(_.code).toSet.contains(Some("missing_row_presence")),
        duplicate.isFailure,
        reserved.isFailure
      )
    },
    test("excludes invalid presence metadata and rejects incompatible declared paths") {
      val invalidPresence = ApplicationDefinition.event(
        FormData(
          Vector(
            "application[qualifications][row_a][_scalive_row]" -> "0",
            "application[qualifications][row_a][title]"        -> "Invalid marker",
            "application[qualifications][row_b][_scalive_row]" -> "1",
            "application[qualifications][row_b][_scalive_row]" -> "1",
            "application[qualifications][row_b][title]"        -> "Duplicate marker",
            "application[name][]"                              -> "array"
          )
        ),
        FormEventKind.Changed
      )
      val empty = ApplicationDefinition.initial()

      assertTrue(
        invalidPresence.form.rows(QualificationRows).isEmpty,
        invalidPresence.form.values == empty.values,
        invalidPresence.errors.all.map(_.code).contains(Some("invalid_row_presence")),
        invalidPresence.errors.all.map(_.code).contains(Some("duplicate_row")),
        invalidPresence.errors.all.map(_.code).contains(Some("invalid_field_path"))
      )
    },
    test("bounds projected rows, values, and validation errors") {
      val limited = ApplicationDefinition.withLimits(
        FormLimits(maxValuesPerField = 1, maxRowsPerGroup = 1, maxErrors = 3)
      )
      val event = limited.event(
        FormData(
          Vector(
            "application[name]"                                -> "first",
            "application[name]"                                -> "second",
            "application[name]"                                -> "third",
            "application[qualifications][row_a][_scalive_row]" -> "1",
            "application[qualifications][row_a][title]"        -> "",
            "application[qualifications][row_b][_scalive_row]" -> "1",
            "application[qualifications][row_c][_scalive_row]" -> "1"
          )
        ),
        FormEventKind.Changed
      )

      assertTrue(
        event.form.fieldView(ApplicantName).rawValues == Vector("first"),
        event.form.rows(QualificationRows).map(_.key.value) == Vector("row_a"),
        event.errors.all.size <= 3,
        event.errors.all.count(_.code.contains("too_many_values")) == 1,
        event.errors.all.count(_.code.contains("too_many_rows")) == 1
      )
    },
    test("normalizes product errors that target undeclared addresses") {
      val root       = FormRoot("product")
      val declared   = root.text("declared")
      val undeclared = root.text("undeclared")
      final case class Declared(declared: String)
      val base       = root.product[Declared]((declared = declared))
      val refined    = base.emap { _ =>
        Left(FormErrors.one(undeclared.address, FieldIssue("wrong address")))
      }
      val form = refined.initial(declared.initial("value"))

      assertTrue(
        form.errors.all.map(_.address) == Vector(refined.address),
        form.errors.all.map(_.code) == Vector(Some("undeclared_error_address"))
      )
    },
    test("renders and strictly decodes definition-owned submit actions") {
      val validData = FormData(
        Vector(
          Name.name             -> "Ada",
          EmailField.name       -> "ada@example.com",
          ProfileSubmitter.name -> "preview"
        )
      )
      val event      = ProfileDefinition.event(validData, FormEventKind.Submitted)
      val attributes = ProfileSubmitter.attributes(ProfileIntent.Save)
      val saveButton = ProfileSubmitter.button(ProfileIntent.Save)("Save")

      assertTrue(
        ProfileSubmitter.name == "profile[_scalive_submitter]",
        ProfileSubmitter.raw(ProfileIntent.Preview) ==
          RawFormSubmitter("profile[_scalive_submitter]", "preview"),
        ProfileSubmitter.decode(validData) == Right(ProfileIntent.Preview),
        ProfileSubmitter.decode(FormData.empty) ==
          Left(FormSubmitter.DecodeError.Missing(ProfileSubmitter.name)),
        ProfileSubmitter.decode(FormData(Vector(ProfileSubmitter.name -> "publish"))) ==
          Left(FormSubmitter.DecodeError.Unknown(ProfileSubmitter.name, "publish")),
        ProfileSubmitter.decode(
          FormData(Vector(ProfileSubmitter.name -> "save", ProfileSubmitter.name -> "preview"))
        ) == Left(
          FormSubmitter.DecodeError.Duplicate(
            ProfileSubmitter.name,
            Vector("save", "preview")
          )
        ),
        saveButton.tag.name == "button",
        saveButton.mods.headOption.contains(attributes),
        event.form.valueOption.contains(Profile("Ada", Email("ada@example.com"), Vector.empty))
      )
    },
    test("validates custom submitter names and finite enum mappings") {
      val custom      = ProfileDefinition.submitter(ProfileIntent.values, "action")(_.wireValue)
      val overlapping = scala.util.Try(
        ProfileDefinition.submitter(ProfileIntent.values, Name.name)(_.wireValue)
      )
      val invalidName = scala.util.Try(
        ProfileDefinition.submitter(ProfileIntent.values, "profile[action")(_.wireValue)
      )
      val reservedName = scala.util.Try(
        ProfileDefinition.submitter(ProfileIntent.values, "profile[_scalive_action]")(_.wireValue)
      )
      val unusedName = scala.util.Try(
        ProfileDefinition.submitter(ProfileIntent.values, "profile[_unused_name]")(_.wireValue)
      )
      val csrfName = scala.util.Try(
        ProfileDefinition.submitter(ProfileIntent.values, "_csrf_token")(_.wireValue)
      )
      val duplicateWireValue = scala.util.Try(
        ProfileDefinition.submitter(ProfileIntent.values)(_ => "same")
      )
      val emptyWireValue = scala.util.Try(
        ProfileDefinition.submitter(ProfileIntent.values)(_ => "")
      )
      val noActions = scala.util.Try(
        ProfileDefinition.submitter(Vector.empty[ProfileIntent])(_.wireValue)
      )

      assertTrue(
        custom.name == "action",
        overlapping.isFailure,
        invalidName.isFailure,
        reservedName.isFailure,
        unusedName.isFailure,
        csrfName.isFailure,
        duplicateWireValue.isFailure,
        emptyWireValue.isFailure,
        noActions.isFailure
      )
    },
    test("checks product names, values, and ownership at compile time") {
      val sixFieldsCompile = scala.compiletime.testing.typeChecks("""
        import scalive.*
        final case class Six(a: String, b: String, c: String, d: String, e: String, f: String)
        val root = FormRoot("six")
        root.product[Six]((
          a = root.text("a"), b = root.text("b"), c = root.text("c"),
          d = root.text("d"), e = root.text("e"), f = root.text("f")
        ))
      """)
      val swappedSameTypes = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Person(first: String, last: String)
        val root = FormRoot("person")
        val first = root.text("first")
        val last = root.text("last")
        root.product[Person]((last = last, first = first))
      """)
      val wrongName = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Person(first: String, last: String)
        val root = FormRoot("person")
        root.product[Person]((first = root.text("first"), surname = root.text("last")))
      """)
      val wrongType = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Wrong(name: String, count: Int)
        val root = FormRoot("wrong")
        val name = root.text("name")
        val count = root.text("count").map(_.toInt)
        root.product[Wrong]((name = count, count = name))
      """)
      val positional = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Person(first: String, last: String)
        val root = FormRoot("person")
        root.product[Person]((root.text("first"), root.text("last")))
      """)
      val wrongOwner = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Pair(left: String, right: String)
        val left = FormRoot("left")
        val right = FormRoot("right")
        left.product[Pair]((left = left.text("value"), right = right.text("value")))
      """)
      val differentDefinitionValues = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Value(name: String)
        val root = FormRoot("value")
        val name = root.text("name")
        val first = root.product[Value]((name = name))
        val second = root.product[Value]((name = name))
        first.fromValues(second.initial(name.initial("Ada")).values)
      """)
      val differentDefinitionSubmitter = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Value(name: String)
        enum Intent(val wireValue: String):
          case Save extends Intent("save")
        val root = FormRoot("value")
        val name = root.text("name")
        val first = root.product[Value]((name = name))
        val second = root.product[Value]((name = name))
        val submitter = first.submitter(Intent.values)(_.wireValue)
        second.onSubmit(submitter)((_, _) => ())
      """)
      val twentyThreeFieldsCompile = scala.compiletime.testing.typeChecks("""
        import scalive.*
        final case class Large(
          f01: String, f02: String, f03: String, f04: String, f05: String,
          f06: String, f07: String, f08: String, f09: String, f10: String,
          f11: String, f12: String, f13: String, f14: String, f15: String,
          f16: String, f17: String, f18: String, f19: String, f20: String,
          f21: String, f22: String, f23: String
        )
        val root = FormRoot("large")
        root.product[Large]((
          f01 = root.text("f01"), f02 = root.text("f02"), f03 = root.text("f03"),
          f04 = root.text("f04"), f05 = root.text("f05"), f06 = root.text("f06"),
          f07 = root.text("f07"), f08 = root.text("f08"), f09 = root.text("f09"),
          f10 = root.text("f10"), f11 = root.text("f11"), f12 = root.text("f12"),
          f13 = root.text("f13"), f14 = root.text("f14"), f15 = root.text("f15"),
          f16 = root.text("f16"), f17 = root.text("f17"), f18 = root.text("f18"),
          f19 = root.text("f19"), f20 = root.text("f20"), f21 = root.text("f21"),
          f22 = root.text("f22"), f23 = root.text("f23")
        ))
      """)

      val singleFieldCompile = scala.compiletime.testing.typeChecks("""
        import scalive.*
        final case class Note(text: String)
        final case class Tag(name: String)
        final case class Tags(values: Vector[Tag])
        val noteRoot = FormRoot("note")
        val note = noteRoot.product[Note]((text = noteRoot.text("text")))
        val tagsRoot = FormRoot("tags")
        val group = tagsRoot.rows("values")
        val rows = group.product[Tag]((name = group.text("name")))
        val tags = tagsRoot.product[Tags]((values = rows))
      """)
      val singleFieldWrongType = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Count(value: Int)
        val root = FormRoot("count")
        root.product[Count]((value = root.text("value")))
      """)
      val singleFieldWrongArity = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Pair(left: String, right: String)
        val root = FormRoot("pair")
        root.product[Pair]((left = root.text("left")))
      """)
      val singleFieldWrongOwner = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Note(text: String)
        val left = FormRoot("left")
        val right = FormRoot("right")
        left.product[Note]((text = right.text("text")))
      """)
      val rowWrongName = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Phone(label: String, number: String)
        val root = FormRoot("contact")
        val phones = root.rows("phones")
        phones.product[Phone]((number = phones.text("number"), label = phones.text("label")))
      """)

      def mentions(errors: List[scala.compiletime.testing.Error], text: String) =
        errors.exists(_.message.contains(text))

      assertTrue(
        sixFieldsCompile,
        twentyThreeFieldsCompile,
        singleFieldCompile,
        mentions(swappedSameTypes, "do not match the fields of Person"),
        mentions(wrongName, "do not match the fields of Person"),
        mentions(wrongType, "do not match the field types of Wrong"),
        positional.nonEmpty,
        wrongOwner.nonEmpty,
        singleFieldWrongType.nonEmpty,
        singleFieldWrongArity.nonEmpty,
        singleFieldWrongOwner.nonEmpty,
        mentions(rowWrongName, "do not match the fields of Phone"),
        differentDefinitionValues.nonEmpty,
        differentDefinitionSubmitter.nonEmpty
      )
    }
  )
end FormDefinitionApiSpec
