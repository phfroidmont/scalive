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
  private val ProfileDefinition = ProfileRoot.product[Profile]((Name, EmailField, Tags))
  private enum ProfileIntent(val wireValue: String):
    case Preview extends ProfileIntent("preview")
    case Save    extends ProfileIntent("save")
  private val ProfileSubmitter =
    ProfileDefinition.submitter(ProfileIntent.values)(_.wireValue)

  final private case class Qualification(title: String, year: String)
  final private case class Application(name: String, qualifications: Vector[Qualification])
  private val ApplicationRoot = FormRoot("application")
  private val ApplicantName   = ApplicationRoot.text("name")
  private val Qualifications  = ApplicationRoot.rows("qualifications")
  private val Title = Qualifications.text("title").required(FieldIssue("Title is required"))
  private val Year  = Qualifications.text("year")
  private val QualificationRows     = Qualifications.product[Qualification]((Title, Year))
  private val ApplicationDefinition =
    ApplicationRoot.product[Application]((ApplicantName, QualificationRows))
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
      val definition = root.product[Tuple1[Boolean]](Tuple1(enabled))
      val initial    = definition.initial(enabled.initial(false))
      val checked    = initial.updated(enabled, true)
      val unchecked  = checked.updated(enabled, false)
      val absent     = definition.event(FormData.empty, FormEventKind.Submitted).form

      assertTrue(
        initial.valueOption.contains(Tuple1(false)),
        initial.field(enabled).input == Right(false),
        initial.field(enabled).rawValues.isEmpty,
        checked.valueOption.contains(Tuple1(true)),
        checked.field(enabled).input == Right(true),
        checked.field(enabled).rawValues == Vector("true"),
        checked.values == definition.initial(enabled.initial(true)).values,
        unchecked.valueOption.contains(Tuple1(false)),
        unchecked.field(enabled).rawValues.isEmpty,
        unchecked.values == initial.values,
        absent.valueOption.contains(Tuple1(false)),
        absent.field(enabled).rawValues.isEmpty,
        absent.values == initial.values
      )
    },
    test("retains malformed submitted checkbox values and visible structural errors") {
      val root       = FormRoot("settings")
      val enabled    = root.field("enabled", FieldInput.checkbox())
      val definition = root.product[Tuple1[Boolean]](Tuple1(enabled))
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
        val field = event.form.field(enabled)

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
      val rows       = group.product[Tuple1[Boolean]](Tuple1(enabled))
      val definition = root.product[Tuple1[Vector[Tuple1[Boolean]]]](Tuple1(rows))
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
        row.result.contains(Tuple1(false)),
        row.field(enabled).input == Right(false),
        row.field(enabled).rawValues.isEmpty,
        event.form.values == initial.values,
        checked.rows(rows).head.result.contains(Tuple1(true)),
        checked.rows(rows).head.field(enabled).rawValues == Vector("true"),
        unchecked.rows(rows).map(_.key) == Vector(key),
        unchecked.rows(rows).head.result.contains(Tuple1(false)),
        unchecked.rows(rows).head.field(enabled).rawValues.isEmpty,
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
        duplicate.field(EmailField).rawValues == Vector("first", "second"),
        duplicate.field(EmailField).fieldValue == "second",
        duplicate.field(EmailField).input.isLeft,
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
        rowB.field(Title).name == "application[qualifications][row_b][title]",
        changed.valueOption.exists(_.qualifications.head.title == "Symbolic logic"),
        changedAfterMove.valueOption.exists(_.qualifications(1).title == "Analysis"),
        replaced.values != form.values,
        rowB.presence().mods.nonEmpty
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
        val rowSchema = group.product[Row](Tuple1(name))
        val definition = root.product[Application](Tuple1(rowSchema))

        def render(form: Signal[definition.Form]): HtmlElement[Nothing] =
          div(
            form.rows(rowSchema).splitBy(_.key.value) { (_, row) =>
              val field = row.map(_.field(name))
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
        val rows = group.product[Tuple1[Vector[String]]](Tuple1(values))
        val definition = root.product[Tuple1[Vector[Tuple1[Vector[String]]]]](Tuple1(rows))

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
          field: FormFieldView[Owner, Input, Value],
          signal: Signal[FormFieldView[Owner, Input, Value]],
          control: FormControl[Owner, Input, Value, Msg]
        ): Vector[HtmlElement[Msg]] =
          val staticWithMessage = field.tel(
            field.validationAttributes,
            placeholder := "Telephone",
            on.focus.withValue(Msg.Focused.apply)
          )
          val signalWithMessage = signal.date(
            signal.validationAttributes,
            Vector(cls := "date"),
            on.focus.withValue(Msg.Focused.apply)
          )
          val controlWithMessage = control.search(
            Option(placeholder := "Search"),
            on.focus.withValue(Msg.Focused.apply)
          )
          Vector(
            field.tel(),
            field.date(),
            field.search(),
            signal.tel(),
            signal.date(),
            signal.search(),
            control.tel(),
            control.date(),
            control.search(),
            input(typ := "number", control.inputAttributes, control.validationAttributes),
            staticWithMessage,
            signalWithMessage,
            controlWithMessage
          )

        final case class Refined(value: String)
        val root = FormRoot("contact")
        val field = root.text("value").emap { raw =>
          if raw.nonEmpty then Right(Refined(raw))
          else Left(FieldIssues.one(FieldIssue("Required")))
        }
        val definition = root.product[Tuple1[Refined]](Tuple1(field))

        def refined(form: Signal[definition.Form]): Vector[HtmlElement[Msg]] =
          val binding = form.bind[Msg](
            DomRef("contact-form"),
            _ => Msg.Updated,
            _ => Msg.Submitted
          )
          render(
            definition.initial(field.initial("raw")).field(field),
            form.field(field),
            binding.field(field)
          )
      """)

      assertTrue(errors.isEmpty)
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
        rows.head.field(Title).rawValues == Vector(""),
        rows.head.field(Title).visibleErrors.map(_.message) == Vector("Title is required"),
        rows(1).result.contains(Qualification("Mathematics", "1835")),
        event.form.valueOption.isEmpty
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
        root.product[Tuple2[String, String]]((first, again))
      }
      val reserved = scala.util.Try {
        val root = FormRoot("reserved")
        root.product[Tuple1[String]](Tuple1(root.text("_scalive_private")))
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
        event.form.field(ApplicantName).rawValues == Vector("first"),
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
      val base       = root.product[Tuple1[String]](Tuple1(declared))
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
      val attributes = ProfileSubmitter.attributes(ProfileIntent.Save).flattened
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
        attributes == Vector(
          Mod.Attr.Static("type", "submit"),
          Mod.Attr.Static("name", ProfileSubmitter.name),
          Mod.Attr.Static("value", "save")
        ),
        saveButton.tag.name == "button",
        saveButton.attrMods == attributes,
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
    test("checks arbitrary products and ownership at compile time") {
      val sixFieldsCompile = scala.compiletime.testing.typeChecks("""
        import scalive.*
        final case class Six(a: String, b: String, c: String, d: String, e: String, f: String)
        val root = FormRoot("six")
        root.product[Six]((root.text("a"), root.text("b"), root.text("c"), root.text("d"), root.text("e"), root.text("f")))
      """)
      val wrongOrder = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Wrong(name: String, count: Int)
        val root = FormRoot("wrong")
        val name = root.text("name")
        val count = root.text("count").map(_.toInt)
        root.product[Wrong]((count, name))
      """)
      val wrongOwner = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Pair(left: String, right: String)
        val left = FormRoot("left")
        val right = FormRoot("right")
        left.product[Pair]((left.text("value"), right.text("value")))
      """)
      val differentDefinitionValues = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Value(name: String)
        val root = FormRoot("value")
        val name = root.text("name")
        val first = root.product[Value](Tuple1(name))
        val second = root.product[Value](Tuple1(name))
        first.fromValues(second.initial(name.initial("Ada")).values)
      """)
      val differentDefinitionSubmitter = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Value(name: String)
        enum Intent(val wireValue: String):
          case Save extends Intent("save")
        val root = FormRoot("value")
        val name = root.text("name")
        val first = root.product[Value](Tuple1(name))
        val second = root.product[Value](Tuple1(name))
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
          root.text("f01"), root.text("f02"), root.text("f03"), root.text("f04"),
          root.text("f05"), root.text("f06"), root.text("f07"), root.text("f08"),
          root.text("f09"), root.text("f10"), root.text("f11"), root.text("f12"),
          root.text("f13"), root.text("f14"), root.text("f15"), root.text("f16"),
          root.text("f17"), root.text("f18"), root.text("f19"), root.text("f20"),
          root.text("f21"), root.text("f22"), root.text("f23")
        ))
      """)

      assertTrue(
        sixFieldsCompile,
        twentyThreeFieldsCompile,
        wrongOrder.nonEmpty,
        wrongOwner.nonEmpty,
        differentDefinitionValues.nonEmpty,
        differentDefinitionSubmitter.nonEmpty
      )
    }
  )
end FormDefinitionApiSpec
