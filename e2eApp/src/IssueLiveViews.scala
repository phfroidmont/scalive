import java.util.UUID

import zio.Chunk
import zio.ZIO
import zio.durationInt
import zio.http.URL
import zio.json.ast.Json

import scalive.*
import scalive.LiveIO.given

private val actionAttr      = htmlAttr("action", scalive.codecs.StringAsIsEncoder)
private val methodAttr      = htmlAttr("method", scalive.codecs.StringAsIsEncoder)
private val formAttr        = htmlAttr("form", scalive.codecs.StringAsIsEncoder)
private val multipleAttr    = htmlAttr("multiple", scalive.codecs.BooleanAsAttrPresenceEncoder)
private val placeholderAttr = htmlAttr("placeholder", scalive.codecs.StringAsIsEncoder)
private val feedbackForAttr = htmlAttr("phx-feedback-for", scalive.codecs.StringAsIsEncoder)
private val targetAttr      = htmlAttr("phx-target", scalive.codecs.StringAsIsEncoder)
private val phxClickAttr    = htmlAttr("phx-click", scalive.codecs.StringAsIsEncoder)
private val phxChangeAttr   = htmlAttr("phx-change", scalive.codecs.StringAsIsEncoder)
private val phxSubmitAttr   = htmlAttr("phx-submit", scalive.codecs.StringAsIsEncoder)
private val ariaLabelAttr   = htmlAttr("aria-label", scalive.codecs.StringAsIsEncoder)
private val onClickAttr     = htmlAttr("onclick", scalive.codecs.StringAsIsEncoder)

class Issue3719LiveView extends LiveView[Issue3719LiveView.Msg, Issue3719LiveView.Model]:
  import Issue3719LiveView.*

  def mount(ctx: MountContext) =
    Model()

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Change(event) => model.copy(target = event.target.map(_.segments))

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

object Issue3719LiveView:
  final case class Model(target: Option[Vector[String]] = None)
  enum Msg:
    case Change(event: FormEvent[FormData])

