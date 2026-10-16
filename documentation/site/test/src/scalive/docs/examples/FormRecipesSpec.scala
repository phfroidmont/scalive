package scalive.docs.examples

import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import zio.ZIO
import zio.test.*

import scalive.*
import scalive.render.{HtmlRenderer, RenderProgram}

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
        notifications.initial.fieldView(notifications.Enabled).rawValues == Vector("true"),
        unchecked.valueOption.contains(notifications.Preferences(false)),
        unchecked.form.fieldView(notifications.Enabled).rawValues.isEmpty,
        malformed.valueOption.isEmpty,
        malformed.form.fieldView(notifications.Enabled).visibleErrors.map(_.code) ==
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
        empty.form.fieldView(topics.Topics).rawValues.isEmpty
      )
    },
    test("renders a submitted form value's controls as static HTML") {
      val password = FormRecipes.PasswordForm
      val form     = password.Definition
        .event(
          FormData(Vector(password.Current.name -> "old", password.Replacement.name -> "")),
          FormEventKind.Submitted
        )
        .form
      val current     = form.fieldView(password.Current)
      val replacement = form.fieldView(password.Replacement)

      for html <- password.rendered(form)
      yield assertTrue(
        html.contains(
          s"""<input type="password" id="${current.id}" name="password[current]" value="old" aria-describedby="${current.errorId}">"""
        ),
        html.contains(
          s"""<input type="password" id="${replacement.id}" name="password[replacement]" value="" aria-describedby="${replacement.errorId}" aria-invalid="true">"""
        ),
        html.contains("""<span class="form-error">Choose a new password.</span>""")
      )
    },
    test("renders Phoenix rows as controls whose submission decodes to the same rows") {
      val phones = FormRecipes.PhoenixPhoneForm
      val home   = FormRowKey.from[phones.Phones.type]("home").toOption.get
      val work   = FormRowKey.from[phones.Phones.type]("work").toOption.get
      val form   = phones.Definition.initial(
        phones.PhoneRows.initial(
          phones.PhoneRows.row(home)(phones.Label.initial("Home"), phones.Number.initial("555")),
          phones.PhoneRows.row(work)(phones.Label.initial("Work"), phones.Number.initial("777"))
        )
      )

      for
        program   <- ZIO.fromEither(RenderProgram.compile[Unit, phones.Msg](_ => phones.render(form)))
        candidate <- program.evaluate(())
        html      = HtmlRenderer.render(candidate.tree)
        submitted = Jsoup
                      .parse(html).select("input[name]").asScala.toVector
                      .map(input => input.attr("name") -> input.attr("value"))
        event = phones.Adapter.event(FormData(submitted), FormEventKind.Submitted)
      yield assertTrue(
        html.contains(
          """<label for="contact-form_phones_1_number">Phone number</label><input type="tel" id="contact-form_phones_1_number" name="contact[phones][1][number]" value="777">"""
        ),
        html.contains(
          """<input type="text" id="contact-form_phones_0_label" name="contact[phones][0][label]" value="Home">"""
        ),
        event.valueOption.contains(
          phones.Contact(Vector(phones.Phone("Home", "555"), phones.Phone("Work", "777")))
        ),
        event.form.rows(phones.PhoneRows).map(_.key) == Vector(home, work)
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
      val field = form.fieldView(quantity.Quantity)

      assertTrue(
        !form.isValid,
        field.fieldValue == "not-a-number",
        field.errors.map(_.message) == Vector("validation.quantity.positive")
      )
    }
  )
end FormRecipesSpec
