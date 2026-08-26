import java.nio.charset.StandardCharsets

import zio.durationInt
import zio.json.ast.Json
import zio.{Chunk, Task, ZIO}

import scalive.*
import scalive.codecs.StringAsIsEncoder

private val issueDataName          = htmlAttr("data-name", StringAsIsEncoder)
private val issueDataTestId        = htmlAttr("data-testid", StringAsIsEncoder)
private val issueTabIndex          = htmlAttr("tabindex", StringAsIsEncoder)
private val issue4212Element       = HtmlTag("lv-custom-el")
private val issue4323Face          = HtmlTag("issue-4323-face")
private val issue4323DelegatesFace = HtmlTag("issue-4323-delegates-face")

private def issueUploadError(error: LiveUploadError): String =
  error match
    case LiveUploadError.TooManyFiles          => ":too_many_files"
    case LiveUploadError.TooLarge              => ":too_large"
    case LiveUploadError.NotAccepted           => ":not_accepted"
    case LiveUploadError.WriterFailure(reason) => s"{:writer_failure, :$reason}"
    case LiveUploadError.ExternalClientFailure => ":external_client_failure"
    case LiveUploadError.Custom(reason)        => s":$reason"
    case LiveUploadError.Unknown(reason)       => s":$reason"
    case LiveUploadError.External(meta)        => meta.toString

class Issue2835LiveView extends LiveView[Issue2835LiveView.Msg, Issue2835LiveView.Model]:
  import Issue2835LiveView.*

  def mount(ctx: MountContext) = ctx.uploads.allow(Upload).map(Model(_))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate => refresh(model, ctx.uploads)
    case Msg.Progress =>
      refresh(model, ctx.uploads).flatMap { current =>
        current.upload.entries.find(_.status == LiveUploadEntryStatus.Completed) match
          case Some(entry) =>
            ctx.uploads
              .consume(entry)(completed =>
                ZIO.succeed(ConsumeDecision.Consume(completed.client.fileName))
              ).map { case (name, upload) =>
                current.copy(upload = upload, uploadedFiles = current.uploadedFiles :+ name)
              }
          case None => ZIO.succeed(current)
      }

  override def view(model: Signal[Model]) =
    val upload = model.map(_.upload)
    div(
      form(
        idAttr   := "upload-form",
        phx.hook := "Issue2835UploadSync",
        on.change(_ => Msg.Validate),
        liveFileInput(upload, upload.onProgress(_ => Msg.Progress)),
        upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
          articleTag(
            cls           := "upload-entry",
            issueDataName := entry.map(_.client.fileName),
            entry.map(current => s"${current.client.fileName}: ${current.progress}%")
          )
        },
        uploadErrors(upload).splitBy(_.toString)((_, error) =>
          p(cls := "upload-error", error.map(issueUploadError))
        )
      ),
      ul(
        idAttr := "uploaded-files",
        model.map(_.uploadedFiles).splitBy(identity)((_, name) => li(name))
      )
    )

  private def refresh(model: Model, uploads: Uploads): Task[Model] =
    uploads.get(Upload).map(_.fold(model)(upload => model.copy(upload = upload)))
end Issue2835LiveView

object Issue2835LiveView:
  enum Msg:
    case Validate, Progress

  final case class Model(
    upload: LiveUpload[Chunk[Byte]],
    uploadedFiles: Vector[String] = Vector.empty)

  private val Upload = LiveUploadDef.inMemory(
    "documents",
    LiveUploadAccept.Any,
    maxEntries = 2,
    autoUpload = true,
    maxEntriesMode = LiveUploadMaxEntriesMode.Total
  )

class Issue3199LiveView(away: Boolean)
    extends LiveView[Issue3199LiveView.Msg, Option[LiveStream[Issue3199LiveView.Item]]]:
  import Issue3199LiveView.*

  def mount(ctx: MountContext) =
    if away then ZIO.succeed(None)
    else ctx.streams.create(Items, List(Item(1, "Item 1"), Item(2, "Item 2"))).map(Some(_))

  def handleMessage(model: Option[LiveStream[Item]], ctx: MessageContext) =
    case Msg.Delete(id) =>
      model match
        case Some(_) => ctx.streams.delete(Items, id).map(Some(_))
        case None    => ZIO.succeed(model)

  override def view(model: Signal[Option[LiveStream[Item]]]) =
    if away then h1("Away")
    else
      div(
        h1("Items"),
        link.pushNavigateUnsafe("/issues/3199/away", "Navigate away"),
        div(
          idAttr := "root-remove-transition",
          hidden := true,
          dom.onRemove(JS.transition("view-removing", time = 100))
        ),
        ul(
          idAttr     := "items",
          phx.update := PhxUpdate.Stream,
          model
            .map(_.get).stream((domId, item) =>
              li(
                idAttr := domId,
                dom.onRemove(JS.transition("item-removing", time = 1000)),
                item.map(_.name),
                button(on.click(item.map(current => Msg.Delete(current.id))), "Delete")
              )
            )
        )
      )
