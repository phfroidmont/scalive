import java.util.UUID

import zio.stream.ZStream
import zio.ZIO
import zio.json.ast.Json
import zio.http.URL

import scalive.*

private val actionAttr = htmlAttr("action", scalive.codecs.StringAsIsEncoder)
private val methodAttr = htmlAttr("method", scalive.codecs.StringAsIsEncoder)
private val multipleAttr = htmlAttr("multiple", scalive.codecs.BooleanAsAttrPresenceEncoder)
private val placeholderAttr = htmlAttr("placeholder", scalive.codecs.StringAsIsEncoder)
private val feedbackForAttr = htmlAttr("phx-feedback-for", scalive.codecs.StringAsIsEncoder)
private val targetAttr = htmlAttr("phx-target", scalive.codecs.StringAsIsEncoder)
private val phxClickAttr = htmlAttr("phx-click", scalive.codecs.StringAsIsEncoder)
private val dataPhxAutoUploadAttr = htmlAttr("data-phx-auto-upload", scalive.codecs.BooleanAsAttrPresenceEncoder)
class Issue3719LiveView extends LiveView[Issue3719LiveView.Msg, Issue3719LiveView.Model]:
  import Issue3719LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg.Change(event) => model.copy(target = event.target.map(_.segments))

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    div(
      form(
        phx.onChangeForm(FormCodec.formData)(Msg.Change(_)),
        input(idAttr := "a", typ := "text", nameAttr := "foo"),
        input(idAttr := "b", typ := "text", nameAttr := "foo[bar]")
      ),
      span(idAttr := "target", renderTarget(model.target))
    )

  private def renderTarget(target: Option[Vector[String]]): String =
    target match
      case Some(segments) => segments.map(segment => s"\"$segment\"").mkString("[", ", ", "]")
      case None           => "nil"
end Issue3719LiveView

object Issue3719LiveView:
  final case class Model(target: Option[Vector[String]] = None)
  enum Msg:
    case Change(event: FormEvent[FormData])

class Issue2965LiveView extends LiveView[Issue2965LiveView.Msg.type, LiveUpload]:
  import Issue2965LiveView.*

  def mount =
    LiveContext
      .allowUpload(UploadName, UploadOptions)
      .catchAll(_ => ZIO.succeed(disconnectedUpload))

  def handleMessage(upload: LiveUpload) =
    case Msg => LiveContext.upload(UploadName).map(_.getOrElse(upload))

  def subscriptions(upload: LiveUpload) = ZStream.empty

  def render(upload: LiveUpload) =
    form(
      phx.onChange(Msg),
      issue2965FileInput(upload, phx.onProgress(_ => Msg)),
      table(
        tbody(
          upload.entries.splitBy(_.ref) { (_, entry) =>
            tr(
              td(entry.clientName),
              td(progressTag(value := "100", maxAttr := "100", "100%"))
            )
          }
        )
      )
    )

  private def issue2965FileInput(upload: LiveUpload, mods: Mod[Issue2965LiveView.Msg.type]*) =
    val activeRefs      = upload.entries.map(_.ref).mkString(",")
    val doneRefs        = upload.entries.filter(_.done).map(_.ref).mkString(",")
    val preflightedRefs = upload.entries.filter(entry => entry.preflighted || entry.done).map(_.ref).mkString(",")

    input(
      idAttr                           := "fileinput",
      typ                              := "file",
      nameAttr                         := upload.name,
      accept                           := upload.accept.toHtmlValue,
      dataAttr("phx-hook")             := "Phoenix.LiveFileUpload",
      dataAttr("phx-update")           := "ignore",
      dataAttr("phx-upload-ref")       := upload.ref,
      dataAttr("phx-active-refs")      := activeRefs,
      dataAttr("phx-done-refs")        := doneRefs,
      dataAttr("phx-preflighted-refs") := preflightedRefs,
      dataPhxAutoUploadAttr            := upload.autoUpload,
      multipleAttr                     := true,
      mods
    )
end Issue2965LiveView