class Issue2965LiveView extends LiveView[Issue2965LiveView.Msg, Issue2965LiveView.Model]:
  import Issue2965LiveView.*

  def mount(ctx: MountContext) =
    ctx.uploads
      .allow(UploadName, UploadOptions)
      .map(upload => Model(upload = upload))
      .catchAll(_ => ZIO.succeed(Model(upload = disconnectedUpload)))

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("issue-2965") { (model, event, _) =>
      if event.bindingId == "upload_scrub_list" then
        val fileNames = fileNamesFromScrubEvent(event.value).toVector
        val reply     = Json.Obj("deduped_filenames" -> Json.Arr(fileNames.map(Json.Str(_))*))
        ZIO.succeed(LiveEventHookResult.haltReply(model.copy(fileNames = fileNames), reply))
      else ZIO.succeed(LiveEventHookResult.cont(model))
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate => refreshUpload(model, ctx.uploads)
    case Msg.Progress => refreshUpload(model, ctx.uploads).flatMap(pushNextFileEvents(_, ctx))
    case Msg.CancelUpload(ref) =>
      ctx.uploads.cancel(UploadName, ref) *> refreshUpload(model, ctx.uploads)
    case Msg.Save => model

  def render(model: Model) =
    mainTag(
      h1("Uploader reproduction"),
      form(
        phx.onSubmit(Msg.Save),
        phx.onChange(_ => Msg.Validate),
        sectionTag(
          liveFileInput(
            model.upload,
            styleAttr := "display: none;",
            phx.onProgress(_ => Msg.Progress)
          ),
          input(
            idAttr                      := "fileinput",
            typ                         := "file",
            multipleAttr                := true,
            phx.hook                    := "QueuedUploaderHook",
            dataAttr("max-concurrency") := "3",
            disabled                    := filePickerDisabled(model.upload)
          ),
          if model.fileNames.nonEmpty || model.upload.entries.nonEmpty then
            h2("Currently uploading files")
          else "",
          div(
            table(
              thead(
                tr(
                  th("File Name"),
                  th("Progress"),
                  th("Cancel"),
                  th("Errors")
                )
              ),
              tbody(
                uploadRows(model)
              )
            )
          ),
          uploadErrors(model.upload).splitBy(_.toString) { (_, error) =>
            p(styleAttr := "color: red;", errorToString(error))
          }
        )
      )
    )

  private def refreshUpload(model: Model, uploads: Uploads): LiveIO[Model] =
    uploads.get(UploadName).map {
      case Some(upload) => model.copy(upload = upload)
      case None         => model
    }

  private def pushNextFileEvents(model: Model, ctx: MessageContext): LiveIO[Model] =
    val completedRefs = model.upload.entries.filter(_.done).map(_.ref).toSet
    val newRefs       = completedRefs -- model.nextFileSentFor

    ZIO
      .foreachDiscard(newRefs)(_ =>
        ctx.client.pushEvent("upload_send_next_file", Map.empty[String, String])
      )
      .as(
        model.copy(
          completedCount = (model.completedCount + newRefs.size).min(model.fileNames.size),
          nextFileSentFor = model.nextFileSentFor ++ newRefs
        )
      )

  private def filePickerDisabled(upload: LiveUpload): Boolean =
    upload.entries.exists(entry => !entry.done)

  private def uploadRows(model: Model): Mod[Msg] =
    if model.fileNames.nonEmpty then
      model.fileNames.zipWithIndex.splitBy { case (fileName, _) => fileName } {
        case (_, (fileName, index)) =>
          queuedUploadRow(fileName, if index < model.completedCount then 100 else 0)
      }
    else
      model.upload.entries.filter(_.clientName.nonEmpty).splitBy(_.ref) { (_, entry) =>
        uploadEntryRow(model.upload, entry)
      }

  private def queuedUploadRow(fileName: String, progress: Int) =
    tr(
      td(fileName),
      td(progressTag(value := progress.toString, maxAttr := "100", s"$progress%")),
      td(button(typ := "button", ariaLabelAttr := "cancel", span("x"))),
      td()
    )

  private def uploadEntryRow(upload: LiveUpload, entry: LiveUploadEntry) =
    tr(
      td(entry.clientName),
      td(
        progressTag(
          value   := entry.progress.toString,
          maxAttr := "100",
          s"${entry.progress}%"
        )
      ),
      td(
        button(
          typ := "button",
          phx.onClick(params => Msg.CancelUpload(params.getOrElse("ref", ""))),
          phx.value("ref") := entry.ref,
          ariaLabelAttr    := "cancel",
          span("x")
        )
      ),
      td(
        uploadErrors(upload, entry).splitBy(_.toString) { (_, error) =>
          p(styleAttr := "color: red;", errorToString(error))
        }
      )
    )
end Issue2965LiveView

object Issue2965LiveView:
  final case class Model(
    upload: LiveUpload,
    fileNames: Vector[String] = Vector.empty,
    completedCount: Int = 0,
    nextFileSentFor: Set[String] = Set.empty)

  enum Msg:
    case Validate
    case Progress
    case CancelUpload(ref: String)
    case Save

  private val UploadName    = "files"
  private val UploadOptions = LiveUploadOptions(
    accept = LiveUploadAccept.Any,
    maxEntries = 1500,
    maxFileSize = 10_000_000_000L,
    chunkSize = 5 * 1024 * 1024,
    autoUpload = true,
    writer = NoOpWriter
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

  private def fileNamesFromScrubEvent(value: Json): List[String] =
    value match
      case Json.Obj(fields) =>
        fields
          .collectFirst { case ("file_names", Json.Arr(values)) =>
            values.collect { case Json.Str(name) => name }.toList
          }.getOrElse(Nil)
      case _ => Nil

  private def errorToString(error: LiveUploadError): String =
    error match
      case LiveUploadError.TooLarge    => "Too large"
      case LiveUploadError.NotAccepted => "You have selected an unacceptable file type"
      case LiveUploadError.External(_) => "Error on writing to cloudflare"
      case _                           => "unknown error"

  private object NoOpWriter extends LiveUploadWriter:
    def init(uploadName: String, entry: LiveExternalUploadEntry) =
      LiveUploadWriter.InMemory.init(uploadName, entry)

    def meta(state: LiveUploadWriterState) =
      LiveUploadWriter.InMemory.meta(state)

    def writeChunk(data: Chunk[Byte], state: LiveUploadWriterState) =
      zio.Random
        .nextIntBetween(1, 201).flatMap(delay =>
          ZIO.sleep(delay.millis) *> LiveUploadWriter.InMemory.writeChunk(data, state)
        )

    def close(state: LiveUploadWriterState, reason: LiveUploadWriterCloseReason) =
      LiveUploadWriter.InMemory.close(state, reason)
end Issue2965LiveView

class Issue3814LiveView extends LiveView[Issue3814LiveView.Msg, Issue3814LiveView.Model]:
  import Issue3814LiveView.*

  def mount(ctx: MountContext) =
    Model()

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("issue-3814") { (model, event, _) =>
      if event.bindingId == "submit" then LiveEventHookResult.halt(model.copy(triggerSubmit = true))
      else LiveEventHookResult.cont(model)
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg) => model

  def render(model: Model) =
    form(
      phxSubmitAttr     := "submit",
      phx.triggerAction := model.triggerSubmit,
      actionAttr        := "/submit",
      methodAttr        := "post",
      input(typ := "hidden", nameAttr := "greeting", value := "hello"),
      button(
        typ      := "submit",
        nameAttr := "i-am-the-submitter",
        value    := "submitter-value",
        "Submit"
      )
    )

object Issue3814LiveView:
  final case class Model(triggerSubmit: Boolean = false)
  enum Msg:
    case Noop

class Issue3040LiveView extends LiveView[Issue3040LiveView.Msg, Issue3040LiveView.Model]:
  import Issue3040LiveView.*

  def mount(ctx: MountContext) =
    Model()

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Open   => model.copy(open = true, submitted = false)
    case Msg.Close  => model.copy(open = false)
    case Msg.Submit => model.copy(submitted = true)

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

class Issue3047LiveView(pageName: String) extends LiveView[Unit, Unit]:

  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    span(idAttr := "page", s"Page $pageName")

object Issue3047LiveView:
  val Layout: LiveLayout[Any, Any] = LiveLayout[Any, Any]((content, _) =>
    div(
      div(
        link.navigate("/issues/3047/a", "Page A"),
        link.navigate("/issues/3047/b", "Page B")
      ),
      content,
      liveView("test", Issue3047LiveView.Sticky(), sticky = true)
    )
  )

  class Sticky extends LiveView[Reset.type, Model]:
    def mount(ctx: MountContext) =
      ctx.streams.init(ItemsStreamDef, InitialItems).map(items => Model(items))

    def handleMessage(model: Model, ctx: MessageContext) =
      (_: Reset.type) =>
        ctx.streams
          .init(ItemsStreamDef, ResetItems, reset = true)
          .map(items => model.copy(items = items))

    def render(model: Model) =
      div(
        styleAttr := "border: 2px solid black;",
        h1("This is the sticky liveview"),
        div(
          idAttr       := "items",
          phx.onUpdate := "stream",
          styleAttr    := "display: flex; flex-direction: column; gap: 4px;",
          model.items.stream((domId, item) => span(idAttr := domId, item.name))
        ),
        button(phx.onClick(Reset), "Reset")
      )

  final case class Item(id: Int, name: String)
  final case class Model(items: LiveStream[Item])

  case object Reset

  private val ItemsStreamDef = LiveStreamDef.byId[Item, Int]("items")(_.id)
  private val InitialItems   = (1 to 10).map(id => Item(id, s"item-$id")).toList
  private val ResetItems     = (5 to 15).map(id => Item(id, s"item-$id")).toList
end Issue3047LiveView

class Issue3529LiveView(page: String) extends LiveView[Unit, String]:
  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount(ctx: MountContext) =
    UUID.randomUUID().toString

  override def handleParams(model: String, params: Unit, url: URL, ctx: ParamsContext) =
    model

  def handleMessage(model: String, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: String) =
    div(
      h1(s"$page $model"),
      link.navigate("/issues/3529/navigated", "Navigate"),
      link.patch("/issues/3529/navigated?patched=true", "Patch")
    )

class Issue3530LiveView extends LiveView[Unit, Issue3530LiveView.Model]:
  import Issue3530LiveView.*

  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount(ctx: MountContext) =
    ctx.streams
      .init(ItemsStream, List.empty[Item])
      .map(items => Model(count = 3, items = items))

  override def handleParams(model: Model, params: Unit, url: URL, ctx: ParamsContext) =
    val itemIds = url.queryParam("q") match
      case Some("a") => List(1, 3)
      case Some("b") => List(2, 3)
      case _         => List(1, 2, 3)

    ctx.streams
      .init(ItemsStream, itemIds.map(Item(_)), reset = true)
      .map(items => model.copy(items = items))

  override def hooks: LiveHooks[Unit, Model] =
    LiveHooks.empty.rawEvent("inc") { (model, event, ctx) =>
      if event.bindingId == "inc" then
        val nextCount = model.count + 1
        ctx.streams
          .insert(ItemsStream, Item(nextCount))
          .map(items =>
            LiveEventHookResult.halt(
              model.copy(count = nextCount, items = items)
            )
          )
      else LiveEventHookResult.cont(model)
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Model) =
    div(
      ul(
        idAttr       := "stream-list",
        phx.onUpdate := "stream",
        model.items.stream((domId, item) =>
          div(
            idAttr := domId,
            liveView(domId, NestedLive(item.id))
          )
        )
      ),
      link.patch("/issues/3530?q=a", "patch a"),
      link.patch("/issues/3530?q=b", "patch b"),
      div(phxClickAttr := "inc", "+")
    )

end Issue3530LiveView

object Issue3530LiveView:
  final case class Item(id: Int)
  final case class Model(
    count: Int,
    items: LiveStream[Item])

  private val ItemsStream = LiveStreamDef.byId[Item, Int]("item")(_.id)

  class NestedLive(itemId: Int) extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ()

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(model: Unit) =
      div(
        idAttr := s"item-outer-$itemId",
        "test hook with nested liveview",
        div(idAttr := s"test-hook-$itemId", phx.hook := "test")
      )

class Issue3647LiveView extends LiveView[Issue3647LiveView.Msg, Issue3647LiveView.Model]:
  import Issue3647LiveView.*

  def mount(ctx: MountContext) =
    ctx.uploads
      .allow(UploadName, UploadOptions)
      .map(upload => Model(upload = upload))
      .catchAll(_ => ZIO.succeed(Model(upload = disconnectedUpload)))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ValidateUser(event) =>
      model.copy(userName = event.raw.getOrElse("user[name]", ""))
    case Msg.Validate =>
      refreshUpload(model, ctx.uploads)
    case Msg.Progress =>
      saveCompletedEntries(model, ctx.uploads)
    case Msg.CancelUpload(ref) =>
      ctx.uploads.cancel(UploadName, ref) *> refreshUpload(model, ctx.uploads)

  def render(model: Model) =
    div(
      form(
        idAttr := "user-form",
        phx.onChangeForm(FormCodec.formData)(Msg.ValidateUser(_)),
        input(
          idAttr   := "user_name",
          nameAttr := "user[name]",
          value    := model.userName,
          typ      := "text"
        ),
        button(idAttr := "x", typ := "button", phx.hook := "JsUpload", "Upload then Input"),
        button(
          idAttr             := "y",
          typ                := "button",
          phx.hook           := "JsUpload",
          dataAttr("before") := "true",
          "Input then Upload"
        ),
        liveFileInput(
          model.upload,
          formAttr := "auto-form",
          phx.onProgress(_ => Msg.Progress)
        )
      ),
      form(idAttr := "auto-form", phx.onChange(_ => Msg.Validate)),
      sectionTag(
        cls            := "pending-uploads",
        phx.dropTarget := model.upload.ref,
        styleAttr      := "min-height: 100%;",
        h3(s"Pending Uploads (${model.upload.entries.length})"),
        model.upload.entries.splitBy(_.ref) { (_, entry) =>
          div(
            progressTag(
              value   := entry.progress.toString,
              maxAttr := "100",
              s"${entry.progress}%"
            ),
            div(
              entry.ref,
              br(),
              a(
                href := "#",
                phx.onClick(params => Msg.CancelUpload(params.getOrElse("ref", ""))),
                phx.value("ref") := entry.ref,
                cls              := "upload-entry__cancel",
                "Cancel Upload"
              )
            )
          )
        }
      ),
      ul(
        model.uploadedFiles.splitBy(identity) { (_, fileName) =>
          li(a(href := fileName, fileName))
        }
      )
    )

  private def refreshUpload(model: Model, uploads: Uploads): LiveIO[Model] =
    uploads.get(UploadName).map {
      case Some(upload) => model.copy(upload = upload)
      case None         => model
    }

  private def saveCompletedEntries(model: Model, uploads: Uploads): LiveIO[Model] =
    for
      consumed  <- uploads.consumeCompleted(UploadName)
      refreshed <- uploads.get(UploadName)
    yield model.copy(
      upload = refreshed.getOrElse(model.upload),
      uploadedFiles = model.uploadedFiles ++ consumed.map(_.name)
    )
end Issue3647LiveView

object Issue3647LiveView:
  enum Msg:
    case ValidateUser(event: FormEvent[FormData])
    case Validate
    case Progress
    case CancelUpload(ref: String)

  final case class Model(
    upload: LiveUpload,
    userName: String = "",
    uploadedFiles: List[String] = Nil)

  private val UploadName = "avatar"

  private val UploadOptions = LiveUploadOptions(
    accept = LiveUploadAccept.Exactly(List(".txt", ".md")),
    maxEntries = 2,
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
end Issue3647LiveView

class Issue3819LiveView extends LiveView[Issue3819LiveView.Msg, Boolean]:
  import Issue3819LiveView.*

  def mount(ctx: MountContext) =
    false

  def handleMessage(model: Boolean, ctx: MessageContext) =
    case Msg.Noop(_) => model

  override def hooks: LiveHooks[Msg, Boolean] =
    LiveHooks.empty.rawEvent("reconnected") { (model, event, _) =>
      if event.bindingId == "reconnected" then LiveEventHookResult.halt(true)
      else LiveEventHookResult.cont(model)
    }

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

object Issue3819LiveView:
  enum Msg:
    case Noop(data: FormData)

class Issue3107LiveView extends LiveView[Issue3107LiveView.Msg.type, Boolean]:
  def mount(ctx: MountContext) =
    true

  def handleMessage(model: Boolean, ctx: MessageContext) =
    (_: Issue3107LiveView.Msg.type) => false

  def render(disabledButton: Boolean) =
    form(
      phx.onChange(Issue3107LiveView.Msg),
      select(
        option(value := "ONE", "ONE"),
        option(value := "TWO", "TWO")
      ),
      button(disabled := disabledButton, "OK")
    )

object Issue3107LiveView:
  case object Msg

class Issue3083LiveView extends LiveView[Issue3083LiveView.Msg.type, Issue3083LiveView.Model]:
  import Issue3083LiveView.*

  def mount(ctx: MountContext) =
    Model()

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg.type) => model

  override def hooks: LiveHooks[Msg.type, Model] =
    LiveHooks.empty.rawEvent("sandbox") { (model, event, _) =>
      if event.bindingId != "sandbox:eval" then LiveEventHookResult.cont(model)
      else
        val code = event.value match
          case Json.Obj(fields) =>
            fields.collectFirst { case ("value", Json.Str(v)) => v }.getOrElse("")
          case _ => ""
        val selected = code match
          case value if value.contains("[1,2]") => Some(Vector(1, 2))
          case value if value.contains("[2,3]") => Some(Vector(2, 3))
          case value if value.contains("[3,4]") => Some(Vector(3, 4))
          case _                                => None

        selected match
          case Some(values) =>
            LiveEventHookResult.haltReply(
              model.copy(selected = values),
              Json.Obj("result" -> Json.Null)
            )
          case None => E2ESandboxEval.handle(model, event.bindingId, event.value)
    }

  def render(model: Model) =
    form(
      idAttr := "form",
      phx.onChange(Msg),
      select(
        idAttr       := "ids",
        nameAttr     := "ids[]",
        multipleAttr := true,
        (1 to 5).map(number =>
          option(
            value    := number.toString,
            selected := model.selected.contains(number),
            number.toString
          )
        )
      ),
      input(typ := "text", placeholderAttr := "focus me!")
    )
end Issue3083LiveView

object Issue3083LiveView:
  final case class Model(selected: Vector[Int] = Vector.empty)
  case object Msg

class Issue2787LiveView extends LiveView[Issue2787LiveView.Msg, Issue2787LiveView.Model]:
  import Issue2787LiveView.*

  def mount(ctx: MountContext) =
    Model()

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Updated(data) =>
      val select1 = data.get("demo[select1]").filter(_.nonEmpty)
      val select2 = data.get("demo[select2]").filter(_.nonEmpty)
      model.copy(select1 = select1, select2 = select2)
    case Msg.Submitted(_) => Model()

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
            option(
              selected := model.select1.contains(optionValue),
              value    := optionValue,
              optionValue
            )
          )
        ),
        select(
          idAttr   := "demo_select2",
          nameAttr := "demo[select2]",
          option(value := "", "Select"),
          model.select2Options.map(optionValue =>
            option(
              selected := model.select2.contains(optionValue),
              value    := optionValue,
              optionValue
            )
          )
        ),
        input(typ  := "text", idAttr := "demo_dummy", nameAttr := "demo[dummy]"),
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

  def mount(ctx: MountContext) =
    Vector.empty

  def handleMessage(model: Vector[String], ctx: MessageContext) =
    case Msg.Validate(data) => data.values("a[]")
    case Msg.Search         => model

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

