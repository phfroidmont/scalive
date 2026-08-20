package scalive.runtime.resources

import zio.*
import zio.json.ast.Json

import scalive.runtime.contracts.ComponentInstanceId
import scalive.runtime.contracts.Epoch
import scalive.runtime.contracts.LifecycleId
import scalive.upload.*

final private[scalive] case class UploadKey[Result](definition: LiveUploadDef[Result])

final private[scalive] case class UploadToken[Result](
  owner: OwnerId,
  ownerEpoch: Epoch,
  key: UploadKey[Result],
  ref: UploadRef,
  generation: Long)

final private[scalive] case class UploadEntryToken[Result](
  upload: UploadToken[Result],
  ref: UploadEntryRef)

private[scalive] enum UploadRegistryError:
  case NotAllowed(name: String)
  case DefinitionMismatch(name: String)
  case ActiveEntries(name: String)
  case StaleAuthority
  case EntryInactive(ref: UploadEntryRef)
  case DuplicateEntry(ref: UploadEntryRef)
  case MetadataMismatch(ref: UploadEntryRef)
  case InvalidEntryState(ref: UploadEntryRef)
  case InvalidProgress(ref: UploadEntryRef, previous: Int, requested: Int)

final private[scalive] case class UploadOperation[+A](run: Task[A])

final private[scalive] case class HostedWorkerId(
  owner: OwnerId,
  ownerEpoch: Epoch,
  uploadRef: UploadRef,
  entryRef: UploadEntryRef,
  generation: Long)

private[scalive] enum UploadRetirementInstruction:
  case Hosted(worker: HostedWorkerId, reason: LiveUploadAbortReason)
  case Cleanup(operation: UploadOperation[Unit])

final private[scalive] case class UploadRetirementPlan(
  instructions: Vector[UploadRetirementInstruction]):
  def ++(other: UploadRetirementPlan): UploadRetirementPlan =
    UploadRetirementPlan(instructions ++ other.instructions)

private[scalive] object UploadRetirementPlan:
  val empty: UploadRetirementPlan = UploadRetirementPlan(Vector.empty)

/** The only hosted writer-state erasure boundary. A connection owns this handle after admission.
  * Its synchronized state serially threads writes and makes completion, abort, and ownership
  * transfer one-shot operations. The registry never stores this value.
  */
sealed private[scalive] trait HostedUploadWorker:
  def id: HostedWorkerId
  def write(data: Chunk[Byte]): Task[Unit]
  def complete: Task[HostedUploadCompletion]
  def abort(reason: LiveUploadAbortReason): Task[Unit]

private[scalive] object HostedUploadWorker:
  final private class Handle[State, Result](
    val id: HostedWorkerId,
    writer: LiveUploadWriter[State, Result],
    current: Ref.Synchronized[WorkerState[State, Result]])
      extends HostedUploadWorker:

    def write(data: Chunk[Byte]): Task[Unit] =
      current.modifyZIO {
        case WorkerState.Active(state) =>
          writer.writeChunk(data, state).map(next => () -> WorkerState.Active(next))
        case _ => ZIO.fail(new IllegalStateException("Hosted upload worker is terminal"))
      }

    def complete: Task[HostedUploadCompletion] =
      current.modifyZIO {
        case WorkerState.Active(state) =>
          writer.complete(state).map { result =>
            val completion: HostedUploadCompletion = new Completion(result, writer.metadata(result))
            completion -> WorkerState.Completed(result)
          }
        case _ => ZIO.fail(new IllegalStateException("Hosted upload worker is terminal"))
      }

    def abort(reason: LiveUploadAbortReason): Task[Unit] =
      current.modify {
        case WorkerState.Active(state)     => writer.abort(state, reason) -> WorkerState.Retired()
        case WorkerState.Completed(result) => writer.discard(result)      -> WorkerState.Retired()
        case WorkerState.OwnershipTransferred() => ZIO.unit -> WorkerState.Retired()
        case WorkerState.Retired()              => ZIO.unit -> WorkerState.Retired()
      }.flatten

    def transfer: Task[Unit] =
      current.modify {
        case WorkerState.Completed(_) =>
          ZIO.unit -> WorkerState.OwnershipTransferred()
        case state =>
          ZIO.fail(new IllegalStateException("Hosted result is no longer owned")) -> state
      }.flatten

    final private class Completion(result: Result, val metadata: Json.Obj)
        extends HostedUploadCompletion:
      def workerId: HostedWorkerId       = id
      def cleanup: UploadOperation[Unit] =
        UploadOperation(abort(LiveUploadAbortReason.Failed("late_completion")))
      def consume[R, A](
        ref: UploadEntryRef,
        client: UploadClientMetadata,
        callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
      ): UploadOperation[ConsumeDecision[A]] =
        UploadOperation(
          callback(new CompletedUpload(ref, client, result.asInstanceOf[R], metadata))
        )
      def transferOwnership: UploadOperation[Unit] = UploadOperation(transfer)
  end Handle

  private enum WorkerState[State, Result]:
    case Active(state: State)
    case Completed(result: Result)
    case OwnershipTransferred()
    case Retired()

  def initialize[State, Result](
    id: HostedWorkerId,
    writer: LiveUploadWriter[State, Result],
    client: UploadClientMetadata
  ): Task[HostedUploadWorker] =
    writer.init(client).flatMap { state =>
      Ref.Synchronized.make[WorkerState[State, Result]](WorkerState.Active(state)).map { current =>
        new Handle(id, writer, current)
      }
    }