object Issue2965LiveView:
  case object Msg

  private val UploadName = "files"
  private val UploadOptions = LiveUploadOptions(
    accept = LiveUploadAccept.Exactly(List(".txt")),
    maxEntries = 20,
    maxFileSize = 200_000,
    autoUpload = true
  )

  private def disconnectedUpload =
    LiveUpload(
      name = UploadName,
      ref = s"$UploadName-upload",
      accept = UploadOptions.accept,
      maxEntries = UploadOptions.maxEntries,
      maxFileSize = UploadOptions.maxFileSize,
      chunkSize = UploadOptions.chunkSize,
      chunkTimeout = UploadOptions.chunkTimeout,
      autoUpload = UploadOptions.autoUpload,
      external = UploadOptions.external.nonEmpty,
      entries = Nil,
      errors = Nil
    )

class Issue3814LiveView extends LiveView[Issue3814LiveView.Msg, Issue3814LiveView.Model]:
  import Issue3814LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg.Submit(event) =>
      val submitter = event.submitter.orElse(
        event.raw.get("i-am-the-submitter").map(value => FormSubmitter("i-am-the-submitter", value))
      )
      model.copy(triggerSubmit = true, submitter = submitter)

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    form(
      phx.onSubmitForm(FormCodec.formData)(Msg.Submit(_)),
      phx.triggerAction := model.triggerSubmit,
      actionAttr        := "/submit",
      methodAttr        := "post",
      input(typ := "hidden", nameAttr := "greeting", value := "hello"),
      model.submitter.map(submitter =>
        input(typ := "hidden", nameAttr := submitter.name, value := submitter.value)
      ),
      button(
        typ      := "submit",
        nameAttr := "i-am-the-submitter",
        value    := "submitter-value",
        "Submit"
      )
    )
end Issue3814LiveView

object Issue3814LiveView:
  final case class Model(triggerSubmit: Boolean = false, submitter: Option[FormSubmitter] = None)
  enum Msg:
    case Submit(event: FormEvent[FormData])

class Issue3040LiveView extends LiveView[Issue3040LiveView.Msg, Issue3040LiveView.Model]:
  import Issue3040LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg.Open   => model.copy(open = true, submitted = false)
    case Msg.Close  => model.copy(open = false)
    case Msg.Submit => model.copy(submitted = true)

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    div(
      a(href := "#", phx.onClick(Msg.Open), "Add new"),
      div(
        idAttr    := "my-modal-container",
        styleAttr := (if model.open then "position: fixed; inset: 0" else "display: none"),
        phx.onWindowKeydown(Msg.Close),
        phx.key := "Escape",
        if model.open then
          div(
            styleAttr := "margin: 320px 0 0 300px; width: 300px; padding: 20px",
            phx.onClickAway(Msg.Close),
            phx.onMounted(JS.focusFirst(to = "#my-modal-container")),
            form(
              phx.onSubmit(Msg.Submit),
              if model.submitted then "Form was submitted!" else input(nameAttr := "name")
            )
          )
        else ""
      )
    )
end Issue3040LiveView

object Issue3040LiveView:
  final case class Model(open: Boolean = false, submitted: Boolean = false)
  enum Msg:
    case Open, Close, Submit

class Issue3047LiveView(pageName: String, afterReset: Boolean)
    extends LiveView[Issue3047LiveView.Msg.type, Issue3047LiveView.Model]:
  import Issue3047LiveView.*

  def mount =
    LiveContext
      .stream(ItemsStreamDef, if afterReset then ResetItems else InitialItems)
      .map(items => Model(items))

  def handleMessage(model: Model) =
    case Msg =>
      LiveContext
        .stream(ItemsStreamDef, ResetItems, reset = true)
        .map(items => model.copy(items = items))

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    div(
      div(idAttr := "page", s"Page $pageName"),
      div(
        phx.onUpdate := "stream",
        model.items.stream { (domId, item) => span(idAttr := domId, item.id.toString) }
      ),
      button(phx.onClick(Msg), "Reset"),
      link.navigate("/issues/3047/b", "Page B")
    )
end Issue3047LiveView

object Issue3047LiveView:
  final case class Item(id: Int)
  final case class Model(items: LiveStream[Item])

  case object Msg

  private val ItemsStreamDef = LiveStreamDef.byId[Item, Int]("items")(_.id)
  private val InitialItems   = (1 to 10).map(Item(_)).toList
  private val ResetItems     = (5 to 15).map(Item(_)).toList