class Issue3194LiveView extends LiveView[Issue3194LiveView.Msg, Unit]:
  import Issue3194LiveView.*

  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    case Msg.Validate => model
    case Msg.Submit   => ctx.nav.pushNavigate("/issues/3194/other").as(model)

  def render(model: Unit) =
    form(
      phx.onChange(Msg.Validate),
      phx.onSubmit(Msg.Submit),
      input(
        idAttr       := "foo_store_number",
        nameAttr     := "foo[store_number]",
        typ          := "text",
        phx.debounce := "blur"
      )
    )

object Issue3194LiveView:
  enum Msg:
    case Validate, Submit

class Issue3194OtherLiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) = h2("Another LiveView")

class Issue3200LiveView extends LiveView[Issue3200LiveView.Msg, Issue3200LiveView.Model]:
  import Issue3200LiveView.*

  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount(ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: Unit, url: URL, ctx: ParamsContext) =
    val _   = (params, ctx)
    val tab = url.path.segments.toList match
      case "issues" :: "3200" :: "messages" :: Nil => Tab.Messages
      case _                                       => Tab.Settings
    model.copy(tab = tab)

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg) => model

  def render(model: Model) =
    div(
      button(typ := "button", phx.onClick(JS.patch("/issues/3200/messages")), "Messages tab"),
      button(typ := "button", phx.onClick(JS.patch("/issues/3200/settings")), "Settings tab"),
      model.tab match
        case Tab.Settings => liveComponent(SettingsTab, id = "settings_tab", props = ())
        case Tab.Messages => liveComponent(MessagesTab, id = "messages_tab", props = ())
    )

