package scaliveapi

import zio.test.*

import scalive.*

object FormDefinitionApiSpec extends ZIOSpecDefault:
  private final case class Profile(name: String, email: Email, tags: Vector[String])
  private final case class Email(value: String)

  private val ProfileRoot = FormRoot("profile")
  private val Name = ProfileRoot.text("name").map(_.trim).required(FieldIssue("Name is required"))
  private val EmailField = ProfileRoot.text("email").emap { value =>
    if value.contains('@') then Right(Email(value))
    else Left(FieldIssues.one(FieldIssue("Email is invalid", Some("invalid_email"))))
  }
  private val Tags = ProfileRoot.texts("tags")
  private val ProfileDefinition = ProfileRoot.product[Profile]((Name, EmailField, Tags))
  private enum ProfileIntent(val wireValue: String):
    case Preview extends ProfileIntent("preview")
    case Save    extends ProfileIntent("save")
  private val ProfileSubmitter =
    ProfileDefinition.submitter(ProfileIntent.values)(_.wireValue)

  private final case class Qualification(title: String, year: String)
  private final case class Application(name: String, qualifications: Vector[Qualification])
  private val ApplicationRoot = FormRoot("application")
  private val ApplicantName   = ApplicationRoot.text("name")
  private val Qualifications  = ApplicationRoot.rows("qualifications")
  private val Title = Qualifications.text("title").required(FieldIssue("Title is required"))
  private val Year = Qualifications.text("year")
  private val QualificationRows = Qualifications.product[Qualification]((Title, Year))
  private val ApplicationDefinition =
    ApplicationRoot.product[Application]((ApplicantName, QualificationRows))
  private val RowA = FormRowKey.from[Qualifications.type]("row_a").toOption.get
  private val RowB = FormRowKey.from[Qualifications.type]("row_b").toOption.get

  def spec = suite("FormDefinitionApiSpec")(
    test("retains editable input after domain refinement and preserves duplicate raw input") {
      val initial = ProfileDefinition.initial(
        Name.initial(" Ada "),
        EmailField.initial("ada@example.com"),
        Tags.initial(Vector("scala", "liveview"))
      )
      val updated = initial.updated(EmailField, "grace@example.com")
      val duplicate = updated.updatedRaw(EmailField, Vector("first", "second"))

      assertTrue(
        initial.valueOption.contains(Profile("Ada", Email("ada@example.com"), Vector("scala", "liveview"))),
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
          Name.name       -> "Ada",
          EmailField.name -> "ada@example.com",
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
      val reordered = form.movedBefore(QualificationRows, RowB, RowA)
      val rowB       = reordered.rows(QualificationRows).head
      val changed    = reordered.updated(rowB.bind(Title), "Symbolic logic")
      val boundBeforeMove = form.rows(QualificationRows).head.bind(Title)
      val changedAfterMove = reordered.updated(boundBeforeMove, "Analysis")
      val replaced = changed
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
    test("projects core row-presence controls and attaches row errors to stable addresses") {
      val data = FormData(
        Vector(
          "application[name]"                                  -> "Ada",
          "application[qualifications][row_b][_scalive_row]"   -> "1",
          "application[qualifications][row_b][title]"          -> "",
          "application[qualifications][row_b][year]"           -> "1843",
          "application[qualifications][row_a][_scalive_row]"   -> "1",
          "application[qualifications][row_a][title]"          -> "Mathematics",
          "application[qualifications][row_a][year]"           -> "1835"
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
            "application[qualifications][orphan][title]"          -> "orphan"
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
            "application[name]"                                  -> "first",
            "application[name]"                                  -> "second",
            "application[name]"                                  -> "third",
            "application[qualifications][row_a][_scalive_row]"   -> "1",
            "application[qualifications][row_a][title]"          -> "",
            "application[qualifications][row_b][_scalive_row]"   -> "1",
            "application[qualifications][row_c][_scalive_row]"   -> "1"
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
      val refined = base.emap { _ =>
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
      val custom = ProfileDefinition.submitter(ProfileIntent.values, "action")(_.wireValue)
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