class Issue3529LiveView(page: String) extends LiveView[Unit, String]:
  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount = UUID.randomUUID().toString

  override def handleParams(model: String, params: Unit, url: URL) = model

  def handleMessage(model: String) = Function.const(model)

  def subscriptions(model: String) = ZStream.empty

  def render(model: String) =
    div(
      h1(s"$page $model"),
      link.navigate("/issues/3529/navigated", "Navigate"),
      link.patch("/issues/3529/navigated?patched=true", "Patch")
    )
end Issue3529LiveView

class Issue3530LiveView extends LiveView[Unit, Vector[Int]]:
  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount = Vector(1, 2, 3)

  override def handleParams(model: Vector[Int], params: Unit, url: URL) =
    if url.encode.contains("patch=a") then Vector(1, 3)
    else if url.encode.contains("patch=b") then Vector(2, 3)
    else model

  override def interceptEvent(model: Vector[Int], event: String, value: Json) =
    if event == "inc" then ZIO.succeed(InterceptResult.halt(model :+ 4))
    else ZIO.succeed(InterceptResult.cont(model))

  def handleMessage(model: Vector[Int]) = Function.const(model)

  def subscriptions(model: Vector[Int]) = ZStream.empty

  def render(items: Vector[Int]) =
    div(
      link.patch("/issues/3530?patch=a", "patch a"),
      link.patch("/issues/3530?patch=b", "patch b"),
      div(phxClickAttr := "inc", "inc"),
      div(
        items.map(item => div(idAttr := s"item-$item", phx.hook := "Issue3530Item", s"item $item"))
      )
    )
end Issue3530LiveView

class Issue3647LiveView extends LiveView[Issue3647LiveView.Msg.type, Boolean]:
  def mount = false

  def handleMessage(model: Boolean) =
    case Issue3647LiveView.Msg => true

  def subscriptions(model: Boolean) = ZStream.empty

  def render(uploaded: Boolean) =
    div(
      input(nameAttr := "user[name]", value := (if uploaded then "0" else "")),
      button(phx.onClick(Issue3647LiveView.Msg), "Upload then Input"),
      ul(if uploaded then li("file.txt") else "")
    )
end Issue3647LiveView

object Issue3647LiveView:
  case object Msg

class Issue3819LiveView extends LiveView[Issue3819LiveView.Msg, Boolean]:
  import Issue3819LiveView.*

  def mount = false

  def handleMessage(model: Boolean) =
    case Msg.Noop(_) => model

  override def interceptEvent(model: Boolean, event: String, value: Json) =
    if event == "reconnected" then ZIO.succeed(InterceptResult.halt(true))
    else ZIO.succeed(InterceptResult.cont(model))

  def subscriptions(model: Boolean) = ZStream.empty

  def render(reconnected: Boolean) =
    div(
      form(
        idAttr := "recover",
        phx.onChangeForm(Msg.Noop(_)),
        phx.onSubmitForm(Msg.Noop(_)),
        button("Submit")
      ),
      if reconnected then p(idAttr := "reconnected", "Reconnected!") else ""
    )
end Issue3819LiveView

object Issue3819LiveView:
  enum Msg:
    case Noop(data: FormData)

class Issue3107LiveView extends LiveView[Issue3107LiveView.Msg.type, Boolean]:
  def mount = true

  def handleMessage(model: Boolean) =
    case Issue3107LiveView.Msg => false

  def subscriptions(model: Boolean) = ZStream.empty

  def render(disabledButton: Boolean) =
    form(
      phx.onChange(Issue3107LiveView.Msg),
      select(
        option(value := "ONE", "ONE"),
        option(value := "TWO", "TWO")
      ),
      button(disabled := disabledButton, "OK")
    )
end Issue3107LiveView

object Issue3107LiveView:
  case object Msg

