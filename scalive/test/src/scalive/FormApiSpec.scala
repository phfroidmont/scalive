package scalive

import scala.util.Try

import zio.http.Method
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

  private def formData(value: String): FormData =
    FormData.fromUrlEncoded(value).fold(error => throw new AssertionError(error), identity)

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
        formData("profile%5Bname%5D=Alice")
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
        FormData(
          Vector(
            "profile[name]"          -> "",
            "profile[_unused_email]" -> "",
            "profile[email]"         -> ""
          )
        ),
        FormEvent.Meta(target = Some(FormPath("profile", "name")))
      )

      assertTrue(
        binding(payload) == Right(
          Changed(
            Left(FormErrors.one("profile[name]", "required")),
            Some(FormPath("profile", "name")),
            Set(FormPath("profile", "name"))
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

      val Right(BindingPayload.Form(data, meta)) = event.bindingPayload: @unchecked

      assertTrue(
        data.string("profile[name]").contains("Alice"),
        meta.target.contains(FormPath("profile", "name")),
        meta.componentId.contains(7),
        meta.metadata.get("_target").contains("profile[name]")
      )
    },
    test("websocket form payload reports malformed URL encoding") {
      val event: Payload.Event = Payload.Event(
        `type` = "form",
        event = "validate",
        value = Json.Str("profile%5Bname%5D=%ZZ")
      )

      assertTrue(
        event.bindingPayload.left.exists(_.startsWith("Could not decode form event payload:"))
      )
    },
    test("render-side form helpers generate names ids values and errors") {
      val state = FormState(
        raw = formData("profile%5Bname%5D=Alice"),
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
      val payload = BindingPayload.Form(formData("profile%5Bname%5D=Alice"))

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
    },
    test("ordinary forms derive action method and rooted controls") {
      val state = FormState(
        formData("profile%5Bname%5D=Alice"),
        Right(Profile("Alice")),
        submitted = false
      )
      val formModel = Form.of("profile", state, profileCodec)
      val target    = FormAction.from(Method.POST / "profiles")
      val html = HtmlBuilder.build(
        formModel.http(target)(
          idAttr := "profile-form",
          formModel.text("name")
        )
      )

      assertTrue(
        html.contains("<form action=\"/profiles\" method=\"post\""),
        html.contains("id=\"profile-form\""),
        html.contains("id=\"profile_name\""),
        html.contains("name=\"profile[name]\""),
        html.contains("value=\"Alice\""),
        !html.contains("phx-change"),
        !html.contains("phx-submit"),
        !html.contains("phx-trigger-action")
      )
    },
    test("ordinary forms keep Live submission and trigger action explicit") {
      final case class Submitted(value: Either[FormErrors, Profile])

      val state = FormState(FormData.empty, Right(Profile("")), submitted = false)
      val formModel = Form.of("profile", state, profileCodec)
      val view: HtmlElement[Submitted] = formModel.http(
        FormAction.from(Method.POST / "profiles")
      )(
        formModel.onSubmit(event => Submitted(event.value)),
        phx.triggerAction := true,
        formModel.text("name")
      )
      val html = HtmlBuilder.build(view)

      assertTrue(
        BindingRegistry.collect[Submitted](view).size == 1,
        html.contains("phx-submit"),
        html.contains("phx-trigger-action")
      )
    },
    test("ordinary forms reject action and method overrides") {
      val state = FormState(FormData.empty, Right(Profile("")), submitted = false)
      val formModel = Form.of("profile", state, profileCodec)
      val target    = FormAction.from(Method.POST / "profiles")

      assertTrue(
        Try(formModel.http(target)(action := "/other")).isFailure,
        Try(formModel.http(target)(method := "get")).isFailure,
        Try(
          formModel.http(target)(
            htmlAttr("ACTION", scalive.codecs.StringAsIsEncoder) := "/other"
          )
        ).isFailure
      )
    }
  )
end FormApiSpec
