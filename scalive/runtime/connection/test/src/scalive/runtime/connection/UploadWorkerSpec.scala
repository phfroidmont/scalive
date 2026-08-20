package scalive.runtime.connection

import zio.*
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*
import scalive.upload.*

object UploadWorkerSpec extends ZIOSpecDefault:
  private val metadata = RootConnectionMetadata(staticChanged = false, connectParams = Map.empty)

  private def config(queueCapacity: Int = 1, maxChunkBytes: Int = 4): ConnectionConfig =
    ConnectionConfig.make(4, 4, 4, 4, 4, queueCapacity, maxChunkBytes).toOption.get

  private enum Message:
    case Add(amount: Int)
    case Cancel
    case Consume
    case Disallow
    case Refresh

  private final case class Model(
    upload: Option[LiveUpload[Chunk[Byte]]],
    consumed: Option[Chunk[Byte]],
    count: Int)

  private final case class WriterState(name: String, bytes: Chunk[Byte])

  private final class RecordingWriter(
    initialized: Ref[Int],
    writes: Ref[Vector[(String, Chunk[Byte])]],
    activeWrites: Ref[Int],
    maximumWrites: Ref[Int],
    aborts: Ref[Vector[(String, LiveUploadAbortReason)]],
    discards: Ref[Int],
    blockedNames: Set[String],
    defectiveNames: Set[String],
    entered: Queue[String],
    release: Promise[Nothing, Unit])
      extends LiveUploadWriter[WriterState, Chunk[Byte]]:
    def init(client: UploadClientMetadata): Task[WriterState] =
      initialized.update(_ + 1).as(WriterState(client.fileName, Chunk.empty))

    def writeChunk(data: Chunk[Byte], state: WriterState): Task[WriterState] =
      ZIO.acquireReleaseWith(
        activeWrites.updateAndGet(_ + 1).tap(current => maximumWrites.update(_.max(current)))
      )(_ => activeWrites.update(_ - 1)) { _ =>
        writes.update(_ :+ (state.name -> data)) *>
          ZIO.when(blockedNames.contains(state.name))(entered.offer(state.name) *> release.await) *>
          ZIO.when(defectiveNames.contains(state.name))(ZIO.dieMessage("write defect")) *>
          ZIO.succeed(state.copy(bytes = state.bytes ++ data))
      }

    def complete(state: WriterState): Task[Chunk[Byte]] = ZIO.succeed(state.bytes)

    def abort(state: WriterState, reason: LiveUploadAbortReason): Task[Unit] =
      aborts.update(_ :+ (state.name -> reason))

    def discard(result: Chunk[Byte]): Task[Unit] = discards.update(_ + 1)

    override def metadata(result: Chunk[Byte]): Json.Obj =
      Json.Obj("bytes" -> Json.Num(BigDecimal(result.length)))

  private final case class WriterFixture(
    writer: RecordingWriter,
    initialized: Ref[Int],
    writes: Ref[Vector[(String, Chunk[Byte])]],
    maximumWrites: Ref[Int],
    aborts: Ref[Vector[(String, LiveUploadAbortReason)]],
    discards: Ref[Int],
    entered: Queue[String],
    release: Promise[Nothing, Unit])

  private def writerFixture(
    blockedNames: Set[String] = Set.empty,
    defectiveNames: Set[String] = Set.empty
  ): UIO[WriterFixture] =
    for
      initialized <- Ref.make(0)
      writes      <- Ref.make(Vector.empty[(String, Chunk[Byte])])
      active      <- Ref.make(0)
      maximum     <- Ref.make(0)
      aborts      <- Ref.make(Vector.empty[(String, LiveUploadAbortReason)])
      discards    <- Ref.make(0)
      entered     <- Queue.unbounded[String]
      release     <- Promise.make[Nothing, Unit]
    yield WriterFixture(
      RecordingWriter(
        initialized,
        writes,
        active,
        maximum,
        aborts,
        discards,
        blockedNames,
        defectiveNames,
        entered,
        release
      ),
      initialized,
      writes,
      maximum,
      aborts,
      discards,
      entered,
      release
    )

  private def definition(fixture: WriterFixture, maxEntries: Int = 2) =
    LiveUploadDef.hosted(
      "files",
      LiveUploadAccept.Any,
      fixture.writer,
      maxEntries = maxEntries,
      maxFileSize = 64L,
      chunkSize = 4
    )

  private def client(name: String, size: Long): UploadClientMetadata =
    new UploadClientMetadata(name, None, size, "application/octet-stream", None, None)

  private def root(definition: LiveUploadDef[Chunk[Byte]]) = new LiveView[Message, Model]:
    def mount(ctx: MountContext): LiveIO[Model] =
      ctx.uploads.allow(definition).map(upload => Model(Some(upload), None, 0))

    def handleMessage(model: Model, ctx: MessageContext): Message => LiveIO[Model] =
      case Message.Add(amount) =>
        ctx.uploads.get(definition).map(upload => model.copy(upload = upload, count = model.count + amount))
      case Message.Refresh => ctx.uploads.get(definition).map(upload => model.copy(upload = upload))
      case Message.Cancel =>
        ctx.uploads.get(definition).someOrFail(Exception("upload missing")).flatMap { upload =>
          ctx.uploads.cancel(upload.entries.head).map(next => model.copy(upload = Some(next)))
        }
      case Message.Consume =>
        ctx.uploads.get(definition).someOrFail(Exception("upload missing")).flatMap { upload =>
          ctx.uploads
            .consume(upload.entries.head)(completed =>
              ZIO.succeed(ConsumeDecision.Postpone(completed.result))
            ).map { case (bytes, next) => model.copy(upload = Some(next), consumed = Some(bytes)) }
        }
      case Message.Disallow => ctx.uploads.disallow(definition).as(model.copy(upload = None))

    def view(model: Signal[Model]): HtmlElement[Message] = div(model.map(_.count.toString))

  private final case class Running(
    connection: RootConnection[Message, Model],
    outputs: Queue[ConnectionOutput],
    uploadRef: UploadRef)

  private def start(
    fixture: WriterFixture,
    selectedConfig: ConnectionConfig = config()
  ): ZIO[Scope, ConnectionError, Running] =
    val uploadDef = definition(fixture)
    for
      outputs    <- Queue.unbounded[ConnectionOutput]
      connection <- RootConnection.start(selectedConfig, metadata, root(uploadDef), outputs.offer(_).unit)
      _          <- outputs.take
      model      <- connection.inspectModel
    yield Running(connection, outputs, model.upload.get.ref)

  private def preflight(
    running: Running,
    entries: Vector[(UploadEntryRef, UploadClientMetadata)]
  ): IO[ConnectionError, UploadPreflightView] =
    for
      command  = CommandId.fresh().toOption.get
      result  <- running.connection.preflightUpload(command, None, running.uploadRef, entries)
      _       <- running.outputs.take
    yield result.toOption.get

  private def admit(
    running: Running,
    entry: UploadEntryRef,
    generation: Long = 1L,
    component: Option[ComponentInstanceId] = None
  ) = running.connection.admitUpload(component, running.uploadRef, entry, generation)

  private def admitted(running: Running, entry: UploadEntryRef) =
    admit(running, entry).flatMap(result =>
      ZIO.fromEither(result.left.map(error => Exception(s"admission failed: $error")))
    )

  override def spec = suite("UploadWorkerSpec")(
    test("hosted preflight is effect-free and admission is exact and one-shot") {
      ZIO.scoped {
        for
          fixture <- writerFixture()
          running <- start(fixture)
          entry    = UploadEntryRef("entry")
          _       <- preflight(running, Vector(entry -> client("a", 2L)))
          afterPreflight <- fixture.initialized.get
          stale          <- admit(running, entry, generation = 2L)
          foreign        <- admit(running, entry, component = Some(ComponentInstanceId(999L)))
          beforeExact    <- fixture.initialized.get
          exact          <- admit(running, entry)
          duplicate      <- admit(running, entry)
          afterAll       <- fixture.initialized.get
        yield assertTrue(
          afterPreflight == 0,
          stale == Left(UploadAdmissionError.Rejected(UploadRegistryError.StaleAuthority)),
          foreign == Left(UploadAdmissionError.Rejected(UploadRegistryError.StaleAuthority)),
          beforeExact == 0,
          exact.isRight,
          duplicate == Left(
            UploadAdmissionError.Rejected(UploadRegistryError.InvalidEntryState(entry))
          ),
          afterAll == 1
        )
      }
    },
    test("chunks serialize, complete at the declared size, and publish a consumable snapshot") {
      ZIO.scoped {
        for
          fixture <- writerFixture()
          running <- start(fixture)
          entry    = UploadEntryRef("entry")
          _       <- preflight(running, Vector(entry -> client("a", 4L)))
          worker  <- admitted(running, entry)
          first   <- running.connection.uploadChunk(worker, Chunk[Byte](1, 2))
          second  <- running.connection.uploadChunk(worker, Chunk[Byte](3, 4))
          closed  <- running.connection.uploadChunk(worker, Chunk.single(5.toByte)).either
          _       <- running.connection.submitInfo(Message.Consume)
          model   <- running.connection.inspectModel
          writes  <- fixture.writes.get
          maximum <- fixture.maximumWrites.get
        yield assertTrue(
          first == 50,
          second == 100,
          closed == Left(UploadChunkError.Closed),
          writes.map(_._2) == Vector(Chunk[Byte](1, 2), Chunk[Byte](3, 4)),
          maximum == 1,
          model.upload.get.entries.head.status == LiveUploadEntryStatus.Completed,
          model.consumed.contains(Chunk[Byte](1, 2, 3, 4))
        )
      }
    },
    test("a processing defect fails the in-flight chunk promptly") {
      ZIO.scoped {
        for
          fixture <- writerFixture(
                       blockedNames = Set("defective"),
                       defectiveNames = Set("defective")
                     )
          scope   <- ZIO.service[Scope]
          handle <- HostedUploadWorker.initialize(
                      HostedWorkerId(
                        OwnerId.Root(LifecycleId(1L)),
                        Epoch.initial,
                        UploadRef("upload"),
                        UploadEntryRef("entry"),
                        1L
                      ),
                      fixture.writer,
                      client("defective", 1L)
                    )
          worker <- UploadEntryWorker.start(
                      handle,
                      expectedBytes = 1L,
                      maxChunkBytes = 4,
                      capacity = 1,
                      callbacks = UploadWorkerCallbacks(
                        complete = _ => ZIO.unit,
                        fail = _ => ZIO.dieMessage("failure callback defect")
                      ),
                      scope = scope
                    )
          first <- worker.offer(Chunk.single(1.toByte)).either.fork
          _     <- fixture.entered.take
          queued <- worker.offer(Chunk.single(2.toByte)).either.fork
          _      <- queued.status.repeatUntil(_.isSuspended)
          _      <- fixture.release.succeed(())
          firstResult  <- first.join.timeout(5.seconds)
          queuedResult <- queued.join.timeout(5.seconds)
          future       <- worker.offer(Chunk.single(3.toByte)).either
        yield assertTrue(
          firstResult == Some(Left(UploadChunkError.WriterFailed("writer_error"))),
          queuedResult == Some(Left(UploadChunkError.WriterFailed("writer_error"))),
          future == Left(UploadChunkError.Closed)
        )
      }
    },
    test("queue overflow invalidates only its entry, aborts once, and leaves the root usable") {
      ZIO.scoped {
        for
          fixture <- writerFixture(Set("blocked"))
          running <- start(fixture, config(queueCapacity = 1))
          blocked  = UploadEntryRef("blocked-entry")
          other    = UploadEntryRef("other-entry")
          _ <- preflight(
                 running,
                 Vector(blocked -> client("blocked", 3L), other -> client("other", 1L))
               )
          blockedWorker <- admitted(running, blocked)
          otherWorker   <- admitted(running, other)
          first         <- running.connection.uploadChunk(blockedWorker, Chunk.single(1.toByte)).fork
          _             <- fixture.entered.take
          queued        <- running.connection.uploadChunk(blockedWorker, Chunk.single(2.toByte)).fork
          _             <- queued.status.repeatUntil(_.isSuspended)
          overflow      <- running.connection.uploadChunk(blockedWorker, Chunk.single(3.toByte)).either.fork
          _             <- running.connection.submitInfo(Message.Add(7))
          duringBlock   <- running.connection.inspectModel
          otherResult   <- running.connection.uploadChunk(otherWorker, Chunk.single(9.toByte))
          _             <- fixture.release.succeed(())
          overflowResult <- overflow.await
          _              <- first.await *> queued.interrupt
          _              <- running.connection.submitInfo(Message.Refresh)
          model          <- running.connection.inspectModel
          aborts         <- fixture.aborts.get
          statuses        = model.upload.get.entries.map(entry => entry.ref -> entry.status).toMap
        yield assertTrue(
          duringBlock.count == 7,
          otherResult == 100,
          overflowResult == Exit.succeed(Left(UploadChunkError.QueueOverflow(1))),
          aborts == Vector("blocked" -> LiveUploadAbortReason.Failed("queue_overflow")),
          statuses(blocked).isInstanceOf[LiveUploadEntryStatus.Invalid],
          statuses(other) == LiveUploadEntryStatus.Completed
        )
      }
    },
    test("hard chunk and declared-size limits fail explicitly") {
      ZIO.scoped {
        for
          fixture <- writerFixture()
          running <- start(fixture, config(maxChunkBytes = 2))
          large    = UploadEntryRef("large")
          excess   = UploadEntryRef("excess")
          _ <- preflight(
                 running,
                 Vector(large -> client("large", 4L), excess -> client("excess", 2L))
               )
          largeWorker  <- admitted(running, large)
          excessWorker <- admitted(running, excess)
          tooLarge <- running.connection.uploadChunk(largeWorker, Chunk[Byte](1, 2, 3)).either.exit
          _        <- running.connection.uploadChunk(excessWorker, Chunk.single(1.toByte))
          exceeded <- running.connection.uploadChunk(excessWorker, Chunk[Byte](2, 3)).either.exit
        yield assertTrue(
          tooLarge == Exit.succeed(Left(UploadChunkError.ChunkTooLarge(2, 3))),
          exceeded == Exit.succeed(Left(UploadChunkError.DeclaredSizeExceeded(2L, 3L)))
        )
      }
    },
    test("blocked upload traffic does not occupy the ordinary root command path") {
      ZIO.scoped {
        for
          fixture <- writerFixture(Set("blocked"))
          running <- start(fixture, config(queueCapacity = 2))
          entry    = UploadEntryRef("entry")
          _       <- preflight(running, Vector(entry -> client("blocked", 4L)))
          worker  <- admitted(running, entry)
          first   <- running.connection.uploadChunk(worker, Chunk.single(1.toByte)).fork
          _       <- fixture.entered.take
          queued <- ZIO.foreach(2 to 3)(byte =>
                      running.connection.uploadChunk(worker, Chunk.single(byte.toByte)).fork
                    )
          _       <- ZIO.foreachDiscard(queued)(_.status.repeatUntil(_.isSuspended))
          _       <- running.connection.submitInfo(Message.Add(11))
          model   <- running.connection.inspectModel
          _       <- fixture.release.succeed(())
          _       <- first.await *> ZIO.foreachDiscard(queued)(_.await)
        yield assertTrue(model.count == 11)
      }
    },
    test("cancel, disallow, and socket close retire active and completed resources once") {
      def activeRetirement(message: Message, expected: LiveUploadAbortReason) = ZIO.scoped {
        for
          fixture <- writerFixture()
          running <- start(fixture)
          entry    = UploadEntryRef("entry")
          _       <- preflight(running, Vector(entry -> client("active", 2L)))
          _       <- admit(running, entry)
          _       <- running.connection.submitInfo(message)
          aborts  <- fixture.aborts.get
        yield assertTrue(aborts == Vector("active" -> expected))
      }

      def completedRetirement(message: Message) = ZIO.scoped {
        for
          fixture <- writerFixture()
          running <- start(fixture)
          entry    = UploadEntryRef("entry")
          _       <- preflight(running, Vector(entry -> client("complete", 1L)))
          worker  <- admitted(running, entry)
          _       <- running.connection.uploadChunk(worker, Chunk.single(1.toByte))
          _       <- running.connection.submitInfo(message)
          discards <- fixture.discards.get
        yield assertTrue(discards == 1)
      }

      for
        cancelled <- activeRetirement(Message.Cancel, LiveUploadAbortReason.Cancelled)
        disallowed <- activeRetirement(Message.Disallow, LiveUploadAbortReason.Disallowed)
        cancelledComplete <- completedRetirement(Message.Cancel)
        disallowedComplete <- completedRetirement(Message.Disallow)
        socket <- ZIO.scoped {
                    for
                      fixture <- writerFixture()
                      running <- start(fixture)
                      active   = UploadEntryRef("active")
                      complete = UploadEntryRef("complete")
                      _ <- preflight(
                             running,
                             Vector(active -> client("active", 2L), complete -> client("done", 1L))
                           )
                      _          <- admit(running, active)
                      doneWorker <- admitted(running, complete)
                      _          <- running.connection.uploadChunk(doneWorker, Chunk.single(1.toByte))
                      _          <- running.connection.close *> running.connection.close
                      aborts     <- fixture.aborts.get
                      discards   <- fixture.discards.get
                    yield assertTrue(
                      aborts == Vector("active" -> LiveUploadAbortReason.SocketShutdown),
                      discards == 1
                    )
                  }
      yield cancelled && disallowed && cancelledComplete && disallowedComplete && socket
    },
    test("component removal retires its active worker and completed result exactly once") {
      ZIO.scoped {
        for
          fixture <- writerFixture()
          uploadDef = definition(fixture)
          componentDef = new LiveComponent.Eventless[Unit, LiveUpload[Chunk[Byte]]]:
                           def mount(props: Unit, ctx: MountContext) = ctx.uploads.allow(uploadDef)
                           def view(
                             props: Signal[Unit],
                             model: Signal[LiveUpload[Chunk[Byte]]],
                             self: ComponentRef[Nothing]
                           ) = span()
          instance = component(componentDef, "uploader")
          root = new LiveView[Boolean, Boolean]:
                   def mount(ctx: MountContext) = ZIO.succeed(true)
                   def handleMessage(model: Boolean, ctx: MessageContext): Boolean => LiveIO[Boolean] =
                     shown => ZIO.succeed(shown)
                   def view(model: Signal[Boolean]) = div(model.when(div(instance.render(()))))
          outputs    <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config(), metadata, root, outputs.offer(_).unit)
          _          <- outputs.take
          componentId <- connection.inspectComponentIds.map(_.head)
          componentUpload <- connection.inspectComponentModel[LiveUpload[Chunk[Byte]]](componentId).someOrFail(
                               Exception("component upload missing")
                             )
          activeEntry   = UploadEntryRef("active")
          completeEntry = UploadEntryRef("complete")
          command  = CommandId.fresh().toOption.get
          result <- connection.preflightUpload(
                      command,
                      Some(componentId),
                      componentUpload.ref,
                      Vector(
                        activeEntry -> client("component-active", 2L),
                        completeEntry -> client("component-complete", 1L)
                      )
                    )
          _      <- outputs.take
          _ <- ZIO.fromEither(result.left.map(error => Exception(s"preflight failed: $error")))
          activeJoined <- connection.admitUpload(
                            Some(componentId),
                            componentUpload.ref,
                            activeEntry,
                            1L
                          )
          _ <- ZIO.fromEither(
                 activeJoined.left.map(error => Exception(s"admission failed: $error"))
               )
          completeJoined <- connection.admitUpload(
                              Some(componentId),
                              componentUpload.ref,
                              completeEntry,
                              1L
                            )
          completeWorker <- ZIO.fromEither(
                              completeJoined.left.map(error => Exception(s"admission failed: $error"))
                            )
          _        <- connection.uploadChunk(completeWorker, Chunk.single(1.toByte))
          _        <- connection.submitInfo(false)
          aborts   <- fixture.aborts.get
          discards <- fixture.discards.get
        yield assertTrue(
          aborts == Vector("component-active" -> LiveUploadAbortReason.ComponentRemoved),
          discards == 1
        )
      }
    },
    test("entries have independent workers and queues") {
      ZIO.scoped {
        for
          fixture <- writerFixture(Set("blocked"))
          running <- start(fixture, config(queueCapacity = 1))
          first    = UploadEntryRef("first")
          second   = UploadEntryRef("second")
          _ <- preflight(
                 running,
                 Vector(first -> client("blocked", 2L), second -> client("free", 1L))
               )
          firstWorker  <- admitted(running, first)
          secondWorker <- admitted(running, second)
          blocked      <- running.connection.uploadChunk(firstWorker, Chunk.single(1.toByte)).fork
          _            <- fixture.entered.take
          secondResult <- running.connection.uploadChunk(secondWorker, Chunk.single(2.toByte))
          _            <- fixture.release.succeed(())
          _            <- blocked.join
          writes       <- fixture.writes.get
        yield assertTrue(
          secondResult == 100,
          writes.map(_._1).toSet == Set("blocked", "free")
        )
      }
    }
  )
end UploadWorkerSpec
