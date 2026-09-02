package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object FormFieldViewRenderingSpec extends ZIOSpecDefault:
  private val Profile    = FormRoot("profile")
  private val Name       = Profile.requiredString("name", "validation.required")
  private val Definition = Profile.form((name: String) => name)(Name)

  private val messages = Map(
    "validation.required" -> "Name is required.",
    "validation.too_short" -> "Name is too short."
  )

  override def spec = suite("FormFieldViewRenderingSpec")(
    test("renders static errors through the application renderer inside owned markup") {
      val errors = Vector(
        FormError(Name.path, "validation.required"),
        FormError(Name.path, "validation.too_short")
      )
      val state = FormState[String](
        raw = FormData.empty,
        value = Left(FormErrors(errors)),
        used = Set(Name.path),
        submitted = false
      )
      val field = Definition.from(state).field(Name)
      val compiled = RenderProgram.compile[Unit, Nothing] { _ =>
        field.errorFeedback(
          error => em(s"${error.path.name}: ${messages(error.message)}"),
          cls               := "field-feedback",
          dataAttr("scope") := "name"
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
      yield assertTrue(
        HtmlRenderer.render(candidate.tree) ==
          "<div id=\"profile_name_errors\" phx-feedback-for=\"profile[name]\" aria-live=\"polite\" class=\"form-errors field-feedback\" data-scope=\"name\"><span class=\"form-error\"><em>profile[name]: Name is required.</em></span><span class=\"form-error\"><em>profile[name]: Name is too short.</em></span></div>"
      )
    },
    test("renders signal errors through signals without replacing owned markup") {
      val initial = Definition.initial()
      val errors = Vector(
        FormError(Name.path, "validation.required"),
        FormError(Name.path, "validation.too_short")
      )
      val visible = Definition.from(
        FormState[String](
          raw = FormData.empty,
          value = Left(FormErrors(errors)),
          used = Set(Name.path),
          submitted = false
        )
      )
      val updated = Definition.from(
        FormState[String](
          raw = FormData.empty,
          value = Left(FormErrors.one(Name.path, "validation.too_short")),
          used = Set(Name.path),
          submitted = false
        )
      )
      val compiled = RenderProgram.compile[Definition.Form, Nothing] { form =>
        val field = form.field(Name)
        div(
          field.text(field.validationAttributes),
          field.errorFeedback(
            error => error.map(value => messages(value.message)),
            dataAttr("scope") := "name"
          )
        )
      }

      for
        program <- ZIO.fromEither(compiled)
        hidden  <- program.evaluate(initial)
        shown   <- program.evaluate(visible, Some(hidden.commit))
        changed <- program.evaluate(updated, Some(shown.commit))
        revealDelta = TreeDiffer.diff(hidden.tree, shown.tree)
        updateDelta = TreeDiffer.diff(shown.tree, changed.tree)
      yield assertTrue(
        HtmlRenderer.render(hidden.tree).contains(
          "<input type=\"text\" id=\"profile_name\" name=\"profile[name]\" value=\"\" aria-describedby=\"profile_name_errors\">"
        ),
        HtmlRenderer.render(hidden.tree).contains(
          "<div id=\"profile_name_errors\" phx-feedback-for=\"profile[name]\" aria-live=\"polite\" class=\"form-errors\" data-scope=\"name\"></div>"
        ),
        HtmlRenderer.render(shown.tree).contains(
          "<input type=\"text\" id=\"profile_name\" name=\"profile[name]\" value=\"\" aria-describedby=\"profile_name_errors\" aria-invalid=\"true\">"
        ),
        HtmlRenderer.render(shown.tree).contains(
          "<span class=\"form-error\">Name is required.</span><span class=\"form-error\">Name is too short.</span>"
        ),
        HtmlRenderer.render(changed.tree).contains(
          "<span class=\"form-error\">Name is too short.</span>"
        ),
        revealDelta match
          case RenderDelta.Update(_, changes) =>
            changes.exists(_.isInstanceOf[RenderChange.Keyed]) &&
              !changes.exists(_.isInstanceOf[RenderChange.Replace])
          case _ => false,
        updateDelta match
          case RenderDelta.Update(
                _,
                Vector(RenderChange.Keyed(_, Vector(KeyedRowChange.Retain(_, changes))))
              ) =>
            changes.exists(_.isInstanceOf[RenderChange.Text]) &&
              !changes.exists(_.isInstanceOf[RenderChange.Replace])
          case _ => false
      )
    }
  )
end FormFieldViewRenderingSpec
