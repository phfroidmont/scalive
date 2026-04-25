package scalive

import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.Payload

object FormApiSpec extends ZIOSpecDefault:

  final case class Profile(name: String)

  private val profileCodec: FormCodec[Profile] =
    FormCodec { data =>
      data.string("profile[name]").filter(_.nonEmpty) match
        case Some(name) => Right(Profile(name))
        case None       => Left(FormErrors.one("profile[name]", "required"))
    }

  override def spec = suite("FormApiSpec")(
    test("parses nested and array form paths") {
      assertTrue(
        FormPath.parse("profile[address][city]") == FormPath("profile", "address", "city"),
        FormPath.parse("order_item[addons][][name]") == FormPath(
          "order_item",
          "addons",
          "name"
        ),
        FormPath("profile", "name").name == "profile[name]",
        FormData
          .fromUrlEncoded("profile%5Bname%5D=Alice")
          .string(FormPath("profile", "name"))
          .contains("Alice")
      )
    },
    test("typed change bindings expose decoded value, target, and used fields") {
      final case class Changed(
        value: Either[FormErrors, Profile],
        target: Option[FormPath],
        used: Set[FormPath])

      val view: HtmlElement[Changed] = form(
        phx.onChangeForm(profileCodec)(event => Changed(event.value, event.target, event.state.used)),
        input(nameAttr := "profile[name]")
      )

      val binding = BindingRegistry.collect[Changed](view).values.head
      val payload = BindingPayload.Form(
        FormData(Vector("_unused_profile[name]" -> "", "profile[name]" -> "")),
        FormEvent.Meta(target = Some(FormPath("profile", "name")))
      )

      assertTrue(
        binding(payload) == Right(
          Changed(
            Left(FormErrors.one("profile[name]", "required")),
            Some(FormPath("profile", "name")),
            Set.empty
          )
        )
      )
    },
    test("typed submit bindings keep submitter metadata and mark fields used") {
      final case class Submitted(
        submitter: Option[FormSubmitter],
        submitted: Boolean,
        nameUsed: Boolean,
        valid: Boolean)

      val view: HtmlElement[Submitted] = form(
        phx.onSubmitForm(FormCodec.formData) { event =>
          Submitted(
            event.submitter,
            event.state.submitted,
            event.state.used.contains(FormPath("name")),
            event.value.exists(_.string("name").contains("Alice"))
          )
        },
        input(nameAttr := "name")
      )

      val binding = BindingRegistry.collect[Submitted](view).values.head
      val payload = BindingPayload.Form(
        FormData(Vector("_unused_name" -> "", "name" -> "Alice", "save" -> "draft")),
        FormEvent.Meta(submitter = Some(FormSubmitter("save", "draft")))
      )

      assertTrue(
        binding(payload) == Right(
          Submitted(Some(FormSubmitter("save", "draft")), submitted = true, nameUsed = true, valid = true)
        )
      )
    },
    test("websocket form payload extracts target metadata") {
      val event = Payload.Event(
        `type` = "form",
        event = "validate",
        value = Json.Str("profile%5Bname%5D=Alice"),
        cid = Some(7),
        meta = Some(Json.Obj("_target" -> Json.Str("profile[name]")))
      )

      val BindingPayload.Form(data, meta) = event.bindingPayload: @unchecked

      assertTrue(
        data.string("profile[name]").contains("Alice"),
        meta.target.contains(FormPath("profile", "name")),
        meta.componentId.contains(7),
        meta.metadata.get("_target").contains("profile[name]")
      )
    },
    test("render-side form helpers generate names ids values and errors") {
      val state = FormState(
        raw = FormData.fromUrlEncoded("profile%5Bname%5D=Alice"),
        value = Left(FormErrors.one("profile[name]", "is invalid")),
        submitted = false
      )
      val form = Form.of("profile", state, profileCodec)

      val html = HtmlBuilder.build(
        div(
          form.text("name"),
          form.errors("name"),
          form.feedback("name", "feedback")
        )
      )

      assertTrue(
        form.name("name") == "profile[name]",
        form.id("name") == "profile_name",
        form.value("name") == "Alice",
        html.contains("name=\"profile[name]\""),
        html.contains("id=\"profile_name\""),
        html.contains("value=\"Alice\""),
        html.contains("is invalid"),
        html.contains("phx-feedback-for=\"profile[name]\"")
      )
    },
    test("render-side form bindings dispatch typed events") {
      final case class Changed(value: Either[FormErrors, Profile])

      val state = FormState(FormData.empty, Right(Profile("")), submitted = false)
      val formModel = Form.of("profile", state, profileCodec)
      val view: HtmlElement[Changed] = form(
        formModel.onChange(event => Changed(event.value)),
        formModel.text("name")
      )

      val binding = BindingRegistry.collect[Changed](view).values.head
      val payload = BindingPayload.Form(FormData.fromUrlEncoded("profile%5Bname%5D=Alice"))

      assertTrue(binding(payload) == Right(Changed(Right(Profile("Alice")))))
    },
    test("render-side common field helpers generate expected markup") {
      val state = FormState(
        raw = FormData(
          Vector(
            "profile[bio]"      -> "Hello",
            "profile[password]" -> "secret",
            "profile[active]"   -> "yes",
            "profile[role]"     -> "admin"
          )
        ),
        value = Right(Profile("Alice")),
        submitted = false
      )
      val form = Form.of("profile", state, profileCodec)

      val html = HtmlBuilder.build(
        div(
          form.text("name", "custom-name-id"),
          form.password("password"),
          form.textarea("bio"),
          form.checkbox("active", "yes"),
          form.select("role", Vector("user" -> "User", "admin" -> "Admin"))
        )
      )

      assertTrue(
        html.contains("id=\"custom-name-id\""),
        html.contains("type=\"password\""),
        html.contains("<textarea"),
        html.contains("name=\"profile[bio]\""),
        html.contains("Hello"),
        html.contains("type=\"checkbox\""),
        html.contains("checked"),
        html.contains("<select"),
        html.contains("value=\"admin\" selected")
      )
    },
    test("form paths can generate array-style names") {
      val state = FormState(FormData.empty, Right(Profile("")), submitted = false)
      val form = Form.of("profile", state, profileCodec)
      val sortPath = FormPath("users_sort").array

      assertTrue(
        sortPath.name == "users_sort[]",
        form.name(sortPath) == "profile[users_sort][]",
        form.id(sortPath) == "profile_users_sort"
      )
    }
  )
end FormApiSpec
