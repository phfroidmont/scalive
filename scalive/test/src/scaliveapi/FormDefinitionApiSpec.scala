package scaliveapi

import zio.test.*

import scalive.*

object FormDefinitionApiSpec extends ZIOSpecDefault:
  private val Profile = FormRoot("profile")
  private val Name    = Profile.requiredString("name")
  private val Email   = Profile.requiredString("email")
  private val Definition = Profile.form(Name.codec.zip(Email.codec))

  def spec = suite("FormDefinitionApiSpec")(
    test("constructs an unsubmitted form with typed initial values") {
      val form  = Definition.initial(Name.initial("Alice"))
      val name  = form.field(Name)
      val email = form.field(Email)

      assertTrue(
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

      assertTrue(codecErrors.nonEmpty, fieldErrors.nonEmpty, initialErrors.nonEmpty)
    }
  )
end FormDefinitionApiSpec
