package scalive.examples.forms

import zio.test.*

import scalive.*

object ProfileFormCodecSpec extends ZIOSpecDefault:

  private def formData(name: String, email: String, biography: String): FormData =
    FormData(
      Vector(
        "profile[name]"      -> name,
        "profile[email]"     -> email,
        "profile[biography]" -> biography
      )
    )

  def spec = suite("ProfileFormCodecSpec")(
    test("rejects blank fields with path-specific messages") {
      val result = Profile.codec.decode(formData(" ", "", "\t"))

      assertTrue(
        result == Left(
          FormErrors(
            Vector(
              FormError("name", "Name is required."),
              FormError("email", "Email is required."),
              FormError("biography", "Biography is required.")
            )
          )
        )
      )
    },
    test("rejects a malformed email address") {
      val result = Profile.codec.decode(
        formData("Ada Lovelace", "ada.example.com", "Analytical engine pioneer.")
      )

      assertTrue(
        result == Left(FormErrors.one("email", "Enter a valid email address."))
      )
    },
    test("rejects a biography longer than 500 characters") {
      val result = Profile.codec.decode(
        formData("Ada Lovelace", "ada@example.com", "a" * 501)
      )

      assertTrue(
        result == Left(FormErrors.one("biography", "Biography must be 500 characters or fewer."))
      )
    },
    test("accumulates every validation error in field order") {
      val result = Profile.codec.decode(formData("", "invalid", "a" * 501))

      assertTrue(
        result == Left(
          FormErrors(
            Vector(
              FormError("name", "Name is required."),
              FormError("email", "Enter a valid email address."),
              FormError("biography", "Biography must be 500 characters or fewer.")
            )
          )
        )
      )
    },
    test("decodes valid trimmed profile input") {
      val result = Profile.codec.decode(
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
        form = Form.of("profile", model.form, Profile.codec)
      yield assertTrue(
        model.form.errors.nonEmpty,
        !form.isUsed("name"),
        !form.isUsed("email"),
        !form.isUsed("biography")
      )
    }
  )
end ProfileFormCodecSpec
