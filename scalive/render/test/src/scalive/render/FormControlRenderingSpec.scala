package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object FormControlRenderingSpec extends ZIOSpecDefault:
  private val Profile = FormRoot("profile")
  private val Name    = Profile.text("name").validateAll { value =>
    Vector(
      Option.when(value.isEmpty)(FieldIssue("validation.required", Some("required"))),
      Option.when(value.length < 2)(FieldIssue("validation.too_short", Some("too_short")))
    ).flatten
  }
  private val Topics = Profile.texts("topics")
  final private case class Person(name: String, topics: Vector[String])
  private val Definition = Profile.product[Person]((name = Name, topics = Topics))

  private enum Msg:
    case Blurred(value: String)

  private val messages = Map(
    "validation.required"  -> "Name is required.",
    "validation.too_short" -> "Name is too short."
  )

  private def submitted(name: String): Definition.Form =
    Definition.event(FormData(Vector(Name.name -> name)), FormEventKind.Submitted).form

  override def spec = suite("FormControlRenderingSpec")(
    test("unbound tel, date and search inputs update raw values using the previous commit") {
      // Name intentionally uses a text codec: these helpers render, rather than parse, dates.
      val cases = Vector(
        ("tel", " +32 0123 456 ", "  +44 020 1234  "),
        ("date", "2026-02-30", " 2026-13-40 "),
        ("search", "  Ada Lovelace  ", " Grace Hopper ")
      )

      ZIO
        .foreach(cases) { case (inputType, beforeValue, afterValue) =>
          val initial  = Definition.initial(Name.initial(beforeValue))
          val updated  = Definition.initial(Name.initial(afterValue))
          val fieldId  = initial.field(Name).id
          val compiled = RenderProgram.compile[Definition.Form, Nothing] { form =>
            val field = form.field(Name)
            inputType match
              case "tel" =>
                field.tel(
                  field.validationAttributes,
                  cls               := "caller-control",
                  dataAttr("scope") := "name"
                )
              case "date" =>
                field.date(
                  field.validationAttributes,
                  cls               := "caller-control",
                  dataAttr("scope") := "name"
                )
              case _ =>
                field.search(
                  field.validationAttributes,
                  cls               := "caller-control",
                  dataAttr("scope") := "name"
                )
          }

          for
            program <- ZIO.fromEither(compiled)
            before  <- program.evaluate(initial)
            after   <- program.evaluate(updated, Some(before.commit))
          yield assertTrue(
            HtmlRenderer.render(before.tree) ==
              s"<input type=\"$inputType\" id=\"$fieldId\" name=\"profile[name]\" value=\"$beforeValue\" aria-describedby=\"${fieldId}_errors\" class=\"caller-control\" data-scope=\"name\">",
            HtmlRenderer.render(after.tree) ==
              s"<input type=\"$inputType\" id=\"$fieldId\" name=\"profile[name]\" value=\"$afterValue\" aria-describedby=\"${fieldId}_errors\" class=\"caller-control\" data-scope=\"name\">"
          )
        }.map(_.reduce(_ && _))
    },
    test("renders submitted errors through application-owned markup") {
      val compiled = RenderProgram.compile[Definition.Form, Nothing] { form =>
        form
          .field(Name).errorFeedback(
            error => em(error.map(value => s"profile[name]: ${messages(value.message)}")),
            cls               := "field-feedback",
            dataAttr("scope") := "name"
          )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(submitted(""))
        errorId = Definition.initial().field(Name).errorId
      yield assertTrue(
        HtmlRenderer.render(candidate.tree) ==
          s"<div id=\"$errorId\" phx-feedback-for=\"profile[name]\" aria-live=\"polite\" class=\"form-errors field-feedback\" data-scope=\"name\"><span class=\"form-error\"><em>profile[name]: Name is required.</em></span><span class=\"form-error\"><em>profile[name]: Name is too short.</em></span></div>"
      )
    },
    test("updates signal errors without replacing owned markup") {
      val initial  = Definition.initial()
      val visible  = submitted("")
      val updated  = submitted("A")
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
        revealDelta  = TreeDiffer.diff(hidden.tree, shown.tree)
        updateDelta  = TreeDiffer.diff(shown.tree, changed.tree)
        fieldId      = initial.field(Name).id
        fieldErrorId = initial.field(Name).errorId
      yield assertTrue(
        HtmlRenderer
          .render(hidden.tree).contains(
            s"<input type=\"text\" id=\"$fieldId\" name=\"profile[name]\" value=\"\" aria-describedby=\"$fieldErrorId\">"
          ),
        HtmlRenderer.render(shown.tree).contains("aria-invalid=\"true\""),
        HtmlRenderer
          .render(shown.tree).contains(
            "<span class=\"form-error\">Name is required.</span><span class=\"form-error\">Name is too short.</span>"
          ),
        HtmlRenderer
          .render(changed.tree).contains(
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
      end for
    },
    test("an unbound control rejects overrides of the attributes it owns") {
      val compiled = RenderProgram.compile[Definition.Form, Nothing] { form =>
        form.field(Name).text(idAttr := "other")
      }

      assertTrue(compiled.left.exists {
        case RenderError.EvaluationFailed(error: IllegalArgumentException) =>
          error.getMessage.endsWith("form control owns attributes: id")
        case _ => false
      })
    },
    test("an unbound control's blur handler sends the browser value without feedback dispatch") {
      val compiled = RenderProgram.compile[Definition.Form, Msg] { form =>
        form.field(Name).onBlur(Msg.Blurred(_)).text()
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(Definition.initial())
        html      = HtmlRenderer.render(candidate.tree)
        handlerId = candidate.bindings.ids.find(_.encoded.startsWith("j")).get
        result    = candidate.bindings
                   .resolve(handlerId).get.dispatch(
                     BindingPayload.Params(Map("value" -> "Ada"))
                   )
      yield assertTrue(
        html.contains("phx-blur="),
        !html.contains("&quot;dispatch&quot;"),
        result == Right(BindingDispatch.Owner(Msg.Blurred("Ada")))
      )
    },
    test("unbound checkbox items share the field name and derive ids from its logical id") {
      val compiled = RenderProgram.compile[Definition.Form, Nothing] { form =>
        val topics = form.field(Topics)
        val news   = topics.item("news")
        div(news.checkbox("news"), label(forId := news.id, "News"))
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(Definition.initial(Topics.initial(Vector("news"))))
        html    = HtmlRenderer.render(candidate.tree)
        topicId = Definition.initial().field(Topics).id
      yield assertTrue(
        html.contains(s"id=\"${topicId}_item_006e006500770073\""),
        html.contains("name=\"profile[topics]\""),
        html.contains("checked"),
        html.contains(s"for=\"${topicId}_item_006e006500770073\"")
      )
    }
  )
end FormControlRenderingSpec
