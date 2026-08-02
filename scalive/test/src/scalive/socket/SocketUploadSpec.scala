package scalive.socket

import scalive.*

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Payload.given
import scalive.WebSocketMessage.ReplyStatus

object SocketUploadSpec extends ZIOSpecDefault:

  private val uploadName = "avatar"
  private val meta       = WebSocketMessage.Meta(None, None, topic = "lv:test", eventType = "event")

  private def makeSocket[R](
    definition: LiveUploadDef[R],
    allowedUploadPromise: Promise[Throwable, LiveUpload[R]],
    snapshots: Queue[Option[LiveUpload[R]]]
  ) =
    Socket.start(
      "id",
      "token",
      makeLiveView(definition, allowedUploadPromise, snapshots),
      LiveContext(staticChanged = false),
      meta
    )

  private def makeLiveView[R](
    definition: LiveUploadDef[R],
    allowedUploadPromise: Promise[Throwable, LiveUpload[R]],
    snapshots: Queue[Option[LiveUpload[R]]]
  ) =
    new LiveView[Unit, Unit]:
      def mount(ctx: MountContext) =
        for
          upload <- ctx.uploads.allow(definition)
          _      <- allowedUploadPromise.succeed(upload).ignore
        yield ()

      def handleMessage(model: Unit, ctx: MessageContext) =
        (_: Unit) => ZIO.succeed(model)

      def render(model: Unit): HtmlElement[Unit] =
        div("upload")

      override def hooks: LiveHooks[Unit, Unit] =
        LiveHooks.empty.rawEvent("uploads") { (model, event, ctx) =>
          event.bindingId match
          case "capture" =>
            ctx.uploads.get(definition)
              .flatMap(upload => snapshots.offer(upload))
              .as(LiveEventHookResult.halt(model))
          case "cancel"  =>
            entryRefFromHookValue(event.value) match
              case Some(entryRef) =>
                ctx.uploads.get(definition).flatMap {
                  case Some(upload) =>
                    upload.entries.find(_.ref.value == entryRef) match
                      case Some(entry) => ctx.uploads.cancel(entry).flatMap(next => snapshots.offer(Some(next)))
                      case None        => snapshots.offer(Some(upload))
                  case None => snapshots.offer(None)
                }.as(LiveEventHookResult.halt(model))
              case None           =>
                ctx.uploads.get(definition)
                  .flatMap(upload => snapshots.offer(upload))
                  .as(LiveEventHookResult.halt(model))
          case _         =>
            ZIO.succeed(LiveEventHookResult.cont(model))
        }

  private def entryRefFromHookValue(value: Json): Option[String] =
    value match
      case obj: Json.Obj =>
        obj.fields.collectFirst {
          case ("entry_ref", Json.Str(ref)) => ref
        }
      case _             => None

  private def preflightEntry(ref: String, name: String, size: Long = 10L) =
    WebSocketMessage.UploadPreflightEntry(
      ref = ref,
      name = name,
      relative_path = None,
      size = size,
      `type` = "text/plain",
      last_modified = None,
      meta = None
    )

  private def captureEvent(uploads: Option[Json]): Payload.Event =
    Payload.Event(
      `type` = "click",
      event = "capture",
      value = Json.Obj.empty,
      uploads = uploads,
      cid = None,
      meta = None
    )

  private def cancelEvent(entryRef: String): Payload.Event =
    Payload.Event(
      `type` = "click",
      event = "cancel",
      value = Json.Obj("entry_ref" -> Json.Str(entryRef)),
      uploads = None,
      cid = None,
      meta = None
    )

  private def waitForAllowedUpload[R](promise: Promise[Throwable, LiveUpload[R]]): Task[LiveUpload[R]] =
    zio.test.Live.live(
      promise.await.timeoutFail(new RuntimeException("Timed out waiting for allowed upload"))(5.seconds)
    )

  private def waitForSnapshot[R](snapshots: Queue[Option[LiveUpload[R]]]): Task[LiveUpload[R]] =
    zio.test.Live.live(
      snapshots.take
        .timeoutFail(new RuntimeException("Timed out waiting for upload snapshot"))(5.seconds)
    )
      .flatMap(upload => ZIO.fromOption(upload).orElseFail(new RuntimeException("Upload not found")))

  private def uploadsJson(uploadRef: String, entries: List[WebSocketMessage.UploadPreflightEntry]): Task[Json] =
    ZIO.fromEither(
      Map(uploadRef -> entries).toJsonAST.left.map(error => new RuntimeException(error))
    )

  override def spec = suite("SocketUploadSpec")(
    test("syncs uploads from event payload before hook execution") {
      val definition = LiveUploadDef.inMemory(uploadName, LiveUploadAccept.Any, maxEntries = 1)
      for
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Chunk[Byte]]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Chunk[Byte]]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        entries = List(
                    preflightEntry("entry-1", name = "a.txt"),
                    preflightEntry("entry-2", name = "b.txt")
                  )
        encodedUploads <- uploadsJson(upload.ref.value, entries)
        _              <- socket.inbox.offer(captureEvent(Some(encodedUploads)) -> meta)
        synced         <- waitForSnapshot(snapshots)
      yield
        val syncedShapeOk =
          synced.entries match
            case first :: second :: Nil =>
              first.ref.value == "entry-1" &&
              first.status == LiveUploadEntryStatus.Selected &&
              first.progress == 0 &&
              second.ref.value == "entry-2" &&
              second.status == LiveUploadEntryStatus.Invalid(List(LiveUploadError.TooManyFiles))
            case _                      =>
              false

        assertTrue(
          synced.ref == upload.ref,
          syncedShapeOk,
          synced.errors.contains(LiveUploadError.TooManyFiles)
        )
    },
    test("cancelled refs are ignored by later preflight") {
      val definition = LiveUploadDef.inMemory(uploadName, LiveUploadAccept.Any, maxEntries = 1)
      for
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Chunk[Byte]]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Chunk[Byte]]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        entries = List(
                    preflightEntry("entry-1", name = "a.txt"),
                    preflightEntry("entry-2", name = "b.txt")
                  )
        firstReply <- socket.allowUpload(Payload.AllowUpload(upload.ref.value, entries, None))
        _          <- socket.inbox.offer(cancelEvent("entry-2") -> meta)
        afterCancel <- waitForSnapshot(snapshots)
        secondReply <- socket.allowUpload(Payload.AllowUpload(upload.ref.value, entries, None))
        _           <- socket.inbox.offer(captureEvent(None) -> meta)
        afterSecond <- waitForSnapshot(snapshots)
      yield
        val firstReplyHasEntryError =
          firstReply match
            case Payload.Reply(
                  ReplyStatus.Ok,
                  LiveResponse.UploadPreflightSuccess(_, _, _, errors)
                ) => errors.contains("entry-2")
            case _ => false

        val secondReplyFilteredCancelled =
          secondReply match
            case Payload.Reply(
                  ReplyStatus.Ok,
                  LiveResponse.UploadPreflightSuccess(_, _, responseEntries, responseErrors)
                ) => responseEntries.keySet == Set("entry-1") && responseErrors.isEmpty
            case _ => false

        assertTrue(
          firstReplyHasEntryError,
          afterCancel.entries.map(_.ref.value) == List("entry-1"),
          afterCancel.errors.isEmpty,
          secondReplyFilteredCancelled,
          afterSecond.entries.map(_.ref.value) == List("entry-1"),
          afterSecond.errors.isEmpty
        )
    },
    test("queued upload preflights append new entries") {
      val definition = LiveUploadDef.inMemory(uploadName, LiveUploadAccept.Any, maxEntries = 5)
      for
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Chunk[Byte]]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Chunk[Byte]]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        firstReply           <- socket.allowUpload(
                                  Payload.AllowUpload(
                                     upload.ref.value,
                                    List(preflightEntry("entry-1", name = "a.txt")),
                                    None
                                  )
                                )
        secondReply          <- socket.allowUpload(
                                  Payload.AllowUpload(
                                     upload.ref.value,
                                    List(preflightEntry("entry-2", name = "b.txt")),
                                    None
                                  )
                                )
        _                    <- socket.inbox.offer(captureEvent(None) -> meta)
        afterSecond          <- waitForSnapshot(snapshots)
      yield
        val firstAccepted = firstReply match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.UploadPreflightSuccess(_, _, entries, _)) =>
            entries.keySet == Set("entry-1")
          case _ => false

        val secondAccepted = secondReply match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.UploadPreflightSuccess(_, _, entries, _)) =>
            entries.keySet == Set("entry-2")
          case _ => false

        assertTrue(
          firstAccepted,
          secondAccepted,
          afterSecond.entries.map(_.ref.value) == List("entry-1", "entry-2"),
          afterSecond.errors.isEmpty
        )
    },
    test("sync preserves existing entries omitted by later queued payloads") {
      val definition = LiveUploadDef.inMemory(uploadName, LiveUploadAccept.Any, maxEntries = 5)
      for
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Chunk[Byte]]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Chunk[Byte]]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        _                    <- socket.allowUpload(
                                  Payload.AllowUpload(
                                     upload.ref.value,
                                    List(preflightEntry("entry-1", name = "a.txt")),
                                    None
                                  )
                                )
        encodedUploads       <- uploadsJson(
                                   upload.ref.value,
                                  List(preflightEntry("entry-2", name = "b.txt"))
                                )
        _                    <- socket.inbox.offer(captureEvent(Some(encodedUploads)) -> meta)
        afterSync            <- waitForSnapshot(snapshots)
      yield
        val firstPreserved = afterSync.entries.headOption.exists(entry =>
          entry.ref.value == "entry-1" && entry.status == LiveUploadEntryStatus.Preflighted
        )

        assertTrue(
          firstPreserved,
          afterSync.entries.map(_.ref.value) == List("entry-1", "entry-2"),
          afterSync.errors.isEmpty
        )
    },
    test("sync preserves the lifecycle of a preflighted entry included in an event") {
      val definition = LiveUploadDef.inMemory(uploadName, LiveUploadAccept.Any, maxEntries = 1)
      for
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Chunk[Byte]]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Chunk[Byte]]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        entry                 = preflightEntry("entry-1", name = "a.txt", size = 10L)
        preflightReply       <- socket.allowUpload(
                                  Payload.AllowUpload(upload.ref.value, List(entry), None)
                                )
        token                <- ZIO.fromOption(preflightReply match
                                  case Payload.Reply(
                                        ReplyStatus.Ok,
                                        LiveResponse.UploadPreflightSuccess(_, _, entries, _)
                                      ) => entries.get("entry-1").flatMap(_.asString)
                                  case _ => None
                                ).orElseFail(new RuntimeException("Missing upload token"))
        _              <- socket.uploadJoin("lvu:entry-1", token)
        _              <- socket.uploadChunk("lvu:entry-1", Chunk.fill(10)(1.toByte))
        encodedUploads <- uploadsJson(upload.ref.value, List(entry))
        _              <- socket.inbox.offer(captureEvent(Some(encodedUploads)) -> meta)
        afterSync      <- waitForSnapshot(snapshots)
      yield assertTrue(
        afterSync.entries.map(_.ref.value) == List("entry-1"),
        afterSync.entries.headOption.exists(_.status == LiveUploadEntryStatus.Completed)
      )
    },
    test("upload chunks update entry progress") {
      val definition = LiveUploadDef.inMemory(uploadName, LiveUploadAccept.Any, maxEntries = 5)
      for
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Chunk[Byte]]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Chunk[Byte]]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        preflightReply       <- socket.allowUpload(
                                  Payload.AllowUpload(
                                     upload.ref.value,
                                    List(preflightEntry("entry-1", name = "a.txt", size = 10L)),
                                    None
                                  )
                                )
        token                <- ZIO.fromOption(preflightReply match
                                  case Payload.Reply(
                                        ReplyStatus.Ok,
                                        LiveResponse.UploadPreflightSuccess(_, _, entries, _)
                                      ) =>
                                    entries.get("entry-1").flatMap(_.asString)
                                  case _ => None
                                ).orElseFail(new RuntimeException("Missing upload token"))
        _                    <- socket.uploadJoin("lvu:entry-1", token)
        _                    <- socket.uploadChunk("lvu:entry-1", Chunk.fill(10)(1.toByte))
        _                    <- socket.inbox.offer(captureEvent(None) -> meta)
        afterChunk           <- waitForSnapshot(snapshots)
      yield
        val uploaded = afterChunk.entries.headOption

        assertTrue(
          uploaded.exists(_.ref.value == "entry-1"),
          uploaded.exists(_.progress == 100),
          uploaded.exists(_.status == LiveUploadEntryStatus.Completed)
        )
    },
    test("cancel aborts writer state and returns the updated snapshot") {
      for
        aborts <- Queue.unbounded[LiveUploadAbortReason]
        writer = new LiveUploadWriter[Int, Int]:
                   def init(client: UploadClientMetadata) = ZIO.succeed(0)
                   def writeChunk(data: Chunk[Byte], state: Int) = ZIO.succeed(state + data.length)
                   def complete(state: Int) = ZIO.succeed(state)
                   def abort(state: Int, reason: LiveUploadAbortReason) = aborts.offer(reason).unit
                   def discard(result: Int) = ZIO.unit
        definition = LiveUploadDef.hosted(uploadName, LiveUploadAccept.Any, writer)
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Int]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Int]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        preflightReply       <- socket.allowUpload(
                                  Payload.AllowUpload(
                                    upload.ref.value,
                                    List(preflightEntry("entry-1", "a.txt")),
                                    None
                                  )
                                )
        token <- ZIO.fromOption(preflightReply match
                   case Payload.Reply(
                         ReplyStatus.Ok,
                         LiveResponse.UploadPreflightSuccess(_, _, entries, _)
                       ) => entries.get("entry-1").flatMap(_.asString)
                   case _ => None
                 ).orElseFail(new RuntimeException("Missing upload token"))
        _         <- socket.uploadJoin("lvu:entry-1", token)
        _         <- socket.inbox.offer(cancelEvent("entry-1") -> meta)
        cancelled <- waitForSnapshot(snapshots)
        reason    <- zio.test.Live.live(
                       aborts.take.timeoutFail(new RuntimeException("Missing writer abort"))(5.seconds)
                     )
      yield assertTrue(cancelled.entries.isEmpty, reason == LiveUploadAbortReason.Cancelled)
    },
    test("socket shutdown discards completed results and aborts in-progress writer state") {
      for
        discards <- Queue.unbounded[Int]
        aborts   <- Queue.unbounded[(Int, LiveUploadAbortReason)]
        writer = new LiveUploadWriter[Int, Int]:
                   def init(client: UploadClientMetadata) = ZIO.succeed(0)
                   def writeChunk(data: Chunk[Byte], state: Int) = ZIO.succeed(state + data.length)
                   def complete(state: Int) = ZIO.succeed(state)
                   def abort(state: Int, reason: LiveUploadAbortReason) = aborts.offer(state -> reason).unit
                   def discard(result: Int) = discards.offer(result).unit
        definition = LiveUploadDef.hosted(
                       uploadName,
                       LiveUploadAccept.Any,
                       writer,
                       maxEntries = 2
                     )
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Int]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Int]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        preflightReply       <- socket.allowUpload(
                                  Payload.AllowUpload(
                                    upload.ref.value,
                                    List(
                                      preflightEntry("complete", "complete.txt", 1),
                                      preflightEntry("active", "active.txt", 1)
                                    ),
                                    None
                                  )
                                )
        tokens <- ZIO.fromOption(preflightReply match
                    case Payload.Reply(
                          ReplyStatus.Ok,
                          LiveResponse.UploadPreflightSuccess(_, _, entries, _)
                        ) =>
                      for
                        complete <- entries.get("complete").flatMap(_.asString)
                        active   <- entries.get("active").flatMap(_.asString)
                      yield complete -> active
                    case _ => None
                  ).orElseFail(new RuntimeException("Missing upload tokens"))
        _         <- socket.uploadJoin("lvu:complete", tokens._1)
        _         <- socket.uploadChunk("lvu:complete", Chunk.single(1.toByte))
        _         <- socket.uploadJoin("lvu:active", tokens._2)
        _         <- socket.shutdown
        discarded <- discards.take
        aborted   <- aborts.take
      yield assertTrue(
        discarded == 1,
        aborted == (0 -> LiveUploadAbortReason.SocketShutdown)
      )
    },
    test("component removal aborts writer state owned by the component") {
      for
        aborts <- Queue.unbounded[LiveUploadAbortReason]
        writer = new LiveUploadWriter[Int, Int]:
                   def init(client: UploadClientMetadata) = ZIO.succeed(0)
                   def writeChunk(data: Chunk[Byte], state: Int) = ZIO.succeed(state + data.length)
                   def complete(state: Int) = ZIO.succeed(state)
                   def abort(state: Int, reason: LiveUploadAbortReason) = aborts.offer(reason).unit
                   def discard(result: Int) = ZIO.unit
        definition = LiveUploadDef.hosted(uploadName, LiveUploadAccept.Any, writer)
        ref        <- Ref.make(UploadRuntimeState.empty)
        runtime     = SocketUploadRuntime.scoped(new SocketUploadRuntime(ref), "component:7:")
        upload     <- runtime.allow(definition)
        state      <- ref.get
        config      = state.configs("component:7:avatar")
        entry       = SocketUploadEntries
                        .buildUploadEntryState(
                          config,
                          config.ref,
                          preflightEntry("entry-1", "a.txt", 1),
                          preflighted = true,
                          valid = true,
                          errors = Nil
                        )
                        .copy(destinationState = Some(Int.box(3)))
        _ <- ref.set(
               state.copy(
                 configs = state.configs.updated(
                   config.name,
                   config.copy(entryOrder = Vector(entry.ref))
                 ),
                 entries = Map(entry.ref -> entry)
               )
             )
        _      <- SocketUploadRuntime.removeComponentScopes(ref, Set(7))
        reason <- aborts.take
        after  <- ref.get
      yield assertTrue(
        upload.name == uploadName,
        reason == LiveUploadAbortReason.ComponentRemoved,
        after == UploadRuntimeState.empty
      )
    },
    test("consume decisions preserve postponed and failed callbacks and remove consumed entries") {
      val definition = LiveUploadDef.hosted(
        uploadName,
        LiveUploadAccept.Any,
        new LiveUploadWriter[Unit, Int]:
          def init(client: UploadClientMetadata) = ZIO.unit
          def writeChunk(data: Chunk[Byte], state: Unit) = ZIO.unit
          def complete(state: Unit) = ZIO.succeed(7)
          def abort(state: Unit, reason: LiveUploadAbortReason) = ZIO.unit
          def discard(result: Int) = ZIO.unit
      )
      for
        ref     <- Ref.make(UploadRuntimeState.empty)
        runtime = new SocketUploadRuntime(ref)
        _       <- runtime.allow(definition)
        state   <- ref.get
        config   = state.configs(uploadName)
        stored   = SocketUploadEntries
                     .buildUploadEntryState(
                       config,
                       config.ref,
                       preflightEntry("entry-1", "a.txt", 1),
                       preflighted = true,
                       valid = true,
                       errors = Nil
                     )
                     .copy(
                       progress = 100,
                       bytesReceived = 1,
                       completedResult = Some(Int.box(7))
                     )
        readyState = state.copy(
                       configs = state.configs.updated(
                         uploadName,
                         config.copy(entryOrder = Vector(stored.ref))
                       ),
                       entries = Map(stored.ref -> stored)
                     )
        _        <- ref.set(readyState)
        snapshot <- runtime.get(definition).some
        entry     = snapshot.entries.head
        postponed <- runtime.consume(entry)(upload =>
                       ZIO.succeed(ConsumeDecision.Postpone(upload.result.toString))
                     )
        afterPostpone <- runtime.get(definition).some
        failed <- runtime
                    .consume(entry)(_ => ZIO.fail(new RuntimeException("callback failed")))
                    .either
        afterFailure <- runtime.get(definition).some
        consumed <- runtime.consume(entry)(upload =>
                      ZIO.succeed(ConsumeDecision.Consume(upload.result.toString))
                    )
        afterConsumeState <- ref.get
        activeConfig       = afterConsumeState.configs(uploadName)
        inProgress         = stored.copy(
                               progress = 0,
                               bytesReceived = 0,
                               completedResult = None
                             )
        _ <- ref.set(
               afterConsumeState.copy(
                 configs = afterConsumeState.configs.updated(
                   uploadName,
                   activeConfig.copy(entryOrder = Vector(inProgress.ref))
                 ),
                 entries = Map(inProgress.ref -> inProgress)
               )
             )
        batch <- runtime
                   .consumeCompleted(definition)(_ =>
                     ZIO.succeed(ConsumeDecision.Consume("unexpected"))
                    )
                    .either
        batchRejectedInProgress = batch match
                                    case Left(_: LiveUploadOperationError.EntriesInProgress) => true
                                    case _                                                   => false
      yield assertTrue(
        postponed._1 == "7",
        postponed._2.entries.size == 1,
        afterPostpone.entries.size == 1,
        failed.isLeft,
        afterFailure.entries.size == 1,
        consumed._1 == "7",
        consumed._2.entries.isEmpty,
        batchRejectedInProgress
      )
    },
    test("disconnected nested mounts have a request-local upload runtime") {
      val definition = LiveUploadDef.inMemory(uploadName, LiveUploadAccept.Any)
      val nested = new DisconnectedNestedLiveViewRuntime(
        "lv:parent",
        "parent",
        TokenConfig.default,
        zio.http.URL.root
      )
      val spec = NestedLiveViewSpec[Unit, LiveUpload[Chunk[Byte]]](
        "child",
        () => new LiveView[Unit, LiveUpload[Chunk[Byte]]]:
          def mount(ctx: MountContext) = ctx.uploads.allow(definition)
          def handleMessage(model: LiveUpload[Chunk[Byte]], ctx: MessageContext) = _ => ZIO.succeed(model)
          def render(model: LiveUpload[Chunk[Byte]]) = div(model.name),
        scala.reflect.classTag[Unit],
        sticky = false,
        linkParentOnCrash = false
      )
      nested.register(spec).map(registration => assertTrue(registration.rendered.nonEmpty))
    },
    test("typed writer and progress callbacks receive client metadata and snapshots") {
      for
        clients      <- Queue.unbounded[UploadClientMetadata]
        progressRefs <- Queue.unbounded[String]
        writer = new LiveUploadWriter[Int, Int]:
                   def init(client: UploadClientMetadata) = clients.offer(client).as(0)
                   def writeChunk(data: Chunk[Byte], state: Int) = ZIO.succeed(state + data.length)
                   def complete(state: Int) = ZIO.succeed(state)
                   def abort(state: Int, reason: LiveUploadAbortReason) = ZIO.unit
                   def discard(result: Int) = ZIO.unit
                   override def metadata(result: Int) = Json.Obj("bytes" -> Json.Num(result))
        progress = new LiveUploadProgress[Int]:
                     def onProgress(entry: LiveUploadEntry[Int]) =
                       progressRefs.offer(entry.ref.value).unit
        definition = LiveUploadDef.hosted(
                       uploadName,
                       LiveUploadAccept.Any,
                       writer,
                       progress = Some(progress)
                     )
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload[Int]]
        snapshots            <- Queue.unbounded[Option[LiveUpload[Int]]]
        socket               <- makeSocket(definition, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        preflightReply       <- socket.allowUpload(
                                  Payload.AllowUpload(
                                    upload.ref.value,
                                    List(preflightEntry("entry-1", name = "a.txt", size = 10L)),
                                    None
                                  )
                                )
        token                <- ZIO.fromOption(preflightReply match
                                  case Payload.Reply(
                                        ReplyStatus.Ok,
                                        LiveResponse.UploadPreflightSuccess(_, _, entries, _)
                                      ) =>
                                    entries.get("entry-1").flatMap(_.asString)
                                  case _ => None
                                ).orElseFail(new RuntimeException("Missing upload token"))
        _           <- socket.uploadJoin("lvu:entry-1", token)
        _           <- socket.uploadChunk("lvu:entry-1", Chunk.fill(10)(1.toByte))
        _           <- socket.progressUpload(
                         Payload.Progress(
                           event = None,
                           ref = upload.ref.value,
                           entry_ref = "entry-1",
                           progress = Json.Num(100),
                           cid = None
                         )
                       )
        client      <- zio.test.Live.live(clients.take.timeoutFail(new RuntimeException("Missing client metadata"))(5.seconds))
        progressRef <- zio.test.Live.live(progressRefs.take.timeoutFail(new RuntimeException("Missing progress callback"))(5.seconds))
      yield assertTrue(
        client.fileName == "a.txt",
        client.sizeBytes == 10L,
        progressRef == "entry-1"
      )
    }
  )