object Issue3200LiveView:
  enum Tab:
    case Settings, Messages

  final case class Model(tab: Tab = Tab.Settings)

  enum Msg:
    case Noop

  object SettingsTab extends LiveComponent[Unit, Unit, Unit]:
    def mount(props: Unit, ctx: MountContext) =
      ()

    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(props: Unit, model: Unit, self: ComponentRef[Unit]) =
      div("Settings")

  object MessagesTab extends LiveComponent[Unit, MessagesTab.Msg, String]:
    enum Msg:
      case Change(data: FormData)
      case Submit

    def mount(props: Unit, ctx: MountContext) =
      ""

    def handleMessage(props: Unit, model: String, ctx: MessageContext) =
      case Msg.Change(data) => data.getOrElse("new_message", "")
      case Msg.Submit       => model

    def render(props: Unit, model: String, self: ComponentRef[Msg]) =
      div(
        liveComponent(MessageComponent, id = "some_unique_message_id", props = "Example message"),
        form(
          idAttr := "full_add_message_form",
          phx.onChangeForm(Msg.Change(_)),
          phx.onSubmit(Msg.Submit),
          phx.target("#full_add_message_form"),
          inputComponent(model)
        )
      )

    private def inputComponent(inputValue: String) =
      div(
        feedbackForAttr := "new_message",
        input(idAttr := "new_message_input", nameAttr := "new_message", value := inputValue)
      )

  object MessageComponent extends LiveComponent[String, Unit, String]:
    def mount(props: String, ctx: MountContext) =
      props

    override def update(props: String, model: String, ctx: UpdateContext) =
      props

    def handleMessage(props: String, model: String, ctx: MessageContext) =
      (_: Unit) => model

    def render(props: String, model: String, self: ComponentRef[Unit]) =
      div(model)
end Issue3200LiveView

