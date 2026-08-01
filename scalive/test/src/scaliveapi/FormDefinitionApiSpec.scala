package scaliveapi

import zio.test.*

import scalive.*

object FormDefinitionApiSpec extends ZIOSpecDefault:
  private final case class ProfileData(name: String, email: String)

  private val Profile = FormRoot("profile")
  private val Name    = Profile.requiredString("name")
  private val Email   = Profile.requiredString("email")
  private val Definition = Profile.form(ProfileData.apply)(Name, Email)

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

      assertTrue(
        codecErrors.nonEmpty,
        constructorErrors.nonEmpty,
        fieldErrors.nonEmpty,
        initialErrors.nonEmpty
      )
    }
  )
end FormDefinitionApiSpec
