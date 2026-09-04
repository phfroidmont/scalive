package scalive.docs.examples

import zio.test.*

import scalive.*

object FormRecipesSpec extends ZIOSpecDefault:
  override def spec = suite("FormRecipesSpec")(
    test("attaches cross-field refinement to the confirmation address") {
      val registration = FormRecipes.RegistrationForm
      val form = registration.Definition
        .event(
          FormData(
            Vector(
              registration.Email.name        -> "ada@example.com",
              registration.Confirmation.name -> "grace@example.com"
            )
          ),
          FormEventKind.Submitted
        )
        .form

      assertTrue(
        form.errors.all.map(error => error.address -> error.message) == Vector(
          registration.Confirmation.address -> "validation.email.mismatch"
        )
      )
    },
    test("keeps malformed custom-control input available for rendering") {
      val quantity = FormRecipes.QuantityForm
      val form = quantity.Definition
        .event(
          FormData(Vector(quantity.Quantity.name -> "not-a-number")),
          FormEventKind.Changed
        )
        .form
      val field = form.field(quantity.Quantity)

      assertTrue(
        !form.isValid,
        field.fieldValue == "not-a-number",
        field.errors.map(_.message) == Vector("validation.quantity.positive")
      )
    }
  )
end FormRecipesSpec
