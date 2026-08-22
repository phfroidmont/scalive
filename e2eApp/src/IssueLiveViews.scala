import java.util.UUID

import zio.durationInt
import zio.http.URL
import zio.json.ast.Json
import zio.{Chunk, Task, ZIO}

import scalive.*

private val phxClickAttr  = htmlAttr("phx-click", scalive.codecs.StringAsIsEncoder)
private val phxChangeAttr = htmlAttr("phx-change", scalive.codecs.StringAsIsEncoder)
private val phxSubmitAttr = htmlAttr("phx-submit", scalive.codecs.StringAsIsEncoder)
private val onClickAttr   = htmlAttr("onclick", scalive.codecs.StringAsIsEncoder)

class Issue3719LiveView extends LiveView[Issue3719LiveView.Msg, Issue3719LiveView.Model]:
  import Issue3719LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Change(event) => ZIO.succeed(model.copy(target = event.target.map(_.segments)))

  override def view(model: Signal[Model]) =
    div(
      form(
        on.change.form(FormCodec.formData)(Msg.Change(_)),
        input(idAttr := "a", typ := "text", nameAttr := "foo"),
        input(idAttr := "b", typ := "text", nameAttr := "foo[bar]")
      ),
      span(idAttr := "target", model.map(current => renderTarget(current.target)))
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
    ctx.uploads.allow(Upload).map(upload => Model(upload = upload))

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.onRawEvent { (model, event, _) =>
      if event.bindingId == "upload_scrub_list" then
        val fileNames = fileNamesFromScrubEvent(event.value).toVector
        val reply     = Json.Obj("deduped_filenames" -> Json.Arr(fileNames.map(Json.Str(_))*))
        ZIO.succeed(LiveEventHookResult.haltReply(model, reply))
      else ZIO.succeed(LiveEventHookResult.cont(model))
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate           => refreshUpload(model, ctx.uploads)
    case Msg.Progress(entryRef) =>
      refreshUpload(model, ctx.uploads).flatMap(pushNextFileEvent(_, entryRef, ctx))
    case Msg.CancelUpload(entry) =>
      ctx.uploads.cancel(entry).map(upload => model.copy(upload = upload))
    case Msg.Save => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    val upload = model.map(_.upload)

    mainTag(
      h1("Uploader reproduction"),
      form(
        on.submit(Msg.Save),
        on.change(_ => Msg.Validate),
        sectionTag(
          liveFileInput(
            upload,
            styleAttr := "display: none;",
            upload.onProgress(params => Msg.Progress(params.getOrElse("entry_ref", "")))
          ),
          input(
            dom.hook("QueuedUploaderHook", DomRef("fileinput")),
            typ                         := "file",
            multiple                    := true,
            dataAttr("max-concurrency") := "3",
            disabled                    := upload.map(filePickerDisabled)
          ),
          Signal.when(
            upload.map(_.entries.nonEmpty)
          )(
            h2("Currently uploading files")
          ),
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
                uploadRows(upload)
              )
            )
          ),
          uploadErrors(upload).splitBy(_.toString) { (_, error) =>
            p(styleAttr := "color: red;", error.map(errorToString))
          }
        )
      )
    )
  end view

  private def refreshUpload(model: Model, uploads: Uploads): Task[Model] =
    uploads.get(Upload).map {
      case Some(upload) => model.copy(upload = upload)
      case None         => model
    }

  private def pushNextFileEvent(
    model: Model,
    entryRef: String,
    ctx: MessageContext
  ): Task[Model] =
    val completedRef = model.upload.entries
      .find(entry =>
        entry.ref.value == entryRef && entry.status == LiveUploadEntryStatus.Completed
      ).map(_.ref)
    completedRef match
      case Some(ref) if !model.nextFileSentFor.contains(ref) =>
        ctx.client
          .push(UploadSendNextFileEvent, Map.empty[String, String])
          .as(
            model.copy(
              nextFileSentFor = model.nextFileSentFor + ref
            )
          )
      case _ => ZIO.succeed(model)

  private def filePickerDisabled(upload: LiveUpload[Unit]): Boolean =
    upload.entries.exists(_.status != LiveUploadEntryStatus.Completed)

  private def uploadRows(upload: Signal[LiveUpload[Unit]]): Mod[Msg] =
    upload
      .map(_.entries.filter(_.client.fileName.nonEmpty))
      .splitBy(_.ref)((_, entry) => uploadEntryRow(upload, entry))

  private def uploadEntryRow(
    upload: Signal[LiveUpload[Unit]],
    entry: Signal[LiveUploadEntry[Unit]]
  ) =
    tr(
      td(entry.map(_.client.fileName)),
      td(
        progressTag(
          value   := entry.map(_.progress.toString),
          maxAttr := "100",
          entry.map(current => s"${current.progress}%")
        )
      ),
      td(
        button(
          typ := "button",
          on.click(entry.map(Msg.CancelUpload.apply)),
          phx.value("ref") := entry.map(_.ref.value),
          aria.label       := "cancel",
          span("x")
        )
      ),
      td(
        upload
          .zip(entry).map { case (currentUpload, currentEntry) =>
            uploadErrors(currentUpload, currentEntry)
          }.splitBy(_.toString) { (_, error) =>
            p(styleAttr := "color: red;", error.map(errorToString))
          }
      )
    )
end Issue2965LiveView