end HostedUploadWorker

/** A claimed join factory. Merely constructing or returning it performs no writer effect. */
sealed private[scalive] trait HostedUploadFactory:
  def id: HostedWorkerId
  def initialize: UploadOperation[HostedUploadWorker]

private[scalive] object HostedUploadFactory:
  final private case class Factory[State, Result](
    id: HostedWorkerId,
    writer: LiveUploadWriter[State, Result],
    client: UploadClientMetadata)
      extends HostedUploadFactory:
    def initialize: UploadOperation[HostedUploadWorker] =
      UploadOperation(HostedUploadWorker.initialize(id, writer, client))

  def apply[Result](
    id: HostedWorkerId,
    definition: LiveUploadDef[Result],
    client: UploadClientMetadata
  ): Option[HostedUploadFactory] = definition.destination match
    case LiveUploadDestination.Hosted(writer) => Some(Factory(id, writer, client))
    case LiveUploadDestination.External(_)    => None

sealed private[scalive] trait OwnedUploadResult:
  def metadata: Json.Obj
  def cleanup: UploadOperation[Unit]
  def consume[Result, A](
    ref: UploadEntryRef,
    client: UploadClientMetadata,
    callback: CompletedUpload[Result] => Task[ConsumeDecision[A]]
  ): UploadOperation[ConsumeDecision[A]]
  def transferOwnership: UploadOperation[Unit]

private[scalive] object OwnedUploadResult:
  final private case class External[Result](
    result: Result,
    val metadata: Json.Obj,
    uploader: LiveUploadExternalUploader[Result],
    owned: Ref.Synchronized[Boolean])
      extends OwnedUploadResult:
    def cleanup: UploadOperation[Unit] = UploadOperation(
      owned
        .modify(wasOwned => wasOwned -> false).flatMap(ZIO.when(_)(uploader.discard(result)).unit)
    )
    def consume[R, A](
      ref: UploadEntryRef,
      client: UploadClientMetadata,
      callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
    ): UploadOperation[ConsumeDecision[A]] =
      UploadOperation(callback(new CompletedUpload(ref, client, result.asInstanceOf[R], metadata)))
    def transferOwnership: UploadOperation[Unit] = UploadOperation(owned.set(false))

  def external[Result](
    result: Result,
    metadata: Json.Obj,
    uploader: LiveUploadExternalUploader[Result]
  ): UIO[OwnedUploadResult] =
    Ref.Synchronized.make(true).map(External(result, metadata, uploader, _))

sealed private[scalive] trait HostedUploadCompletion extends OwnedUploadResult:
  def workerId: HostedWorkerId

final private[scalive] case class ExternalUploadPreparation(
  entry: UploadEntryToken[?],
  result: OwnedUploadResult)

final private[scalive] case class ExternalPreparationPlan(
  entry: UploadEntryToken[?],
  operation: UploadOperation[Either[LiveUploadError, ExternalUploadPreparation]])

final private[scalive] case class UploadPreflight(
  registry: UploadRegistry,
  upload: UploadToken[?],
  externalPreparations: Vector[ExternalPreparationPlan],
  hostedRegistrations: Vector[UploadEntryToken[?]],
  retirement: UploadRetirementPlan)

final private[scalive] case class UploadPreflightEntry(
  ref: UploadEntryRef,
  errors: List[LiveUploadError],
  external: Option[Json.Obj],
  hosted: Option[UploadEntryToken[?]])

final private[scalive] case class UploadPreflightView(
  ref: UploadRef,
  maxEntries: Int,
  maxFileSize: Long,
  chunkSize: Int,
  chunkTimeoutMillis: Int,
  autoUpload: Boolean,
  entries: Vector[UploadPreflightEntry])
    extends UploadControlReply

final private[scalive] case class HostedJoinClaim(
  registry: UploadRegistry,
  entry: UploadEntryToken[?],
  factory: HostedUploadFactory,
  expectedBytes: Long,
  chunkSize: Int)

final private[scalive] case class UploadInstallation(
  registry: UploadRegistry,
  accepted: Boolean,
  retirement: UploadRetirementPlan)

final private[scalive] case class UploadRemoval(
  registry: UploadRegistry,
  retirement: UploadRetirementPlan)

final private[scalive] case class UploadConsume[A](
  entry: UploadEntryToken[?],
  operation: UploadOperation[ConsumeDecision[A]])

final private[scalive] case class UploadConsumeResult(
  registry: UploadRegistry,
  ownership: Option[UploadOperation[Unit]])