end Issue3199LiveView

object Issue3199LiveView:
  final case class Item(id: Int, name: String)
  enum Msg:
    case Delete(id: Int)
  private val Items = LiveStreamDef.byId[Item, Int]("items")(_.id)

class Issue3319LiveView extends LiveView[Issue3319LiveView.Msg, Issue3319LiveView.Model]:
  import Issue3319LiveView.*

  def mount(ctx: MountContext) = ctx.uploads.allow(Upload).map(Model(_))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate => refresh(model, ctx.uploads)
    case Msg.Submit   =>
      ctx.uploads
        .consumeCompleted(Upload)(entry =>
          ZIO.succeed(ConsumeDecision.Consume(entry.client.fileName))
        ).map { case (names, upload) => model.copy(upload = upload, submitted = names.toVector) }

  override def view(model: Signal[Model]) =
    val upload = model.map(_.upload)
    div(
      form(
        idAttr := "upload-form",
        on.change(_ => Msg.Validate),
        on.submit(Msg.Submit),
        liveFileInput(upload, required := true),
        button(typ                     := "submit", "Submit"),
        upload
          .map(_.entries).splitBy(_.ref)((_, entry) =>
            p(cls := "upload-entry", entry.map(_.client.fileName))
          )
      ),
      p(idAttr := "submitted", model.map(_.submitted.mkString(",")))
    )

  private def refresh(model: Model, uploads: Uploads) =
    uploads.get(Upload).map(_.fold(model)(upload => model.copy(upload = upload)))
end Issue3319LiveView

object Issue3319LiveView:
  enum Msg:
    case Validate, Submit
  final case class Model(upload: LiveUpload[Chunk[Byte]], submitted: Vector[String] = Vector.empty)
  private val Upload = LiveUploadDef.inMemory(
    "documents",
    LiveUploadAccept.Any,
    maxEntries = 2
  )

class Issue3368LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext)                        = ZIO.unit
  def handleMessage(model: Unit, ctx: MessageContext) = _ => ZIO.unit
  override def view(model: Signal[Unit])              =
    div(liveComponent(Issue3368LiveView.UploadComponent, id = "uploader", props = ()))

object Issue3368LiveView:
  object UploadComponent extends LiveComponent[Unit, UploadComponent.Msg, UploadComponent.Model]:
    enum Msg:
      case Validate, Save
    final case class Model(
      upload: LiveUpload[Chunk[Byte]],
      savedFiles: Vector[String] = Vector.empty)

    private val Upload = LiveUploadDef.inMemory(
      "file",
      LiveUploadAccept.only(".jpg", ".jpeg", ".png", ".gif")
    )

    def mount(props: Unit, ctx: MountContext) = ctx.uploads.allow(Upload).map(Model(_))

    def handleMessage(props: Unit, model: Model, ctx: MessageContext) =
      case Msg.Validate =>
        ctx.uploads.get(Upload).map(_.fold(model)(upload => model.copy(upload = upload)))
      case Msg.Save =>
        ctx.uploads
          .consumeCompleted(Upload)(entry =>
            ZIO.succeed(ConsumeDecision.Consume(entry.client.fileName))
          ).map { case (names, upload) => model.copy(upload, names.toVector) }

    override def view(props: Signal[Unit], model: Signal[Model], self: ComponentRef[Msg]) =
      val upload = model.map(_.upload)
      div(
        form(
          idAttr := "upload-form",
          phx.target(self),
          on.change(_ => Msg.Validate),
          on.submit(Msg.Save),
          div(idAttr := "dropzone", upload.dropTarget, "Drop files here"),
          liveFileInput(upload),
          button(typ := "submit", "Upload")
        ),
        upload
          .map(_.entries).splitBy(_.ref)((_, entry) =>
            p(cls := "upload-entry", entry.map(_.client.fileName))
          ),
        uploadErrors(upload).splitBy(_.toString)((_, error) =>
          p(cls := "upload-error", error.map(issueUploadError))
        ),
        p(idAttr := "saved-files", model.map(_.savedFiles.mkString(",")))
      )
  end UploadComponent
