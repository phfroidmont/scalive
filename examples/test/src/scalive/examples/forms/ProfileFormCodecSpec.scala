package scalive.examples.forms

import zio.test.*

import scalive.*

object ProfileFormCodecSpec extends ZIOSpecDefault:

  private def formData(name: String, email: String, biography: String): FormData =
    FormData(
      Vector(
        Profile.Name.name      -> name,
        Profile.Email.name     -> email,
        Profile.Biography.name -> biography
      )
    )

  def spec = suite("ProfileFormCodecSpec")(
    test("rejects blank fields with path-specific messages") {
      val result = Profile.Definition.codec.decode(formData(" ", "", "\t"))

      assertTrue(
        result == Left(
          FormErrors(
            Vector(
              FormError(Profile.Name.path, "Name is required."),
              FormError(Profile.Email.path, "Email is required."),
              FormError(Profile.Biography.path, "Biography is required.")
            )
          )
        )
      )
    },
    test("rejects a malformed email address") {
      val result = Profile.Definition.codec.decode(
        formData("Ada Lovelace", "ada.example.com", "Analytical engine pioneer.")
      )

      assertTrue(
        result == Left(FormErrors.one(Profile.Email.path, "Enter a valid email address."))
      )
    },
    test("rejects a biography longer than 500 characters") {
      val result = Profile.Definition.codec.decode(
        formData("Ada Lovelace", "ada@example.com", "a" * 501)
      )

      assertTrue(
        result == Left(
          FormErrors.one(Profile.Biography.path, "Biography must be 500 characters or fewer.")
        )
      )
    },
    test("accumulates every validation error in field order") {
      val result = Profile.Definition.codec.decode(formData("", "invalid", "a" * 501))

      assertTrue(
        result == Left(
          FormErrors(
            Vector(
              FormError(Profile.Name.path, "Name is required."),
              FormError(Profile.Email.path, "Enter a valid email address."),
              FormError(Profile.Biography.path, "Biography must be 500 characters or fewer.")
            )
          )
        )
      )
    },
    test("decodes valid trimmed profile input") {
      val result = Profile.Definition.codec.decode(
        formData(
          "  Ada Lovelace  ",
          "  ada@example.com  ",
          "  Analytical engine pioneer.  "
        )
      )

      assertTrue(
        result == Right(Profile("Ada Lovelace", "ada@example.com", "Analytical engine pioneer."))
      )
    },
    test("keeps initial validation errors hidden until fields are used") {
      for
        model <- ProfileFormLiveView().mount(null)
      yield assertTrue(
        model.form.state.errors.nonEmpty,
        !model.form.field(Profile.Name).isUsed,
        !model.form.field(Profile.Email).isUsed,
        !model.form.field(Profile.Biography).isUsed
      )
    }
  )
end ProfileFormCodecSpec