class Issue3026LiveView extends LiveView[Issue3026LiveView.Msg, Issue3026LiveView.Model]:
  import Issue3026LiveView.*

  def mount(ctx: MountContext) =
    if ctx.connected then startLoad(ctx.async).as(Model(status = Status.Loading))
    else Model(status = Status.Connecting)

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("issue-3026-form") { (model, event, ctx) =>
      event.bindingId match
        case "validate" =>
          val data = event.value.asString
            .map(FormData.fromUrlEncoded).getOrElse(FormData.fromMap(event.params))
          LiveEventHookResult.halt(
            model.copy(
              name = data.getOrElse("name", model.name),
              email = data.getOrElse("email", model.email)
            )
          )
        case "submit" =>
          startLoad(ctx.async).as(LiveEventHookResult.halt(model.copy(status = Status.Loading)))
        case _ => LiveEventHookResult.cont(model)
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ChangeStatus(data) =>
      model.copy(status = Status.valueOf(data.getOrElse("status", "loaded").capitalize))
    case Msg.Loaded(result) =>
      model.copy(status = Status.Loaded, name = result.name, email = result.email)

  def render(model: Model) =
    div(
      form(
        phx.onChangeForm(Msg.ChangeStatus(_)),
        select(
          nameAttr := "status",
          Vector(Status.Connecting, Status.Loading, Status.Connected, Status.Loaded).map(status =>
            option(
              value    := status.value,
              selected := (status == model.status),
              status.value.capitalize
            )
          )
        )
      ),
      model.status match
        case Status.Loaded =>
          liveComponent(
            Issue3026FormComponent,
            id = "my-form",
            props = FormProps(model.name, model.email)
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
  final case class LoadResult(name: String, email: String)
  final case class FormProps(name: String, email: String)

  enum Msg:
    case ChangeStatus(data: FormData)
    case Loaded(result: LoadResult)

  private def startLoad(async: Async[Msg]) =
    async.start("load")(ZIO.sleep(200.millis).as(LoadResult("John", "")))(Msg.Loaded(_))

  object Issue3026FormComponent extends LiveComponent[FormProps, Unit, Unit]:
    def mount(props: FormProps, ctx: MountContext) =
      ()

    def handleMessage(props: FormProps, model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(props: FormProps, model: Unit, self: ComponentRef[Unit]) =
      div(
        "Example form",
        form(
          phxChangeAttr := "validate",
          phxSubmitAttr := "submit",
          input(nameAttr := "name", typ  := "text", value := props.name),
          input(nameAttr := "email", typ := "text", value := props.email),
          button(typ     := "submit", "Submit")
        )
      )
end Issue3026LiveView

class Issue3117LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(
      link.navigate("/issues/3117?nav", idAttr := "navigate", "Navigate"),
      (1 to 2).map(i =>
        div(liveComponent(Issue3117LiveView.Row, id = s"row-$i", props = s"row-$i"))
      )
    )

object Issue3117LiveView:
  object Row extends LiveComponent[String, Row.Msg, Row.Model]:
    enum Msg:
      case Loaded(value: String)

    final case class Model(result: Option[String] = None, started: Boolean = false)

    def mount(props: String, ctx: MountContext) =
      Model()

    override def update(props: String, model: Model, ctx: UpdateContext) =
      if model.started then model
      else ctx.async.start("foo")(ZIO.succeed("bar"))(Msg.Loaded(_)).as(model.copy(started = true))

    def handleMessage(props: String, model: Model, ctx: MessageContext) =
      case Msg.Loaded(value) => model.copy(result = Some(value))

    def render(props: String, model: Model, self: ComponentRef[Msg]) =
      val result = model.result.map(value => s"Some($value)").getOrElse("None")
      div(
        idAttr := props,
        s"Example LC Row $result",
        div(cls := "static", "static content")
      )

class Issue3169LiveView extends LiveView[Issue3169LiveView.Msg, Option[String]]:
  import Issue3169LiveView.*

  def mount(ctx: MountContext) =
    None

  def handleMessage(model: Option[String], ctx: MessageContext) =
    case Msg.Select(name) => Some(name)

  def render(selected: Option[String]) =
    div(
      "HomeLive ",
      liveComponent(FormComponent, id = "form_view", props = selected),
      button(
        idAttr            := "select-a",
        phx.value("name") := "a",
        phx.onClick(params => Msg.Select(params.getOrElse("name", ""))),
        "Select A"
      ),
      button(
        idAttr            := "select-b",
        phx.value("name") := "b",
        phx.onClick(params => Msg.Select(params.getOrElse("name", ""))),
        "Select B"
      ),
      button(
        idAttr            := "select-z",
        phx.value("name") := "z",
        phx.onClick(params => Msg.Select(params.getOrElse("name", ""))),
        "Select Z"
      )
    )
end Issue3169LiveView

object Issue3169LiveView:
  enum Msg:
    case Select(name: String)

  final case class Record(id: Int, name: String)

  object FormComponent extends LiveComponent[Option[String], FormComponent.Msg, Option[Record]]:
    enum Msg:
      case Loaded(record: Record)

    def mount(props: Option[String], ctx: MountContext) =
      None

    override def update(props: Option[String], model: Option[Record], ctx: UpdateContext) =
      props match
        case Some(name) =>
          ctx.async
            .start("load")(
              ZIO.sleep(50.millis).as(Record(scala.util.Random.nextInt(1000000), s"Record $name"))
            )(Msg.Loaded(_))
            .as(None)
        case None => model

    def handleMessage(props: Option[String], model: Option[Record], ctx: MessageContext) =
      case Msg.Loaded(record) => Some(record)

    def render(props: Option[String], model: Option[Record], self: ComponentRef[Msg]) =
      div(
        "FormComponent (c1)",
        model.map(record => div(liveComponent(FormCore, id = "core", props = record))),
        hr()
      )

  object FormCore extends LiveComponent[Record, Unit, Record]:
    def mount(props: Record, ctx: MountContext) =
      props

    override def update(props: Record, model: Record, ctx: UpdateContext) =
      props

    def handleMessage(props: Record, model: Record, ctx: MessageContext) =
      (_: Unit) => model

    def render(props: Record, model: Record, self: ComponentRef[Unit]) =
      div(
        "FormCore (c2)",
        form(liveComponent(FormColumn, id = s"column-${model.id}", props = model))
      )

  object FormColumn extends LiveComponent[Record, Unit, Record]:
    def mount(props: Record, ctx: MountContext) =
      props

    override def update(props: Record, model: Record, ctx: UpdateContext) =
      props

    def handleMessage(props: Record, model: Record, ctx: MessageContext) =
      (_: Unit) => model

    def render(props: Record, model: Record, self: ComponentRef[Unit]) =
      div(
        "FormColumn (c3) ",
        input(typ := "text", value := model.name),
        inputComponent(model),
        testComponent("foo")
      )

  private def inputComponent(record: Record) =
    div(
      record.name,
      input(typ := "text", value := record.name),
      inputTwo(record)
    )

  private def inputTwo(record: Record) =
    div(
      record.name,
      input(typ := "text", value := record.name)
    )

  private def testComponent(value: String) =
    div("This is a test! ", value)
end Issue3169LiveView

class Issue3378LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(liveView("appbar", Issue3378LiveView.AppBarLive()))

object Issue3378LiveView:
  final case class Notification(id: Int, message: String)

  private val NotificationsStream =
    LiveStreamDef.byId[Notification, Int]("notifications")(_.id)

  class AppBarLive extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ()

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(model: Unit) =
      div(liveView("notifications", NotificationsLive()))

  class NotificationsLive extends LiveView[Unit, LiveStream[Notification]]:
    def mount(ctx: MountContext) =
      ctx.streams.init(NotificationsStream, List(Notification(1, "Hello")))

    def handleMessage(model: LiveStream[Notification], ctx: MessageContext) =
      (_: Unit) => model

    def render(model: LiveStream[Notification]) =
      div(
        ul(
          idAttr       := "notifications_list",
          phx.onUpdate := "stream",
          model.stream((domId, _) => div(idAttr := domId, p("big!")))
        )
      )
end Issue3378LiveView

class Issue3496LiveView(pageName: String, includeStickyHook: Boolean) extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(
      h1(s"Page $pageName"),
      if pageName == "A" then link.navigate("/issues/3496/b", "Go to page B") else "",
      if includeStickyHook then liveView("sticky", Issue3496LiveView.StickyLive(), sticky = true)
      else Issue3496LiveView.myComponent
    )

object Issue3496LiveView:
  def myComponent =
    div(idAttr := "my-component", phx.hook := "MyHook")

  class StickyLive extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ()

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(model: Unit) =
      div(myComponent)

class Issue3612LiveView(pageName: String) extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(
      liveView("sticky", Issue3612LiveView.StickyLive(), sticky = true),
      h1(s"Page $pageName")
    )

object Issue3612LiveView:
  enum Msg:
    case NavigateToA, NavigateToB

  class StickyLive extends LiveView[Msg, Unit]:
    import Msg.*

    def mount(ctx: MountContext) =
      ()

    def handleMessage(model: Unit, ctx: MessageContext) =
      case NavigateToA => ctx.nav.pushNavigate("/issues/3612/a").as(model)
      case NavigateToB => ctx.nav.pushNavigate("/issues/3612/b").as(model)

    def render(model: Unit) =
      div(
        a(href := "#", phx.onClick(NavigateToA), "Go to page A"),
        a(href := "#", phx.onClick(NavigateToB), "Go to page B")
      )

class Issue3636LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    focusWrap("focus-wrap")(
      button("One"),
      button("Two"),
      button("Three")
    )

class Issue3651LiveView extends LiveView[Issue3651LiveView.Msg, Issue3651LiveView.Model]:
  import Issue3651LiveView.*

  def mount(ctx: MountContext) =
    val init = Model()
    if ctx.connected then
      ctx.async.start("change-id")(ZIO.unit)(_ => Msg.ChangeId) *>
        ctx.client.pushEvent("myevent", Map.empty[String, String]).as(init)
    else init

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("issue-3651") { (model, event, ctx) =>
      event.bindingId match
        case "lol" =>
          LiveEventHookResult.halt(model)
        case "reload" =>
          val next = model.copy(counter = model.counter + 1)
          ctx.client
            .pushEvent("myevent", Map.empty[String, String]).as(LiveEventHookResult.halt(next))
        case _ =>
          LiveEventHookResult.cont(model)
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ChangeId => model.copy(id = 2)

  def render(model: Model) =
    div(
      div(
        idAttr   := "main",
        phx.hook := "OuterHook",
        div(phx.hook := "InnerHook", idAttr := s"id-${model.id}"),
        "This is an example of nested hooks resulting in a ghost element that isn't on the DOM, and is never cleaned up.",
        p("Doing any of the following things fixes it:"),
        ol(
          li("Setting the phx-hook to use a fixed id."),
          li("Removing the pushEvent from the OuterHook mounted callback."),
          li("Deferring the pushEvent by wrapping it in a setTimeout.")
        )
      ),
      div(
        "To prevent blowing up your computer, the page will reload after 4096 events, which takes ~12 seconds"
      ),
      div(
        styleAttr := "color: blue; font-size: 20px",
        idAttr    := "counter",
        "Total Event Calls: ",
        span(idAttr := "total", model.counter.toString)
      ),
      div(
        styleAttr    := "color: red; font-size: 72px",
        idAttr       := "notice",
        phx.onUpdate := "ignore",
        "I will disappear if the bug is not present."
      )
    )
end Issue3651LiveView

object Issue3651LiveView:
  enum Msg:
    case ChangeId

  final case class Model(id: Int = 1, counter: Int = 0)

class Issue3658LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(
      link.navigate("/issues/3658?navigated=true", "Link 1"),
      liveView("sticky", Issue3658LiveView.Sticky(), sticky = true)
    )

object Issue3658LiveView:
  class Sticky extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ()

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(model: Unit) =
      div(
        div(idAttr := "foo", phx.onRemove(JS.dispatch("my-event")), "Hi")
      )

class Issue3656LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(
      styleTag(
        "* { font-size: 1.1em }",
        "nav { margin-top: 1em }",
        "nav a { padding: 8px 16px; border: 1px solid black; text-decoration: none }",
        "nav a:visited { color: inherit }",
        "nav a.active { border: 3px solid green }",
        "nav a.phx-click-loading { animation: pulsate 2s infinite }",
        "@keyframes pulsate {",
        "  0% { background-color: white; }",
        "  50% { background-color: red; }",
        "  100% { background-color: white; }",
        "}"
      ),
      liveView("sticky", Issue3656LiveView.StickyLive(), sticky = true)
    )

object Issue3656LiveView:
  class StickyLive extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ()

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(model: Unit) =
      navTag(
        link.navigate("/issues/3656?navigated=true", "Link 1")
      )

class Issue3681LiveView(onAway: Boolean) extends LiveView[Unit, Issue3681LiveView.Model]:
  import Issue3681LiveView.*

  def mount(ctx: MountContext) =
    if onAway then
      for
        _        <- ctx.streams.init(MessagesStream, List.empty[Message])
        messages <- ctx.streams.init(MessagesStream, List(Message(4, 4)), reset = true)
      yield Model(Some(messages))
    else Model(None)

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Model) =
    div(
      liveView("sticky", Issue3681LiveView.StickyLive(), sticky = true),
      hr(),
      if onAway then
        div(
          h3("A liveview with a stream configured twice"),
          h4("This causes the nested liveview in the layout above to be reset by the client."),
          link.navigate("/issues/3681", "Go back to (the now borked) LV without a stream"),
          h1("Normal Stream"),
          div(
            idAttr       := "msgs-normal",
            phx.onUpdate := "stream",
            model.messages.map(
              _.stream((domId, message) => div(idAttr := domId, div(message.value.toString)))
            )
          )
        )
      else
        div(
          h3("A LiveView that does nothing but render it's layout."),
          link.navigate("/issues/3681/away", "Go to a different LV with a (funcky) stream")
        )
      ,
      hr()
    )
end Issue3681LiveView

object Issue3681LiveView:
  final case class Message(id: Int, value: Int = 0)
  final case class Model(messages: Option[LiveStream[Message]])

  private val MessagesStream = LiveStreamDef.byId[Message, Int]("messages")(_.id)

  class StickyLive extends LiveView[Unit, LiveStream[Message]]:
    def mount(ctx: MountContext) =
      ctx.streams.init(MessagesStream, List(Message(1, 1), Message(2, 2), Message(3, 3)))

    def handleMessage(model: LiveStream[Message], ctx: MessageContext) =
      (_: Unit) => model

    def render(messages: LiveStream[Message]) =
      div(
        idAttr       := "msgs-sticky",
        phx.onUpdate := "stream",
        messages.stream((domId, message) => div(idAttr := domId, div(message.value.toString)))
      )

class Issue3684LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(liveComponent(Issue3684LiveView.BadgeForm, id = "badge_form", props = ()))

object Issue3684LiveView:
  object BadgeForm extends LiveComponent[Unit, BadgeForm.Msg, String]:
    enum Msg:
      case ChangeType(value: String)

    def mount(props: Unit, ctx: MountContext) =
      "huey"

    def handleMessage(props: Unit, model: String, ctx: MessageContext) =
      case Msg.ChangeType(value) => value

    def render(props: Unit, selected: String, self: ComponentRef[Msg]) =
      div(
        form(
          idAttr        := "foo",
          cls           := "max-w-lg p-8 flex flex-col gap-4",
          phxChangeAttr := "change",
          phxSubmitAttr := "submit",
          radios(selected, self)
        )
      )

    private def radios(selected: String, self: ComponentRef[Msg]) =
      fieldset(
        legend("Radio example:"),
        Vector("huey", "dewey").map(radioType =>
          div(
            phx.onClick(Msg.ChangeType(radioType)),
            phx.target(self),
            phx.value("type") := radioType,
            input(
              typ      := "radio",
              idAttr   := radioType,
              nameAttr := "type",
              value    := radioType,
              checked  := selected == radioType
            ),
            label(radioType)
          )
        )
      )
  end BadgeForm
end Issue3684LiveView

class Issue3686LiveView(pageName: String) extends LiveView[Issue3686LiveView.Msg.type, Unit]:
  import Issue3686LiveView.*

  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount(ctx: MountContext) =
    ()

  override def handleParams(model: Unit, params: Unit, url: URL, ctx: ParamsContext) =
    model

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Msg.type) =>
      pageName match
        case "A" =>
          ctx.flash.put("info", "Flash from A") *> ctx.nav.pushNavigate("/issues/3686/b").as(model)
        case "B" =>
          ctx.flash.put("info", "Flash from B") *> ctx.nav.redirect("/issues/3686/c").as(model)
        case _ =>
          ctx.flash.put("info", "Flash from C") *> ctx.nav.pushNavigate("/issues/3686/a").as(model)

  def render(model: Unit) =
    val next = pageName match
      case "A" => "B"
      case "B" => "C"
      case _   => "A"

    div(
      h1(pageName),
      button(phx.onClick(Msg), s"To $next"),
      div(idAttr := "flash", "%{}", flash("info")(message => span(message)))
    )
end Issue3686LiveView

object Issue3686LiveView:
  case object Msg

class Issue3709LiveView extends LiveView[Unit, String]:
  import Issue3709LiveView.*

  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount(ctx: MountContext) =
    ""

  override def handleParams(model: String, params: Unit, url: URL, ctx: ParamsContext) =
    idFromPath(url).getOrElse("")

  def handleMessage(model: String, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: String) =
    div(
      ul(
        (1 to 10).map { i =>
          li(link.patch(s"/issues/3709/$i", s"Link $i"))
        }
      ),
      div(
        liveComponent(SomeComponent, id = s"user-$model", props = ()),
        s" id: $model",
        div(
          "Click the button, then click any link.",
          button(
            onClickAttr := "document.querySelectorAll('li a').forEach((x) => x.click())",
            "Break Stuff"
          )
        )
      )
    )

  private def idFromPath(url: URL): Option[String] =
    url.path.segments.toList match
      case "issues" :: "3709" :: id :: Nil => Some(id)
      case _                               => None
end Issue3709LiveView

object Issue3709LiveView:
  object SomeComponent extends LiveComponent[Unit, Unit, Unit]:
    def mount(props: Unit, ctx: MountContext) =
      ()

    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(props: Unit, model: Unit, self: ComponentRef[Unit]) =
      div("Hello")

class Issue3919LiveView extends LiveView[Issue3919LiveView.Msg, Issue3919LiveView.Action]:
  import Issue3919LiveView.*

  def mount(ctx: MountContext) =
    Action(text = "No red")

  def handleMessage(model: Action, ctx: MessageContext) =
    case Msg.Toggle =>
      if model.attrs.nonEmpty then Action(text = "No red")
      else Action(text = "Red", attrs = Some(ComponentAttrs(special = true)))

  def render(model: Action) =
    val renderedComponent =
      model.attrs match
        case Some(attrs) => myComponent(attrs)(model.text)
        case None        => myComponent()(model.text)

    div(
      renderedComponent,
      button(phx.onClick(Msg.Toggle), "toggle")
    )

  private def myComponent(attrs: ComponentAttrs = ComponentAttrs())(inner: String) =
    div(
      styleAttr := (if attrs.special then "background-color: red;" else ""),
      inner
    )

object Issue3919LiveView:
  final case class Action(text: String, attrs: Option[ComponentAttrs] = None)
  final case class ComponentAttrs(special: Boolean = false)

  enum Msg:
    case Toggle

class Issue3941LiveView extends LiveView[Issue3941LiveView.Msg, Set[String]]:
  import Issue3941LiveView.*

  def mount(ctx: MountContext) =
    Set("Item_1", "Item_2")

  def handleMessage(model: Set[String], ctx: MessageContext) =
    case Msg.Toggle(id) =>
      if model.contains(id) then model - id else model + id

  def render(model: Set[String]) =
    div(
      itemCheckbox("Item_1", model),
      itemCheckbox("Item_2", model),
      if model.contains("Item_1") then div(idAttr := "Item_1", "I AM LOADED") else "",
      if model.contains("Item_2") then div(idAttr := "Item_2", "I AM LOADED") else ""
    )

  private def itemCheckbox(id: String, selected: Set[String]) =
    input(
      idAttr  := s"select-$id",
      typ     := "checkbox",
      checked := selected.contains(id),
      phx.onClick(Msg.Toggle(id))
    )

object Issue3941LiveView:
  enum Msg:
    case Toggle(id: String)

class Issue3953LiveView extends LiveView[Issue3953LiveView.Msg, Boolean]:
  import Issue3953LiveView.*

  def mount(ctx: MountContext) =
    false

  def handleMessage(model: Boolean, ctx: MessageContext) =
    case Msg.Toggle => !model

  def render(model: Boolean) =
    div(
      liveComponent(Component, id = "comp", props = ()),
      button(phx.onClick(Msg.Toggle), "Show"),
      if model then liveView("nested_view", NestedViewLive()) else ""
    )

object Issue3953LiveView:
  enum Msg:
    case Toggle

  object Component extends LiveComponent[Unit, Unit, Unit]:
    def mount(props: Unit, ctx: MountContext) =
      ()

    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(props: Unit, model: Unit, self: ComponentRef[Unit]) =
      div("Component")

  class NestedViewLive extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ()

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(model: Unit) =
      div(
        "Nested Content",
        liveComponent(Component, id = "comp2", props = ())
      )

class Issue3979LiveView extends LiveView[Issue3979LiveView.Msg, Issue3979LiveView.Model]:
  import Issue3979LiveView.*

  def mount(ctx: MountContext) =
    Model(counter = 1, components = (1 to 10).map(id => ComponentState(id, counter = 0)).toVector)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Bump =>
      val target         = model.counter
      val nextComponents = model.components.map { component =>
        if component.id == target then component.copy(counter = component.counter + 1)
        else component
      }
      ctx.async
        .start(s"update-$target")(ZIO.sleep(100.millis).as(target))(Msg.DelayedUpdate(_))
        .as(model.copy(counter = target + 1, components = nextComponents))
    case Msg.DelayedUpdate(id) =>
      model.components.find(_.id == id) match
        case Some(component) =>
          ctx.components
            .sendUpdate[CounterComponent.type](
              s"comp-$id",
              CounterProps(id = id, domCounter = component.counter, counter = 10)
            ).as(model)
        case None => model

  def render(model: Model) =
    div(
      model.components.map { component =>
        liveComponent(
          CounterComponent,
          id = s"comp-${component.id}",
          props =
            CounterProps(component.id, domCounter = component.counter, counter = component.counter)
        )
      },
      button(phx.onClick(Msg.Bump), "Bump ID (and counter)")
    )
end Issue3979LiveView

object Issue3979LiveView:
  final case class ComponentState(id: Int, counter: Int)
  final case class Model(counter: Int, components: Vector[ComponentState])
  final case class CounterProps(id: Int, domCounter: Int, counter: Int)

  enum Msg:
    case Bump
    case DelayedUpdate(id: Int)

  object CounterComponent extends LiveComponent[CounterProps, Unit, CounterProps]:
    def mount(props: CounterProps, ctx: MountContext) =
      props

    override def update(props: CounterProps, model: CounterProps, ctx: UpdateContext) =
      props

    def handleMessage(props: CounterProps, model: CounterProps, ctx: MessageContext) =
      (_: Unit) => model

    def render(props: CounterProps, model: CounterProps, self: ComponentRef[Unit]) =
      div(idAttr := s"hello-${model.id}-${model.domCounter}", model.counter.toString)

class Issue4027LiveView extends LiveView[Issue4027LiveView.Msg, Issue4027LiveView.Model]:
  import Issue4027LiveView.*

  override val queryCodec: LiveQueryCodec[QueryParams] = QueryParams.codec

  def mount(ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    model.copy(caseName = params.caseName)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Load =>
      startLoad(ctx.async, InitialItems).as(model.copy(data = AsyncValue.markLoading(model.data)))
    case Msg.Remove =>
      startLoad(ctx.async, InitialItems.tail).as(
        model.copy(data = AsyncValue.markLoading(model.data))
      )
    case Msg.Loaded(items) => model.copy(data = AsyncValue.ok(items))

  def render(model: Model) =
    div(
      cls := "p-4",
      p(
        cls := "my-4",
        "Click Load Data. 3 items should be displayed. Then click Remove First entry. The expected result is 2 items displayed."
      ),
      if model.caseName == "second" then
        div(styleAttr := "margin: 10px; height: 1px; background-color: black;")
      else "",
      if model.caseName == "first" then
        AsyncValue.currentValue(model.data) match
          case Some(items) => liveComponent(ReproLiveComponent, id = "repro", props = items)
          case None        => ""
      else liveComponent(ReproLiveComponentWithAsyncResult, id = "repro_async", props = model.data),
      div(
        button(phx.onClick(Msg.Load), "Load data"),
        button(phx.onClick(Msg.Remove), "Remove first entry")
      )
    )
end Issue4027LiveView

object Issue4027LiveView:
  final case class QueryParams(caseName: String = "first")

  object QueryParams:
    val codec: LiveQueryCodec[QueryParams] =
      LiveQueryCodec.custom(
        decodeFn = url => Right(QueryParams(url.queryParam("case").getOrElse("first"))),
        encodeFn = params => Right(s"?case=${params.caseName}")
      )

  final case class Item(id: Int, value: String)
  final case class Model(
    caseName: String = "first",
    data: AsyncValue[Vector[Item]] = AsyncValue.ok(Vector.empty))

  enum Msg:
    case Load, Remove
    case Loaded(items: Vector[Item])

  private val InitialItems = Vector(Item(1, "First"), Item(2, "Second"), Item(3, "Third"))

  private def startLoad(async: Async[Msg], items: Vector[Item]) =
    async.start("data")(ZIO.sleep(100.millis).as(items))(Msg.Loaded(_))

  object ReproLiveComponent extends LiveComponent[Vector[Item], Unit, Vector[Item]]:
    def mount(props: Vector[Item], ctx: MountContext) =
      props

    override def update(props: Vector[Item], model: Vector[Item], ctx: UpdateContext) =
      props

    def handleMessage(props: Vector[Item], model: Vector[Item], ctx: MessageContext) =
      (_: Unit) => model

    def render(props: Vector[Item], model: Vector[Item], self: ComponentRef[Unit]) =
      keyedResult(model)

  object ReproLiveComponentWithAsyncResult
      extends LiveComponent[AsyncValue[Vector[Item]], Unit, AsyncValue[Vector[Item]]]:
    def mount(props: AsyncValue[Vector[Item]], ctx: MountContext) =
      props

    override def update(
      props: AsyncValue[Vector[Item]],
      model: AsyncValue[Vector[Item]],
      ctx: UpdateContext
    ) =
      props

    def handleMessage(
      props: AsyncValue[Vector[Item]],
      model: AsyncValue[Vector[Item]],
      ctx: MessageContext
    ) =
      (_: Unit) => model

    def render(
      props: AsyncValue[Vector[Item]],
      model: AsyncValue[Vector[Item]],
      self: ComponentRef[Unit]
    ) =
      asyncResult(model)

  private def keyedResult(items: Vector[Item]) =
    div(
      idAttr := "result",
      keyedItems(items)
    )

  private def asyncResult(data: AsyncValue[Vector[Item]]) =
    div(
      idAttr := "result",
      AsyncValue.currentValue(data) match
        case Some(items) => keyedItems(items)
        case None        => ""
    )

  private def keyedItems(items: Vector[Item]) =
    items.splitBy(_.id)((_, item) => p(item.value))
end Issue4027LiveView

class Issue4066LiveView extends LiveView[Issue4066LiveView.Msg, Issue4066LiveView.Model]:
  import Issue4066LiveView.*

  override val queryCodec: LiveQueryCodec[QueryParams] = QueryParams.codec

  def mount(ctx: MountContext) =
    Model(renderTime = java.time.Instant.now.toString)

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    model.copy(delay = params.delay)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Toggle => model.copy(renderInput = !model.renderInput)

  def render(model: Model) =
    div(
      p(idAttr := "render-time", model.renderTime),
      button(phx.onClick(Msg.Toggle), "Toggle"),
      Option.when(model.renderInput)(
        liveComponent(DelayedInputComponent, id = "foo", props = model.delay)
      )
    )

object Issue4066LiveView:
  final case class QueryParams(delay: Int = 3000)

  object QueryParams:
    val codec: LiveQueryCodec[QueryParams] =
      LiveQueryCodec.custom(
        decodeFn =
          url => Right(QueryParams(url.queryParam("delay").flatMap(_.toIntOption).getOrElse(3000))),
        encodeFn = params => Right(s"?delay=${params.delay}")
      )

  final case class Model(renderTime: String, renderInput: Boolean = true, delay: Int = 3000)

  enum Msg:
    case Toggle

  object DelayedInputComponent extends LiveComponent[Int, Unit, Unit]:
    override def hooks: ComponentLiveHooks[Int, Unit, Unit] =
      ComponentLiveHooks.empty.rawEvent("issue-4066") { (_, model, event, _) =>
        if event.bindingId == "do-something" then LiveEventHookResult.halt(model)
        else LiveEventHookResult.cont(model)
      }

    def mount(props: Int, ctx: MountContext) =
      ()

    def handleMessage(props: Int, model: Unit, ctx: MessageContext) =
      (_: Unit) => model

    def render(delay: Int, model: Unit, self: ComponentRef[Unit]) =
      input(
        idAttr   := "foo",
        phx.hook := "Issue4066Hook",
        phx.target(self),
        dataAttr("delay") := delay.toString
      )
end Issue4066LiveView

class Issue4078LiveView extends LiveView[Issue4078LiveView.Msg, Issue4078LiveView.Model]:
  import Issue4078LiveView.*

  def mount(ctx: MountContext) =
    ctx.uploads
      .allow(UploadName, UploadOptions)
      .map(upload => Model(upload = upload))
      .catchAll(_ => ZIO.succeed(Model(upload = disconnectedUpload)))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate       => refreshUpload(model, ctx.uploads)
    case Msg.ToggleDisabled => model.copy(disabled = !model.disabled)
    case Msg.ToggleClass    =>
      val nextClass =
        if model.customClass == "initial-class" then "updated-class" else "initial-class"
      model.copy(customClass = nextClass)

  def render(model: Model) =
    div(
      form(
        idAttr := "upload-form",
        phx.onChange(_ => Msg.Validate),
        liveFileInput(
          model.upload,
          disabled := model.disabled,
          cls      := model.customClass
        )
      ),
      button(
        idAttr := "toggle-disabled",
        typ    := "button",
        phx.onClick(Msg.ToggleDisabled),
        "Toggle Disabled"
      ),
      button(
        idAttr := "toggle-class",
        typ    := "button",
        phx.onClick(Msg.ToggleClass),
        "Toggle Class"
      ),
      model.upload.entries.splitBy(_.ref) { (_, entry) =>
        articleTag(cls := "upload-entry", span(cls := "entry-name", entry.clientName))
      }
    )

  private def refreshUpload(model: Model, uploads: Uploads): LiveIO[Model] =
    uploads.get(UploadName).map {
      case Some(upload) => model.copy(upload = upload)
      case None         => model
    }

end Issue4078LiveView

object Issue4078LiveView:
  enum Msg:
    case Validate
    case ToggleDisabled
    case ToggleClass

  final case class Model(
    upload: LiveUpload,
    disabled: Boolean = true,
    customClass: String = "initial-class")

  private val UploadName    = "avatar"
  private val UploadOptions = LiveUploadOptions(
    accept = LiveUploadAccept.Exactly(List(".jpg", ".jpeg", ".png", ".txt")),
    maxEntries = 2
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
end Issue4078LiveView

class Issue4088LiveView extends LiveView[Issue4088LiveView.Msg, String]:
  import Issue4088LiveView.*

  def mount(ctx: MountContext) =
    "value"

  override def hooks: LiveHooks[Msg, String] =
    LiveHooks.empty.rawEvent("issue-4088") { (model, event, _) =>
      if event.bindingId == "my_update" then LiveEventHookResult.halt(System.nanoTime.toString)
      else LiveEventHookResult.cont(model)
    }

  def handleMessage(model: String, ctx: MessageContext) =
    (_: Msg) => model

  def render(value: String) =
    div(idAttr := "foo", phx.hook := "Issue4088Hook", value)

object Issue4088LiveView:
  enum Msg:
    case Noop

class Issue4094LiveView extends LiveView[Unit, Unit]:
  override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

  def mount(ctx: MountContext) =
    ()

  override def handleParams(model: Unit, params: Unit, url: URL, ctx: ParamsContext) =
    if url.queryParam("foo").contains("bar") then ctx.nav.redirect("/navigation/a").as(model)
    else model

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    link.patch("/issues/4094?foo=bar", "Patch")

class Issue4095LiveView extends LiveView[Issue4095LiveView.Msg, String]:
  import Issue4095LiveView.*

  def mount(ctx: MountContext) =
    "true"

  def handleMessage(model: String, ctx: MessageContext) =
    case Msg.Validate(data) => data.getOrElse("show?", "")

  def render(show: String) =
    div(
      form(
        idAttr := "issue-4095-form",
        phx.onChangeForm(Msg.Validate(_)),
        input(typ := "text", nameAttr := "show?", idAttr := "show?", value := show),
        portal("portal", target = "#portal_target")(
          div(Option.when(show.nonEmpty)(button("Show?")))
        )
      ),
      div(idAttr := "portal_target")
    )

object Issue4095LiveView:
  enum Msg:
    case Validate(data: FormData)

class Issue4102LiveView extends LiveView[Issue4102LiveView.Msg, String]:
  import Issue4102LiveView.*

  def mount(ctx: MountContext) =
    "Test"

  def handleMessage(model: String, ctx: MessageContext) =
    case Msg.Validate(data) => data.getOrElse("name", model)
    case Msg.Submit(data)   => data.getOrElse("name", model)

  def render(name: String) =
    div(
      input(
        formAttr     := "my-form",
        phx.debounce := 500,
        nameAttr     := "name",
        idAttr       := "name",
        value        := name,
        typ          := "text"
      ),
      form(
        idAttr := "my-form",
        phx.onChangeForm(Msg.Validate(_)),
        phx.onSubmitForm(Msg.Submit(_)),
        button(typ := "submit", phx.disableWith := "Submitting...", "Submit")
      )
    )

object Issue4102LiveView:
  enum Msg:
    case Validate(data: FormData)
    case Submit(data: FormData)

class Issue4107LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(
      portal("test-form-portal", target = "body")(
        form(
          idAttr     := "test-form",
          actionAttr := "/api/test",
          methodAttr := "post",
          input(typ := "hidden", nameAttr := "test_input", value := "test_value")
        )
      ),
      button(typ := "submit", formAttr := "test-form", "Submit")
    )

class Issue4121LiveView extends LiveView[Issue4121LiveView.Msg.type, Issue4121LiveView.Model]:
  import Issue4121LiveView.*

  def mount(ctx: MountContext) =
    ctx.streams.init(ItemsStream, InitialItems).map(items => Model(items))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg.type) =>
      val id = System.nanoTime.toInt
      ctx.streams.init(ItemsStream, Vector(Item(id, s"Item $id")), reset = true).map(Model(_))

  def render(model: Model) =
    div(
      button(phx.onClick(Msg), "Reset teleported stream"),
      portal("teleported-stream", target = "body")(
        ul(
          idAttr       := "stream-in-lv",
          phx.onUpdate := "stream",
          model.items.stream((domId, item) => li(idAttr := domId, item.name))
        )
      )
    )

object Issue4121LiveView:
  final case class Item(id: Int, name: String)
  final case class Model(items: LiveStream[Item])
  case object Msg

  private val ItemsStream  = LiveStreamDef.byId[Item, Int]("items")(_.id)
  private val InitialItems = Vector(Item(1, "Item 1"), Item(2, "Item 2"))

class Issue4147LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(
      div(idAttr := "foobar", phx.hook := "HookOutside"),
      h1("Inside")
    )
