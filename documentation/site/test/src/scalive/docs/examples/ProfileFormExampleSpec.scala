package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.*
import scalive.docs.SiteLiveViewHarness

object ProfileFormExampleSpec extends ZIOSpecDefault:
  private def formData(name: String, email: String, biography: String): FormData =
    FormData(
      Vector(
        ProfileFormExample.Profile.Name.name      -> name,
        ProfileFormExample.Profile.Email.name     -> email,
        ProfileFormExample.Profile.Biography.name -> biography
      )
    )

  private def document(harness: SiteLiveViewHarness[?, ?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  private val validFields = Vector(
    ProfileFormExample.Profile.Name.name      -> "  Ada Lovelace  ",
    ProfileFormExample.Profile.Email.name     -> "  ada@example.com  ",
    ProfileFormExample.Profile.Biography.name -> "  Analytical engine pioneer.  "
  )

  override def spec = suite("ProfileFormExampleSpec")(
    test("decodes trimmed values and accumulates path-specific validation errors") {
      val profile = ProfileFormExample.Profile
      val invalid = profile.Definition.codec.decode(formData("", "invalid", "a" * 501))
      val valid = profile.Definition.codec.decode(
        formData("  Ada Lovelace  ", "  ada@example.com  ", "  Pioneer.  ")
      )
      assertTrue(
        invalid == Left(
          FormErrors(
            Vector(
              FormError(profile.Name.path, "Name is required."),
              FormError(profile.Email.path, "Enter a valid email address."),
              FormError(
                profile.Biography.path,
                "Biography must be 500 characters or fewer."
              )
            )
          )
        ),
        valid == Right(ProfileFormExample.Profile("Ada Lovelace", "ada@example.com", "Pioneer."))
      )
    },
    test("keeps initial errors hidden and reveals only the changed field") {
      ZIO.scoped {
        val profile = ProfileFormExample.Profile
        for
          harness <- SiteLiveViewHarness.join(new ProfileFormExample)
          initial <- document(harness)
          _ <- harness.changeForm(
                 "[data-profile-form]",
                 Vector(
                   profile.Name.name                  -> "",
                   s"profile[_unused_email]"          -> "",
                   profile.Email.name                 -> "",
                   s"profile[_unused_biography]"      -> "",
                   profile.Biography.name             -> ""
                 ),
                 target = Some(profile.Name.name)
               )
          changed <- document(harness)
        yield assertTrue(
          initial.select("[data-field-error] .form-error").isEmpty,
          changed.select("[data-field-error=name] .form-error").text() == "Name is required.",
          changed.select("[data-field-error=email] .form-error").isEmpty,
          changed.select("[data-field-error=biography] .form-error").isEmpty
        )
      }
    },
    test("reveals invalid submit errors, saves valid input, and resets") {
      ZIO.scoped {
        val profile = ProfileFormExample.Profile
        for
          harness <- SiteLiveViewHarness.join(new ProfileFormExample)
          _ <- harness.submitForm(
                 "[data-profile-form]",
                 Vector(
                   profile.Name.name      -> "",
                   profile.Email.name     -> "invalid",
                   profile.Biography.name -> ""
                 )
               )
          invalid <- document(harness)
          _       <- harness.submitForm("[data-profile-form]", validFields)
          saved   <- document(harness)
          _       <- harness.clickButton("Reset form")
          reset   <- document(harness)
        yield assertTrue(
          invalid.select("[data-field-error] .form-error").size() == 3,
          invalid.select("[data-profile-saved]").isEmpty,
          saved.select("[data-profile-saved]").text() == "Saved Ada Lovelace's profile.",
          saved.select("[name='profile[name]']").attr("value") == "  Ada Lovelace  ",
          reset.select("[data-profile-saved]").isEmpty,
          reset.select("[name='profile[name]']").attr("value").isEmpty,
          reset.select("[data-field-error] .form-error").isEmpty
        )
      }
    }
  )
end ProfileFormExampleSpec