object Issue2965LiveView:
  final case class Model(
    upload: LiveUpload[Unit],
    nextFileSentFor: Set[UploadEntryRef] = Set.empty)

  enum Msg:
    case Validate
    case Progress(entryRef: String)
    case CancelUpload(entry: LiveUploadEntry[Unit])
    case Save

  private val UploadSendNextFileEvent =
    ServerToBrowserEvent[Map[String, String]]("upload_send_next_file")
  private val Upload: LiveUploadDef[Unit] = LiveUploadDef.hosted(
    name = "files",
    accept = LiveUploadAccept.Any,
    writer = NoOpWriter,
    maxEntries = 1500,
    maxFileSize = 10_000_000_000L,
    chunkSize = 5 * 1024 * 1024,
    autoUpload = true
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

  private object NoOpWriter extends LiveUploadWriter[Unit, Unit]:
    def init(client: UploadClientMetadata) = ZIO.unit

    def writeChunk(data: Chunk[Byte], state: Unit) =
      zio.Random
        .nextIntBetween(1, 201).flatMap(delay => ZIO.sleep(delay.millis).as(()))

    def complete(state: Unit)                             = ZIO.unit
    def abort(state: Unit, reason: LiveUploadAbortReason) = ZIO.unit
    def discard(result: Unit)                             = ZIO.unit
end Issue2965LiveView

class Issue3814LiveView extends LiveView[Issue3814LiveView.Msg, Issue3814LiveView.Model]:
  import Issue3814LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Submit => ZIO.succeed(model.copy(triggerSubmit = true))

  override def view(model: Signal[Model]) =
    form(
      on.submit(Msg.Submit),
      phx.triggerAction := model.map(_.triggerSubmit),
      action            := "/submit",
      method            := "post",
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
    case Submit

class Issue3040LiveView extends LiveView[Issue3040LiveView.Msg, Issue3040LiveView.Model]:
  import Issue3040LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Open   => ZIO.succeed(model.copy(open = true, submitted = false))
    case Msg.Close  => ZIO.succeed(model.copy(open = false))
    case Msg.Submit => ZIO.succeed(model.copy(submitted = true))

  override def view(model: Signal[Model]) =
    val open      = model.map(_.open)
    val submitted = model.map(_.submitted)

    div(
      a(href := "#", on.click(Msg.Open), "Add new"),
      div(
        idAttr    := "my-modal-container",
        styleAttr := open.map(if _ then "position: fixed; inset: 0" else "display: none"),
        on.windowKeyDown.key(Key.Escape)(Msg.Close),
        Signal.when(open)(
          div(
            styleAttr := "margin: 320px 0 0 300px; width: 300px; padding: 20px",
            on.clickAway(Msg.Close),
            dom.onMount(JS.focusFirst(to = DomSelector.css("#my-modal-container"))),
            form(
              on.submit(Msg.Submit),
              submitted.chooseMod("Form was submitted!", input(nameAttr := "name"))
            )
          )
        )
      )
    )
end Issue3040LiveView

object Issue3040LiveView:
  final case class Model(open: Boolean = false, submitted: Boolean = false)
  enum Msg:
    case Open, Close, Submit

class Issue3047LiveView(pageName: String) extends LiveView[Unit, Unit]:

  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    span(idAttr := "page", s"Page $pageName")

object Issue3047LiveView:
  val Layout: LiveLayout[Any, Any] = LiveLayout[Any, Any]([Msg] =>
    (content: HtmlElement[Msg], _: LiveLayoutContext[Any, Any]) =>
      div(
        div(
          link.pushNavigate(E2ERoutes.issue3047A.location, "Page A"),
          link.pushNavigate(E2ERoutes.issue3047B.location, "Page B")
        ),
        content,
        liveView("test", Issue3047LiveView.Sticky(), sticky = true)
      )
  )

  class Sticky extends LiveView[Reset.type, Model]:
    def mount(ctx: MountContext) =
      ctx.streams.create(ItemsStreamDef, InitialItems).map(items => Model(items))

    def handleMessage(model: Model, ctx: MessageContext) =
      (_: Reset.type) =>
        ctx.streams
          .reset(ItemsStreamDef, ResetItems)
          .map(items => model.copy(items = items))

    override def view(model: Signal[Model]) =
      div(
        styleAttr := "border: 2px solid black;",
        h1("This is the sticky liveview"),
        div(
          idAttr     := "items",
          phx.update := PhxUpdate.Stream,
          styleAttr  := "display: flex; flex-direction: column; gap: 4px;",
          model.map(_.items).stream((domId, item) => span(idAttr := domId, item.map(_.name)))
        ),
        button(on.click(Reset), "Reset")
      )

  final case class Item(id: Int, name: String)
  final case class Model(items: LiveStream[Item])

  case object Reset

  private val ItemsStreamDef = LiveStreamDef.byId[Item, Int]("items")(_.id)
  private val InitialItems   = (1 to 10).map(id => Item(id, s"item-$id")).toList
  private val ResetItems     = (5 to 15).map(id => Item(id, s"item-$id")).toList
end Issue3047LiveView

class Issue3529LiveView extends LiveView.Routed[Unit, Issue3529LiveView.Model, Option[String]]:
  import Issue3529LiveView.*

  def mount(_params: Option[String], ctx: MountContext) =
    val model = Model(mounted = UUID.randomUUID().toString, next = UUID.randomUUID().toString)
    ZIO.succeed(model)

  override def handleParams(model: Model, params: Option[String], url: URL, ctx: ParamsContext) =
    val next =
      model.copy(
        mounted = params.fold(UUID.randomUUID().toString)(_ => model.mounted),
        next = UUID.randomUUID().toString
      )
    ZIO.succeed(next)

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    div(
      h1(model.map(current => s"Mounted at ${current.mounted}")),
      a(
        href := model.map(current => E2ERoutes.issue3529.location(Some(current.next)).href),
        dataAttr("phx-link")       := "redirect",
        dataAttr("phx-link-state") := "push",
        "Navigate"
      ),
      a(
        href := model.map(current => E2ERoutes.issue3529.location(Some(current.next)).href),
        dataAttr("phx-link")       := "patch",
        dataAttr("phx-link-state") := "push",
        "Patch"
      )
    )
end Issue3529LiveView

object Issue3529LiveView:
  final case class Model(mounted: String, next: String)

class Issue3530LiveView extends LiveView.Routed[Unit, Issue3530LiveView.Model, Option[String]]:
  import Issue3530LiveView.*

  def mount(_params: Option[String], ctx: MountContext) =
    ctx.streams
      .create(ItemsStream, List.empty[Item])
      .map(items => Model(count = 3, items = items))

  override def handleParams(model: Model, params: Option[String], url: URL, ctx: ParamsContext) =
    val itemIds = params match
      case Some("a") => List(1, 3)
      case Some("b") => List(2, 3)
      case _         => List(1, 2, 3)

    ctx.streams
      .reset(ItemsStream, itemIds.map(Item(_)))
      .map(items => model.copy(items = items))

  override def hooks: LiveHooks[Unit, Model] =
    LiveHooks.empty.onBrowserEvent(BrowserToServerEvent[Json]("inc")) { (model, _, ctx) =>
      val nextCount = model.count + 1
      ctx.streams
        .insert(ItemsStream, Item(nextCount))
        .map(items => model.copy(count = nextCount, items = items))
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    div(
      ul(
        idAttr     := "stream-list",
        phx.update := PhxUpdate.Stream,
        model
          .map(_.items).stream((domId, item) =>
            div(
              idAttr := domId,
              liveView(domId, item.map(_.id))(NestedLive(_))
            )
          )
      ),
      link.pushPatch(E2ERoutes.issue3530.location(Some("a")), "patch a"),
      link.pushPatch(E2ERoutes.issue3530.location(Some("b")), "patch b"),
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
      ZIO.succeed(())

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(model: Signal[Unit]) =
      div(
        idAttr := s"item-outer-$itemId",
        "test hook with nested liveview",
        div(dom.hook("test", DomRef(s"test-hook-$itemId")))
      )

class Issue3647LiveView extends LiveView[Issue3647LiveView.Msg, Issue3647LiveView.Model]:
  import Issue3647LiveView.*

  def mount(ctx: MountContext) =
    ctx.uploads.allow(Upload).map(upload => Model(upload = upload))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ValidateUser(event) =>
      ZIO.succeed(model.copy(userName = event.raw.getOrElse("user[name]", "")))
    case Msg.Validate =>
      refreshUpload(model, ctx.uploads)
    case Msg.Progress(entryRef) =>
      ctx.uploads.get(Upload).flatMap {
        case Some(upload) =>
          upload.entries.find(_.ref.value == entryRef) match
            case Some(entry) if entry.status == LiveUploadEntryStatus.Completed =>
              saveCompletedEntry(model, entry, ctx.uploads)
            case _ => ZIO.succeed(model.copy(upload = upload))
        case None => ZIO.succeed(model)
      }
    case Msg.CancelUpload(entry) =>
      ctx.uploads.cancel(entry).map(upload => model.copy(upload = upload))

  override def view(model: Signal[Model]) =
    val upload = model.map(_.upload)

    div(
      form(
        idAttr := "user-form",
        on.change.form(FormCodec.formData)(Msg.ValidateUser(_)),
        input(
          idAttr   := "user_name",
          nameAttr := "user[name]",
          value    := model.map(_.userName),
          typ      := "text"
        ),
        button(dom.hook("JsUpload", DomRef("x")), typ := "button", "Upload then Input"),
        button(
          dom.hook("JsUpload", DomRef("y")),
          typ                := "button",
          dataAttr("before") := "true",
          "Input then Upload"
        ),
        liveFileInput(
          upload,
          formId := "auto-form",
          upload.onProgress(params => Msg.Progress(params.getOrElse("entry_ref", "")))
        )
      ),
      form(idAttr := "auto-form", on.change(_ => Msg.Validate)),
      sectionTag(
        cls := "pending-uploads",
        upload.dropTarget,
        styleAttr := "min-height: 100%;",
        h3(model.map(current => s"Pending Uploads (${current.upload.entries.length})")),
        upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
          div(
            progressTag(
              value   := entry.map(_.progress.toString),
              maxAttr := "100",
              entry.map(current => s"${current.progress}%")
            ),
            div(
              entry.map(_.ref.value),
              br(),
              a(
                href := "#",
                on.click(entry.map(Msg.CancelUpload.apply)),
                phx.value("ref") := entry.map(_.ref.value),
                cls              := "upload-entry__cancel",
                "Cancel Upload"
              )
            )
          )
        }
      ),
      ul(
        model.map(_.uploadedFiles).splitBy(identity) { (_, fileName) =>
          li(a(href := fileName, fileName))
        }
      )
    )
  end view

  private def refreshUpload(model: Model, uploads: Uploads): Task[Model] =
    uploads.get(Upload).map {
      case Some(upload) => model.copy(upload = upload)
      case None         => model
    }

  private def saveCompletedEntry(
    model: Model,
    entry: LiveUploadEntry[Chunk[Byte]],
    uploads: Uploads
  ): Task[Model] =
    uploads
      .consume(entry)(upload => ZIO.succeed(ConsumeDecision.Consume(upload.client.fileName))).map {
        case (fileName, upload) =>
          model.copy(upload = upload, uploadedFiles = model.uploadedFiles :+ fileName)
      }
end Issue3647LiveView

object Issue3647LiveView:
  enum Msg:
    case ValidateUser(event: FormEvent[FormData])
    case Validate
    case Progress(entryRef: String)
    case CancelUpload(entry: LiveUploadEntry[Chunk[Byte]])

  final case class Model(
    upload: LiveUpload[Chunk[Byte]],
    userName: String = "",
    uploadedFiles: List[String] = Nil)

  private val Upload: LiveUploadDef[Chunk[Byte]] = LiveUploadDef.inMemory(
    name = "avatar",
    accept = LiveUploadAccept.only(".txt", ".md"),
    maxEntries = 2,
    autoUpload = true
  )

class Issue3819LiveView extends LiveView[Issue3819LiveView.Msg, Boolean]:
  import Issue3819LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(false)

  def handleMessage(model: Boolean, ctx: MessageContext) =
    case Msg.Noop(_) => ZIO.succeed(model)

  override def hooks: LiveHooks[Msg, Boolean] =
    LiveHooks.empty.onBrowserEvent(BrowserToServerEvent[Json]("reconnected"))((_, _, _) =>
      ZIO.succeed(true)
    )

  override def view(reconnected: Signal[Boolean]) =
    div(
      form(
        idAttr := "recover",
        on.change.form(Msg.Noop(_)),
        on.submit.form(Msg.Noop(_)),
        button("Submit")
      ),
      Signal.when(reconnected)(p(idAttr := "reconnected", "Reconnected!"))
    )

object Issue3819LiveView:
  enum Msg:
    case Noop(data: FormData)

class Issue3107LiveView extends LiveView[Issue3107LiveView.Msg.type, Boolean]:
  def mount(ctx: MountContext) =
    ZIO.succeed(true)

  def handleMessage(model: Boolean, ctx: MessageContext) =
    (_: Issue3107LiveView.Msg.type) => ZIO.succeed(false)

  override def view(disabledButton: Signal[Boolean]) =
    form(
      on.change(Issue3107LiveView.Msg),
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
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg.type) => ZIO.succeed(model)

  override def hooks: LiveHooks[Msg.type, Model] =
    LiveHooks.empty.onRawEvent { (model, event, _) =>
      if event.bindingId != "sandbox:eval" then ZIO.succeed(LiveEventHookResult.cont(model))
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
            ZIO.succeed(
              LiveEventHookResult.haltReply(
                model.copy(selected = values),
                Json.Obj("result" -> Json.Null)
              )
            )
          case None => E2ESandboxEval.handle(model, event.bindingId, event.value)
    }

  override def view(model: Signal[Model]) =
    form(
      idAttr := "form",
      on.change(Msg),
      select(
        idAttr   := "ids",
        nameAttr := "ids[]",
        multiple := true,
        (1 to 5).map(number =>
          option(
            value    := number.toString,
            selected := model.map(_.selected.contains(number)),
            number.toString
          )
        )
      ),
      input(typ := "text", placeholder := "focus me!")
    )
end Issue3083LiveView

object Issue3083LiveView:
  final case class Model(selected: Vector[Int] = Vector.empty)
  case object Msg

class Issue2787LiveView extends LiveView[Issue2787LiveView.Msg, Issue2787LiveView.Model]:
  import Issue2787LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Updated(data) =>
      val select1 = data.get("demo[select1]").filter(_.nonEmpty)
      val select2 = data.get("demo[select2]").filter(_.nonEmpty)
      ZIO.succeed(model.copy(select1 = select1, select2 = select2))
    case Msg.Submitted(_) => ZIO.succeed(Model())

  override def view(model: Signal[Model]) =
    div(
      form(
        on.change.form(Msg.Updated(_)),
        on.submit.form(Msg.Submitted(_)),
        select(
          idAttr   := "demo_select1",
          nameAttr := "demo[select1]",
          option(value := "", "Select"),
          Vector("greetings", "goodbyes").map(optionValue =>
            option(
              selected := model.map(_.select1.contains(optionValue)),
              value    := optionValue,
              optionValue
            )
          )
        ),
        select(
          idAttr   := "demo_select2",
          nameAttr := "demo[select2]",
          option(value := "", "Select"),
          model.map(_.select2Options).splitBy(identity) { (_, optionValue) =>
            option(
              selected := model.zip(optionValue).map { case (current, value) =>
                current.select2.contains(value)
              },
              value := optionValue,
              optionValue
            )
          }
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
    ZIO.succeed(Vector.empty)

  def handleMessage(model: Vector[String], ctx: MessageContext) =
    case Msg.Validate(data) => ZIO.succeed(data.values("a[]"))
    case Msg.Search         => ZIO.succeed(model)

  override def view(selectedValues: Signal[Vector[String]]) =
    form(
      idAttr := "my_form",
      on.change.form(Msg.Validate(_)),
      div(
        selectedValues.splitBy(identity)((_, value) => div(value)),
        input(idAttr := "search", typ := "search", nameAttr := "value", on.change(Msg.Search))
      ),
      div(
        Vector("settings", "content").map(optionValue =>
          input(
            typ      := "checkbox",
            nameAttr := "a[]",
            value    := optionValue,
            checked  := selectedValues.map(_.contains(optionValue)),
            on.click(JS.dispatch("input").focus(to = DomSelector.css("#search")))
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
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    case Msg.Validate => ZIO.succeed(model)
    case Msg.Submit   => ctx.nav.pushNavigate(E2ERoutes.issue3194Other.location).as(model)

  override def view(model: Signal[Unit]) =
    form(
      on.change(Msg.Validate),
      on.submit(Msg.Submit),
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
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) = h2("Another LiveView")

class Issue3200LiveView
    extends LiveView.Routed[Issue3200LiveView.Msg, Issue3200LiveView.Model, String]:
  import Issue3200LiveView.*

  def mount(_params: String, ctx: MountContext) =
    ZIO.succeed(Model())

  override def handleParams(model: Model, params: String, url: URL, ctx: ParamsContext) =
    val _   = (url, ctx)
    val tab = if params == "messages" then Tab.Messages else Tab.Settings
    ZIO.succeed(model.copy(tab = tab))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg) => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    div(
      button(
        typ := "button",
        on.click(JS.pushPatch(E2ERoutes.issue3200.location("messages"))),
        "Messages tab"
      ),
      button(
        typ := "button",
        on.click(JS.pushPatch(E2ERoutes.issue3200.location("settings"))),
        "Settings tab"
      ),
      model
        .map(_.tab == Tab.Settings).chooseMod(
          liveComponent(SettingsTab, id = "settings_tab", props = ()),
          liveComponent(MessagesTab, id = "messages_tab", props = ())
        )
    )
end Issue3200LiveView

object Issue3200LiveView:
  enum Tab:
    case Settings, Messages

  final case class Model(tab: Tab = Tab.Settings)

  enum Msg:
    case Noop

  object SettingsTab extends LiveComponent[Unit, Unit, Unit]:
    def mount(props: Unit, ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Unit]) =
      div("Settings")

  object MessagesTab extends LiveComponent[Unit, MessagesTab.Msg, String]:
    enum Msg:
      case Change(data: FormData)
      case Submit

    def mount(props: Unit, ctx: MountContext) =
      ZIO.succeed("")

    def handleMessage(props: Unit, model: String, ctx: MessageContext) =
      case Msg.Change(data) => ZIO.succeed(data.getOrElse("new_message", ""))
      case Msg.Submit       => ZIO.succeed(model)

    override def view(props: Signal[Unit], model: Signal[String], self: ComponentRef[Msg]) =
      div(
        liveComponent(MessageComponent, id = "some_unique_message_id", props = "Example message"),
        form(
          idAttr := "full_add_message_form",
          on.change.form(Msg.Change(_)),
          on.submit(Msg.Submit),
          phx.target(DomSelector.css("#full_add_message_form")),
          inputComponent(model)
        )
      )

    private def inputComponent(inputValue: Signal[String]) =
      div(
        phx.feedbackFor := "new_message",
        input(idAttr := "new_message_input", nameAttr := "new_message", value := inputValue)
      )

  object MessageComponent extends LiveComponent[String, Unit, String]:
    def mount(props: String, ctx: MountContext) =
      ZIO.succeed(props)

    override def update(props: String, model: String, ctx: UpdateContext) =
      ZIO.succeed(props)

    def handleMessage(props: String, model: String, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(
      props: Signal[String],
      model: Signal[String],
      self: ComponentRef[Unit]
    ) =
      div(model)
end Issue3200LiveView

class Issue3026LiveView extends LiveView[Issue3026LiveView.Msg, Issue3026LiveView.Model]:
  import Issue3026LiveView.*

  def mount(ctx: MountContext) =
    ctx.connection match
      case Connection.Connected(capabilities) =>
        startLoad(capabilities.async).as(Model(status = Status.Loading))
      case Connection.Disconnected => ZIO.succeed(Model(status = Status.Connecting))

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks
      .empty[Msg, Model]
      .onBrowserEvent(BrowserToServerEvent[Json]("validate")) { (model, value, _) =>
        val data = value.asString
          .flatMap(raw => FormData.fromUrlEncoded(raw).toOption)
          .getOrElse(FormData.empty)
        ZIO.succeed(
          model.copy(
            name = data.getOrElse("name", model.name),
            email = data.getOrElse("email", model.email)
          )
        )
      }
      .onBrowserEvent(BrowserToServerEvent[Json]("submit")) { (model, _, ctx) =>
        startLoad(ctx.async).as(model.copy(status = Status.Loading))
      }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ChangeStatus(data) =>
      ZIO.succeed(
        model.copy(status = Status.valueOf(data.getOrElse("status", "loaded").capitalize))
      )
    case Msg.Loaded(LiveAsyncResult.Succeeded(result)) =>
      ZIO.succeed(model.copy(status = Status.Loaded, name = result.name, email = result.email))
    case Msg.Loaded(_) => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    val status = model.map(_.status)

    div(
      form(
        on.change.form(Msg.ChangeStatus(_)),
        select(
          nameAttr := "status",
          Vector(Status.Connecting, Status.Loading, Status.Connected, Status.Loaded).map(status =>
            option(
              value    := status.value,
              selected := model.map(_.status == status),
              status.value.capitalize
            )
          )
        )
      ),
      status
        .map(_ == Status.Loaded).chooseMod(
          liveComponent(
            Issue3026FormComponent,
            id = "my-form",
            props = model.map(current => FormProps(current.name, current.email))
          ),
          div(cls := "p-8 bg-gray-200 mb-4", status.map(_.value))
        )
    )
end Issue3026LiveView

object Issue3026LiveView:
  private val Load = AsyncKey[LoadResult]("load")
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
    case Loaded(result: LiveAsyncResult[LoadResult])

  private def startLoad(async: Async[Msg]) =
    async.start(Load)(ZIO.sleep(200.millis).as(LoadResult("John", "")))(Msg.Loaded(_))

  object Issue3026FormComponent extends LiveComponent[FormProps, Unit, Unit]:
    def mount(props: FormProps, ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(props: FormProps, model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(
      props: Signal[FormProps],
      model: Signal[Unit],
      self: ComponentRef[Unit]
    ) =
      div(
        "Example form",
        form(
          phxChangeAttr := "validate",
          phxSubmitAttr := "submit",
          input(nameAttr := "name", typ  := "text", value := props.map(_.name)),
          input(nameAttr := "email", typ := "text", value := props.map(_.email)),
          button(typ     := "submit", "Submit")
        )
      )
end Issue3026LiveView

class Issue3117LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    div(
      link.pushNavigateUnsafe("/issues/3117?nav", idAttr := "navigate", "Navigate"),
      (1 to 2).map(i =>
        div(liveComponent(Issue3117LiveView.Row, id = s"row-$i", props = s"row-$i"))
      )
    )

object Issue3117LiveView:
  object Row extends LiveComponent[String, Row.Msg, Row.Model]:
    private val Load = AsyncKey[String]("foo")
    enum Msg:
      case Loaded(result: LiveAsyncResult[String])

    final case class Model(result: Option[String] = None, started: Boolean = false)

    def mount(props: String, ctx: MountContext) =
      ZIO.succeed(Model())

    override def update(props: String, model: Model, ctx: UpdateContext) =
      if model.started then ZIO.succeed(model)
      else
        ctx.connection match
          case Connection.Connected(capabilities) =>
            capabilities.async
              .start(Load)(ZIO.succeed("bar"))(Msg.Loaded(_)).as(model.copy(started = true))
          case Connection.Disconnected => ZIO.succeed(model)

    def handleMessage(props: String, model: Model, ctx: MessageContext) =
      case Msg.Loaded(LiveAsyncResult.Succeeded(value)) =>
        ZIO.succeed(model.copy(result = Some(value)))
      case Msg.Loaded(_) => ZIO.succeed(model)

    override def view(
      props: Signal[String],
      model: Signal[Model],
      self: ComponentRef[Msg]
    ) =
      val content = model.map { current =>
        val result = current.result.map(value => s"Some($value)").getOrElse("None")
        s"Example LC Row $result"
      }
      div(
        idAttr := props,
        content,
        div(cls := "static", "static content")
      )
  end Row
end Issue3117LiveView

class Issue3169LiveView extends LiveView[Issue3169LiveView.Msg, Option[String]]:
  import Issue3169LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(None)

  def handleMessage(model: Option[String], ctx: MessageContext) =
    case Msg.Select(name) => ZIO.succeed(Some(name))

  override def view(selected: Signal[Option[String]]) =
    div(
      "HomeLive ",
      liveComponent(FormComponent, id = "form_view", props = selected),
      button(
        idAttr            := "select-a",
        phx.value("name") := "a",
        on.click(params => Msg.Select(params.getOrElse("name", ""))),
        "Select A"
      ),
      button(
        idAttr            := "select-b",
        phx.value("name") := "b",
        on.click(params => Msg.Select(params.getOrElse("name", ""))),
        "Select B"
      ),
      button(
        idAttr            := "select-z",
        phx.value("name") := "z",
        on.click(params => Msg.Select(params.getOrElse("name", ""))),
        "Select Z"
      )
    )
end Issue3169LiveView

object Issue3169LiveView:
  enum Msg:
    case Select(name: String)

  final case class Record(id: Int, name: String)

  object FormComponent extends LiveComponent[Option[String], FormComponent.Msg, Option[Record]]:
    private val Load = AsyncKey[Record]("load")
    enum Msg:
      case Loaded(result: LiveAsyncResult[Record])

    def mount(props: Option[String], ctx: MountContext) =
      ZIO.succeed(None)

    override def update(props: Option[String], model: Option[Record], ctx: UpdateContext) =
      props match
        case Some(name) =>
          ctx.connection match
            case Connection.Connected(capabilities) =>
              capabilities.async
                .start(Load)(
                  ZIO
                    .sleep(50.millis).as(
                      Record(scala.util.Random.nextInt(1000000), s"Record $name")
                    )
                )(Msg.Loaded(_))
                .as(None)
            case Connection.Disconnected => ZIO.succeed(model)
        case None => ZIO.succeed(model)

    def handleMessage(props: Option[String], model: Option[Record], ctx: MessageContext) =
      case Msg.Loaded(LiveAsyncResult.Succeeded(record)) => ZIO.succeed(Some(record))
      case Msg.Loaded(_)                                 => ZIO.succeed(model)

    override def view(
      props: Signal[Option[String]],
      model: Signal[Option[Record]],
      self: ComponentRef[Msg]
    ) =
      div(
        "FormComponent (c1)",
        Signal.option(model)((record: Signal[Record]) =>
          div(liveComponent(FormCore, id = "core", props = record))
        ),
        hr()
      )
  end FormComponent

  object FormCore extends LiveComponent[Record, Unit, Record]:
    def mount(props: Record, ctx: MountContext) =
      ZIO.succeed(props)

    override def update(props: Record, model: Record, ctx: UpdateContext) =
      ZIO.succeed(props)

    def handleMessage(props: Record, model: Record, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(props: Signal[Record], model: Signal[Record], self: ComponentRef[Unit]) =
      div(
        "FormCore (c2)",
        form(
          liveComponent(FormColumn, id = model.map(record => s"column-${record.id}"), props = model)
        )
      )

  object FormColumn extends LiveComponent[Record, Unit, Record]:
    def mount(props: Record, ctx: MountContext) =
      ZIO.succeed(props)

    override def update(props: Record, model: Record, ctx: UpdateContext) =
      ZIO.succeed(props)

    def handleMessage(props: Record, model: Record, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(props: Signal[Record], model: Signal[Record], self: ComponentRef[Unit]) =
      div(
        "FormColumn (c3) ",
        input(typ := "text", value := model.map(_.name)),
        inputComponent(model),
        testComponent("foo")
      )

  private def inputComponent(record: Signal[Record]) =
    div(
      record.map(_.name),
      input(typ := "text", value := record.map(_.name)),
      inputTwo(record)
    )

  private def inputTwo(record: Signal[Record]) =
    div(
      record.map(_.name),
      input(typ := "text", value := record.map(_.name))
    )

  private def testComponent(value: String) =
    div("This is a test! ", value)
end Issue3169LiveView

class Issue3378LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    div(liveView("appbar", Issue3378LiveView.AppBarLive()))

object Issue3378LiveView:
  final case class Notification(id: Int, message: String)

  private val NotificationsStream =
    LiveStreamDef.byId[Notification, Int]("notifications")(_.id)

  class AppBarLive extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(model: Signal[Unit]) =
      div(liveView("notifications", NotificationsLive()))

  class NotificationsLive extends LiveView[Unit, LiveStream[Notification]]:
    def mount(ctx: MountContext) =
      ctx.streams.create(NotificationsStream, List(Notification(1, "Hello")))

    def handleMessage(model: LiveStream[Notification], ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(model: Signal[LiveStream[Notification]]) =
      div(
        ul(
          idAttr     := "notifications_list",
          phx.update := PhxUpdate.Stream,
          model.stream((domId, _) => div(idAttr := domId, p("big!")))
        )
      )
end Issue3378LiveView

class Issue3496LiveView(pageName: String, includeStickyHook: Boolean) extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    div(
      h1(s"Page $pageName"),
      if pageName == "A" then link.pushNavigate(E2ERoutes.issue3496B.location, "Go to page B")
      else "",
      if includeStickyHook then liveView("sticky", Issue3496LiveView.StickyLive(), sticky = true)
      else Issue3496LiveView.myComponent
    )

object Issue3496LiveView:
  def myComponent =
    div(dom.hook("MyHook", DomRef("my-component")))

  class StickyLive extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(model: Signal[Unit]) =
      div(myComponent)

class Issue3612LiveView(pageName: String) extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
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
      ZIO.succeed(())

    def handleMessage(model: Unit, ctx: MessageContext) =
      case NavigateToA => ctx.nav.pushNavigate(E2ERoutes.issue3612A.location).as(model)
      case NavigateToB => ctx.nav.pushNavigate(E2ERoutes.issue3612B.location).as(model)

    override def view(model: Signal[Unit]) =
      div(
        a(href := "#", on.click(NavigateToA), "Go to page A"),
        a(href := "#", on.click(NavigateToB), "Go to page B")
      )

class Issue3636LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    focusWrap("focus-wrap")(
      button("One"),
      button("Two"),
      button("Three")
    )

class Issue3651LiveView extends LiveView[Issue3651LiveView.Msg, Issue3651LiveView.Model]:
  import Issue3651LiveView.*

  def mount(ctx: MountContext) =
    val init = Model()
    ctx.connection match
      case Connection.Connected(capabilities) =>
        capabilities.async.start(ChangeId)(ZIO.unit)(_ => Msg.ChangeId) *>
          capabilities.client.push(MyEvent, Map.empty[String, String]).as(init)
      case Connection.Disconnected => ZIO.succeed(init)

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks
      .empty[Msg, Model]
      .onBrowserEvent(BrowserToServerEvent[Json]("lol"))((model, _, _) => ZIO.succeed(model))
      .onBrowserEvent(BrowserToServerEvent[Json]("reload")) { (model, _, ctx) =>
        val next = model.copy(counter = model.counter + 1)
        ctx.client.push(MyEvent, Map.empty[String, String]).as(next)
      }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ChangeId => ZIO.succeed(model.copy(id = 2))

  override def view(model: Signal[Model]) =
    div(
      div(
        dom.hook("OuterHook", DomRef("main")),
        div(
          idAttr   := model.map(current => s"id-${current.id}"),
          phx.hook := "InnerHook"
        ),
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
        span(idAttr := "total", model.map(_.counter.toString))
      ),
      div(
        styleAttr  := "color: red; font-size: 72px",
        idAttr     := "notice",
        phx.update := PhxUpdate.Ignore,
        "I will disappear if the bug is not present."
      )
    )
end Issue3651LiveView

object Issue3651LiveView:
  private val ChangeId = AsyncKey[Unit]("change-id")
  private val MyEvent  = ServerToBrowserEvent[Map[String, String]]("myevent")
  enum Msg:
    case ChangeId

  final case class Model(id: Int = 1, counter: Int = 0)

class Issue3658LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    div(
      link.pushNavigateUnsafe("/issues/3658?navigated=true", "Link 1"),
      liveView("sticky", Issue3658LiveView.Sticky(), sticky = true)
    )

object Issue3658LiveView:
  class Sticky extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(model: Signal[Unit]) =
      div(
        div(idAttr := "foo", dom.onRemove(JS.dispatch("my-event")), "Hi")
      )

class Issue3656LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
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
      ZIO.succeed(())

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(model: Signal[Unit]) =
      navTag(
        link.pushNavigateUnsafe("/issues/3656?navigated=true", "Link 1")
      )

class Issue3681LiveView(onAway: Boolean) extends LiveView[Unit, Issue3681LiveView.Model]:
  import Issue3681LiveView.*

  def mount(ctx: MountContext) =
    if onAway then
      for
        _        <- ctx.streams.create(MessagesStream, List.empty[Message])
        messages <- ctx.streams.reset(MessagesStream, List(Message(4, 4)))
      yield Model(Some(messages))
    else ZIO.succeed(Model(None))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    div(
      liveView("sticky", Issue3681LiveView.StickyLive(), sticky = true),
      hr(),
      if onAway then
        div(
          h3("A liveview with a stream configured twice"),
          h4("This causes the nested liveview in the layout above to be reset by the client."),
          link.pushNavigate(
            E2ERoutes.issue3681.location,
            "Go back to (the now borked) LV without a stream"
          ),
          h1("Normal Stream"),
          div(
            idAttr     := "msgs-normal",
            phx.update := PhxUpdate.Stream,
            model
              .map(_.messages.get)
              .stream((domId, message) => div(idAttr := domId, div(message.map(_.value.toString))))
          )
        )
      else
        div(
          h3("A LiveView that does nothing but render it's layout."),
          link.pushNavigate(
            E2ERoutes.issue3681Away.location,
            "Go to a different LV with a (funcky) stream"
          )
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
      ctx.streams.create(MessagesStream, List(Message(1, 1), Message(2, 2), Message(3, 3)))

    def handleMessage(model: LiveStream[Message], ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(messages: Signal[LiveStream[Message]]) =
      div(
        idAttr     := "msgs-sticky",
        phx.update := PhxUpdate.Stream,
        messages.stream((domId, message) =>
          div(idAttr := domId, div(message.map(_.value.toString)))
        )
      )

class Issue3684LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    div(liveComponent(Issue3684LiveView.BadgeForm, id = "badge_form", props = ()))

object Issue3684LiveView:
  object BadgeForm extends LiveComponent[Unit, BadgeForm.Msg, String]:
    enum Msg:
      case ChangeType(value: String)
      case FormChanged

    def mount(props: Unit, ctx: MountContext) =
      ZIO.succeed("huey")

    def handleMessage(props: Unit, model: String, ctx: MessageContext) =
      case Msg.ChangeType(value) => ZIO.succeed(value)
      case Msg.FormChanged       => ZIO.succeed(model)

    override def view(props: Signal[Unit], selected: Signal[String], self: ComponentRef[Msg]) =
      div(
        form(
          idAttr := "foo",
          cls    := "max-w-lg p-8 flex flex-col gap-4",
          on.change(_ => Msg.FormChanged),
          phx.target(self),
          phxSubmitAttr := "submit",
          radios(selected, self)
        )
      )

    private def radios(selected: Signal[String], self: ComponentRef[Msg]) =
      fieldset(
        legend("Radio example:"),
        Vector("huey", "dewey").map(radioType =>
          div(
            on.click(Msg.ChangeType(radioType)),
            phx.target(self),
            phx.value("type") := radioType,
            input(
              typ      := "radio",
              idAttr   := radioType,
              nameAttr := "type",
              value    := radioType,
              checked  := selected.map(_ == radioType)
            ),
            label(radioType)
          )
        )
      )
  end BadgeForm
end Issue3684LiveView

class Issue3686LiveView(pageName: String) extends LiveView[Issue3686LiveView.Msg.type, Unit]:
  import Issue3686LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Msg.type) =>
      pageName match
        case "A" =>
          ctx.flash.put(Info, "Flash from A") *>
            ctx.nav.pushNavigate(E2ERoutes.issue3686B.location).as(model)
        case "B" =>
          ctx.flash.put(Info, "Flash from B") *>
            ctx.nav.redirect(E2ERoutes.issue3686C.location).as(model)
        case _ =>
          ctx.flash.put(Info, "Flash from C") *>
            ctx.nav.pushNavigate(E2ERoutes.issue3686A.location).as(model)

  override def view(model: Signal[Unit]) =
    val next = pageName match
      case "A" => "B"
      case "B" => "C"
      case _   => "A"

    div(
      h1(pageName),
      button(on.click(Msg), s"To $next"),
      div(idAttr := "flash", "%{}", flash(Info)(message => span(message)))
    )
end Issue3686LiveView

object Issue3686LiveView:
  private val Info = FlashKind("info")
  case object Msg

class Issue3709LiveView extends LiveView.Routed[Unit, String, Option[String]]:
  import Issue3709LiveView.*

  def mount(params: Option[String], ctx: MountContext) =
    ZIO.succeed(params.getOrElse(""))

  override def handleParams(model: String, params: Option[String], url: URL, ctx: ParamsContext) =
    ZIO.succeed(params.getOrElse(""))

  def handleMessage(model: String, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[String]) =
    div(
      ul(
        (1 to 10).map { i =>
          li(link.pushPatch(E2ERoutes.issue3709Id.location(i), s"Link $i"))
        }
      ),
      div(
        liveComponent(SomeComponent, id = model.map(value => s"user-$value"), props = ()),
        model.map(value => s" id: $value"),
        div(
          "Click the button, then click any link.",
          button(
            onClickAttr := "document.querySelectorAll('li a').forEach((x) => x.click())",
            "Break Stuff"
          )
        )
      )
    )

end Issue3709LiveView

object Issue3709LiveView:
  object SomeComponent extends LiveComponent[Unit, Unit, Unit]:
    def mount(props: Unit, ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Unit]) =
      div("Hello")

class Issue3919LiveView extends LiveView[Issue3919LiveView.Msg, Issue3919LiveView.Action]:
  import Issue3919LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Action(text = "No red"))

  def handleMessage(model: Action, ctx: MessageContext) =
    case Msg.Toggle =>
      if model.attrs.nonEmpty then ZIO.succeed(Action(text = "No red"))
      else ZIO.succeed(Action(text = "Red", attrs = Some(ComponentAttrs(special = true))))

  override def view(model: Signal[Action]) =
    div(
      myComponent(model),
      button(on.click(Msg.Toggle), "toggle")
    )

  private def myComponent(action: Signal[Action]) =
    div(
      styleAttr := action.map(current =>
        if current.attrs.exists(_.special) then "background-color: red;" else ""
      ),
      action.map(_.text)
    )

object Issue3919LiveView:
  final case class Action(text: String, attrs: Option[ComponentAttrs] = None)
  final case class ComponentAttrs(special: Boolean = false)

  enum Msg:
    case Toggle

class Issue3941LiveView extends LiveView[Issue3941LiveView.Msg, Issue3941LiveView.Model]:
  import Issue3941LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.onBrowserEvent(BrowserToServerEvent[Json]("page_position_update")) {
      (model, _, _) => ZIO.succeed(model)
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Toggle(id) =>
      val selectedItems =
        if model.selectedItems.contains(id) then model.selectedItems - id
        else model.selectedItems + id

      ZIO.succeed(model.copy(selectedItems = selectedItems))

  override def view(model: Signal[Model]) =
    div(
      AllItems.map(item => itemCheckbox(item, model.map(_.selectedItems))),
      AllItems.map(item => selectedItem(item, model.map(_.selectedItems)))
    )

  private def selectedItem(item: String, selected: Signal[Set[String]]): Mod[Msg] =
    Signal.when(selected.map(_.contains(item)))(
      div(liveComponent(ItemComponent, id = s"item-$item", props = item))
    )

  private def itemCheckbox(id: String, selected: Signal[Set[String]]) =
    label(
      forId := s"item-select-$id",
      input(
        idAttr   := s"select-$id",
        typ      := "checkbox",
        nameAttr := "select",
        value    := id,
        checked  := selected.map(_.contains(id)),
        on.click(Msg.Toggle(id))
      ),
      id
    )
end Issue3941LiveView

object Issue3941LiveView:
  private val AllItems = Vector("Item_1", "Item_2")

  final case class Model(selectedItems: Set[String] = AllItems.toSet)

  enum Msg:
    case Toggle(id: String)

  object ItemComponent extends LiveComponent.Eventless[String, Unit]:
    def mount(props: String, ctx: MountContext) =
      ZIO.succeed(())

    override def view(
      props: Signal[String],
      model: Signal[Unit],
      self: ComponentRef[Nothing]
    ) =
      div(
        idAttr   := props.map(value => s"item-$value"),
        phx.hook := "PagePositionNotifier",
        liveComponent(
          ItemHeaderComponent,
          id = props.map(value => s"item-header-$value"),
          props = props
        )
      )

  object ItemHeaderComponent
      extends LiveComponent[String, ItemHeaderComponent.Msg, ItemHeaderComponent.Model]:
    private val Load = AsyncKey[String]("async_assign")

    enum Msg:
      case Loaded(result: LiveAsyncResult[String])

    final case class Model(item: String, asyncAssign: AsyncValue[String] = AsyncValue.empty)

    def mount(props: String, ctx: MountContext) =
      ZIO.succeed(Model(props))

    override def update(props: String, model: Model, ctx: UpdateContext) =
      val loading = model.copy(
        item = props,
        asyncAssign = AsyncValue.markLoading(model.asyncAssign, reset = true)
      )
      ctx.connection match
        case Connection.Connected(capabilities) =>
          capabilities.async.start(Load)(ZIO.succeed(props))(Msg.Loaded(_)).as(loading)
        case Connection.Disconnected => ZIO.succeed(loading)

    def handleMessage(props: String, model: Model, ctx: MessageContext) =
      case Msg.Loaded(result @ LiveAsyncResult.Succeeded(item)) =>
        ZIO.succeed(model.copy(item = item, asyncAssign = model.asyncAssign.updated(result)))
      case Msg.Loaded(result) =>
        ZIO.succeed(model.copy(asyncAssign = model.asyncAssign.updated(result)))

    override def view(
      props: Signal[String],
      model: Signal[Model],
      self: ComponentRef[Msg]
    ) =
      val item   = model.map(_.item)
      val loaded = model.map(_.asyncAssign.isInstanceOf[AsyncValue.Ok[?]])

      div(
        idAttr := item.map(value => s"header-$value"),
        loaded.chooseMod(
          div(
            idAttr := item,
            cls    := "border border-y-0 bg-green-500 text-white",
            item.map(value => s"$value - I AM LOADED!")
          ),
          div(
            idAttr := item,
            cls    := "border border-y-0 bg-red-500 text-white",
            item.map(value => s"$value - I AM LOADING")
          )
        ),
        model.map(_.asyncAssign.toString)
      )
  end ItemHeaderComponent
end Issue3941LiveView

class Issue3953LiveView extends LiveView[Issue3953LiveView.Msg, Boolean]:
  import Issue3953LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(false)

  def handleMessage(model: Boolean, ctx: MessageContext) =
    case Msg.Toggle => ZIO.succeed(!model)

  override def view(model: Signal[Boolean]) =
    div(
      liveComponent(Component, id = "comp", props = ()),
      button(on.click(Msg.Toggle), "Show"),
      model.chooseMod(liveView("nested_view", NestedViewLive()), "")
    )

object Issue3953LiveView:
  enum Msg:
    case Toggle

  object Component extends LiveComponent[Unit, Unit, Unit]:
    def mount(props: Unit, ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Unit]) =
      div("Component")

  class NestedViewLive extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(model: Signal[Unit]) =
      div(
        "Nested Content",
        liveComponent(Component, id = "comp2", props = ())
      )

class Issue3979LiveView extends LiveView[Issue3979LiveView.Msg, Issue3979LiveView.Model]:
  import Issue3979LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(
      Model(counter = 1, components = (1 to 10).map(id => ComponentState(id, counter = 0)).toVector)
    )

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Bump =>
      val target         = model.counter
      val nextComponents = model.components.map { component =>
        if component.id == target then component.copy(counter = component.counter + 1)
        else component
      }
      ctx.async
        .start(AsyncKey[Int](s"update-$target"))(ZIO.sleep(100.millis).as(target))(
          Msg.DelayedUpdate(_)
        )
        .as(model.copy(counter = target + 1, components = nextComponents))
    case Msg.DelayedUpdate(LiveAsyncResult.Succeeded(id)) =>
      model.components.find(_.id == id) match
        case Some(component) =>
          ctx.components
            .sendUpdate[CounterComponent.type](
              s"comp-$id",
              CounterProps(id = id, domCounter = component.counter, counter = 10)
            ).as(model)
        case None => ZIO.succeed(model)
    case Msg.DelayedUpdate(_) => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    div(
      (1 to 10).map { id =>
        liveComponent(
          CounterComponent,
          id = s"comp-$id",
          props = model
            .map(_.components.find(_.id == id).get).map(current =>
              CounterProps(current.id, domCounter = current.counter, counter = current.counter)
            )
        )
      },
      button(on.click(Msg.Bump), "Bump ID (and counter)")
    )
end Issue3979LiveView

object Issue3979LiveView:
  final case class ComponentState(id: Int, counter: Int)
  final case class Model(counter: Int, components: Vector[ComponentState])
  final case class CounterProps(id: Int, domCounter: Int, counter: Int)

  enum Msg:
    case Bump
    case DelayedUpdate(result: LiveAsyncResult[Int])

  object CounterComponent extends LiveComponent[CounterProps, Unit, CounterProps]:
    def mount(props: CounterProps, ctx: MountContext) =
      ZIO.succeed(props)

    override def update(props: CounterProps, model: CounterProps, ctx: UpdateContext) =
      ZIO.succeed(props)

    def handleMessage(props: CounterProps, model: CounterProps, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(
      props: Signal[CounterProps],
      model: Signal[CounterProps],
      self: ComponentRef[Unit]
    ) =
      div(
        idAttr := model.map(current => s"hello-${current.id}-${current.domCounter}"),
        model.map(_.counter.toString)
      )

class Issue4027LiveView
    extends LiveView.Routed[
      Issue4027LiveView.Msg,
      Issue4027LiveView.Model,
      Issue4027LiveView.QueryParams
    ]:
  import Issue4027LiveView.*

  def mount(params: QueryParams, ctx: MountContext) =
    ZIO.succeed(Model(caseName = params.caseName))

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    ZIO.succeed(model.copy(caseName = params.caseName))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Load =>
      startLoad(ctx.async, InitialItems).as(model.copy(data = AsyncValue.markLoading(model.data)))
    case Msg.Remove =>
      startLoad(ctx.async, InitialItems.tail).as(
        model.copy(data = AsyncValue.markLoading(model.data))
      )
    case Msg.Loaded(result) => ZIO.succeed(model.copy(data = model.data.updated(result)))

  override def view(model: Signal[Model]) =
    val caseName     = model.map(_.caseName)
    val data         = model.map(_.data)
    val currentItems = data.map(AsyncValue.currentValue)

    div(
      cls := "p-4",
      p(
        cls := "my-4",
        "Click Load Data. 3 items should be displayed. Then click Remove First entry. The expected result is 2 items displayed."
      ),
      Signal.when(caseName.map(_ == "second"))(
        div(styleAttr := "margin: 10px; height: 1px; background-color: black;")
      ),
      caseName
        .map(_ == "first").chooseMod(
          currentItems
            .map(_.isDefined).chooseMod(
              liveComponent(
                ReproLiveComponent,
                id = "repro",
                props = currentItems.map(_.getOrElse(Vector.empty))
              ),
              ""
            ),
          liveComponent(ReproLiveComponentWithAsyncResult, id = "repro_async", props = data)
        ),
      div(
        button(on.click(Msg.Load), "Load data"),
        button(on.click(Msg.Remove), "Remove first entry")
      )
    )
  end view
end Issue4027LiveView

object Issue4027LiveView:
  private val Load = AsyncKey[Vector[Item]]("data")
  final case class QueryParams(caseName: String = "first")

  final case class Item(id: Int, value: String)
  final case class Model(
    caseName: String = "first",
    data: AsyncValue[Vector[Item]] = AsyncValue.ok(Vector.empty))

  enum Msg:
    case Load, Remove
    case Loaded(result: LiveAsyncResult[Vector[Item]])

  private val InitialItems = Vector(Item(1, "First"), Item(2, "Second"), Item(3, "Third"))

  private def startLoad(async: Async[Msg], items: Vector[Item]) =
    async.start(Load)(ZIO.sleep(100.millis).as(items))(Msg.Loaded(_))

  object ReproLiveComponent extends LiveComponent[Vector[Item], Unit, Vector[Item]]:
    def mount(props: Vector[Item], ctx: MountContext) =
      ZIO.succeed(props)

    override def update(props: Vector[Item], model: Vector[Item], ctx: UpdateContext) =
      ZIO.succeed(props)

    def handleMessage(props: Vector[Item], model: Vector[Item], ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(
      props: Signal[Vector[Item]],
      model: Signal[Vector[Item]],
      self: ComponentRef[Unit]
    ) =
      keyedResult(model)

  object ReproLiveComponentWithAsyncResult
      extends LiveComponent[
        AsyncValue[Vector[Item]],
        Unit,
        AsyncValue[Vector[Item]]
      ]:
    def mount(props: AsyncValue[Vector[Item]], ctx: MountContext) =
      ZIO.succeed(props)

    override def update(
      props: AsyncValue[Vector[Item]],
      model: AsyncValue[Vector[Item]],
      ctx: UpdateContext
    ) =
      ZIO.succeed(props)

    def handleMessage(
      props: AsyncValue[Vector[Item]],
      model: AsyncValue[Vector[Item]],
      ctx: MessageContext
    ) =
      (_: Unit) => ZIO.succeed(model)

    override def view(
      props: Signal[AsyncValue[Vector[Item]]],
      model: Signal[AsyncValue[Vector[Item]]],
      self: ComponentRef[Unit]
    ) =
      asyncResult(model)

  private def keyedResult(items: Signal[Vector[Item]]) =
    div(
      idAttr := "result",
      keyedItems(items)
    )

  private def asyncResult(data: Signal[AsyncValue[Vector[Item]]]) =
    div(
      idAttr := "result",
      data
        .map(AsyncValue.currentValue)
        .map(_.isDefined)
        .chooseMod(
          keyedItems(data.map(current => AsyncValue.currentValue(current).getOrElse(Vector.empty))),
          ""
        )
    )

  private def keyedItems(items: Signal[Vector[Item]]) =
    items.splitBy(_.id)((_, item) => p(item.map(_.value)))
end Issue4027LiveView

class Issue4066LiveView
    extends LiveView.Routed[
      Issue4066LiveView.Msg,
      Issue4066LiveView.Model,
      Issue4066LiveView.QueryParams
    ]:
  import Issue4066LiveView.*

  def mount(_params: QueryParams, ctx: MountContext) =
    val model = Model(renderTime = java.time.Instant.now.toString)
    ZIO.succeed(model)

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    ZIO.succeed(model.copy(delay = params.delay))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Toggle => ZIO.succeed(model.copy(renderInput = !model.renderInput))

  override def view(model: Signal[Model]) =
    div(
      p(idAttr := "render-time", model.map(_.renderTime)),
      button(on.click(Msg.Toggle), "Toggle"),
      model
        .map(_.renderInput).chooseMod(
          liveComponent(DelayedInputComponent, id = "foo", props = model.map(_.delay)),
          ""
        )
    )

object Issue4066LiveView:
  final case class QueryParams(delay: Int = 3000)

  final case class Model(renderTime: String, renderInput: Boolean = true, delay: Int = 3000)

  enum Msg:
    case Toggle

  object DelayedInputComponent extends LiveComponent[Int, Unit, Unit]:
    override def hooks: ComponentLiveHooks[Int, Unit, Unit] =
      ComponentLiveHooks.empty.onBrowserEvent(BrowserToServerEvent[Json]("do-something")) {
        (_, model, _, _) => ZIO.succeed(model)
      }

    def mount(props: Int, ctx: MountContext) =
      ZIO.succeed(())

    def handleMessage(props: Int, model: Unit, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    override def view(props: Signal[Int], model: Signal[Unit], self: ComponentRef[Unit]) =
      input(
        dom.hook("Issue4066Hook", DomRef("foo")),
        phx.target(self),
        dataAttr("delay") := props.map(_.toString)
      )

class Issue4078LiveView extends LiveView[Issue4078LiveView.Msg, Issue4078LiveView.Model]:
  import Issue4078LiveView.*

  def mount(ctx: MountContext) =
    ctx.uploads.allow(Upload).map(upload => Model(upload = upload))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate       => refreshUpload(model, ctx.uploads)
    case Msg.ToggleDisabled => ZIO.succeed(model.copy(disabled = !model.disabled))
    case Msg.ToggleClass    =>
      val nextClass =
        if model.customClass == "initial-class" then "updated-class" else "initial-class"
      ZIO.succeed(model.copy(customClass = nextClass))

  override def view(model: Signal[Model]) =
    val upload = model.map(_.upload)

    div(
      form(
        idAttr := "upload-form",
        on.change(_ => Msg.Validate),
        liveFileInput(
          upload,
          disabled := model.map(_.disabled),
          cls      := model.map(_.customClass)
        )
      ),
      button(
        idAttr := "toggle-disabled",
        typ    := "button",
        on.click(Msg.ToggleDisabled),
        "Toggle Disabled"
      ),
      button(
        idAttr := "toggle-class",
        typ    := "button",
        on.click(Msg.ToggleClass),
        "Toggle Class"
      ),
      upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
        articleTag(cls := "upload-entry", span(cls := "entry-name", entry.map(_.client.fileName)))
      }
    )

  private def refreshUpload(model: Model, uploads: Uploads): Task[Model] =
    uploads.get(Upload).map {
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
    upload: LiveUpload[Chunk[Byte]],
    disabled: Boolean = true,
    customClass: String = "initial-class")

  private val Upload: LiveUploadDef[Chunk[Byte]] = LiveUploadDef.inMemory(
    name = "avatar",
    accept = LiveUploadAccept.only(".jpg", ".jpeg", ".png", ".txt"),
    maxEntries = 2
  )

class Issue4088LiveView extends LiveView[Issue4088LiveView.Msg, String]:
  import Issue4088LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed("value")

  override def hooks: LiveHooks[Msg, String] =
    LiveHooks.empty.onBrowserEvent(BrowserToServerEvent[Json]("my_update")) { (_, _, _) =>
      val value = System.nanoTime.toString
      ZIO.succeed(value)
    }

  def handleMessage(model: String, ctx: MessageContext) =
    (_: Msg) => ZIO.succeed(model)

  override def view(value: Signal[String]) =
    div(dom.hook("Issue4088Hook", DomRef("foo")), value)

object Issue4088LiveView:
  enum Msg:
    case Noop

class Issue4094LiveView extends LiveView.Routed[Unit, Unit, Option[String]]:
  def mount(_params: Option[String], ctx: MountContext) =
    ZIO.succeed(())

  override def handleParams(model: Unit, params: Option[String], url: URL, ctx: ParamsContext) =
    if params.contains("bar") then
      ctx.nav.redirect(E2ERoutes.navigationA.location(NavigationLiveViews.AParams(None))).as(model)
    else ZIO.succeed(model)

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    link.pushPatch(E2ERoutes.issue4094.location(Some("bar")), "Patch")

class Issue4095LiveView extends LiveView[Issue4095LiveView.Msg, String]:
  import Issue4095LiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed("true")

  def handleMessage(model: String, ctx: MessageContext) =
    case Msg.Validate(data) => ZIO.succeed(data.getOrElse("show?", ""))

  override def view(show: Signal[String]) =
    div(
      form(
        idAttr := "issue-4095-form",
        on.change.form(Msg.Validate(_)),
        input(typ := "text", nameAttr := "show?", idAttr := "show?", value := show),
        portal("portal", target = DomSelector.css("#portal_target"))(
          div(Signal.when(show.map(_.nonEmpty))(button("Show?")))
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
    ZIO.succeed("Test")

  def handleMessage(model: String, ctx: MessageContext) =
    case Msg.Validate(data) => ZIO.succeed(data.getOrElse("name", model))
    case Msg.Submit(data)   => ZIO.succeed(data.getOrElse("name", model))

  override def view(name: Signal[String]) =
    div(
      input(
        formId       := "my-form",
        phx.debounce := 500,
        nameAttr     := "name",
        idAttr       := "name",
        value        := name,
        typ          := "text"
      ),
      form(
        idAttr := "my-form",
        on.change.form(Msg.Validate(_)),
        on.submit.form(Msg.Submit(_)),
        button(typ := "submit", submission.replaceTextWith("Submitting..."), "Submit")
      )
    )

object Issue4102LiveView:
  enum Msg:
    case Validate(data: FormData)
    case Submit(data: FormData)

class Issue4107LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    div(
      portal("test-form-portal", target = DomSelector.css("body"))(
        form(
          idAttr := "test-form",
          action := "/api/test",
          method := "post",
          input(typ := "hidden", nameAttr := "test_input", value := "test_value")
        )
      ),
      button(typ := "submit", formId := "test-form", "Submit")
    )

class Issue4121LiveView extends LiveView[Issue4121LiveView.Msg.type, Issue4121LiveView.Model]:
  import Issue4121LiveView.*

  def mount(ctx: MountContext) =
    ctx.streams.create(ItemsStream, InitialItems).map(items => Model(items))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg.type) =>
      val id = System.nanoTime.toInt
      ctx.streams.reset(ItemsStream, Vector(Item(id, s"Item $id"))).map(Model(_))

  override def view(model: Signal[Model]) =
    div(
      button(on.click(Msg), "Reset teleported stream"),
      portal("teleported-stream", target = DomSelector.css("body"))(
        ul(
          idAttr     := "stream-in-lv",
          phx.update := PhxUpdate.Stream,
          model.map(_.items).stream((domId, item) => li(idAttr := domId, item.map(_.name)))
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
    ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => ZIO.succeed(model)

  override def view(model: Signal[Unit]) =
    h1("Inside")
