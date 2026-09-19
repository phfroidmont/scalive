package scalive.docs.examples

import zio.test.*

import scalive.*

object FormRecipesSpec extends ZIOSpecDefault:
  override def spec = suite("FormRecipesSpec")(
    test("attaches cross-field refinement to the confirmation address") {
      val registration = FormRecipes.RegistrationForm
      val form         = registration.Definition
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
    test("initializes a Boolean checkbox and decodes unchecked and malformed submissions") {
      val notifications = FormRecipes.NotificationForm
      val unchecked     = notifications.Definition.event(FormData.empty, FormEventKind.Submitted)
      val malformed     = notifications.Definition.event(
        FormData(Vector(notifications.Enabled.name -> "false")),
        FormEventKind.Submitted
      )

      assertTrue(
        notifications.initial.valueOption.contains(notifications.Preferences(true)),
        notifications.initial.field(notifications.Enabled).rawValues == Vector("true"),
        unchecked.valueOption.contains(notifications.Preferences(false)),
        unchecked.form.field(notifications.Enabled).rawValues.isEmpty,
        malformed.valueOption.isEmpty,
        malformed.form.field(notifications.Enabled).visibleErrors.map(_.code) ==
          Vector(Some("invalid_checkbox"))
      )
    },
    test("decodes repeated checkbox selections and an empty submission") {
      val topics   = FormRecipes.TopicForm
      val selected = topics.Definition.event(
        FormData(Vector(topics.Topics.name -> "news", topics.Topics.name -> "research")),
        FormEventKind.Submitted
      )
      val empty = topics.Definition.event(FormData.empty, FormEventKind.Submitted)

      assertTrue(
        selected.valueOption.contains(topics.Preferences(Vector("news", "research"))),
        empty.valueOption.contains(topics.Preferences(Vector.empty)),
        empty.form.field(topics.Topics).rawValues.isEmpty
      )
    },
    test("keeps malformed custom-control input available for rendering") {
      val quantity = FormRecipes.QuantityForm
      val form     = quantity.Definition
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