class Issue3083LiveView extends LiveView[Issue3083LiveView.Msg.type, Issue3083LiveView.Model]:
  import Issue3083LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg => model

  override def interceptEvent(model: Model, event: String, value: Json) =
    if event != "sandbox:eval" then ZIO.succeed(InterceptResult.cont(model))
    else
      val code = value match
        case Json.Obj(fields) => fields.collectFirst { case ("value", Json.Str(v)) => v }.getOrElse("")
        case _                => ""
      val selected = code match
        case value if value.contains("[1,2]") => Some(Vector(1, 2))
        case value if value.contains("[2,3]") => Some(Vector(2, 3))
        case value if value.contains("[3,4]") => Some(Vector(3, 4))
        case _                                => None

      selected match
        case Some(values) => ZIO.succeed(InterceptResult.haltReply(model.copy(selected = values), Json.Obj("result" -> Json.Null)))
        case None         => ZIO.succeed(E2ESandboxEval.handle(model, event, value))

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    form(
      idAttr := "form",
      phx.onChange(Msg),
      select(
        idAttr       := "ids",
        nameAttr     := "ids[]",
        multipleAttr := true,
        (1 to 5).map(number => option(value := number.toString, selected := model.selected.contains(number), number.toString))
      ),
      input(typ := "text", placeholderAttr := "focus me!")
    )
end Issue3083LiveView

object Issue3083LiveView:
  final case class Model(selected: Vector[Int] = Vector.empty)
  case object Msg

class Issue2787LiveView extends LiveView[Issue2787LiveView.Msg, Issue2787LiveView.Model]:
  import Issue2787LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg.Updated(data) =>
      val select1 = data.get("demo[select1]").filter(_.nonEmpty)
      val select2 = data.get("demo[select2]").filter(_.nonEmpty)
      model.copy(select1 = select1, select2 = select2)
    case Msg.Submitted(_) => Model()

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    div(
      form(
        phx.onChangeForm(Msg.Updated(_)),
        phx.onSubmitForm(Msg.Submitted(_)),
        select(
          idAttr   := "demo_select1",
          nameAttr := "demo[select1]",
          option(value := "", "Select"),
          Vector("greetings", "goodbyes").map(optionValue =>
            option(selected := model.select1.contains(optionValue), value := optionValue, optionValue)
          )
        ),
        select(
          idAttr   := "demo_select2",
          nameAttr := "demo[select2]",
          option(value := "", "Select"),
          model.select2Options.map(optionValue => option(selected := model.select2.contains(optionValue), value := optionValue, optionValue))
        ),
        input(typ := "text", idAttr := "demo_dummy", nameAttr := "demo[dummy]"),
        button(typ := "submit", "Submit")
      )
    )
end Issue2787LiveView

object Issue2787LiveView:
  final case class Model(select1: Option[String] = None, select2: Option[String] = None):
    def select2Options: Vector[String] =
      select1 match
        case Some("greetings") => Vector("hello", "hallo", "hei")
        case Some("goodbyes")  => Vector("goodbye", "auf wiedersehen", "ha det bra")
        case _                 => Vector.empty

  enum Msg:
    case Updated(data: FormData)
    case Submitted(data: FormData)

class Issue3448LiveView extends LiveView[Issue3448LiveView.Msg, Vector[String]]:
  import Issue3448LiveView.*

  def mount = Vector.empty

  def handleMessage(model: Vector[String]) =
    case Msg.Validate(data) => data.values("a[]")
    case Msg.Search        => model

  def subscriptions(model: Vector[String]) = ZStream.empty

  def render(selectedValues: Vector[String]) =
    form(
      idAttr := "my_form",
      phx.onChangeForm(Msg.Validate(_)),
      div(
        selectedValues.map(value => div(value)),
        input(idAttr := "search", typ := "search", nameAttr := "value", phx.onChange(Msg.Search))
      ),
      div(
        Vector("settings", "content").map(optionValue =>
          input(
            typ      := "checkbox",
            nameAttr := "a[]",
            value    := optionValue,
            checked  := selectedValues.contains(optionValue),
            phx.onClick(JS.dispatch("input").focus(to = "#search"))
          )
        )
      )
    )
end Issue3448LiveView

object Issue3448LiveView:
  enum Msg:
    case Validate(data: FormData)
    case Search

class Issue3194LiveView extends LiveView[Issue3194LiveView.Msg.type, Unit]:
  def mount = ()

  def handleMessage(model: Unit) =
    case Issue3194LiveView.Msg => model

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    form(
      phx.onChange(Issue3194LiveView.Msg),
      phx.onSubmit(JS.navigate("/issues/3194/other")),
      input(
        idAttr       := "foo_store_number",
        nameAttr     := "foo[store_number]",
        typ          := "text",
        phx.debounce := "blur"
      )
    )
