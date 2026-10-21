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

  final private case class Phone(number: String)
  final private case class Contact(phones: Vector[Phone])
  private val ContactRoot       = FormRoot("contact")
  private val Phones            = ContactRoot.rows("phones")
  private val Number            = Phones.text("number").required(FieldIssue("Number is required"))
  private val PhoneRows         = Phones.product[Phone]((number = Number))
  private val ContactDefinition = ContactRoot.product[Contact]((phones = PhoneRows))
  private val Home              = FormRowKey.from[Phones.type]("home").toOption.get

  /** One helper for every page: it only sees the control, never where the form comes from. */
  private def labelledPassword[Owner](
    control: FormControl[Owner, String, String, Nothing]
  ): HtmlElement[Nothing] =
    div(
      label(forId := control.id, "Password"),
      control.password(control.validationAttributes, autoComplete := "current-password"),
      control.errorFeedback(error => error.map(_.message))
    )

  private def renderLive(
    form: Definition.Form,
    view: Signal[Definition.Form] => HtmlElement[Nothing]
  ): ZIO[Any, RenderError, String] =
    for
      program   <- ZIO.fromEither(RenderProgram.compile[Definition.Form, Nothing](view))
      candidate <- program.evaluate(form)
    yield HtmlRenderer.render(candidate.tree)

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
          val fieldId  = initial.fieldView(Name).id
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
        errorId = Definition.initial().fieldView(Name).errorId
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
        fieldId      = initial.fieldView(Name).id
        fieldErrorId = initial.fieldView(Name).errorId
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
          error.getMessage.endsWith(
            "form control owns attributes: id. Use the control's id for a label's forId, or " +
              "build a custom control from its inputAttributes"
          )
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
    test("a plain form's control renders statically with its id, name, value, type and errors") {
      val form = submitted("A")
      val id   = form.fieldView(Name).id
      for html <- StaticHtml.render(labelledPassword(form.field(Name)))
      yield assertTrue(
        html ==
          s"<div><label for=\"$id\">Password</label>" +
          s"<input type=\"password\" id=\"$id\" name=\"profile[name]\" value=\"A\" " +
          s"aria-describedby=\"${id}_errors\" aria-invalid=\"true\" autocomplete=\"current-password\">" +
          s"<div id=\"${id}_errors\" phx-feedback-for=\"profile[name]\" aria-live=\"polite\" class=\"form-errors\">" +
          "<span class=\"form-error\">validation.too_short</span></div></div>"
      )
    },
    test("a plain form's control renders like a form signal's control through the same helper") {
      val forms = Vector(Definition.initial(), submitted(""), submitted("Ada"))
      ZIO
        .foreach(forms) { form =>
          for
            live         <- renderLive(form, source => labelledPassword(source.field(Name)))
            staticInLive <- renderLive(form, _ => labelledPassword(form.field(Name)))
            static       <- StaticHtml.render(labelledPassword(form.field(Name)))
          yield assertTrue(static == live, staticInLive == live)
        }.map(_.reduce(_ && _))
    },
    test("a plain form's row controls render like a form signal's row controls") {
      val form = ContactDefinition
        .event(
          FormData(
            Vector(
              "contact[phones][home][_scalive_row]" -> "1",
              "contact[phones][home][number]"       -> ""
            )
          ),
          FormEventKind.Submitted
        ).form
      val bound = form.rows(PhoneRows).head.bind(Number)
      def render(control: FormControl[ContactRoot.type, String, String, Nothing]) =
        div(control.tel(), control.errorFeedback(error => error.map(_.message)))

      for
        program <- ZIO.fromEither(
                     RenderProgram.compile[ContactDefinition.Form, Nothing] { source =>
                       div(render(source.field(PhoneRows, Home, Number)), render(source.field(bound)))
                     }
                   )
        candidate <- program.evaluate(form)
        live = HtmlRenderer.render(candidate.tree)
        static <- StaticHtml.render(
                    div(render(form.field(PhoneRows, Home, Number)), render(form.field(bound)))
                  )
      yield assertTrue(
        static == live,
        static.contains("name=\"contact[phones][home][number]\""),
        static.contains("<span class=\"form-error\">Number is required</span>")
      )
    },
    test("a Phoenix adapter control renders positional names over keyed values and errors") {
      val adapter = PhoenixNestedParamsAdapter(ContactDefinition, PhoneRows)
      val Work    = FormRowKey.from[Phones.type]("work").toOption.get
      val form    = adapter
        .event(
          FormData(
            Vector(
              adapter.persistentIdName(0) -> "work",
              adapter.fieldName(0, Number) -> "",
              adapter.persistentIdName(1) -> "home",
              adapter.fieldName(1, Number) -> "555"
            )
          ),
          FormEventKind.Submitted
        ).form
      def render(form: ContactDefinition.Form) =
        div(form.rows(PhoneRows).zipWithIndex.map { case (row, index) =>
          val number = adapter.field("contact-form", row, index, Number)
          span(number.tel(number.validationAttributes), number.errorFeedback(_.map(_.message)))
        })
      def expectedInput(index: Int, value: String, invalid: Boolean) =
        val id = adapter.fieldId("contact-form", index, Number)
        s"""<input type="tel" id="$id" name="${adapter.fieldName(index, Number)}" value="$value" aria-describedby="${id}_errors"""" +
          (if invalid then """ aria-invalid="true">""" else ">")
      def expectedFeedback(index: Int) =
        s"""<div id="${adapter.fieldId("contact-form", index, Number)}_errors" phx-feedback-for="${adapter.fieldName(index, Number)}" aria-live="polite" class="form-errors"><span class="form-error">Number is required</span></div>"""

      for
        html  <- StaticHtml.render(render(form))
        moved <- StaticHtml.render(render(form.movedAfter(PhoneRows, Work, Home)))
      yield assertTrue(
        html.contains(expectedInput(0, "", invalid = true) + expectedFeedback(0)),
        html.contains(expectedInput(1, "555", invalid = false)),
        moved.contains(expectedInput(0, "555", invalid = false)),
        moved.contains(expectedInput(1, "", invalid = true) + expectedFeedback(1))
      )
    },
    test("a plain form's row control rejects a missing row") {
      val form = ContactDefinition.initial()
      assertTrue(
        scala.util.Try(form.field(PhoneRows, Home, Number)).failed.toOption.exists {
          case error: IllegalArgumentException => error.getMessage == "bound row no longer exists"
          case _                               => false
        }
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
        topicId = Definition.initial().fieldView(Topics).id
      yield assertTrue(
        html.contains(s"id=\"${topicId}_item_006e006500770073\""),
        html.contains("name=\"profile[topics]\""),
        html.contains("checked"),
        html.contains(s"for=\"${topicId}_item_006e006500770073\"")
      )
    }
  )
end FormControlRenderingSpec