/** Immutable upload control state. Destination effects are returned as explicit plans. */
final private[scalive] case class UploadRegistry private (
  private val uploads: Vector[UploadRegistry.Allowed],
  private val generations: Map[(OwnerId, String), Long]):
  import UploadRegistry.*

  def allow[Result](
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: UploadKey[Result],
    ref: UploadRef
  ): Either[UploadRegistryError, (UploadRegistry, UploadToken[Result])] =
    find(owner, key.definition.name) match
      case Some(current) if current.ownerEpoch != ownerEpoch =>
        Left(UploadRegistryError.StaleAuthority)
      case Some(current) if equivalent(current.definition, key.definition) =>
        Right(this -> current.token(key))
      case Some(current) if current.entries.nonEmpty =>
        Left(UploadRegistryError.ActiveEntries(key.definition.name))
      case Some(current) =>
        fresh(owner, ownerEpoch, key, ref, Some(current))
      case None => fresh(owner, ownerEpoch, key, ref, None)

  def get[Result](
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: UploadKey[Result]
  ): Either[UploadRegistryError, (UploadToken[Result], LiveUpload[Result])] =
    lookup(owner, ownerEpoch, key).map(allowed => allowed.token(key) -> allowed.snapshot(key))

  def snapshotForToken[Result](
    token: UploadToken[Result]
  ): Either[UploadRegistryError, LiveUpload[Result]] =
    current(token).map(_.snapshot(token.key))

  def snapshotFor[Result](
    owner: OwnerId,
    ownerEpoch: Epoch,
    entry: LiveUploadEntry[Result]
  ): Either[UploadRegistryError, LiveUpload[Result]] =
    find(owner, entry.uploadName) match
      case None => Left(UploadRegistryError.NotAllowed(entry.uploadName))
      case Some(allowed) if allowed.ownerEpoch != ownerEpoch =>
        Left(UploadRegistryError.StaleAuthority)
      case Some(allowed) =>
        val definition = allowed.definition.asInstanceOf[LiveUploadDef[Result]]
        Right(allowed.snapshot(UploadKey(definition)))

  def disallow[Result](
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: UploadKey[Result]
  ): Either[UploadRegistryError, UploadRemoval] =
    lookup(owner, ownerEpoch, key).map(removeAllowed(_, LiveUploadAbortReason.Disallowed))

  def preflight[Result](
    token: UploadToken[Result],
    selected: Vector[(UploadEntryRef, UploadClientMetadata)]
  ): Either[UploadRegistryError, UploadPreflight] =
    reconcileSelection(token, selected, prepare = true)

  def synchronizeSelection[Result](
    token: UploadToken[Result],
    selected: Vector[(UploadEntryRef, UploadClientMetadata)]
  ): Either[UploadRegistryError, UploadPreflight] =
    reconcileSelection(token, selected, prepare = false)

  private def reconcileSelection[Result](
    token: UploadToken[Result],
    selected: Vector[(UploadEntryRef, UploadClientMetadata)],
    prepare: Boolean
  ): Either[UploadRegistryError, UploadPreflight] = current(token).flatMap { allowed =>
    val duplicate = selected
      .foldLeft((Set.empty[UploadEntryRef], Option.empty[UploadEntryRef])) {
        case ((seen, found @ Some(_)), _)                   => seen         -> found
        case ((seen, None), (ref, _)) if seen.contains(ref) => seen         -> Some(ref)
        case ((seen, None), (ref, _))                       => (seen + ref) -> None
      }._2
    val existingByRef = allowed.entries.iterator.map(entry => entry.ref -> entry).toMap
    duplicate match
      case Some(ref) => Left(UploadRegistryError.DuplicateEntry(ref))
      case None      =>
        selected.collectFirst {
          case (ref, client) if existingByRef.get(ref).exists(e => !sameClient(e.client, client)) =>
            ref
        } match
          case Some(ref) => Left(UploadRegistryError.MetadataMismatch(ref))
          case None      => Right(reconcile(token, allowed, selected, prepare))
  }

  def preflight(
    owner: OwnerId,
    ownerEpoch: Epoch,
    ref: UploadRef,
    selected: Vector[(UploadEntryRef, UploadClientMetadata)]
  ): Either[UploadRegistryError, UploadPreflight] =
    resolveUpload(owner, ownerEpoch, ref).flatMap { token =>
      preflight(token.asInstanceOf[UploadToken[Any]], selected)
    }

  def synchronizeSelection(
    owner: OwnerId,
    ownerEpoch: Epoch,
    ref: UploadRef,
    selected: Vector[(UploadEntryRef, UploadClientMetadata)]
  ): Either[UploadRegistryError, UploadPreflight] =
    resolveUpload(owner, ownerEpoch, ref).flatMap { token =>
      synchronizeSelection(token.asInstanceOf[UploadToken[Any]], selected)
    }

  def preflightView(token: UploadToken[?]): Either[UploadRegistryError, UploadPreflightView] =
    currentUntyped(token).map { allowed =>
      val definition = allowed.definition
      val entries    = allowed.entries.map { entry =>
        val (errors, external, hosted) = entry.state match
          case EntryState.Invalid(values, _)          => (values, None, None)
          case EntryState.ExternalPrepared(result, _) => (Nil, Some(result.metadata), None)
          case EntryState.Completed(result)           => (Nil, Some(result.metadata), None)
          case EntryState.HostedReady                 =>
            (Nil, None, Some(UploadEntryToken(token, entry.ref)))
          case _ => (Nil, None, None)
        UploadPreflightEntry(entry.ref, errors, external, hosted)
      }
      UploadPreflightView(
        allowed.ref,
        definition.maxEntries,
        definition.maxFileSize,
        definition.chunkSize,
        definition.chunkTimeout.toMillis.toInt,
        definition.autoUpload,
        entries
      )
    }

  def resolveEntry(
    owner: OwnerId,
    ownerEpoch: Epoch,
    uploadRef: UploadRef,
    entryRef: UploadEntryRef
  ): Either[UploadRegistryError, UploadEntryToken[?]] =
    resolveUpload(owner, ownerEpoch, uploadRef).flatMap { token =>
      currentEntryUntyped(UploadEntryToken(token, entryRef)).map(_ =>
        UploadEntryToken(token, entryRef)
      )
    }

  def claimHostedJoin[Result](
    token: UploadEntryToken[Result]
  ): Either[UploadRegistryError, HostedJoinClaim] = currentEntry(token).flatMap {
    case (allowed, entry) =>
      entry.state match
        case EntryState.HostedReady =>
          val id = workerId(token)
          HostedUploadFactory(id, token.upload.key.definition, entry.client) match
            case Some(factory) =>
              val next = update(allowed, entry.copy(state = EntryState.HostedClaimed(id)))
              Right(
                HostedJoinClaim(
                  next,
                  token,
                  factory,
                  entry.client.sizeBytes,
                  token.upload.key.definition.chunkSize
                )
              )
            case None => Left(UploadRegistryError.InvalidEntryState(token.ref))
        case _ => Left(UploadRegistryError.InvalidEntryState(token.ref))
  }

  def installHostedWorker[Result](
    token: UploadEntryToken[Result],
    worker: HostedUploadWorker
  ): UploadInstallation =
    currentEntry(token).toOption match
      case Some((allowed, entry)) if entry.state == EntryState.HostedClaimed(worker.id) =>
        UploadInstallation(
          update(allowed, entry.copy(state = EntryState.HostedJoined(worker.id, 0))),
          accepted = true,
          UploadRetirementPlan.empty
        )
      case _ => lateCleanup(worker.abort(LiveUploadAbortReason.Failed("stale_join")))

  def installExternal(
    preparation: ExternalUploadPreparation
  ): UploadInstallation =
    currentEntryUntyped(preparation.entry).toOption match
      case Some((allowed, entry)) if entry.state == EntryState.ExternalPreparing =>
        UploadInstallation(
          update(allowed, entry.copy(state = EntryState.ExternalPrepared(preparation.result, 0))),
          accepted = true,
          UploadRetirementPlan.empty
        )
      case Some((_, Entry(_, _, EntryState.ExternalPrepared(result, _))))
          if result eq preparation.result =>
        UploadInstallation(this, accepted = false, UploadRetirementPlan.empty)
      case Some((_, Entry(_, _, EntryState.Completed(result)))) if result eq preparation.result =>
        UploadInstallation(this, accepted = false, UploadRetirementPlan.empty)
      case _ => lateCleanup(preparation.result.cleanup.run)

  def rejectExternal(
    token: UploadEntryToken[?],
    error: LiveUploadError
  ): Either[UploadRegistryError, UploadRegistry] =
    updateEntryUntyped(token) { entry =>
      if entry.state == EntryState.ExternalPreparing then
        Right(entry.copy(state = EntryState.Invalid(error :: Nil, terminalFailure = true)))
      else Left(UploadRegistryError.InvalidEntryState(token.ref))
    }

  def progress[Result](
    token: UploadEntryToken[Result],
    requested: Int
  ): Either[UploadRegistryError, UploadRegistry] = currentEntry(token).flatMap {
    case (allowed, entry) =>
      val previous = entry.progress
      if requested < previous || requested < 0 || requested > 100 then
        Left(UploadRegistryError.InvalidProgress(token.ref, previous, requested))
      else
        entry.state match
          case EntryState.HostedJoined(id, _) =>
            Right(update(allowed, entry.copy(state = EntryState.HostedJoined(id, requested))))
          case EntryState.ExternalPrepared(result, _) if requested == 100 =>
            Right(update(allowed, entry.copy(state = EntryState.Completed(result))))
          case EntryState.ExternalPrepared(result, _) =>
            Right(
              update(allowed, entry.copy(state = EntryState.ExternalPrepared(result, requested)))
            )
          case EntryState.Completed(_) if requested == 100 => Right(this)
          case _ => Left(UploadRegistryError.InvalidEntryState(token.ref))
  }

  def installHostedCompletion[Result](
    token: UploadEntryToken[Result],
    completion: HostedUploadCompletion
  ): UploadInstallation =
    currentEntry(token).toOption match
      case Some((allowed, entry)) if entry.state match
            case EntryState.HostedJoined(id, _) => id == completion.workerId
            case _                              => false
          =>
        UploadInstallation(
          update(allowed, entry.copy(state = EntryState.Completed(completion))),
          accepted = true,
          UploadRetirementPlan.empty
        )
      case Some((_, Entry(_, _, EntryState.Completed(result))))
          if result.asInstanceOf[AnyRef] eq completion.asInstanceOf[AnyRef] =>
        UploadInstallation(this, accepted = false, UploadRetirementPlan.empty)
      case _ => lateCleanup(completion.cleanup.run)

  def failEntry[Result](
    token: UploadEntryToken[Result],
    reason: String
  ): Either[UploadRegistryError, UploadRemoval] = currentEntry(token).flatMap {
    case (allowed, entry) =>
      entry.state match
        case EntryState.Invalid(_, _) | EntryState.Completed(_) =>
          Left(UploadRegistryError.InvalidEntryState(token.ref))
        case _ =>
          val plan    = entry.retirement(LiveUploadAbortReason.Failed(reason))
          val invalid = entry.copy(
            state = EntryState.Invalid(
              LiveUploadError.WriterFailure(reason) :: Nil,
              terminalFailure = true
            )
          )
          Right(UploadRemoval(update(allowed, invalid), plan))
  }

  def cancel[Result](
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: UploadKey[Result],
    snapshot: LiveUploadEntry[Result]
  ): Either[UploadRegistryError, UploadRemoval] = lookup(owner, ownerEpoch, key).flatMap { allowed =>
    allowed.entries.find(entry =>
      entry.ref == snapshot.ref && (entry.client eq snapshot.client)
    ) match
      case None        => Left(UploadRegistryError.EntryInactive(snapshot.ref))
      case Some(entry) =>
        val next = replace(allowed, allowed.copy(entries = allowed.entries.filterNot(_ eq entry)))
        Right(UploadRemoval(next, entry.retirement(LiveUploadAbortReason.Cancelled)))
  }

  def cancel[Result](
    owner: OwnerId,
    ownerEpoch: Epoch,
    snapshot: LiveUploadEntry[Result]
  ): Either[UploadRegistryError, UploadRemoval] =
    resolveSnapshot(owner, ownerEpoch, snapshot).map { case (allowed, entry) =>
      val next = replace(allowed, allowed.copy(entries = allowed.entries.filterNot(_ eq entry)))
      UploadRemoval(next, entry.retirement(LiveUploadAbortReason.Cancelled))
    }

  def beginConsume[Result, A](
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: UploadKey[Result],
    snapshot: LiveUploadEntry[Result]
  )(
    callback: CompletedUpload[Result] => Task[ConsumeDecision[A]]
  ): Either[UploadRegistryError, UploadConsume[A]] =
    lookup(owner, ownerEpoch, key).flatMap { allowed =>
      allowed.entries.find(entry =>
        entry.ref == snapshot.ref && (entry.client eq snapshot.client)
      ) match
        case Some(Entry(ref, client, EntryState.Completed(result))) =>
          val token = UploadEntryToken(allowed.token(key), ref)
          Right(UploadConsume(token, result.consume(ref, client, callback)))
        case Some(_) => Left(UploadRegistryError.InvalidEntryState(snapshot.ref))
        case None    => Left(UploadRegistryError.EntryInactive(snapshot.ref))
    }

  def beginConsume[Result, A](
    owner: OwnerId,
    ownerEpoch: Epoch,
    snapshot: LiveUploadEntry[Result]
  )(
    callback: CompletedUpload[Result] => Task[ConsumeDecision[A]]
  ): Either[UploadRegistryError, UploadConsume[A]] =
    resolveSnapshot(owner, ownerEpoch, snapshot).flatMap { case (allowed, entry) =>
      entry.state match
        case EntryState.Completed(result) =>
          val definition = allowed.definition.asInstanceOf[LiveUploadDef[Result]]
          val token      = UploadEntryToken(allowed.token(UploadKey(definition)), entry.ref)
          Right(UploadConsume(token, result.consume(entry.ref, entry.client, callback)))
        case _ => Left(UploadRegistryError.InvalidEntryState(snapshot.ref))
    }

  def finishConsume[A](
    consume: UploadConsume[A],
    decision: ConsumeDecision[A]
  ): Either[UploadRegistryError, UploadConsumeResult] =
    currentEntryUntyped(consume.entry).flatMap { case (allowed, entry) =>
      entry.state match
        case EntryState.Completed(result) =>
          decision match
            case ConsumeDecision.Postpone(_) => Right(UploadConsumeResult(this, None))
            case ConsumeDecision.Consume(_)  =>
              val next =
                replace(allowed, allowed.copy(entries = allowed.entries.filterNot(_ eq entry)))
              Right(UploadConsumeResult(next, Some(result.transferOwnership)))
        case _ => Left(UploadRegistryError.InvalidEntryState(consume.entry.ref))
    }

  def retireOwner(owner: OwnerId, reason: LiveUploadAbortReason): UploadRemoval =
    val (removed, retained) = uploads.partition(_.owner == owner)
    val plan = removed.flatMap(_.entries).foldLeft(UploadRetirementPlan.empty) { (all, entry) =>
      all ++ entry.retirement(reason)
    }
    UploadRemoval(copy(uploads = retained), plan)

  def retireMissingComponents(
    lifecycle: LifecycleId,
    retained: Set[ComponentInstanceId]
  ): UploadRemoval =
    retireWhere(
      {
        case OwnerId.Component(ownerLifecycle, component) =>
          ownerLifecycle == lifecycle && !retained.contains(component)
        case OwnerId.Root(_) => false
      },
      LiveUploadAbortReason.ComponentRemoved
    )

  def retireLifecycle(lifecycle: LifecycleId): UploadRemoval =
    retireWhere(
      {
        case OwnerId.Root(ownerLifecycle)         => ownerLifecycle == lifecycle
        case OwnerId.Component(ownerLifecycle, _) => ownerLifecycle == lifecycle
      },
      LiveUploadAbortReason.SocketShutdown
    )

  private def reconcile[Result](
    token: UploadToken[Result],
    allowed: Allowed,
    selected: Vector[(UploadEntryRef, UploadClientMetadata)],
    prepare: Boolean
  ): UploadPreflight =
    val selectedRefs  = selected.iterator.map(_._1).toSet
    val existingByRef = allowed.entries.iterator.map(entry => entry.ref -> entry).toMap
    val omitted       = allowed.entries.filterNot(entry => selectedRefs.contains(entry.ref))
    var validCount    = 0
    var preparations  = Vector.empty[ExternalPreparationPlan]
    var registrations = Vector.empty[UploadEntryToken[?]]

    val entries = selected.map { case (ref, client) =>
      val existing       = existingByRef.get(ref)
      val metadataErrors = validationErrors(allowed.definition, client)
      val errors = metadataErrors ++ Option.when(validCount >= allowed.definition.maxEntries)(
        LiveUploadError.TooManyFiles
      )
      if errors.isEmpty then validCount += 1

      existing match
        case Some(entry) if entry.state.terminal => entry
        case Some(entry) if errors.nonEmpty      =>
          entry.copy(state = EntryState.Invalid(errors, terminalFailure = false))
        case Some(entry @ Entry(_, _, EntryState.Selected)) if prepare =>
          prepareNew(token, entry, preparations, registrations) match
            case (next, newPreparations, newRegistrations) =>
              preparations = newPreparations
              registrations = newRegistrations
              next
        case Some(entry @ Entry(_, _, EntryState.Invalid(_, false))) =>
          if prepare then
            prepareNew(token, entry, preparations, registrations) match
              case (next, newPreparations, newRegistrations) =>
                preparations = newPreparations
                registrations = newRegistrations
                next
          else entry.copy(state = EntryState.Selected)
        case Some(entry) => entry
        case None        =>
          val entry = Entry(ref, client, EntryState.Invalid(errors, terminalFailure = false))
          if errors.nonEmpty then entry
          else if prepare then
            prepareNew(token, entry, preparations, registrations) match
              case (next, newPreparations, newRegistrations) =>
                preparations = newPreparations
                registrations = newRegistrations
                next
          else entry.copy(state = EntryState.Selected)
    }

    val updatedByRef     = entries.iterator.map(entry => entry.ref -> entry).toMap
    val changedToInvalid = allowed.entries.filter { old =>
      updatedByRef.get(old.ref).exists(next => next.state != old.state && next.state.isInvalid)
    }
    val retirementEntries = (omitted ++ changedToInvalid).distinct
    val retirement        = retirementEntries.foldLeft(UploadRetirementPlan.empty) { (all, entry) =>
      all ++ entry.retirement(LiveUploadAbortReason.Cancelled)
    }
    val updated = allowed.copy(entries = entries)
    UploadPreflight(replace(allowed, updated), token, preparations, registrations, retirement)
  end reconcile

  private def prepareNew[Result](
    token: UploadToken[Result],
    entry: Entry,
    preparations: Vector[ExternalPreparationPlan],
    registrations: Vector[UploadEntryToken[?]]
  ): (Entry, Vector[ExternalPreparationPlan], Vector[UploadEntryToken[?]]) =
    val entryToken = UploadEntryToken(token, entry.ref)
    token.key.definition.destination match
      case LiveUploadDestination.Hosted(_) =>
        (entry.copy(state = EntryState.HostedReady), preparations, registrations :+ entryToken)
      case LiveUploadDestination.External(uploader) =>
        val operation = UploadOperation(
          uploader.preflight(entry.client).either.flatMap {
            case Left(_) => ZIO.succeed(Left(LiveUploadError.ExternalClientFailure))
            case Right(LiveExternalUploadResult.Error(meta)) =>
              ZIO.succeed(Left(LiveUploadError.External(meta)))
            case Right(LiveExternalUploadResult.Ready(config, result)) =>
              OwnedUploadResult.external(result, config.json, uploader).map { owned =>
                Right(ExternalUploadPreparation(entryToken, owned))
              }
          }
        )
        (
          entry.copy(state = EntryState.ExternalPreparing),
          preparations :+ ExternalPreparationPlan(entryToken, operation),
          registrations
        )

  private def fresh[Result](
    owner: OwnerId,
    epoch: Epoch,
    key: UploadKey[Result],
    ref: UploadRef,
    replaced: Option[Allowed]
  ): Either[UploadRegistryError, (UploadRegistry, UploadToken[Result])] =
    val previous = generations.getOrElse(owner -> key.definition.name, 0L)
    if previous == Long.MaxValue then Left(UploadRegistryError.StaleAuthority)
    else
      val allowed  = Allowed(owner, epoch, key.definition, ref, previous + 1L, Vector.empty)
      val retained = replaced.fold(uploads)(old => uploads.filterNot(_ eq old))
      val next     = copy(
        uploads = retained :+ allowed,
        generations = generations.updated(owner -> key.definition.name, previous + 1L)
      )
      Right(next -> allowed.token(key))

  private def lookup[Result](
    owner: OwnerId,
    epoch: Epoch,
    key: UploadKey[Result]
  ): Either[UploadRegistryError, Allowed] = find(owner, key.definition.name) match
    case None => Left(UploadRegistryError.NotAllowed(key.definition.name))
    case Some(allowed) if allowed.ownerEpoch != epoch => Left(UploadRegistryError.StaleAuthority)
    case Some(allowed) if !equivalent(allowed.definition, key.definition) =>
      Left(UploadRegistryError.DefinitionMismatch(key.definition.name))
    case Some(allowed) => Right(allowed)

  private def current[Result](token: UploadToken[Result]): Either[UploadRegistryError, Allowed] =
    find(token.owner, token.key.definition.name)
      .filter(_.matches(token)).toRight(UploadRegistryError.StaleAuthority)

  private def currentUntyped(token: UploadToken[?]): Either[UploadRegistryError, Allowed] =
    uploads.find(_.matchesUntyped(token)).toRight(UploadRegistryError.StaleAuthority)

  private def resolveUpload(
    owner: OwnerId,
    epoch: Epoch,
    ref: UploadRef
  ): Either[UploadRegistryError, UploadToken[?]] =
    uploads.find(allowed => allowed.owner == owner && allowed.ref == ref) match
      case None                                         => Left(UploadRegistryError.StaleAuthority)
      case Some(allowed) if allowed.ownerEpoch != epoch => Left(UploadRegistryError.StaleAuthority)
      case Some(allowed)                                =>
        val definition = allowed.definition.asInstanceOf[LiveUploadDef[Any]]
        Right(allowed.token(UploadKey(definition)))

  private def currentEntry[Result](
    token: UploadEntryToken[Result]
  ): Either[UploadRegistryError, (Allowed, Entry)] = current(token.upload).flatMap { allowed =>
    allowed.entries
      .find(_.ref == token.ref)
      .map(allowed -> _).toRight(UploadRegistryError.EntryInactive(token.ref))
  }

  private def currentEntryUntyped(
    token: UploadEntryToken[?]
  ): Either[UploadRegistryError, (Allowed, Entry)] =
    uploads.find(_.matchesUntyped(token.upload)) match
      case None          => Left(UploadRegistryError.StaleAuthority)
      case Some(allowed) =>
        allowed.entries
          .find(_.ref == token.ref)
          .map(allowed -> _).toRight(UploadRegistryError.EntryInactive(token.ref))

  private def resolveSnapshot[Result](
    owner: OwnerId,
    epoch: Epoch,
    snapshot: LiveUploadEntry[Result]
  ): Either[UploadRegistryError, (Allowed, Entry)] =
    find(owner, snapshot.uploadName) match
      case None => Left(UploadRegistryError.NotAllowed(snapshot.uploadName))
      case Some(allowed) if allowed.ownerEpoch != epoch => Left(UploadRegistryError.StaleAuthority)
      case Some(allowed)                                =>
        allowed.entries
          .find(entry => entry.ref == snapshot.ref && (entry.client eq snapshot.client))
          .map(allowed -> _).toRight(UploadRegistryError.EntryInactive(snapshot.ref))

  private def retireWhere(
    matches: OwnerId => Boolean,
    reason: LiveUploadAbortReason
  ): UploadRemoval =
    val (removed, retained) = uploads.partition(allowed => matches(allowed.owner))
    val plan = removed.flatMap(_.entries).foldLeft(UploadRetirementPlan.empty) { (all, entry) =>
      all ++ entry.retirement(reason)
    }
    UploadRemoval(copy(uploads = retained), plan)

  private def updateEntryUntyped(
    token: UploadEntryToken[?]
  )(
    f: Entry => Either[UploadRegistryError, Entry]
  ): Either[UploadRegistryError, UploadRegistry] =
    currentEntryUntyped(token).flatMap { case (allowed, entry) => f(entry).map(update(allowed, _)) }

  private def update(allowed: Allowed, entry: Entry): UploadRegistry =
    val index = allowed.entries.indexWhere(_.ref == entry.ref)
    replace(allowed, allowed.copy(entries = allowed.entries.updated(index, entry)))

  private def removeAllowed(allowed: Allowed, reason: LiveUploadAbortReason): UploadRemoval =
    val plan = allowed.entries.foldLeft(UploadRetirementPlan.empty)(_ ++ _.retirement(reason))
    UploadRemoval(copy(uploads = uploads.filterNot(_ eq allowed)), plan)

  private def replace(previous: Allowed, next: Allowed): UploadRegistry =
    copy(uploads = uploads.updated(uploads.indexWhere(_ eq previous), next))

  private def find(owner: OwnerId, name: String): Option[Allowed] =
    uploads.find(allowed => allowed.owner == owner && allowed.definition.name == name)

  private def lateCleanup(effect: Task[Unit]): UploadInstallation =
    UploadInstallation(
      this,
      accepted = false,
      UploadRetirementPlan(Vector(UploadRetirementInstruction.Cleanup(UploadOperation(effect))))
    )
