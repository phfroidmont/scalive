package scaliveapi

import zio.test.*

import scalive.*

object FormDefinitionApiSpec extends ZIOSpecDefault:
  private final case class ProfileData(name: String, email: String, tags: Vector[String])

  private val Profile = FormRoot("profile")
  private val Name    = Profile.requiredString("name")
  private val Email   = Profile.requiredString("email")
  private val Tags    = Profile.strings("tags")
  private val Definition = Profile.form(ProfileData.apply)(Name, Email, Tags)

  def spec = suite("FormDefinitionApiSpec")(
    test("constructs an unsubmitted form with typed initial values") {
      val form  = Definition.initial(Name.initial("Alice"))
      val name  = form.field(Name)
      val email = form.field(Email)
      val typedForm: Definition.Form = form
      val typedField: FormFieldView[String] = name

      assertTrue(
        typedForm eq form,
        typedField eq name,
        form.state.raw.string(Name.path).contains("Alice"),
        form.state.value.isLeft,
        !form.state.submitted,
        form.state.used.isEmpty,
        !name.isUsed,
        !email.isUsed
      )
    },
    test("updates raw field values and revalidates while preserving visibility state") {
      val raw = FormData(
        Vector(
          Name.name                       -> " Alice ",
          "profile[_unused_name]"         -> "",
          Email.name                      -> "alice@example.com",
          Tags.name                       -> "first"
        )
      )
      val form = Definition.from(
        FormState(
          raw = raw,
          value = Definition.codec.decode(raw),
          used = Set(Email.path),
          submitted = false
        )
      )

      val invalid = form.updated(Name, Vector(""))
      val valid   = invalid.updated(Name, Vector("Bob"))

      assertTrue(
        invalid.state.raw.raw == Vector(
          Name.name                       -> "",
          "profile[_unused_name]"         -> "",
          Email.name                      -> "alice@example.com",
          Tags.name                       -> "first"
        ),
        invalid.state.errorsFor(Name.path).map(_.message) == Vector("can't be blank"),
        invalid.state.used == Set(Email.path),
        !invalid.state.submitted,
        invalid.field(Name).visibleErrors.isEmpty,
        valid.state.valueOption.contains(
          ProfileData("Bob", "alice@example.com", Vector("first"))
        ),
        valid.state.used == Set(Email.path),
        !valid.state.submitted,
        valid.field(Name).rawValues == Vector("Bob")
      )
    },
    test("replaces, appends, removes, and clears repeated exact-name values") {
      val raw = FormData(
        Vector(
          Tags.name  -> "one",
          Name.name  -> "Alice",
          Tags.name  -> "two",
          Email.name -> "alice@example.com"
        )
      )
      val form = Definition.from(
        FormState(
          raw = raw,
          value = Definition.codec.decode(raw),
          used = Set(Tags.path),
          submitted = true
        )
      )

      val replaced = form.updated(Tags, Vector("three", "four"))
      val appended = replaced.appended(Tags, "five")
      val removed  = appended.removedAt(Tags, 1)
      val cleared  = removed.updated(Tags, Vector.empty)
      val inserted = cleared.updated(Tags, Iterator.single("six"))

      assertTrue(
        replaced.state.raw.raw == Vector(
          Tags.name  -> "three",
          Tags.name  -> "four",
          Name.name  -> "Alice",
          Email.name -> "alice@example.com"
        ),
        appended.state.raw.raw == Vector(
          Tags.name  -> "three",
          Tags.name  -> "four",
          Name.name  -> "Alice",
          Email.name -> "alice@example.com",
          Tags.name  -> "five"
        ),
        removed.state.raw.raw == Vector(
          Tags.name  -> "three",
          Name.name  -> "Alice",
          Email.name -> "alice@example.com",
          Tags.name  -> "five"
        ),
        removed.state.valueOption.contains(
          ProfileData("Alice", "alice@example.com", Vector("three", "five"))
        ),
        removed.state.used == Set(Tags.path),
        removed.state.submitted,
        cleared.state.raw.raw == Vector(
          Name.name  -> "Alice",
          Email.name -> "alice@example.com"
        ),
        cleared.state.valueOption.contains(
          ProfileData("Alice", "alice@example.com", Vector.empty)
        ),
        inserted.state.raw.raw == Vector(
          Name.name  -> "Alice",
          Email.name -> "alice@example.com",
          Tags.name  -> "six"
        ),
        inserted.state.valueOption.contains(
          ProfileData("Alice", "alice@example.com", Vector("six"))
        )
      )
    },
    test("rejects missing repeated-value indices") {
      val form = Definition.initial(Tags.initial("one"))
      val failures = Vector(
        scala.util.Try(form.removedAt(Tags, -1)).failed.toOption,
        scala.util.Try(form.removedAt(Tags, 1)).failed.toOption,
        scala.util.Try(Definition.initial().removedAt(Tags, 0)).failed.toOption
      )

      assertTrue(
        failures.forall(_.exists(_.isInstanceOf[IndexOutOfBoundsException]))
      )
    },
    test("rejects fields and codecs owned by another root") {
      val codecErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val profile = FormRoot("profile")
        val account = FormRoot("account")
        val name = profile.requiredString("name")
        val email = account.requiredString("email")
        profile.form(name.codec.zip(email.codec))
      """)
      val constructorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val profile = FormRoot("profile")
        val account = FormRoot("account")
        val name = profile.requiredString("name")
        val email = account.requiredString("email")
        profile.form((name: String, email: String) => name -> email)(name, email)
      """)
      val fieldErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val profile = FormRoot("profile")
        val account = FormRoot("account")
        val name = profile.requiredString("name")
        val email = account.requiredString("email")
        val form = profile.form(name.codec).initial()
        form.field(email)
      """)
      val initialErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val profile = FormRoot("profile")
        val account = FormRoot("account")
        val name = profile.requiredString("name")
        val email = account.requiredString("email")
        profile.form(name.codec).initial(email.initial("alice@example.com"))
      """)
      val updateErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val profile = FormRoot("profile")
        val account = FormRoot("account")
        val name = profile.requiredString("name")
        val email = account.requiredString("email")
        val form = profile.form(name.codec).initial()
        form.updated(email, Vector("alice@example.com"))
      """)

      assertTrue(
        codecErrors.nonEmpty,
        constructorErrors.nonEmpty,
        fieldErrors.nonEmpty,
        initialErrors.nonEmpty,
        updateErrors.nonEmpty
      )
    }
  )
end FormDefinitionApiSpec
