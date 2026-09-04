package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.*
import scalive.testing.{ConnectedRender, ConnectedView}

object ProfileFormExampleSpec extends ZIOSpecDefault:
  private def formData(name: String, email: String, biography: String): FormData =
    FormData(
      Vector(
        ProfileFormExample.Profile.Name.name      -> name,
        ProfileFormExample.Profile.Email.name     -> email,
        ProfileFormExample.Profile.Biography.name -> biography
      )
    )

  private def document(harness: ConnectedView[?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  private val validFields = Vector(
    ProfileFormExample.Profile.Name.name      -> "  Ada Lovelace  ",
    ProfileFormExample.Profile.Email.name     -> "  ada@example.com  ",
    ProfileFormExample.Profile.Biography.name -> "  Analytical engine pioneer.  "
  )

  override def spec = suite("ProfileFormExampleSpec")(
    test("decodes trimmed values and accumulates path-specific validation errors") {
      val profile = ProfileFormExample.Profile
      val invalid = profile.Definition
        .event(formData("", "invalid", "a" * 501), FormEventKind.Changed)
        .form
      val valid = profile.Definition
        .event(
          formData("  Ada Lovelace  ", "  ada@example.com  ", "  Pioneer.  "),
          FormEventKind.Changed
        )
        .form
      assertTrue(
        invalid.errors.all.map(error => error.address -> error.message) == Vector(
          profile.Name.address      -> "validation.name.required",
          profile.Email.address     -> "validation.email.invalid",
          profile.Biography.address -> "validation.biography.too_long"
        ),
        valid.valueOption.contains(
          ProfileFormExample.Profile("Ada Lovelace", "ada@example.com", "Pioneer.")
        )
      )
    },
    test("keeps initial errors hidden and reveals only the changed field") {
      ZIO.scoped {
        val profile = ProfileFormExample.Profile
        for
          harness <- ConnectedRender.join(new ProfileFormExample)
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
          harness <- ConnectedRender.join(new ProfileFormExample)
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
