package scalive.runtime.resources

import scalive.runtime.contracts.ComponentInstanceId
import scalive.runtime.contracts.Epoch
import scalive.runtime.contracts.LifecycleId
import scalive.upload.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object UploadRegistrySpec extends ZIOSpecDefault:
  private val lifecycle = LifecycleId(1L)
  private val root      = OwnerId.Root(lifecycle)
  private val component = OwnerId.Component(lifecycle, ComponentInstanceId(1L))
  private val epoch     = Epoch.initial

  private def metadata(name: String, size: Long = 1L): UploadClientMetadata =
    new UploadClientMetadata(name, None, size, "application/octet-stream", None, None)

  private def ref(value: String): UploadRef           = UploadRef(value)
  private def entryRef(value: String): UploadEntryRef = UploadEntryRef(value)

  private final class RecordingWriter(
    initialized: Ref[Int],
    aborted: Ref[Vector[LiveUploadAbortReason]],
    discarded: Ref[Int],
    activeWrites: Ref[Int],
    maximumWrites: Ref[Int])
      extends LiveUploadWriter[Int, Int]:
    def init(client: UploadClientMetadata): Task[Int] = initialized.updateAndGet(_ + 1).as(0)
    def writeChunk(data: Chunk[Byte], state: Int): Task[Int] =
      ZIO.acquireReleaseWith(
        activeWrites.updateAndGet(_ + 1).tap(value => maximumWrites.update(_.max(value)))
      )(_ => activeWrites.update(_ - 1))(_ => ZIO.yieldNow.as(state + data.length))
    def complete(state: Int): Task[Int] = ZIO.succeed(state)
    def abort(state: Int, reason: LiveUploadAbortReason): Task[Unit] = aborted.update(_ :+ reason)
    def discard(result: Int): Task[Unit] = discarded.update(_ + 1)
    override def metadata(result: Int): Json.Obj = Json.Obj("bytes" -> Json.Num(result))

  private final case class WriterFixture(
    writer: RecordingWriter,
    initialized: Ref[Int],
    aborted: Ref[Vector[LiveUploadAbortReason]],
    discarded: Ref[Int],
    maximumWrites: Ref[Int])

  private def writerFixture: UIO[WriterFixture] = for
    initialized <- Ref.make(0)
    aborted     <- Ref.make(Vector.empty[LiveUploadAbortReason])
    discarded   <- Ref.make(0)
    active      <- Ref.make(0)
    maximum     <- Ref.make(0)
  yield WriterFixture(
    new RecordingWriter(initialized, aborted, discarded, active, maximum),
    initialized,
    aborted,
    discarded,
    maximum
  )

  private final class ExternalUploader(preflights: Ref[Int], discards: Ref[Int])
      extends LiveUploadExternalUploader[String]:
    def preflight(client: UploadClientMetadata): Task[LiveExternalUploadResult[String]] =
      preflights.update(_ + 1).as(
        LiveExternalUploadResult.Ready(
          ExternalUploadClientConfig(Json.Obj("uploader" -> Json.Str("test"))),
          client.fileName
        )
      )
    override def discard(result: String): Task[Unit] = discards.update(_ + 1)

  private def snapshot[Result](
    registry: UploadRegistry,
    owner: OwnerId,
    key: UploadKey[Result]
  ): LiveUpload[Result] = registry.get(owner, epoch, key).toOption.get._2

  override def spec = suite("UploadRegistrySpec")(
    test("hosted preflight registers entries without initializing writer state") {
      for
        fixture <- writerFixture
        key       = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        result    = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        count    <- fixture.initialized.get
      yield assertTrue(
        count == 0,
        result.externalPreparations.isEmpty,
        result.hostedRegistrations == Vector(UploadEntryToken(allowed._2, entryRef("e")))
      )
    },
    test("hosted join is exact and one-shot before initialization") {
      for
        fixture <- writerFixture
        key       = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        preflight = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        token     = preflight.hostedRegistrations.head.asInstanceOf[UploadEntryToken[Int]]
        stale     = token.copy(upload = token.upload.copy(generation = token.upload.generation + 1L))
        foreign   = token.copy(upload = token.upload.copy(owner = component))
        staleJoin = preflight.registry.claimHostedJoin(stale)
        foreignJoin = preflight.registry.claimHostedJoin(foreign)
        claim       = preflight.registry.claimHostedJoin(token).toOption.get
        repeated    = claim.registry.claimHostedJoin(token)
        before     <- fixture.initialized.get
        worker     <- claim.factory.initialize.run
        after      <- fixture.initialized.get
        installed   = claim.registry.installHostedWorker(token, worker)
      yield assertTrue(
        staleJoin == Left(UploadRegistryError.StaleAuthority),
        foreignJoin == Left(UploadRegistryError.StaleAuthority),
        repeated == Left(UploadRegistryError.InvalidEntryState(entryRef("e"))),
        before == 0,
        after == 1,
        installed.accepted
      )
    },
    test("empty definition replacement advances identity while active entries block it") {
      for
        fixture <- writerFixture
        firstKey = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        secondKey = UploadKey(LiveUploadDef.inMemory("files", LiveUploadAccept.Any))
        first      = UploadRegistry.empty.allow(root, epoch, firstKey, ref("one")).toOption.get
        replaced   = first._1.allow(root, epoch, secondKey, ref("two")).toOption.get
        staleGet   = replaced._1.get(root, epoch, firstKey)
        selected   = replaced._1.preflight(replaced._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        blocked    = selected.registry.allow(root, epoch, firstKey, ref("three"))
      yield assertTrue(
        replaced._2.generation == first._2.generation + 1L,
        replaced._2.ref == ref("two"),
        staleGet == Left(UploadRegistryError.DefinitionMismatch("files")),
        blocked == Left(UploadRegistryError.ActiveEntries("files"))
      )
    },
    test("lookup distinguishes missing, mismatched definition, and stale epoch") {
      for
        fixture <- writerFixture
        key       = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        other     = UploadKey(LiveUploadDef.inMemory("files", LiveUploadAccept.Any))
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
      yield assertTrue(
        allowed._1.get(component, epoch, key) == Left(UploadRegistryError.NotAllowed("files")),
        allowed._1.get(root, epoch, other) == Left(UploadRegistryError.DefinitionMismatch("files")),
        allowed._1.get(root, Epoch(2L), key) == Left(UploadRegistryError.StaleAuthority)
      )
    },
    test("selection synchronization does not preflight hosted entries") {
      for
        fixture <- writerFixture
        key       = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        selected = allowed._1
                     .synchronizeSelection(allowed._2, Vector(entryRef("e") -> metadata("a")))
                     .toOption.get
        selectedEntry = snapshot(selected.registry, root, key).entries.head
        preflight = selected.registry
                      .preflight(allowed._2, Vector(entryRef("e") -> metadata("a")))
                      .toOption.get
        preflightEntry = snapshot(preflight.registry, root, key).entries.head
      yield assertTrue(
        selectedEntry.status == LiveUploadEntryStatus.Selected,
        selected.hostedRegistrations.isEmpty,
        preflightEntry.status == LiveUploadEntryStatus.Preflighted,
        preflight.hostedRegistrations.size == 1
      )
    },
    test("preflight is idempotent, retains completed omissions, and rejects conflicting metadata") {
      for
        preflights <- Ref.make(0)
        discards   <- Ref.make(0)
        uploader    = new ExternalUploader(preflights, discards)
        key         = UploadKey(LiveUploadDef.external("files", LiveUploadAccept.Any, uploader, maxEntries = 2))
        allowed     = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        selected    = Vector(entryRef("a") -> metadata("a"))
        first       = allowed._1.preflight(allowed._2, selected).toOption.get
        repeated    = first.registry.preflight(allowed._2, selected).toOption.get
        prepared   <- first.externalPreparations.head.operation.run.map(_.toOption.get)
        installed   = repeated.registry.installExternal(prepared)
        completed   = installed.registry.progress(prepared.entry.asInstanceOf[UploadEntryToken[String]], 100).toOption.get
        appended = completed.preflight(
          allowed._2,
          Vector(entryRef("b") -> metadata("b"))
        ).toOption.get
        current  = snapshot(appended.registry, root, key)
        conflict = appended.registry.preflight(
          allowed._2,
          Vector(entryRef("b") -> metadata("changed"))
        )
      yield assertTrue(
        repeated.externalPreparations.isEmpty,
        repeated.hostedRegistrations.isEmpty,
        appended.externalPreparations.size == 1,
        appended.retirement.instructions.isEmpty,
        current.entries.map(_.ref) == List(entryRef("a"), entryRef("b")),
        current.entries.head.status == LiveUploadEntryStatus.Completed,
        conflict == Left(UploadRegistryError.MetadataMismatch(entryRef("b")))
      )
    },
    test("too-many-files entry recovers only after explicit cancellation frees capacity") {
      for
        preflights <- Ref.make(0)
        discards   <- Ref.make(0)
        uploader    = new ExternalUploader(preflights, discards)
        key         = UploadKey(LiveUploadDef.external("files", LiveUploadAccept.Any, uploader))
        allowed     = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        first = allowed._1.preflight(
          allowed._2,
          Vector(entryRef("a") -> metadata("a"), entryRef("b") -> metadata("b"))
        ).toOption.get
        firstSnapshot = snapshot(first.registry, root, key)
        retained = first.registry.preflight(
          allowed._2,
          Vector(entryRef("b") -> metadata("b"))
        ).toOption.get
        retainedSnapshot = snapshot(retained.registry, root, key)
        cancelled = retained.registry
                      .cancel(root, epoch, key, retainedSnapshot.entries.head)
                      .toOption.get
        recovered = cancelled.registry.preflight(
          allowed._2,
          Vector(entryRef("b") -> metadata("b"))
        ).toOption.get
        recoveredSnapshot = snapshot(recovered.registry, root, key)
      yield assertTrue(
        firstSnapshot.entries(1).errors == List(LiveUploadError.TooManyFiles),
        firstSnapshot.errors == List(LiveUploadError.TooManyFiles),
        retainedSnapshot.entries.map(_.ref) == List(entryRef("a"), entryRef("b")),
        retainedSnapshot.errors == List(LiveUploadError.TooManyFiles),
        recoveredSnapshot.entries.head.status == LiveUploadEntryStatus.Preflighted,
        recoveredSnapshot.errors.isEmpty,
        recovered.externalPreparations.size == 1
      )
    },
    test("a stale browser selection cannot recreate a cancelled entry") {
      for
        fixture <- writerFixture
        key       = UploadKey(
                      LiveUploadDef.hosted(
                        "files",
                        LiveUploadAccept.Any,
                        fixture.writer,
                        maxEntries = 2
                      )
                    )
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        first = allowed._1
                  .synchronizeSelection(allowed._2, Vector(entryRef("0") -> metadata("first")))
                  .toOption.get
        firstEntry = snapshot(first.registry, root, key).entries.head
        cancelled  = first.registry.cancel(root, epoch, key, firstEntry).toOption.get
        staleSelection = Vector(
                           entryRef("0") -> metadata("first"),
                           entryRef("1") -> metadata("second")
                         )
        synchronized = cancelled.registry
                         .synchronizeSelection(allowed._2, staleSelection).toOption.get
        preflight = synchronized.registry.preflight(allowed._2, staleSelection).toOption.get
        current   = snapshot(preflight.registry, root, key)
      yield assertTrue(
        current.entries.map(_.ref) == List(entryRef("1")),
        preflight.hostedRegistrations.map(_.ref) == Vector(entryRef("1"))
      )
    },
    test("writer failure invalidates entry, blocks progress, and retires joined worker") {
      for
        fixture <- writerFixture
        key       = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        preflight = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        token     = preflight.hostedRegistrations.head.asInstanceOf[UploadEntryToken[Int]]
        claim     = preflight.registry.claimHostedJoin(token).toOption.get
        worker   <- claim.factory.initialize.run
        joined    = claim.registry.installHostedWorker(token, worker).registry
        failure   = joined.failEntry(token, "queue_overflow").toOption.get
        entry      = snapshot(failure.registry, root, key).entries.head
      yield assertTrue(
        entry.status == LiveUploadEntryStatus.Invalid(
          List(LiveUploadError.WriterFailure("queue_overflow"))
        ),
        failure.registry.progress(token, 1).isLeft,
        failure.retirement.instructions == Vector(
          UploadRetirementInstruction.Hosted(worker.id, LiveUploadAbortReason.Failed("queue_overflow"))
        )
      )
    },
    test("late external preparation is rejected with exactly-once cleanup") {
      for
        preflights <- Ref.make(0)
        discards   <- Ref.make(0)
        uploader    = new ExternalUploader(preflights, discards)
        key         = UploadKey(LiveUploadDef.external("files", LiveUploadAccept.Any, uploader))
        allowed     = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        preflight   = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        prepared   <- preflight.externalPreparations.head.operation.run.map(_.toOption.get)
        entry       = snapshot(preflight.registry, root, key).entries.head
        cancelled   = preflight.registry.cancel(root, epoch, key, entry).toOption.get
        late        = cancelled.registry.installExternal(prepared)
        cleanup     = late.retirement.instructions.head.asInstanceOf[UploadRetirementInstruction.Cleanup]
        _          <- cleanup.operation.run *> cleanup.operation.run
        count      <- discards.get
      yield assertTrue(!late.accepted, count == 1)
    },
    test("late hosted initialization is rejected with worker cleanup") {
      for
        fixture <- writerFixture
        key       = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        preflight = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        token     = preflight.hostedRegistrations.head.asInstanceOf[UploadEntryToken[Int]]
        claim     = preflight.registry.claimHostedJoin(token).toOption.get
        entry     = snapshot(claim.registry, root, key).entries.head
        cancelled = claim.registry.cancel(root, epoch, key, entry).toOption.get
        worker   <- claim.factory.initialize.run
        late      = cancelled.registry.installHostedWorker(token, worker)
        cleanup   = late.retirement.instructions.head.asInstanceOf[UploadRetirementInstruction.Cleanup]
        _        <- cleanup.operation.run
        reasons  <- fixture.aborted.get
      yield assertTrue(
        !late.accepted,
        reasons == Vector(LiveUploadAbortReason.Failed("stale_join"))
      )
    },
    test("disallow removes authority before its exactly-once external cleanup executes") {
      for
        preflights <- Ref.make(0)
        discards   <- Ref.make(0)
        uploader    = new ExternalUploader(preflights, discards)
        key         = UploadKey(LiveUploadDef.external("files", LiveUploadAccept.Any, uploader))
        allowed     = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        preflight   = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        prepared   <- preflight.externalPreparations.head.operation.run.map(_.toOption.get)
        installed   = preflight.registry.installExternal(prepared).registry
        removed     = installed.disallow(root, epoch, key).toOption.get
        missing     = removed.registry.get(root, epoch, key)
        cleanup     = removed.retirement.instructions.head.asInstanceOf[UploadRetirementInstruction.Cleanup]
        _          <- cleanup.operation.run *> cleanup.operation.run
        count      <- discards.get
      yield assertTrue(
        missing == Left(UploadRegistryError.NotAllowed("files")),
        count == 1
      )
    },
    test("hosted worker serializes and threads state independently of registry") {
      for
        fixture <- writerFixture
        key       = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        preflight = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        token     = preflight.hostedRegistrations.head.asInstanceOf[UploadEntryToken[Int]]
        claim     = preflight.registry.claimHostedJoin(token).toOption.get
        worker   <- claim.factory.initialize.run
        _        <- ZIO.foreachParDiscard(1 to 20)(_ => worker.write(Chunk(1.toByte)))
        completion <- worker.complete
        maximum    <- fixture.maximumWrites.get
        joined      = claim.registry.installHostedWorker(token, worker).registry
        installed   = joined.installHostedCompletion(token, completion)
        completed   = snapshot(installed.registry, root, key).entries.head
      yield assertTrue(maximum == 1, installed.accepted, completed.status == LiveUploadEntryStatus.Completed)
    },
    test("hosted progress and worker completion are separate one-shot transitions") {
      for
        fixture <- writerFixture
        key       = UploadKey(LiveUploadDef.hosted("files", LiveUploadAccept.Any, fixture.writer))
        allowed   = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        preflight = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("a"))).toOption.get
        token     = preflight.hostedRegistrations.head.asInstanceOf[UploadEntryToken[Int]]
        claim     = preflight.registry.claimHostedJoin(token).toOption.get
        worker   <- claim.factory.initialize.run
        joined    = claim.registry.installHostedWorker(token, worker).registry
        atHundred = joined.progress(token, 100).toOption.get
        before    = snapshot(atHundred, root, key).entries.head
        completion <- worker.complete
        first       = atHundred.installHostedCompletion(token, completion)
        repeated    = first.registry.installHostedCompletion(token, completion)
        terminalProgress = first.registry.progress(token, 100)
        secondComplete <- worker.complete.exit
      yield assertTrue(
        before.status == LiveUploadEntryStatus.Uploading(100),
        first.accepted,
        !repeated.accepted,
        terminalProgress == Right(first.registry),
        repeated.retirement.instructions.isEmpty,
        secondComplete.isFailure
      )
    },
    test("public snapshots drive consume/postpone and become inactive after consume") {
      for
        preflights <- Ref.make(0)
        discards   <- Ref.make(0)
        uploader    = new ExternalUploader(preflights, discards)
        key         = UploadKey(LiveUploadDef.external("files", LiveUploadAccept.Any, uploader))
        allowed     = UploadRegistry.empty.allow(root, epoch, key, ref("u")).toOption.get
        preflight   = allowed._1.preflight(allowed._2, Vector(entryRef("e") -> metadata("value"))).toOption.get
        prepared   <- preflight.externalPreparations.head.operation.run.map(_.toOption.get)
        installed   = preflight.registry.installExternal(prepared).registry
        token       = prepared.entry.asInstanceOf[UploadEntryToken[String]]
        completed   = installed.progress(token, 100).toOption.get
        entry       = snapshot(completed, root, key).entries.head
        postpone    = completed.beginConsume(root, epoch, entry)(upload =>
          ZIO.succeed(ConsumeDecision.Postpone(upload.result))
        ).toOption.get
        postponedDecision <- postpone.operation.run
        postponed = completed.finishConsume(postpone, postponedDecision).toOption.get
        consume = postponed.registry.beginConsume(root, epoch, entry)(_ =>
          ZIO.succeed(ConsumeDecision.Consume(()))
        ).toOption.get
        consumedDecision <- consume.operation.run
        consumed = postponed.registry.finishConsume(consume, consumedDecision).toOption.get
        _       <- consumed.ownership.get.run
        inactive = consumed.registry.cancel(root, epoch, entry)
        count   <- discards.get
      yield assertTrue(
        postponed.registry.get(root, epoch, key).toOption.get._2.entries.size == 1,
        inactive == Left(UploadRegistryError.EntryInactive(entryRef("e"))),
        count == 0
      )
    }
  )