end Issue3194LiveView

object Issue3194LiveView:
  case object Msg

class Issue3194OtherLiveView extends LiveView[Unit, Unit]:
  def mount = ()

  def handleMessage(model: Unit) = Function.const(model)

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) = h2("Another LiveView")
end Issue3194OtherLiveView

class Issue3200LiveView extends LiveView[Issue3200LiveView.Msg, Issue3200LiveView.Model]:
  import Issue3200LiveView.*

  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount = Model()

  override def handleParams(model: Model, params: Unit, url: URL) =
    val tab = url.path.segments.toList match
      case "issues" :: "3200" :: "messages" :: Nil => Tab.Messages
      case _                                        => Tab.Settings
    model.copy(tab = tab)

  def handleMessage(model: Model) =
    case Msg.Change(data) => model.copy(message = data.getOrElse("new_message", ""))
    case Msg.Submit      => model

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    div(
      button(typ := "button", phx.onClick(JS.patch("/issues/3200/messages")), "Messages tab"),
      button(typ := "button", phx.onClick(JS.patch("/issues/3200/settings")), "Settings tab"),
      model.tab match
        case Tab.Settings => div("Settings")
        case Tab.Messages =>
          div(
            div("Example message"),
            form(
              idAttr     := "full_add_message_form",
              phx.onChangeForm(Msg.Change(_)),
              phx.onSubmit(Msg.Submit),
              targetAttr := "#full_add_message_form",
              div(
                feedbackForAttr := "new_message",
                input(idAttr := "new_message_input", nameAttr := "new_message", value := model.message)
              )
            )
          )
    )
end Issue3200LiveView

object Issue3200LiveView:
  enum Tab:
    case Settings, Messages

  final case class Model(tab: Tab = Tab.Settings, message: String = "")

  enum Msg:
    case Change(data: FormData)
    case Submit

class Issue3026LiveView extends LiveView[Issue3026LiveView.Msg, Issue3026LiveView.Model]:
  import Issue3026LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg.ChangeStatus(data) => model.copy(status = Status.valueOf(data.getOrElse("status", "loaded").capitalize))
    case Msg.Validate(data)     => model.copy(name = data.getOrElse("name", model.name), email = data.getOrElse("email", model.email))
    case Msg.Submit             => model.copy(status = Status.Loaded)

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    div(
      form(
        phx.onChangeForm(Msg.ChangeStatus(_)),
        select(
          nameAttr := "status",
          Vector(Status.Connecting, Status.Loading, Status.Connected, Status.Loaded).map(status =>
            option(value := status.value, selected := (status == model.status), status.value.capitalize)
          )
        )
      ),
      model.status match
        case Status.Loaded =>
          div(
            "Example form",
            form(
              phx.onChangeForm(Msg.Validate(_)),
              phx.onSubmit(Msg.Submit),
              input(nameAttr := "name", typ := "text", value := model.name),
              input(nameAttr := "email", typ := "text", value := model.email),
              button(typ := "submit", "Submit")
            )
          )
        case other => div(cls := "p-8 bg-gray-200 mb-4", other.value)
    )
end Issue3026LiveView

object Issue3026LiveView:
  enum Status(val value: String):
    case Connecting extends Status("connecting")
    case Loading    extends Status("loading")
    case Connected  extends Status("connected")
    case Loaded     extends Status("loaded")

  final case class Model(status: Status = Status.Loaded, name: String = "John", email: String = "")

  enum Msg:
    case ChangeStatus(data: FormData)
    case Validate(data: FormData)
    case Submit

class Issue3117LiveView extends LiveView[Unit, Unit]:
  def mount = ()

  def handleMessage(model: Unit) = Function.const(model)

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    div(
      a(idAttr := "navigate", href := "/issues/3117?nav", phx.onClick(JS.navigate("/issues/3117?nav")), "Navigate"),
      (1 to 2).map(i => div(idAttr := s"row-$i", s"Example LC Row $i", div(cls := "static", "static content")))
    )
end Issue3117LiveView

