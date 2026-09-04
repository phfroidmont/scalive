package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object FormFieldViewRenderingSpec extends ZIOSpecDefault:
  private val Profile = FormRoot("profile")
  private val Name = Profile.text("name").validateAll { value =>
    Vector(
      Option.when(value.isEmpty)(FieldIssue("validation.required", Some("required"))),
      Option.when(value.length < 2)(FieldIssue("validation.too_short", Some("too_short")))
    ).flatten
  }
  private val Definition = Profile.product[Tuple1[String]](Tuple1(Name))

  private val messages = Map(
    "validation.required"  -> "Name is required.",
    "validation.too_short" -> "Name is too short."
  )

  override def spec = suite("FormFieldViewRenderingSpec")(
    test("renders submitted errors through application-owned markup") {
      val field = Definition
        .event(FormData(Vector(Name.name -> "")), FormEventKind.Submitted)
        .form
        .field(Name)
      val compiled = RenderProgram.compile[Unit, Nothing] { _ =>
        field.errorFeedback(
          error => em(s"${field.name}: ${messages(error.message)}"),
          cls               := "field-feedback",
          dataAttr("scope") := "name"
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
      yield assertTrue(
        HtmlRenderer.render(candidate.tree) ==
          s"<div id=\"${field.errorId}\" phx-feedback-for=\"profile[name]\" aria-live=\"polite\" class=\"form-errors field-feedback\" data-scope=\"name\"><span class=\"form-error\"><em>profile[name]: Name is required.</em></span><span class=\"form-error\"><em>profile[name]: Name is too short.</em></span></div>"
      )
    },
    test("updates signal errors without replacing owned markup") {
      val initial = Definition.initial()
      val visible = Definition
        .event(FormData(Vector(Name.name -> "")), FormEventKind.Submitted)
        .form
      val updated = Definition
        .event(FormData(Vector(Name.name -> "A")), FormEventKind.Submitted)
        .form
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
        fieldId     = initial.field(Name).id
        fieldErrorId = initial.field(Name).errorId
      yield assertTrue(
        HtmlRenderer.render(hidden.tree).contains(
          s"<input type=\"text\" id=\"$fieldId\" name=\"profile[name]\" value=\"\" aria-describedby=\"$fieldErrorId\">"
        ),
        HtmlRenderer.render(shown.tree).contains("aria-invalid=\"true\""),
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
          case RenderDelta.Update(_, changes) =>
            changes.exists {
              case RenderChange.Keyed(_, Vector(KeyedRowChange.Retain(_, nested))) =>
                nested.exists(_.isInstanceOf[RenderChange.Text]) &&
                  !nested.exists(_.isInstanceOf[RenderChange.Replace])
              case _ => false
            } && !changes.exists(_.isInstanceOf[RenderChange.Replace])
          case _ => false
      )
    }
  )
end FormFieldViewRenderingSpec