end Issue3368LiveView

class Issue3391LiveView extends LiveView[Issue3391LiveView.Msg, Issue3391LiveView.Model]:
  import Issue3391LiveView.*

  def mount(ctx: MountContext) = ctx.uploads.allow(Upload).map(Model(_))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate => refresh(model, ctx.uploads)
    case Msg.Progress =>
      refresh(model, ctx.uploads).flatMap { current =>
        current.upload.entries.find(_.status == LiveUploadEntryStatus.Completed) match
          case Some(entry) =>
            ctx.uploads
              .consume(entry)(_ => ZIO.succeed(ConsumeDecision.Consume(())))
              .map { case (_, upload) => current.copy(upload = upload, uploaded = true) }
          case None => ZIO.succeed(current)
      }
    case Msg.Cancel(entry) => ctx.uploads.cancel(entry).map(upload => model.copy(upload = upload))
    case Msg.Submit        => ZIO.succeed(model.copy(submitted = true))

  override def view(model: Signal[Model]) =
    val upload = model.map(_.upload)
    div(
      form(
        idAttr := "upload-form",
        on.change(_ => Msg.Validate),
        on.submit(Msg.Submit),
        liveFileInput(upload, upload.onProgress(_ => Msg.Progress)),
        button(typ := "submit", "Submit"),
        upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
          articleTag(
            cls := "upload-entry",
            span(entry.map(current => s"${current.client.fileName}: ${current.progress}%")),
            button(typ := "button", on.click(entry.map(Msg.Cancel.apply)), "Cancel"),
            uploadErrors(entry).splitBy(_.toString)((_, error) =>
              p(cls := "upload-error", error.map(issueUploadError))
            )
          )
        }
      ),
      p(idAttr := "uploaded", model.map(current => s"uploaded: ${current.uploaded}")),
      p(idAttr := "submitted", model.map(current => s"submitted: ${current.submitted}"))
    )

  private def refresh(model: Model, uploads: Uploads) =
    uploads.get(Upload).map(_.fold(model)(upload => model.copy(upload = upload)))
end Issue3391LiveView

object Issue3391LiveView:
  enum Msg:
    case Validate, Progress, Submit
    case Cancel(entry: LiveUploadEntry[Chunk[Byte]])
  final case class Model(
    upload: LiveUpload[Chunk[Byte]],
    submitted: Boolean = false,
    uploaded: Boolean = false)
  private val Upload = LiveUploadDef.inMemory(
    "document",
    LiveUploadAccept.only(".txt"),
    autoUpload = true
  )

class Issue3931LiveView extends LiveView[Issue3931LiveView.Msg, Issue3931LiveView.Model]:
  import Issue3931LiveView.*

  def mount(ctx: MountContext) =
    ctx.connection match
      case Connection.Connected(capabilities) =>
        capabilities.async
          .start(Load)(ZIO.sleep(100.millis).as("This was loaded asynchronously!"))(Msg.Loaded(_))
          .as(Model())
      case Connection.Disconnected => ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Loaded(LiveAsyncResult.Succeeded(value)) => ZIO.succeed(Model(Some(value)))
    case Msg.Loaded(_)                                => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    div(
      cls := "max-w-4xl mx-auto p-8",
      div(
        idAttr := "async",
        model.map(_.value.fold("Loading data...")(identity))
      )
    )

object Issue3931LiveView:
  private val Load = AsyncKey[String]("slow-data")
  final case class Model(value: Option[String] = None)
  enum Msg:
    case Loaded(result: LiveAsyncResult[String])

