package scalive.docs.examples

import zio.*
import zio.test.*

import scalive.*
import scalive.testing.{ConnectedRender, ConnectedView, RenderedHtml}

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
    harness.html.map(RenderedHtml.parse)

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
          _       <- harness.changeForm(
                 "[data-profile-form]",
                 Vector(
                   profile.Name.name             -> "",
                   s"profile[_unused_email]"     -> "",
                   profile.Email.name            -> "",
                   s"profile[_unused_biography]" -> "",
                   profile.Biography.name        -> ""
                 ),
                 target = Some(profile.Name.name)
               )
          changed <- document(harness)
        yield assertTrue(
          initial.selectAll("[data-field-error] .form-error").map(_.isEmpty) == Right(true),
          changed.selectOne("[data-field-error=name] .form-error").map(_.text) ==
            Right("Name is required."),
          changed.selectAll("[data-field-error=email] .form-error").map(_.isEmpty) == Right(true),
          changed.selectAll("[data-field-error=biography] .form-error").map(_.isEmpty) == Right(
            true
          )
        )
      }
    },
    test("reveals invalid submit errors, previews, saves, and resets") {
      ZIO.scoped {
        val profile = ProfileFormExample.Profile
        for
          harness <- ConnectedRender.join(new ProfileFormExample)
          _       <- harness.submitForm(
                 "[data-profile-form]",
                 Vector(
                   profile.Name.name      -> "",
                   profile.Email.name     -> "invalid",
                   profile.Biography.name -> ""
                 ),
                 submitter = Some(profile.Submitter.raw(profile.Intent.Save))
               )
          invalid <- document(harness)
          _       <- harness.submitForm(
                 "[data-profile-form]",
                 validFields,
                 submitter = Some(profile.Submitter.raw(profile.Intent.Preview))
               )
          previewed <- document(harness)
          _         <- harness.submitForm(
                 "[data-profile-form]",
                 validFields,
                 submitter = Some(profile.Submitter.raw(profile.Intent.Save))
               )
          saved <- document(harness)
          _     <- harness.clickButton("Reset form")
          reset <- document(harness)
        yield assertTrue(
          invalid.selectAll("[data-field-error] .form-error").map(_.size) == Right(3),
          invalid.selectAll("[data-profile-saved]").map(_.isEmpty) == Right(true),
          previewed.selectOne("[data-profile-previewed]").map(_.text) ==
            Right("Previewing Ada Lovelace's profile."),
          previewed.selectAll("[data-profile-saved]").map(_.isEmpty) == Right(true),
          saved.selectOne("[data-profile-saved]").map(_.text) == Right(
            "Saved Ada Lovelace's profile."
          ),
          saved.selectAll("[data-profile-previewed]").map(_.isEmpty) == Right(true),
          saved.selectOne("[name='profile[name]']").map(_.value) == Right("  Ada Lovelace  "),
          reset.selectAll("[data-profile-saved]").map(_.isEmpty) == Right(true),
          reset.selectAll("[data-profile-previewed]").map(_.isEmpty) == Right(true),
          reset.selectOne("[name='profile[name]']").map(_.value) == Right(""),
          reset.selectAll("[data-field-error] .form-error").map(_.isEmpty) == Right(true)
        )
        end for
      }
    }
  )
end ProfileFormExampleSpec