class Issue3169LiveView extends LiveView[Issue3169LiveView.Msg, Option[String]]:
  import Issue3169LiveView.*

  def mount = None

  def handleMessage(model: Option[String]) =
    case Msg.Select(name) => Some(name)

  def subscriptions(model: Option[String]) = ZStream.empty

  def render(selected: Option[String]) =
    div(
      "HomeLive ",
      selected.map(name =>
        div(
          "FormComponent (c1)",
          div(
            "FormCore (c2)",
            div(
              "FormColumn (c3) ",
              input(typ := "text", value := s"Record $name"),
              div(s"Record $name", input(typ := "text", value := s"Record $name"), div(s"Record $name", input(typ := "text", value := s"Record $name"))),
              "This is a test! foo"
            )
          )
        )
      ),
      button(idAttr := "select-a", phx.value("name") := "a", phx.onClick(params => Msg.Select(params.getOrElse("name", ""))), "Select A"),
      button(idAttr := "select-b", phx.value("name") := "b", phx.onClick(params => Msg.Select(params.getOrElse("name", ""))), "Select B"),
      button(idAttr := "select-z", phx.value("name") := "z", phx.onClick(params => Msg.Select(params.getOrElse("name", ""))), "Select Z")
    )
end Issue3169LiveView

object Issue3169LiveView:
  enum Msg:
    case Select(name: String)

class Issue3378LiveView extends LiveView[Unit, Unit]:
  def mount = ()

  def handleMessage(model: Unit) = Function.const(model)

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    div(
      idAttr := "notifications",
      ul(
        idAttr       := "notifications_list",
        phx.onUpdate := "stream",
        div(idAttr := "notifications-1", p("big!"))
      )
    )
end Issue3378LiveView

class Issue3496LiveView(pageName: String, includeStickyHook: Boolean) extends LiveView[Unit, Unit]:
  def mount = ()

  def handleMessage(model: Unit) = Function.const(model)

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    div(
      h1(s"Page $pageName"),
      if pageName == "A" then link.navigate("/issues/3496/b", "Go to page B") else "",
      if includeStickyHook then div(idAttr := "my-component", phx.hook := "MyHook")
      else div(idAttr := "my-component", phx.hook := "MyHook")
    )
end Issue3496LiveView

class Issue3612LiveView(pageName: String) extends LiveView[Unit, Unit]:
  def mount = ()

  def handleMessage(model: Unit) = Function.const(model)

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    div(
      div(
        link.navigate("/issues/3612/a", "Go to page A"),
        link.navigate("/issues/3612/b", "Go to page B")
      ),
      h1(s"Page $pageName")
    )
end Issue3612LiveView

class Issue3636LiveView extends LiveView[Unit, Unit]:
  def mount = ()

  def handleMessage(model: Unit) = Function.const(model)

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    focusWrap("focus-wrap")(
      button("One"),
      button("Two"),
      button("Three")
    )
end Issue3636LiveView

class Issue3651LiveView extends LiveView[Unit, Unit]:
  def mount = ()

  def handleMessage(model: Unit) = Function.const(model)

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    div(
      div(idAttr := "notice", styleAttr := "display: none", "too many events"),
      div(idAttr := "total", "0")
    )
end Issue3651LiveView

class Issue3658LiveView extends LiveView[Issue3658LiveView.Msg, Unit]:
  import Issue3658LiveView.*

  def mount = ()

  def handleMessage(model: Unit) =
    case Msg.Noop => model

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    div(
      div(idAttr := "foo", phx.onRemove(JS.hide()), "Foo"),
      navTag(a(href := "#", phx.onClick(Msg.Noop), "Link 1"))
    )
end Issue3658LiveView

object Issue3658LiveView:
  enum Msg:
    case Noop

class Issue3681LiveView(onAway: Boolean) extends LiveView[Unit, Unit]:
  def mount = ()

  def handleMessage(model: Unit) = Function.const(model)

  def subscriptions(model: Unit) = ZStream.empty

  def render(model: Unit) =
    div(
      div(
        idAttr := "msgs-sticky",
        div(idAttr := "messages-1", "one"),
        div(idAttr := "messages-2", "two"),
        div(idAttr := "messages-4", "four")
      ),
      if onAway then link.navigate("/issues/3681", "Go back to (the now borked) LV without a stream")
      else link.navigate("/issues/3681/away", "Go to a different LV with a (funcky) stream")
    )