class Issue4209LiveView extends LiveView[Issue4209LiveView.Msg, Issue4209LiveView.Model]:
  import Issue4209LiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(Model())

  override def hooks =
    LiveHooks.empty.onBrowserEvent(BrowserToServerEvent[Json]("hold-lock")) { (model, _, _) =>
      ZIO.sleep(800.millis).as(model.copy(outsideCount = 1, childLabel = 1))
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Noop => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    div(
      div(idAttr := "outside-count", model.map(_.outsideCount.toString)),
      div(
        idAttr   := "locked-panel",
        phx.hook := ".LockedPanel",
        div(
          idAttr := "locked-child-content",
          span(idAttr := "locked-child-label", model.map(current => s"child ${current.childLabel}"))
        ),
        button(idAttr := "start-locked-update", typ := "button", "Start locked update")
      ),
      div(idAttr := "slow-target", div(idAttr := "slow-target-child", "slow target"))
    )

object Issue4209LiveView:
  final case class Model(outsideCount: Int = 0, childLabel: Int = 0)
  enum Msg:
    case Noop

class Issue4212LiveView extends LiveView[Issue4212LiveView.Msg, Issue4212LiveView.Model]:
  import Issue4212LiveView.*

  def mount(ctx: MountContext) =
    ctx.streams.create(Items, InitialItems).map(items => Model(items))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Insert =>
      val counter = model.counter + 1
      ctx.streams
        .insert(Items, Item(s"new$counter", s"New $counter"), at = StreamAt.Index(1))
        .map(items => Model(items, counter))

  override def view(model: Signal[Model]) =
    div(
      button(idAttr := "insert-at-1", on.click(Msg.Insert), "Insert at 1"),
      ul(
        idAttr     := "items",
        phx.update := PhxUpdate.Stream,
        model
          .map(_.items).stream((domId, item) =>
            li(
              idAttr := domId,
              issue4212Element(
                idAttr := item.map(current => s"el-${current.id}"),
                item.map(_.name)
              )
            )
          )
      )
    )
end Issue4212LiveView

object Issue4212LiveView:
  final case class Item(id: String, name: String)
  final case class Model(items: LiveStream[Item], counter: Int = 0)
  enum Msg:
    case Insert
  private val Items        = LiveStreamDef.byId[Item, String]("items")(_.id)
  private val InitialItems = List(Item("a", "A"), Item("b", "B"), Item("c", "C"))

class Issue4290LiveView(page: String)
    extends LiveView[Issue4290LiveView.Msg, Issue4290LiveView.Model]:
  import Issue4290LiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(Model())

  override def hooks =
    LiveHooks.empty.onRawEvent { (model, event, _) =>
      if page == "B" && event.bindingId != "sandbox:eval" then
        ZIO.succeed(LiveEventHookResult.halt(model.copy(events = model.events :+ event.bindingId)))
      else ZIO.succeed(LiveEventHookResult.cont(model))
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Navigate => ctx.nav.pushNavigateUnsafe("/issues/4290/b").as(model)
    case Msg.Validate => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    if page == "B" then
      div(
        h1("B"),
        span(idAttr := "event-count", model.map(_.events.length.toString)),
        model
          .map(_.events.zipWithIndex).splitBy(_._2)((_, event) =>
            div(idAttr := event.map(current => s"event-${current._2}"), event.map(_._1))
          )
      )
    else
      div(
        h1("A"),
        div(
          idAttr := "slow-remove",
          dom.onRemove(JS.transition("fade-out", time = 1500)),
          "removed with transition"
        ),
        form(
          idAttr := "form",
          on.change(_ => Msg.Validate),
          input(typ := "text", nameAttr := "name")
        ),
        button(on.click(Msg.Navigate), "Navigate")
      )
end Issue4290LiveView

object Issue4290LiveView:
  final case class Model(events: Vector[String] = Vector.empty)
  enum Msg:
    case Navigate, Validate

class Issue4323LiveView extends LiveView[Issue4323LiveView.Msg.type, Int]:
  def mount(ctx: MountContext) = ZIO.succeed(0)

  override def hooks = LiveHooks.empty.onRawEvent { (model, event, _) =>
    if event.bindingId == "sandbox:eval" then
      ZIO.succeed(
        LiveEventHookResult.haltReply(model + 1, Json.Obj("result" -> Json.Null))
      )
    else ZIO.succeed(LiveEventHookResult.cont(model))
  }

  def handleMessage(model: Int, ctx: MessageContext) = _ => ZIO.succeed(model)

  override def view(counter: Signal[Int]) =
    form(
      idAttr := "test-form",
      issue4323Face(
        idAttr        := "face-default",
        issueTabIndex := "0",
        span(idAttr := "face-default-child", counter.map(value => s"count:$value"))
      ),
      issue4323Face(
        idAttr           := "face-opt-in",
        issueTabIndex    := "0",
        phx.patchFocused := true,
        span(idAttr := "face-opt-in-child", counter.map(value => s"count:$value"))
      ),
      issue4323DelegatesFace(
        idAttr           := "face-delegates",
        phx.patchFocused := true,
        span(idAttr := "face-delegates-child", counter.map(value => s"count:$value"))
      ),
      input(idAttr := "native-default", value := counter.map(_.toString)),
      input(
        idAttr           := "native-opt-in",
        value            := counter.map(_.toString),
        phx.patchFocused := true
      )
    )
end Issue4323LiveView

object Issue4323LiveView:
  case object Msg

class Issue4325LiveView extends LiveView[Issue4325LiveView.Msg.type, Int]:
  def mount(ctx: MountContext)                       = ZIO.succeed(0)
  def handleMessage(model: Int, ctx: MessageContext) = _ => ZIO.succeed(model + 1)
  override def view(count: Signal[Int])              =
    div(
      button(on.click(Issue4325LiveView.Msg), "Increment"),
      div(
        idAttr   := "hooked",
        phx.hook := "IdPassthrough",
        count.map(value => s"count is $value")
      )
    )

object Issue4325LiveView:
  case object Msg

class Issue4334LiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext)                        = ZIO.unit
  def handleMessage(model: Unit, ctx: MessageContext) = _ => ZIO.unit
  override def view(model: Signal[Unit])              =
    mainTag(
      h1("LiveComponent root ID corruption repro"),
      liveComponent(Issue4334LiveView.RootChangingComponent, id = "demo-component", props = ())
    )

object Issue4334LiveView:
  case object ChangeRoot

  object RootChangingComponent extends LiveComponent[Unit, ChangeRoot.type, Boolean]:
    def mount(props: Unit, ctx: MountContext)                           = ZIO.succeed(false)
    def handleMessage(props: Unit, model: Boolean, ctx: MessageContext) = _ => ZIO.succeed(true)
    override def view(
      props: Signal[Unit],
      changed: Signal[Boolean],
      self: ComponentRef[ChangeRoot.type]
    ) =
      sectionTag(
        idAttr          := changed.map(if _ then "new-root" else "old-root"),
        issueDataTestId := "component-root",
        phx.hook        := "RootChange",
        button(
          idAttr := "change-root",
          phx.target(self),
          on.click(ChangeRoot),
          "Change component root ID"
        ),
        p(
          idAttr := changed.map(if _ then "new-child" else "old-child"),
          changed.map(if _ then "NEW CHILD SHOULD REMAIN VISIBLE" else "Old child is visible")
        )
      )

class Issue4350LiveView extends LiveView[Issue4350LiveView.Msg, Issue4350LiveView.Model]:
  import Issue4350LiveView.*
  def mount(ctx: MountContext)                         = ZIO.succeed(Model())
  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Tick => ZIO.succeed(model.copy(tick = model.tick + 1))
    case Msg.Hide => ZIO.succeed(model.copy(show = false))
    case Msg.Show => ZIO.succeed(model.copy(show = true))
  override def view(model: Signal[Model]) =
    div(
      button(idAttr := "tick", on.click(Msg.Tick), "tick"),
      button(idAttr := "hide", on.click(Msg.Hide), "hide"),
      button(idAttr := "show", on.click(Msg.Show), "show"),
      Signal.when(model.map(_.show))(
        div(
          idAttr := "wrapper",
          liveComponent(Branch, id = "branch", props = model.map(_.tick))
        )
      )
    )

object Issue4350LiveView:
  final case class Model(show: Boolean = true, tick: Int = 0)
  enum Msg:
    case Tick, Hide, Show

  object Leaf extends LiveComponent[Unit, Leaf.Msg.type, Int]:
    case object Msg
    def mount(props: Unit, ctx: MountContext)                       = ZIO.succeed(0)
    def handleMessage(props: Unit, model: Int, ctx: MessageContext) = _ => ZIO.succeed(model + 1)
    override def view(props: Signal[Unit], count: Signal[Int], self: ComponentRef[Msg.type]) =
      div(
        idAttr := "leaf",
        "count: ",
        span(idAttr   := "leaf-count", count.map(_.toString)),
        button(idAttr := "bump", phx.target(self), on.click(Msg), "bump")
      )

  object Branch extends LiveComponent.Eventless[Int, Unit]:
    def mount(props: Int, ctx: MountContext) = ZIO.unit
    override def view(props: Signal[Int], model: Signal[Unit], self: ComponentRef[Nothing]) =
      div(
        idAttr := "branch",
        props.map(value => s"tick: $value"),
        liveComponent(Leaf, id = "leaf", props = ())
      )

class Issue4359LiveView extends LiveView.Routed[Issue4359LiveView.Msg, Unit, Option[String]]:
  import Issue4359LiveView.*

  def mount(done: Option[String], ctx: MountContext) =
    ctx.connection match
      case Connection.Connected(capabilities) if !done.contains("1") =>
        capabilities.async.start(Navigate)(ZIO.sleep(2.seconds))(_ => Msg.Navigate).as(())
      case _ => ZIO.unit

  def handleMessage(model: Unit, ctx: MessageContext) =
    case Msg.Navigate => ctx.nav.pushNavigateUnsafe("/issues/4359?done=1")

  override def view(model: Signal[Unit]) =
    div(liveView("child", Issue4359LiveView.ChildLiveView()))

object Issue4359LiveView:
  private val Navigate = AsyncKey[Unit]("navigate")
  enum Msg:
    case Navigate

  class ChildLiveView extends LiveView[Unit, Unit]:
    def mount(ctx: MountContext)                        = ZIO.unit
    def handleMessage(model: Unit, ctx: MessageContext) = _ => ZIO.unit
    override def view(model: Signal[Unit])              = div("child")

class Issue4368LiveView extends LiveView[Issue4368LiveView.Msg, Issue4368LiveView.Model]:
  import Issue4368LiveView.*

  def mount(ctx: MountContext) = ctx.uploads.allow(Upload).map(Model(_))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate => refresh(model, ctx.uploads)
    case Msg.Progress =>
      refresh(model, ctx.uploads).flatMap { current =>
        current.upload.entries.find(_.status == LiveUploadEntryStatus.Completed) match
          case Some(entry) =>
            ctx.uploads
              .consume(entry)(completed =>
                ZIO.succeed(ConsumeDecision.Consume(completed.client.fileName))
              ).map { case (name, upload) =>
                current.copy(upload = upload, consumed = name +: current.consumed)
              }
          case None => ZIO.succeed(current)
      }
    case Msg.Cancel(entry) => ctx.uploads.cancel(entry).map(upload => model.copy(upload = upload))
    case Msg.Submit        =>
      ctx.uploads.get(Upload).map {
        case Some(upload) if upload.entries.nonEmpty => model.copy(upload = upload)
        case latest => model.copy(upload = latest.getOrElse(model.upload), submitted = true)
      }

  override def view(model: Signal[Model]) =
    val upload = model.map(_.upload)
    div(
      idAttr := "issue-4368",
      form(
        idAttr := "upload-form",
        on.change(_ => Msg.Validate),
        on.submit(Msg.Submit),
        liveFileInput(upload, upload.onProgress(_ => Msg.Progress)),
        button(typ := "submit", "Submit")
      ),
      p(idAttr := "submitted", model.map(current => s"submitted: ${current.submitted}")),
      p(idAttr := "consumed", model.map(current => s"consumed: ${current.consumed.mkString(",")}")),
      upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
        articleTag(
          cls           := "upload-entry",
          issueDataName := entry.map(_.client.fileName),
          span(entry.map(current => s"${current.client.fileName}: ${current.progress}%")),
          button(typ := "button", on.click(entry.map(Msg.Cancel.apply)), "Cancel"),
          uploadErrors(entry).splitBy(_.toString)((_, error) =>
            p(cls := "upload-error", error.map(issueUploadError))
          )
        )
      }
    )

  private def refresh(model: Model, uploads: Uploads) =
    uploads.get(Upload).map(_.fold(model)(upload => model.copy(upload = upload)))
end Issue4368LiveView

object Issue4368LiveView:
  enum Msg:
    case Validate, Progress, Submit
    case Cancel(entry: LiveUploadEntry[String])

  final case class Model(
    upload: LiveUpload[String],
    consumed: Vector[String] = Vector.empty,
    submitted: Boolean = false)

  private object Writer extends LiveUploadWriter[String, String]:
    def init(client: UploadClientMetadata)           = ZIO.succeed(client.fileName)
    def writeChunk(data: Chunk[Byte], state: String) =
      val chunk = String(data.toArray, StandardCharsets.UTF_8)
      if chunk == "error" then ZIO.fail(LiveUploadWriterError("invalid_pdf"))
      else if chunk == "delay" then ZIO.sleep(500.millis).as(state)
      else ZIO.succeed(state)
    def complete(state: String)                             = ZIO.succeed(state)
    def abort(state: String, reason: LiveUploadAbortReason) = ZIO.unit
    def discard(result: String)                             = ZIO.unit

  private val Upload = LiveUploadDef.hosted(
    "documents",
    LiveUploadAccept.Any,
    Writer,
    maxEntries = 2,
    chunkSize = 5,
    autoUpload = true
  )