end UploadRegistry

private[scalive] object UploadRegistry:
  private enum EntryState:
    case Selected
    case HostedReady
    case HostedClaimed(worker: HostedWorkerId)
    case HostedJoined(worker: HostedWorkerId, progress: Int)
    case ExternalPreparing
    case ExternalPrepared(result: OwnedUploadResult, progress: Int)
    case Completed(result: OwnedUploadResult)
    case Invalid(errors: List[LiveUploadError], terminalFailure: Boolean)

    def terminal: Boolean = this match
      case Completed(_) | Invalid(_, true) => true
      case _                               => false

    def isInvalid: Boolean = this match
      case Invalid(_, _) => true
      case _             => false

  final private case class Entry(
    ref: UploadEntryRef,
    client: UploadClientMetadata,
    state: EntryState):
    def progress: Int = state match
      case EntryState.HostedJoined(_, value)     => value
      case EntryState.ExternalPrepared(_, value) => value
      case EntryState.Completed(_)               => 100
      case _                                     => 0

    def snapshot[Result](name: String): LiveUploadEntry[Result] =
      val (status, metadata) = state match
        case EntryState.Selected => LiveUploadEntryStatus.Selected -> None
        case EntryState.HostedJoined(_, value) if value > 0 =>
          LiveUploadEntryStatus.Uploading(value) -> None
        case EntryState.ExternalPrepared(result, value) if value > 0 =>
          LiveUploadEntryStatus.Uploading(value) -> Some(result.metadata)
        case EntryState.ExternalPrepared(result, _) =>
          LiveUploadEntryStatus.Preflighted -> Some(result.metadata)
        case EntryState.Completed(result) =>
          LiveUploadEntryStatus.Completed -> Some(result.metadata)
        case EntryState.Invalid(errors, _) => LiveUploadEntryStatus.Invalid(errors) -> None
        case _                             => LiveUploadEntryStatus.Preflighted     -> None
      new LiveUploadEntry(ref, client, status, metadata, name)

    def retirement(reason: LiveUploadAbortReason): UploadRetirementPlan = state match
      case EntryState.HostedJoined(worker, _) =>
        UploadRetirementPlan(Vector(UploadRetirementInstruction.Hosted(worker, reason)))
      case EntryState.ExternalPrepared(result, _) =>
        UploadRetirementPlan(Vector(UploadRetirementInstruction.Cleanup(result.cleanup)))
      case EntryState.Completed(result) =>
        UploadRetirementPlan(Vector(UploadRetirementInstruction.Cleanup(result.cleanup)))
      case _ => UploadRetirementPlan.empty
  end Entry

  final private case class Allowed(
    owner: OwnerId,
    ownerEpoch: Epoch,
    definition: LiveUploadDef[?],
    ref: UploadRef,
    generation: Long,
    entries: Vector[Entry]):
    def token[Result](key: UploadKey[Result]): UploadToken[Result] =
      UploadToken(owner, ownerEpoch, key, ref, generation)
    def matches[Result](token: UploadToken[Result]): Boolean =
      owner == token.owner && ownerEpoch == token.ownerEpoch && ref == token.ref &&
        generation == token.generation && equivalent(definition, token.key.definition)
    def matchesUntyped(token: UploadToken[?]): Boolean =
      owner == token.owner && ownerEpoch == token.ownerEpoch && ref == token.ref &&
        generation == token.generation
    def snapshot[Result](key: UploadKey[Result]): LiveUpload[Result] =
      new LiveUpload(
        key.definition,
        ref,
        entries.map(_.snapshot[Result](definition.name)).toList,
        Nil
      )

  val empty: UploadRegistry = UploadRegistry(Vector.empty, Map.empty)

  private def equivalent(left: LiveUploadDef[?], right: LiveUploadDef[?]): Boolean =
    left.name == right.name && left.accept == right.accept && left.maxEntries == right.maxEntries &&
      left.maxFileSize == right.maxFileSize && left.chunkSize == right.chunkSize &&
      left.chunkTimeout == right.chunkTimeout && left.autoUpload == right.autoUpload &&
      sameReference(left.progress, right.progress) &&
      (left.destination.asInstanceOf[AnyRef] eq right.destination.asInstanceOf[AnyRef])

  private def sameReference(left: Option[?], right: Option[?]): Boolean = (left, right) match
    case (None, None)       => true
    case (Some(a), Some(b)) => a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]
    case _                  => false

  private def sameClient(left: UploadClientMetadata, right: UploadClientMetadata): Boolean =
    left.fileName == right.fileName && left.relativePath == right.relativePath &&
      left.sizeBytes == right.sizeBytes && left.mediaType == right.mediaType &&
      left.lastModifiedMillis == right.lastModifiedMillis && left.metadata == right.metadata

  private def validationErrors(
    definition: LiveUploadDef[?],
    client: UploadClientMetadata
  ): List[LiveUploadError] =
    val accepted = definition.accept match
      case LiveUploadAccept.Any => true
      case policy               =>
        policy.values.exists(_.exists { raw =>
          val filter = raw.toLowerCase
          val name   = client.fileName.toLowerCase
          val media  = client.mediaType.toLowerCase
          if filter.startsWith(".") then name.endsWith(filter)
          else if filter.endsWith("/*") then media.startsWith(filter.dropRight(1))
          else media == filter
        })
    List(
      Option.when(!accepted)(LiveUploadError.NotAccepted),
      Option.when(client.sizeBytes < 0L || client.sizeBytes > definition.maxFileSize)(
        LiveUploadError.TooLarge
      )
    ).flatten

  private def workerId(token: UploadEntryToken[?]): HostedWorkerId =
    HostedWorkerId(
      token.upload.owner,
      token.upload.ownerEpoch,
      token.upload.ref,
      token.ref,
      token.upload.generation
    )
end UploadRegistry