end Issue3681LiveView

class Issue3684LiveView extends LiveView[Issue3684LiveView.Msg, Boolean]:
  import Issue3684LiveView.*

  def mount = false

  def handleMessage(model: Boolean) =
    case Msg.Toggle => !model

  def subscriptions(model: Boolean) = ZStream.empty

  def render(model: Boolean) =
    input(idAttr := "dewey", typ := "checkbox", checked := model, phx.onClick(Msg.Toggle))
end Issue3684LiveView

object Issue3684LiveView:
  enum Msg:
    case Toggle

class Issue3686LiveView(pageName: String, flash: String) extends LiveView[Unit, String]:
  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount = flash

  override def handleParams(model: String, params: Unit, url: URL) =
    if pageName == "A" && url.encode.contains("from=c") then "Flash from C" else model

  def handleMessage(model: String) = Function.const(model)

  def subscriptions(model: String) = ZStream.empty

  def render(model: String) =
    val next = pageName match
      case "A" => "B"
      case "B" => "C"
      case _   => "A"
    val nextHref = if pageName == "C" then "/issues/3686/a?from=c" else s"/issues/3686/${next.toLowerCase}"

    div(
      div(idAttr := "flash", model),
      button(phx.onClick(JS.navigate(nextHref)), s"To $next")
    )
end Issue3686LiveView

class Issue3709LiveView(id: Int) extends LiveView[Issue3709LiveView.Msg, Int]:
  import Issue3709LiveView.*

  def mount = id

  def handleMessage(model: Int) =
    case Msg.BreakStuff => model

  def subscriptions(model: Int) = ZStream.empty

  def render(model: Int) =
    div(
      div(s"id: $model"),
      button(phx.onClick(Msg.BreakStuff), "Break Stuff"),
      link.navigate("/issues/3709/5", "Link 5")
    )
end Issue3709LiveView

object Issue3709LiveView:
  enum Msg:
    case BreakStuff

class Issue3919LiveView extends LiveView[Issue3919LiveView.Msg, Boolean]:
  import Issue3919LiveView.*

  def mount = false

  def handleMessage(model: Boolean) =
    case Msg.Toggle => !model

  def subscriptions(model: Boolean) = ZStream.empty

  def render(model: Boolean) =
    div(
      div(styleAttr := (if model then "background-color: red;" else ""), if model then "Red" else "No red"),
      button(phx.onClick(Msg.Toggle), "toggle")
    )
end Issue3919LiveView

object Issue3919LiveView:
  enum Msg:
    case Toggle

class Issue3941LiveView extends LiveView[Issue3941LiveView.Msg, Set[String]]:
  import Issue3941LiveView.*

  def mount = Set("Item_1", "Item_2")

  def handleMessage(model: Set[String]) =
    case Msg.Toggle(id) => if model.contains(id) then model - id else model + id

  def subscriptions(model: Set[String]) = ZStream.empty

  def render(model: Set[String]) =
    div(
      itemCheckbox("Item_1", model),
      itemCheckbox("Item_2", model),
      if model.contains("Item_1") then div(idAttr := "Item_1", "I AM LOADED") else "",
      if model.contains("Item_2") then div(idAttr := "Item_2", "I AM LOADED") else ""
    )

  private def itemCheckbox(id: String, selected: Set[String]) =
    input(idAttr := s"select-$id", typ := "checkbox", checked := selected.contains(id), phx.onClick(Msg.Toggle(id)))
end Issue3941LiveView

object Issue3941LiveView:
  enum Msg:
    case Toggle(id: String)

class Issue3953LiveView extends LiveView[Issue3953LiveView.Msg, Boolean]:
  import Issue3953LiveView.*

  def mount = false

  def handleMessage(model: Boolean) =
    case Msg.Toggle => !model

  def subscriptions(model: Boolean) = ZStream.empty

  def render(model: Boolean) =
    div(
      button(phx.onClick(Msg.Toggle), "Show"),
      div(idAttr := "nested_view", if model then div(dataAttr("phx-component") := "1", "component") else "")
    )
end Issue3953LiveView

object Issue3953LiveView:
  enum Msg:
    case Toggle
