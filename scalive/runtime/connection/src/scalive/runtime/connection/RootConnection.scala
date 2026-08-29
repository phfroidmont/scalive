package scalive.runtime.connection

import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.kernel.*
import scalive.runtime.resources.*

/** Owns one connected, unrouted root lifecycle. */
final private[scalive] class RootConnection[Msg, Model] private (
  val connectionId: ConnectionId,
  val lifecycle: LifecycleId,
  val epoch: Epoch,
  config: ConnectionConfig,
  kernel: SessionKernel[Msg, RootState[Msg, Model]],
  componentEnvironment: ConnectedComponentEnvironment[Msg, Model],
  uploadRuntime: UploadRuntime,
  uploadReplies: Ref[Map[CommandId, UploadControlReply]],
  outbound: InMemoryOutboundReservations[SessionOutput],
  writer: SerialWriter[ConnectionOutput],
  ingress: Queue[RootConnection.Event],
  ingressGate: Semaphore,
  pending: Ref[Map[CommandId, RootConnection.PendingCommand]],
  failure: Promise[Nothing, ConnectionError],
  bootstrapReady: Promise[ConnectionError, Unit],
  closing: Promise[Nothing, Unit],
  closed: Promise[Nothing, Unit],
  closeAfterNavigate: Boolean,
  observer: RuntimeObserver):
  import RootConnection.*

  def submitEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload
  ): IO[ConnectionError, Unit] =
    submitRawEvent(command, binding, payload, bindingEvent(binding, payload))

  /** Installs one event in the bounded ingress queue without waiting for its eventual reply. */
  def offerEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload
  ): IO[ConnectionError, Unit] =
    offerRawEvent(command, binding, payload, bindingEvent(binding, payload))

  private[connection] def submitRawEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    event: LiveEvent
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.Browser(command, binding, payload, event)).flatMap(_.await)

  private[scalive] def offerRawEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    event: LiveEvent
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.Browser(command, binding, payload, event)).unit

  private[scalive] def offerMessage(
    command: CommandId,
    message: Msg
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.Message(command, message)).unit

  private[connection] def submitNamedEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit] =
    submitRawEvent(command, binding, payload, namedEvent(eventName, rawJson, payload, None))

  private[scalive] def offerNamedEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit] =
    offerRawEvent(command, binding, payload, namedEvent(eventName, rawJson, payload, None))

  private[connection] def submitComponentNamedEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit] =
    submitComponentRawEvent(
      command,
      component,
      binding,
      payload,
      namedEvent(eventName, rawJson, payload, None)
    )

  private[connection] def submitComponentRawEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    event: LiveEvent
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentBrowser(command, component, binding, payload, event))
      .flatMap(_.await)

  private[scalive] def offerComponentNamedEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit] =
    offerComponentRawEvent(
      command,
      component,
      binding,
      payload,
      namedEvent(eventName, rawJson, payload, None)
    )

  private[scalive] def offerComponentRawEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    event: LiveEvent
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentBrowser(command, component, binding, payload, event)).unit

  private[connection] def submitComponentMessage[M](
    command: CommandId,
    component: ComponentInstanceId,
    message: M
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentMessage(command, component, message)).flatMap(_.await)

  private[scalive] def offerComponentMessage[M](
    command: CommandId,
    component: ComponentInstanceId,
    message: M
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentMessage(command, component, message)).unit

  private[connection] def submitComponentAsyncCompletion[M](
    command: CommandId,
    component: ComponentInstanceId,
    event: LiveAsyncEvent[M]
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentAsync(command, component, event)).flatMap(_.await)

  private[scalive] def offerComponentAsyncCompletion[M](
    command: CommandId,
    component: ComponentInstanceId,
    event: LiveAsyncEvent[M]
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentAsync(command, component, event)).unit

  private[connection] def submitComponentUpdate(
    command: CommandId,
    component: ComponentInstanceId
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentUpdate(command, component)).flatMap(_.await)

  private[scalive] def offerComponentUpdate(
    command: CommandId,
    component: ComponentInstanceId
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentUpdate(command, component)).unit

  def submitPatch(command: CommandId, destination: URL): IO[ConnectionError, Unit] =
    enqueuePatch(command, destination).flatMap(_.await)

  def offerPatch(command: CommandId, destination: URL): IO[ConnectionError, Unit] =
    enqueuePatch(command, destination).unit

  private[connection] def submitUpload[A](
    command: CommandId,
    mutation: UploadMutation[A]
  ): IO[ConnectionError, A] =
    enqueue(command, Event.Upload(command, mutation))
      .flatMap(_.await)
      .zipRight(mutation.await.mapError(ConnectionError.UploadFailed.apply))
      .onError(_ => uploadReplies.update(_ - command))

  private[connection] def mutateUpload[A](
    mutation: UploadMutation[A],
    publish: Boolean = true
  ): IO[ConnectionError, A] =
    for
      command <- ZIO
                   .fromEither(CommandId.fresh()).mapError(error =>
                     ConnectionError.KernelRejected(SessionRejection.IdentityUnavailable(error))
                   )
      _ <- kernel
             .submit(command, SessionCommand.Upload(epoch, command, mutation, publish))
             .mapError(connectionError)
      result <- mutation.await.mapError(ConnectionError.UploadFailed.apply)
    yield result

  private[connection] def preflightUpload(
    command: CommandId,
    component: Option[ComponentInstanceId],
    ref: scalive.upload.UploadRef,
    selected: Vector[(scalive.upload.UploadEntryRef, scalive.upload.UploadClientMetadata)]
  ): IO[ConnectionError, Either[UploadRegistryError, UploadPreflightView]] =
    val owner = component.fold[OwnerId](OwnerId.Root(lifecycle))(OwnerId.Component(lifecycle, _))
    for
      mutation <- UploadMutation.make(registry => runPreflight(registry, owner, ref, selected))
      result   <- submitUpload(command, mutation)
    yield result

  private[connection] def syncUploadSelection(
    component: Option[ComponentInstanceId],
    ref: scalive.upload.UploadRef,
    selected: Vector[(scalive.upload.UploadEntryRef, scalive.upload.UploadClientMetadata)]
  ): IO[ConnectionError, Either[UploadRegistryError, UploadPreflightView]] =
    val owner = component.fold[OwnerId](OwnerId.Root(lifecycle))(OwnerId.Component(lifecycle, _))
    for
      mutation <- UploadMutation.succeed { registry =>
                    registry.synchronizeSelection(owner, epoch, ref, selected) match
                      case Left(error)      => UploadMutationResult(registry, Left(error))
                      case Right(selection) =>
                        val value = selection.registry.preflightView(selection.upload)
                        UploadMutationResult(
                          selection.registry,
                          value,
                          commit = selection.retirement
                        )
                  }
      result <- mutateUpload(mutation, publish = false)
    yield result

  private def runPreflight(
    registry: UploadRegistry,
    owner: OwnerId,
    ref: scalive.upload.UploadRef,
    selected: Vector[(scalive.upload.UploadEntryRef, scalive.upload.UploadClientMetadata)]
  ): Task[UploadMutationResult[Either[UploadRegistryError, UploadPreflightView]]] =
    registry.preflight(owner, epoch, ref, selected) match
      case Left(error) =>
        ZIO.succeed(
          UploadMutationResult(
            registry,
            Left(error),
            reply = Some(UploadControlError(error.toString))
          )
        )
      case Right(preflight) =>
        ZIO
          .foldLeft(preflight.externalPreparations)(
            (preflight.registry, UploadRetirementPlan.empty, UploadRetirementPlan.empty)
          ) { case ((current, commit, rollback), plan) =>
            plan.operation.run.flatMap {
              case Left(error) =>
                ZIO
                  .fromEither(
                    current
                      .rejectExternal(plan.entry, error).left.map(value =>
                        IllegalStateException(s"upload external rejection became stale: $value")
                      )
                  )
                  .map(next => (next, commit, rollback))
              case Right(preparation) =>
                val installation = current.installExternal(preparation)
                val nextCommit   = commit ++ installation.retirement
                val nextRollback =
                  if installation.accepted then
                    rollback ++ UploadRetirementPlan(
                      Vector(UploadRetirementInstruction.Cleanup(preparation.result.cleanup))
                    )
                  else rollback
                ZIO.succeed((installation.registry, nextCommit, nextRollback))
            }
          }.map { case (next, installationCommit, rollback) =>
            next.preflightView(preflight.upload) match
              case Left(error) =>
                val value: Either[UploadRegistryError, UploadPreflightView] = Left(error)
                UploadMutationResult(
                  next,
                  value,
                  preflight.retirement ++ installationCommit,
                  rollback,
                  Some(UploadControlError(error.toString))
                )
              case Right(view) =>
                val boundedView =
                  view.copy(chunkSize = math.min(view.chunkSize, config.maxUploadChunkBytes))
                val value: Either[UploadRegistryError, UploadPreflightView] = Right(boundedView)
                UploadMutationResult(
                  next,
                  value,
                  preflight.retirement ++ installationCommit,
                  rollback,
                  Some(boundedView)
                )
          }

  private[connection] def admitUpload(
    component: Option[ComponentInstanceId],
    uploadRef: scalive.upload.UploadRef,
    entryRef: scalive.upload.UploadEntryRef,
    generation: Long
  ): IO[ConnectionError, Either[UploadAdmissionError, HostedWorkerId]] =
    val owner = component.fold[OwnerId](OwnerId.Root(lifecycle))(OwnerId.Component(lifecycle, _))
    for
      claimMutation <- UploadMutation.succeed { registry =>
                         val claimed = for
                           token <- registry.resolveEntry(owner, epoch, uploadRef, entryRef)
                           _     <- Either.cond(
                                  token.upload.generation == generation,
                                  (),
                                  UploadRegistryError.StaleAuthority
                                )
                           claim <- registry.claimHostedJoin(
                                      token.asInstanceOf[UploadEntryToken[Any]]
                                    )
                         yield claim
                         claimed match
                           case Right(claim) => UploadMutationResult(claim.registry, Right(claim))
                           case Left(error)  => UploadMutationResult(registry, Left(error))
                       }
      claimed <- mutateUpload(claimMutation)
      result  <- claimed match
                  case Left(error)  => ZIO.succeed(Left(UploadAdmissionError.Rejected(error)))
                  case Right(claim) => initializeClaimedUpload(claim)
    yield result

  private def initializeClaimedUpload(
    claim: HostedJoinClaim
  ): IO[ConnectionError, Either[UploadAdmissionError, HostedWorkerId]] =
    claim.factory.initialize.run.either.flatMap {
      case Left(_) =>
        failUploadEntry(claim.entry, "writer_error")
          .as(Left(UploadAdmissionError.WriterInitializationFailed))
      case Right(handle) =>
        uploadRuntime.reserve(handle).flatMap {
          case false =>
            handle
              .abort(LiveUploadAbortReason.Failed("registration_conflict")).ignore.as(
                Left(UploadAdmissionError.RegistrationConflict)
              )
          case true => installClaimedUpload(claim, handle)
        }
    }

  private def installClaimedUpload(
    claim: HostedJoinClaim,
    handle: HostedUploadWorker
  ): IO[ConnectionError, Either[UploadAdmissionError, HostedWorkerId]] =
    for
      mutation <- UploadMutation.succeed { registry =>
                    val installation = registry.installHostedWorker(
                      claim.entry.asInstanceOf[UploadEntryToken[Any]],
                      handle
                    )
                    UploadMutationResult(
                      installation.registry,
                      installation.accepted,
                      commit = installation.retirement
                    )
                  }
      accepted <- mutateUpload(mutation)
      result   <-
        if !accepted then
          uploadRuntime
            .removeAndRetire(
              handle.id,
              LiveUploadAbortReason.Failed("stale_join")
            ).as(Left(UploadAdmissionError.Rejected(UploadRegistryError.StaleAuthority)))
        else
          for
            worker <- uploadRuntime.startEntry(
                        handle,
                        claim.expectedBytes,
                        math.min(claim.chunkSize, config.maxUploadChunkBytes),
                        config.uploadChunkCapacity,
                        UploadWorkerCallbacks(
                          completion => completeUploadEntry(claim.entry, completion),
                          reason => failUploadEntry(claim.entry, reason).ignore
                        )
                      )
            active <- uploadRuntime.activate(worker)
            value  <-
              if active then ZIO.succeed(Right(worker.id))
              else
                worker.retire(LiveUploadAbortReason.Failed("stale_join")) *>
                  failUploadEntry(claim.entry, "stale_join").ignore.as(
                    Left(UploadAdmissionError.Rejected(UploadRegistryError.StaleAuthority))
                  )
          yield value
    yield result

  private[connection] def uploadChunk(
    worker: HostedWorkerId,
    data: Chunk[Byte]
  ): IO[UploadChunkError, Int] =
    uploadRuntime.active(worker).flatMap {
      case Some(active) => active.offer(data)
      case None         => ZIO.fail(UploadChunkError.Closed)
    }

  private[connection] def leaveUpload(worker: HostedWorkerId): IO[ConnectionError, Unit] =
    for
      mutation <-
        UploadMutation.succeed { registry =>
          registry
            .resolveEntry(worker.owner, worker.ownerEpoch, worker.uploadRef, worker.entryRef)
            .flatMap(token =>
              registry.failEntry(
                token.asInstanceOf[UploadEntryToken[Any]],
                "channel_closed"
              )
            ) match
            case Right(removal) =>
              UploadMutationResult(
                removal.registry,
                (),
                commit = removal.retirement
              )
            case Left(_) => UploadMutationResult(registry, ())
        }
      _ <- mutateUpload(mutation)
    yield ()

  private[connection] def progressUpload(
    command: CommandId,
    component: Option[ComponentInstanceId],
    uploadRef: scalive.upload.UploadRef,
    entryRef: scalive.upload.UploadEntryRef,
    progress: Int
  ): IO[ConnectionError, Either[UploadRegistryError, Unit]] =
    for
      mutation <- uploadProgressMutation(component, uploadRef, entryRef, progress)
      result   <- submitUpload(command, mutation)
    yield result

  private[connection] def syncUploadProgress(
    component: Option[ComponentInstanceId],
    uploadRef: scalive.upload.UploadRef,
    entryRef: scalive.upload.UploadEntryRef,
    progress: Int
  ): IO[ConnectionError, Either[UploadRegistryError, Unit]] =
    for
      mutation <- uploadProgressMutation(component, uploadRef, entryRef, progress)
      result   <- mutateUpload(mutation, publish = false)
    yield result

  private def uploadProgressMutation(
    component: Option[ComponentInstanceId],
    uploadRef: scalive.upload.UploadRef,
    entryRef: scalive.upload.UploadEntryRef,
    progress: Int
  ): UIO[UploadMutation[Either[UploadRegistryError, Unit]]] =
    val owner = component.fold[OwnerId](OwnerId.Root(lifecycle))(OwnerId.Component(lifecycle, _))
    def accepted(registry: UploadRegistry): Boolean =
      registry.resolveEntry(owner, epoch, uploadRef, entryRef) match
        case Left(_)      => false
        case Right(token) =>
          registry.progress(token.asInstanceOf[UploadEntryToken[Any]], progress).isRight

    UploadMutation.guardedWhen(Right(()): Either[UploadRegistryError, Unit])(accepted) { registry =>
      registry.resolveEntry(owner, epoch, uploadRef, entryRef) match
        case Left(error) =>
          ZIO.succeed(
            UploadMutationResult(
              registry,
              Left(error): Either[UploadRegistryError, Unit],
              reply = Some(UploadControlError(error.toString))
            )
          )
        case Right(token) =>
          registry.progress(token.asInstanceOf[UploadEntryToken[Any]], progress) match
            case Left(error) =>
              ZIO.succeed(
                UploadMutationResult(
                  registry,
                  Left(error): Either[UploadRegistryError, Unit],
                  reply = Some(UploadControlError(error.toString))
                )
              )
            case Right(next) =>
              val definition = token.upload.key.definition.asInstanceOf[LiveUploadDef[Any]]
              val callback   = next
                .snapshotForToken(token.upload.asInstanceOf[UploadToken[Any]])
                .toOption
                .flatMap(_.entries.find(_.ref == entryRef))
                .flatMap(entry => definition.progress.map(_.onProgress(entry)))
                .getOrElse(ZIO.unit)
              ZIO.succeed(
                UploadMutationResult(
                  next,
                  Right(()): Either[UploadRegistryError, Unit],
                  afterCommit = callback
                )
              )
    }
  end uploadProgressMutation

  private def completeUploadEntry(
    entry: UploadEntryToken[?],
    completion: HostedUploadCompletion
  ): Task[Unit] =
    (for
      mutation <- UploadMutation.succeed { registry =>
                    val installation = registry.installHostedCompletion(
                      entry.asInstanceOf[UploadEntryToken[Any]],
                      completion
                    )
                    val rollback =
                      if installation.accepted then
                        UploadRetirementPlan(
                          Vector(UploadRetirementInstruction.Cleanup(completion.cleanup))
                        )
                      else UploadRetirementPlan.empty
                    UploadMutationResult(
                      installation.registry,
                      installation.accepted,
                      commit = installation.retirement,
                      rollback = rollback
                    )
                  }
      accepted <- mutateUpload(mutation)
      _        <- uploadRuntime.forget(completion.workerId)
      _        <- ZIO.fail(IllegalStateException("stale upload completion")).unless(accepted)
    yield ()).mapError(error => error: Throwable)

  private def failUploadEntry(
    entry: UploadEntryToken[?],
    reason: String
  ): IO[ConnectionError, Unit] =
    for
      mutation <- UploadMutation.succeed { registry =>
                    registry.failEntry(entry.asInstanceOf[UploadEntryToken[Any]], reason) match
                      case Right(removal) =>
                        UploadMutationResult(
                          removal.registry,
                          (),
                          commit = removal.retirement
                        )
                      case Left(_) => UploadMutationResult(registry, ())
                  }
      _ <- mutateUpload(mutation, publish = false)
      _ <- observer.emit(
             RuntimeEvent.TurnFailed(
               RuntimeCorrelation(connectionId, lifecycle, epoch),
               RuntimeFailure.UploadEntry
             )
           )
    yield ()

  private[scalive] def offerInternalPatch(destination: URL): IO[ConnectionError, Unit] =
    for
      command <- ZIO
                   .fromEither(CommandId.fresh()).mapError(error =>
                     ConnectionError.KernelRejected(SessionRejection.IdentityUnavailable(error))
                   )
      _ <- kernel
             .enqueuePatchAcknowledgement(command, epoch, destination).unit
             .mapError(connectionError)
    yield ()

  private[scalive] def synchronizeUrl(destination: URL): IO[ConnectionError, Unit] =
    for
      command <- ZIO
                   .fromEither(CommandId.fresh()).mapError(error =>
                     ConnectionError.KernelRejected(SessionRejection.IdentityUnavailable(error))
                   )
      _ <- submitPatch(command, destination)
    yield ()

  private def bindingEvent(binding: BindingId, payload: BindingPayload): LiveEvent =
    LiveEvent(
      kind = "event",
      bindingId = binding.encoded,
      value = Json.Obj(payload.params.toSeq.map((name, value) => name -> Json.Str(value))*),
      params = payload.params,
      cid = None,
      meta = None
    )

  private def namedEvent(
    eventName: String,
    rawJson: String,
    payload: BindingPayload,
    cid: Option[Long]
  ): LiveEvent =
    LiveEvent(
      kind = "hook",
      bindingId = eventName,
      value = rawJson.fromJson[Json].getOrElse(Json.Str(rawJson)),
      params = payload.params,
      cid = cid,
      meta = None
    )

  private def enqueuePatch(
    command: CommandId,
    destination: URL
  ): IO[ConnectionError, Promise[ConnectionError, Unit]] =
    enqueue(command, Event.Patch(command, destination))

  private def enqueue(
    command: CommandId,
    event: Event
  ): IO[ConnectionError, Promise[ConnectionError, Unit]] =
    ZIO.uninterruptible {
      for
        response <- Promise.make[ConnectionError, Unit]
        _        <- ingressGate
               .withPermit {
                 closing.isDone.flatMap {
                   case true  => ZIO.fail(ConnectionError.Closed)
                   case false =>
                     pending.modify { current =>
                       val limit = event match
                         case Event.Patch(_, _) => config.ingressCapacity + 2
                         case _                 => config.ingressCapacity + 1
                       if current.contains(command) then
                         Left(ConnectionError.DuplicateCommand(command)) -> current
                       else if current.size >= limit then
                         Left(ConnectionError.IngressSaturated(config.ingressCapacity)) -> current
                       else
                         val sequence =
                           current.valuesIterator.map(_.sequence).maxOption.fold(0L)(_ + 1L)
                         Right(()) -> current.updated(command, PendingCommand(sequence, response))
                     }.absolve *> ingress.offer(event).flatMap {
                       case true  => ZIO.unit
                       case false =>
                         ZIO.fail(ConnectionError.IngressSaturated(config.ingressCapacity))
                     }
                 }
               }.tapError {
                 case error: ConnectionError.IngressSaturated => terminate(error)
                 case _                                       => ZIO.unit
               }
      yield response
    }

  def awaitFailure: UIO[ConnectionError] = failure.await

  private[scalive] def abort(error: ConnectionError): UIO[Unit] = terminate(error)

  private[scalive] def pollFailure: UIO[Option[ConnectionError]] =
    failure.poll.flatMap(ZIO.foreach(_)(identity))

  private[scalive] def awaitClosed: UIO[Unit] = closed.await

  private[connection] def inspectModel: IO[ConnectionError, Model] =
    kernel.inspect.map(_.model.model).mapError(connectionError)

  private[scalive] def inspectTree: IO[ConnectionError, EvaluatedTree] =
    kernel.inspect.map(_.render.tree).mapError(connectionError)

  private[connection] def inspectFlash: IO[ConnectionError, Map[FlashKind, String]] =
    kernel.inspect.map(_.model.flash).mapError(connectionError)

  private[connection] def inspectComponentIds: IO[ConnectionError, Vector[ComponentInstanceId]] =
    kernel.inspect.map(_.components.values.map(_.id)).mapError(connectionError)

  private[connection] def inspectComponentModel[A](
    component: ComponentInstanceId
  ): IO[ConnectionError, Option[A]] =
    kernel.inspect
      .map(_.components.get(component).map(_.model.asInstanceOf[A])).mapError(connectionError)

  private[connection] def inspectComponentProps[A](
    component: ComponentInstanceId
  ): IO[ConnectionError, Option[A]] =
    kernel.inspect
      .map(_.components.get(component).map(_.props.asInstanceOf[A])).mapError(connectionError)

  private[connection] def inspectComponentTree(
    component: ComponentInstanceId
  ): IO[ConnectionError, Option[EvaluatedTree]] =
    kernel.inspect.map(_.components.get(component).map(_.render.tree)).mapError(connectionError)

  private[scalive] def componentForToken(
    token: Object
  ): IO[ConnectionError, Option[ComponentInstanceId]] =
    kernel.inspect
      .map(
        _.components.values.find(component => component.ref.asInstanceOf[AnyRef] eq token).map(_.id)
      ).mapError(connectionError)

  private[connection] def destroyComponents(tokens: Vector[Object]): IO[ConnectionError, Unit] =
    kernel.destroyComponents(tokens).mapError(connectionError)

  private[connection] def componentWasClosed(component: ComponentInstanceId): UIO[Boolean] =
    componentEnvironment.wasClosed(component)

  private[connection] def componentWasDiscarded(component: ComponentInstanceId): UIO[Boolean] =
    componentEnvironment.wasDiscarded(component)

  private[connection] def submitInfo(message: Msg): IO[ConnectionError, Unit] =
    kernel.submit(SessionCommand.Message(epoch, message)).unit.mapError(connectionError)

  private[connection] def submitAsyncCompletion(event: LiveAsyncEvent[Msg])
    : IO[ConnectionError, Unit] =
    kernel.submit(SessionCommand.AsyncCompletion(epoch, event)).unit.mapError(connectionError)

  private[connection] def ingressDepth: UIO[Int] = ingress.size

  private[connection] def pendingCount: UIO[Int] = pending.get.map(_.size)

  private[connection] def isClosing: UIO[Boolean] = closing.isDone

  /** Control-plane shutdown; it never waits for space in an application queue. */
  def close: UIO[Unit] =
    ZIO.uninterruptibleMask { restore =>
      closing.succeed(()).flatMap {
        case false => restore(closed.await)
        case true  =>
          for
            cleanup <- RuntimeCleanup
                         .all(
                           Vector(
                             ingressGate.withPermit {
                               for
                                 queued <- ingress.takeAll
                                 _      <- ZIO.foreachDiscard(queued)(event =>
                                        complete(event.id, Left(ConnectionError.Closed))
                                      )
                                 _ <- ingress.shutdown
                               yield ()
                             },
                             kernel.close,
                             outbound.shutdown,
                             writer.close,
                             failPending(ConnectionError.Closed)
                           )
                         ).exit
            _ <- closed.succeed(())
            _ <- cleanup match
                   case Exit.Success(_)     => ZIO.unit
                   case Exit.Failure(cause) => ZIO.failCause(cause)
          yield ()
      }
    }

  private def runIngress: URIO[Scope, Unit] =
    ingress.take
      .flatMap { event =>
        val command                    = event.id
        val input: SessionCommand[Msg] = event match
          case Event.Browser(_, binding, payload, rawEvent) =>
            SessionCommand.ClientEvent(epoch, binding, payload, Some(rawEvent))
          case Event.ComponentBrowser(_, component, binding, payload, rawEvent) =>
            SessionCommand.ComponentClientEvent(
              epoch,
              component,
              binding,
              payload,
              Some(rawEvent)
            )
          case Event.Message(_, message) =>
            SessionCommand.Message(epoch, message.asInstanceOf[Msg])
          case Event.ComponentMessage(_, component, message) =>
            SessionCommand.ComponentMessage(epoch, component, message)
          case Event.ComponentAsync(_, component, event) =>
            SessionCommand.ComponentAsyncCompletion(
              epoch,
              component,
              event.asInstanceOf[LiveAsyncEvent[Any]]
            )
          case Event.ComponentUpdate(_, component) =>
            SessionCommand.ComponentUpdate(epoch, component)
          case Event.Upload(command, mutation) =>
            SessionCommand.Upload(epoch, command, mutation)
          case Event.Patch(_, destination) => SessionCommand.ParamsPatch(epoch, destination)
        kernel
          .enqueue(command, input).foldZIO(
            rejection => reject(command, rejection),
            await =>
              await
                .foldZIO(rejection => reject(command, rejection), _ => awaitCompletion(command))
                .forkScoped.unit
          ) *> runIngress
      }.catchAllCause { cause =>
        if cause.isInterruptedOnly then ZIO.unit
        else
          closing.isDone.flatMap {
            case true  => ZIO.unit
            case false => terminate(ConnectionError.IngressFailed)
          }
      }

  private def reject(command: CommandId, rejection: SessionRejection): UIO[Unit] =
    observer.reject(
      RuntimeCorrelation(connectionId, lifecycle, epoch, command = Some(command)),
      s"Runtime command rejected: ${rejection.getClass.getSimpleName}"
    ) *> closing.isDone.flatMap {
      case true  => complete(command, Left(ConnectionError.Closed))
      case false =>
        rejection match
          case _: SessionRejection.UnknownBinding | _: SessionRejection.BindingFailed |
              _: SessionRejection.UnknownComponent | _: SessionRejection.StaleComponent |
              SessionRejection.UnknownComponentTarget | _: SessionRejection.AmbiguousComponent =>
            awaitEarlierCommands(command) *> writer
              .send(ConnectionOutput.Rejected(command, rejection)).foldZIO(
                error => completeAfterWriterClose(command, error),
                _ => complete(command, Right(()))
              )
          case SessionRejection.SessionFailed(sessionFailure) =>
            terminate(ConnectionError.SessionFailed(sessionFailure))
          case _: SessionRejection.Terminal =>
            complete(command, Left(ConnectionError.Closed))
          case other => terminate(ConnectionError.KernelRejected(other))
    }

  private def awaitEarlierCommands(command: CommandId): UIO[Unit] =
    pending.get.flatMap { current =>
      current.get(command) match
        case None                 => ZIO.unit
        case Some(currentCommand) =>
          ZIO.foreachDiscard(
            current.valuesIterator.filter(_.sequence < currentCommand.sequence).toVector
          )(_.response.await.ignore)
    }

  private def runOutbound(first: Boolean): UIO[Unit] =
    outbound.take.foldZIO(
      error =>
        closing.isDone.flatMap {
          case true  => ZIO.unit
          case false => terminate(ConnectionError.OutboundFailed(error.toString))
        },
      batch => writeBatch(batch.items, first).flatMap(runOutbound)
    )

  private def writeBatch(outputs: Vector[SessionOutput], first: Boolean): UIO[Boolean] =
    outputs.foldLeft[UIO[Boolean]](ZIO.succeed(first)) { (state, output) =>
      state.flatMap { isFirst =>
        pending.get.flatMap { pendingCommands =>
          uploadReplies
            .modify { current =>
              val reply = output.command.flatMap(current.get)
              reply -> output.command.fold(current)(current - _)
            }.flatMap { uploadReply =>
              val correlatedCommand = output.command.filter(pendingCommands.contains)
              val connectionOutput  = output.termination match
                case Some(SessionTermination.Disconnect(reason)) =>
                  correlatedCommand.fold[ConnectionOutput](ConnectionOutput.Disconnect(reason))(
                    ConnectionOutput.ReplyDisconnect(_, reason)
                  )
                case None =>
                  if isFirst then
                    output.navigation.fold[ConnectionOutput](
                      ConnectionOutput.Joined(output.delta, output.effects)
                    )(
                      ConnectionOutput.JoinedNavigation(output.delta, _, output.effects)
                    )
                  else
                    (correlatedCommand, output.navigation) match
                      case (Some(command), Some(navigation)) =>
                        output.reply.fold[ConnectionOutput](
                          ConnectionOutput.ReplyNavigation(
                            command,
                            output.delta,
                            navigation,
                            output.effects
                          )
                        )(reply =>
                          ConnectionOutput.ReplyNavigationWithPayload(
                            command,
                            output.delta,
                            navigation,
                            output.effects,
                            reply
                          )
                        )
                      case (Some(command), None) =>
                        uploadReply.fold[ConnectionOutput](
                          output.reply.fold[ConnectionOutput](
                            ConnectionOutput.Reply(command, output.delta, output.effects)
                          )(reply =>
                            ConnectionOutput.ReplyWithPayload(
                              command,
                              output.delta,
                              output.effects,
                              reply
                            )
                          )
                        )(reply =>
                          ConnectionOutput.UploadReply(command, output.delta, output.effects, reply)
                        )
                      case (None, Some(navigation)) =>
                        ConnectionOutput.DiffNavigation(output.delta, navigation, output.effects)
                      case (None, None) => ConnectionOutput.Diff(output.delta, output.effects)

              val ordered = connectionOutput match
                case ConnectionOutput.Reply(command, _, _) => awaitEarlierCommands(command)
                case ConnectionOutput.ReplyWithPayload(command, _, _, _) =>
                  awaitEarlierCommands(command)
                case ConnectionOutput.UploadReply(command, _, _, _) =>
                  awaitEarlierCommands(command)
                case ConnectionOutput.ReplyNavigation(command, _, _, _) =>
                  awaitEarlierCommands(command)
                case ConnectionOutput.ReplyNavigationWithPayload(command, _, _, _, _) =>
                  awaitEarlierCommands(command)
                case ConnectionOutput.ReplyDisconnect(command, _) => awaitEarlierCommands(command)
                case _                                            => ZIO.unit

              observer.bindOutput(output, connectionOutput)
              ordered *> writer
                .send(connectionOutput).foldZIO(
                  error =>
                    closing.isDone
                      .flatMap {
                        case false => terminate(writerFailure(error))
                        case true  =>
                          connectionOutput match
                            case ConnectionOutput.Reply(command, _, _) =>
                              complete(command, Left(ConnectionError.Closed))
                            case ConnectionOutput.ReplyWithPayload(command, _, _, _) =>
                              complete(command, Left(ConnectionError.Closed))
                            case ConnectionOutput.UploadReply(command, _, _, _) =>
                              complete(command, Left(ConnectionError.Closed))
                            case ConnectionOutput.ReplyNavigation(command, _, _, _) =>
                              complete(command, Left(ConnectionError.Closed))
                            case ConnectionOutput.ReplyNavigationWithPayload(command, _, _, _, _) =>
                              complete(command, Left(ConnectionError.Closed))
                            case ConnectionOutput.ReplyDisconnect(command, _) =>
                              complete(command, Left(ConnectionError.Closed))
                            case ConnectionOutput.Rejected(command, _) =>
                              complete(command, Left(ConnectionError.Closed))
                            case ConnectionOutput.Joined(_, _) |
                                ConnectionOutput.JoinedNavigation(_, _, _) =>
                              bootstrapReady.fail(ConnectionError.Closed).unit
                            case ConnectionOutput.Diff(_, _) |
                                ConnectionOutput.DiffNavigation(_, _, _) |
                                ConnectionOutput.Disconnect(_) =>
                              ZIO.unit
                      }.as(false),
                  _ =>
                    val signal = connectionOutput match
                      case ConnectionOutput.Joined(_, _) |
                          ConnectionOutput.JoinedNavigation(_, _, _) =>
                        bootstrapReady.succeed(()).unit
                      case ConnectionOutput.Reply(command, _, _) => complete(command, Right(()))
                      case ConnectionOutput.ReplyWithPayload(command, _, _, _) =>
                        complete(command, Right(()))
                      case ConnectionOutput.UploadReply(command, _, _, _) =>
                        complete(command, Right(()))
                      case ConnectionOutput.ReplyNavigation(command, _, _, _) =>
                        complete(command, Right(()))
                      case ConnectionOutput.ReplyNavigationWithPayload(command, _, _, _, _) =>
                        complete(command, Right(()))
                      case ConnectionOutput.ReplyDisconnect(command, _) =>
                        complete(command, Right(()))
                      case ConnectionOutput.Rejected(command, _) => complete(command, Right(()))
                      case ConnectionOutput.Diff(_, _) | ConnectionOutput.DiffNavigation(_, _, _) |
                          ConnectionOutput.Disconnect(_) =>
                        ZIO.unit
                    val finish = connectionOutput match
                      case ConnectionOutput.JoinedNavigation(_, navigation, _)
                          if closeAfterNavigate && !navigation.kind.isPatch =>
                        kernel.awaitTermination.unit *> close
                      case ConnectionOutput.ReplyNavigation(_, _, navigation, _)
                          if closeAfterNavigate && !navigation.kind.isPatch =>
                        kernel.awaitTermination.unit *> close
                      case ConnectionOutput.ReplyNavigationWithPayload(_, _, navigation, _, _)
                          if closeAfterNavigate && !navigation.kind.isPatch =>
                        kernel.awaitTermination.unit *> close
                      case ConnectionOutput.DiffNavigation(_, navigation, _)
                          if closeAfterNavigate && !navigation.kind.isPatch =>
                        kernel.awaitTermination.unit *> close
                      case ConnectionOutput.ReplyDisconnect(_, _) |
                          ConnectionOutput.Disconnect(_) =>
                        kernel.awaitTermination.unit *> close
                      case _ => ZIO.unit
                    (signal *> finish).as(false)
                )
            }
        }
      }
    }

  private def connectionError(rejection: SessionRejection): ConnectionError = rejection match
    case SessionRejection.SessionFailed(value) => ConnectionError.SessionFailed(value)
    case _: SessionRejection.Terminal          => ConnectionError.Closed
    case other                                 => ConnectionError.KernelRejected(other)

  private def monitorKernel: UIO[Unit] =
    kernel.awaitTermination.flatMap {
      case SessionState.Crashed(_, sessionFailure) =>
        terminate(ConnectionError.SessionFailed(sessionFailure))
      case _ => ZIO.unit
    }

  private def terminate(error: ConnectionError): UIO[Unit] =
    observer.emit(
      RuntimeEvent.TurnFailed(
        RuntimeCorrelation(connectionId, lifecycle, epoch),
        runtimeFailure(error)
      )
    ) *> failure.succeed(error).flatMap {
      case true =>
        bootstrapReady.fail(error).unit *> failPending(error) *> close
      case false => ZIO.unit
    }

  private def complete(command: CommandId, result: Either[ConnectionError, Unit]): UIO[Unit] =
    pending.get.flatMap(_.get(command) match
      case Some(pendingCommand) =>
        pendingCommand.response.done(Exit.fromEither(result)).unit *> pending.update(_ - command)
      case None => ZIO.unit)

  private def failPending(error: ConnectionError): UIO[Unit] =
    uploadReplies.set(Map.empty) *> pending
      .getAndSet(Map.empty).flatMap(values =>
        ZIO.foreachDiscard(values.values)(_.response.fail(error).unit)
      )

  private def awaitCompletion(command: CommandId): UIO[Unit] =
    pending.get.flatMap(
      _.get(command).fold[UIO[Unit]](ZIO.unit)(_.response.await.ignore)
    )

  private def writerFailure(error: SerialWriter.Error): ConnectionError = error match
    case SerialWriter.Error.WriteFailed(cause) => ConnectionError.SinkFailed(cause)
    case other                                 => ConnectionError.OutboundFailed(other.toString)

  private def runtimeFailure(error: ConnectionError): RuntimeFailure = error match
    case _: ConnectionError.IngressSaturated => RuntimeFailure.IngressSaturated
    case ConnectionError.IngressFailed       => RuntimeFailure.RuntimeDefect
    case _: ConnectionError.SinkFailed       => RuntimeFailure.Writer
    case _: ConnectionError.UploadFailed     => RuntimeFailure.UploadEntry
    case _: ConnectionError.SessionFailed    => RuntimeFailure.RuntimeDefect
    case _                                   => RuntimeFailure.RuntimeDefect

  private def completeAfterWriterClose(
    command: CommandId,
    error: SerialWriter.Error
  ): UIO[Unit] =
    closing.isDone.flatMap {
      case true  => complete(command, Left(ConnectionError.Closed))
      case false => terminate(writerFailure(error))
    }
end RootConnection

private[scalive] object RootConnection:
  final private case class PendingCommand(
    sequence: Long,
    response: Promise[ConnectionError, Unit])

  private enum Event:
    case Browser(
      command: CommandId,
      binding: BindingId,
      payload: BindingPayload,
      event: LiveEvent)
    case ComponentBrowser(
      command: CommandId,
      component: ComponentInstanceId,
      binding: BindingId,
      payload: BindingPayload,
      event: LiveEvent)
    case Message(command: CommandId, message: Any)
    case ComponentMessage(command: CommandId, component: ComponentInstanceId, message: Any)
    case ComponentAsync(
      command: CommandId,
      component: ComponentInstanceId,
      event: LiveAsyncEvent[?])
    case ComponentUpdate(command: CommandId, component: ComponentInstanceId)
    case Upload(command: CommandId, mutation: UploadMutation[?])
    case Patch(command: CommandId, destination: URL)

    def id: CommandId = this match
      case Browser(command, _, _, _)             => command
      case ComponentBrowser(command, _, _, _, _) => command
      case Message(command, _)                   => command
      case ComponentMessage(command, _, _)       => command
      case ComponentAsync(command, _, _)         => command
      case ComponentUpdate(command, _)           => command
      case Upload(command, _)                    => command
      case Patch(command, _)                     => command
  end Event

  def start[Msg, Model](
    config: ConnectionConfig,
    metadata: RootConnectionMetadata,
    liveView: LiveView[Msg, Model],
    sink: ConnectionOutput => Task[Unit],
    topologyPreparer: NestedTopologyPreparer = NestedTopologyPreparer.unavailable,
    ownsPageTitle: Boolean = true,
    requestedLifecycle: Option[LifecycleId] = None,
    providedConnection: Option[ConnectionId] = None,
    observer: RuntimeObserver = RuntimeObserver.noop,
    closeAfterNavigate: Boolean = true,
    topic: Option[NestedTopic] = None
  ): ZIO[Scope, ConnectionError, RootConnection[Msg, Model]] =
    startLifecycle(
      config,
      metadata,
      RootLifecycle.ordinary(liveView),
      sink,
      topologyPreparer,
      ownsPageTitle,
      requestedLifecycle,
      providedConnection,
      observer,
      closeAfterNavigate,
      topic
    )

  def startLifecycle[Msg, Model](
    config: ConnectionConfig,
    metadata: RootConnectionMetadata,
    lifecycle: RootLifecycle[Msg, Model],
    sink: ConnectionOutput => Task[Unit],
    topologyPreparer: NestedTopologyPreparer = NestedTopologyPreparer.unavailable,
    ownsPageTitle: Boolean = true,
    requestedLifecycle: Option[LifecycleId] = None,
    providedConnection: Option[ConnectionId] = None,
    observer: RuntimeObserver = RuntimeObserver.noop,
    closeAfterNavigate: Boolean = true,
    topic: Option[NestedTopic] = None
  ): ZIO[Scope, ConnectionError, RootConnection[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        connectionId <- providedConnection.fold(
                          ZIO
                            .fromEither(ConnectionId.fresh()).mapError(error =>
                              ConnectionError.SessionFailed(
                                SessionFailure.StageFailed(SessionStage.Identity, error.toString)
                              )
                            )
                        )(ZIO.succeed(_))
        lifecycleId <- requestedLifecycle.fold(
                         ZIO
                           .fromEither(LifecycleId.fresh()).mapError(error =>
                             ConnectionError.SessionFailed(
                               SessionFailure.StageFailed(SessionStage.Identity, error.toString)
                             )
                           )
                       )(ZIO.succeed(_))
        _ <- ZIO.succeed {
               topic.foreach(value =>
                 observer.registerLifecycle(
                   lifecycleId,
                   value.value,
                   _.asInstanceOf[RootState[Msg, Model]].model
                 )
               )
             }
        _ <- ZIO.addFinalizer(ZIO.succeed(observer.unregisterLifecycle(lifecycleId)))
        rootOwner = OwnerId.Root(lifecycleId)
        program <- ZIO.acquireRelease(
                     ZIO
                       .fromEither(
                         RenderProgram.compile[RootState[Msg, Model], Msg](
                           signal => lifecycle.view(signal.map(state => state.model -> state.url)),
                           _.flash
                         )
                       )
                       .mapError(ConnectionError.RenderCompilationFailed.apply)
                   )(_.close)
        sessionConfig <- ZIO
                           .fromEither(
                             SessionConfig.make(
                               config.kernelMailboxCapacity,
                               config.continuationCapacity
                             )
                           ).mapError(error => ConnectionError.OutboundFailed(error.toString))
        outbound <- InMemoryOutboundReservations
                      .make[SessionOutput](config.outboundReservationCapacity)
                      .mapError(error => ConnectionError.OutboundFailed(error.toString))
        writer <- SerialWriter
                    .make[ConnectionOutput](config.writerCapacity)(sink)
                    .mapError(error => ConnectionError.OutboundFailed(error.toString))
        uploadRuntime        <- UploadRuntime.make
        uploadReplies        <- Ref.make(Map.empty[CommandId, UploadControlReply])
        componentEnvironment <- ConnectedComponentEnvironment.make[Msg, Model](
                                  metadata,
                                  lifecycleId,
                                  Epoch.initial
                                )
        initialHooks = RootHookRegistry.fromStatic(lifecycle.hooks)
        logic        = SessionLogic[Msg, RootState[Msg, Model]](
                  bootstrap =
                    for
                      journal <- RootTurnJournal.make(
                                   rootOwner,
                                   initialHooks,
                                   metadata.initialFlash,
                                   ownerEpoch = Epoch.initial
                                 )
                      mountContext = RootMountContext.connected[Msg, Model](
                                       metadata,
                                       lifecycle.initialUrl,
                                       journal
                                     )
                      mounted         <- ZIO.suspend(lifecycle.mount(mountContext))
                      mountNavigation <- journal.navigation.get
                      model           <- mountNavigation match
                                 case Some(_) => ZIO.succeed(mounted)
                                 case None    =>
                                   val paramsContext = RootParamsContext[Msg, Model](
                                     metadata,
                                     lifecycle.initialUrl,
                                     journal,
                                     connected = true
                                   )
                                   for
                                     prepared <- lifecycle.prepareParams(lifecycle.initialUrl)
                                     registry <- journal.hookRegistry[Msg, Model]
                                     hooked   <-
                                       if prepared.runHooks then
                                         runParamsHooks(
                                           registry,
                                           mounted,
                                           lifecycle.initialUrl,
                                           paramsContext
                                         )
                                       else ZIO.succeed(Hooked.Continue(mounted))
                                     result <- hooked match
                                                 case Hooked.Halt(value)     => ZIO.succeed(value)
                                                 case Hooked.Continue(value) =>
                                                   ZIO.suspend(prepared.run(value, paramsContext))
                                   yield result
                      navigation         <- journal.navigationWithFlash
                      hooks              <- journal.hookRegistry[Msg, Model]
                      flash              <- journal.flash.get
                      clientEvents       <- journal.clientEvents.get
                      componentUpdates   <- journal.componentUpdates.get
                      resourceOperations <- journal.resourceOperationSnapshot
                      streams            <- journal.streamSnapshot
                      uploadState        <- journal.uploadSnapshot
                      (uploads, uploadCommit, uploadRollback) = uploadState
                      pageTitle                               = lifecycle.pageTitle(model)
                    yield TurnDraft(
                      RootState(
                        model,
                        lifecycle.initialUrl,
                        hooks,
                        flash,
                        pageTitle,
                        streams,
                        uploads
                      ),
                      url = Some(lifecycle.initialUrl),
                      navigation = navigation,
                      effects =
                        SessionEffects(Option.when(ownsPageTitle)(pageTitle).flatten, clientEvents),
                      componentUpdates = componentUpdates,
                      resourceOperations = resourceOperations,
                      uploadCommit = uploadCommit,
                      uploadRollback = uploadRollback
                    ),
                  handle = (state, message) =>
                    for
                      journal <- rootJournal(rootOwner, state, TurnDraft(state))
                      context = RootMessageContext[Msg, Model](metadata, state.url, journal)
                      model <- ZIO.suspend(lifecycle.handleMessage(state.model, context, message))
                      draft <- rootDraft(state, lifecycle, journal, model, state.url, ownsPageTitle)
                    yield draft,
                  guardConnectedTurn = lifecycle.connectedTurnGuard.run(()).either,
                  handleEvent = Some((draft, message, _) =>
                    runEventTurn(
                      draft,
                      metadata,
                      lifecycle,
                      rootOwner,
                      message,
                      ownsPageTitle
                    )
                  ),
                  handleInfo = Some((state, message) =>
                    runMessageTurn(
                      state,
                      metadata,
                      lifecycle,
                      rootOwner,
                      message,
                      _.info
                    )
                  ),
                  handleAsync = Some((state, event) =>
                    runAsyncTurn(
                      state,
                      metadata,
                      lifecycle,
                      rootOwner,
                      event,
                      None
                    )
                  ),
                  handleManagedAsync = Some((state, event, message) =>
                    runAsyncTurn(
                      state,
                      metadata,
                      lifecycle,
                      rootOwner,
                      event,
                      Some(message)
                    )
                  ),
                  handleUpload = Some((state, command, mutation) =>
                    mutation.execute(state.uploads).flatMap { result =>
                      ZIO.foreachDiscard(result.reply)(reply =>
                        uploadReplies.update(_.updated(command, reply))
                      ) *>
                        ZIO.succeed(
                          TurnDraft(
                            state.copy(uploads = result.registry),
                            uploadCommit = result.commit,
                            uploadRollback = result.rollback,
                            uploadCompletion = mutation.complete(result)
                          )
                        )
                    }
                  ),
                  guardUpload =
                    (state, mutation) => mutation.requiresConnectedTurnGuard(state.uploads),
                  interceptClientEvent = (state, event) =>
                    interceptRootEvent(
                      state,
                      event,
                      metadata,
                      lifecycle,
                      rootOwner,
                      ownsPageTitle
                    ),
                  handleParams = (state, destination) =>
                    for
                      journal <- rootJournal(rootOwner, state, TurnDraft(state))
                      context = RootParamsContext[Msg, Model](
                                  metadata,
                                  destination,
                                  journal,
                                  connected = true
                                )
                      prepared <- lifecycle.prepareParams(destination)
                      hooked   <-
                        if prepared.runHooks then
                          runParamsHooks(state.hooks, state.model, destination, context)
                        else ZIO.succeed(Hooked.Continue(state.model))
                      model <- hooked match
                                 case Hooked.Halt(value)     => ZIO.succeed(value)
                                 case Hooked.Continue(value) =>
                                   ZIO.suspend(prepared.run(value, context))
                      draft <- rootDraft(
                                 state,
                                 lifecycle,
                                 journal,
                                 model,
                                 destination,
                                 ownsPageTitle
                               )
                    yield draft,
                  afterRender = draft =>
                    for
                      journal <- RootTurnJournal.make(
                                   rootOwner,
                                   draft.model.hooks,
                                   draft.model.flash,
                                   draft.effects.clientEvents,
                                   draft.componentUpdates,
                                   draft.navigation,
                                   draft.resourceOperations,
                                   draft.model.streams,
                                   ownerEpoch = Epoch.initial,
                                   initialUploads = draft.model.uploads,
                                   initialUploadCommit = draft.uploadCommit,
                                   initialUploadRollback = draft.uploadRollback
                                 )
                      context = RootAfterRenderContext[Msg, Model](metadata, journal)
                      _ <- ZIO.foreachDiscard(draft.model.hooks.afterRender)(
                             _.invoke(draft.model.model, context)
                           )
                      hooks              <- journal.hookRegistry[Msg, Model]
                      flash              <- journal.flash.get
                      clientEvents       <- journal.clientEvents.get
                      componentUpdates   <- journal.componentUpdates.get
                      resourceOperations <- journal.resourceOperationSnapshot
                      uploadState        <- journal.uploadSnapshot
                      (uploads, uploadCommit, uploadRollback) = uploadState
                    yield draft.copy(
                      model = draft.model.copy(hooks = hooks, flash = flash, uploads = uploads),
                      effects = draft.effects.copy(clientEvents = clientEvents),
                      componentUpdates = componentUpdates,
                      resourceOperations = resourceOperations,
                      uploadCommit = uploadCommit,
                      uploadRollback = uploadRollback
                    ),
                  validateStreams =
                    (state, requirements) => ZIO.attempt(state.streams.validate(requirements)),
                  reconcileUploads = (draft, activeComponents) =>
                    ZIO.succeed {
                      val removal = draft.model.uploads.retireMissingComponents(
                        lifecycleId,
                        activeComponents
                      )
                      draft.copy(
                        model = draft.model.copy(uploads = removal.registry),
                        uploadCommit = draft.uploadCommit ++ removal.retirement
                      )
                    },
                  retireUploads = uploadRuntime.retire,
                  closeUploads = state => uploadRuntime.close(state.uploads, lifecycleId),
                  terminateOnNavigate = closeAfterNavigate
                )
        kernel <- SessionKernel
                    .start(
                      sessionConfig,
                      logic,
                      program,
                      outbound,
                      componentEnvironment,
                      Some(lifecycleId),
                      topologyPreparer,
                      Some(connectionId),
                      observer
                    )
                    .mapError(ConnectionError.SessionFailed.apply)
        ingress        <- Queue.dropping[Event](config.ingressCapacity)
        ingressGate    <- Semaphore.make(1L)
        pending        <- Ref.make(Map.empty[CommandId, PendingCommand])
        failure        <- Promise.make[Nothing, ConnectionError]
        bootstrapReady <- Promise.make[ConnectionError, Unit]
        closing        <- Promise.make[Nothing, Unit]
        closed         <- Promise.make[Nothing, Unit]
        connection = RootConnection(
                       connectionId,
                       kernel.lifecycle,
                       kernel.epoch,
                       config,
                       kernel,
                       componentEnvironment,
                       uploadRuntime,
                       uploadReplies,
                       outbound,
                       writer,
                       ingress,
                       ingressGate,
                       pending,
                       failure,
                       bootstrapReady,
                       closing,
                       closed,
                       closeAfterNavigate,
                       observer
                     )
        _ <- connection.runOutbound(first = true).interruptible.forkScoped
        _ <- connection.runIngress.interruptible.forkScoped
        _ <- connection.monitorKernel.interruptible.forkScoped
        _ <- ZIO.addFinalizer(connection.close)
        _ <- restore(bootstrapReady.await).onError(_ => connection.close)
      yield connection
    }

  private enum Hooked[+A]:
    case Continue(value: A)
    case Halt(value: A)

  private def titleChange(
    previous: Option[String],
    current: Option[String]
  ): Option[String] = Option.when(previous != current)(current.getOrElse(""))

  private def interceptRootEvent[Msg, Model](
    state: RootState[Msg, Model],
    event: LiveEvent,
    metadata: RootConnectionMetadata,
    lifecycle: RootLifecycle[Msg, Model],
    owner: OwnerId,
    ownsPageTitle: Boolean
  ): Task[ClientEventInterception[Msg, RootState[Msg, Model]]] =
    for
      journal <- rootJournal(owner, state, TurnDraft(state))
      context = RootMessageContext[Msg, Model](metadata, state.url, journal)
      rawResult <- runRawHooks(state.hooks.raw, state.model, event, context)
      result    <- rawResult match
                  case halt: LiveEventHookResult.Halt[Model] => ZIO.succeed(halt)
                  case LiveEventHookResult.Continue(model)   =>
                    val matching =
                      if event.cid.isEmpty then
                        state.hooks.browser.filter(_.name == event.bindingId)
                      else Vector.empty
                    if matching.isEmpty then ZIO.succeed(LiveEventHookResult.cont(model))
                    else
                      runBrowserHooks(matching, model, event.value.toJson, context)
                        .map(LiveEventHookResult.halt(_))
      draft <- rootDraft(
                 state,
                 lifecycle,
                 journal,
                 result match
                   case LiveEventHookResult.Continue(model) => model
                   case LiveEventHookResult.Halt(model, _)  => model,
                 state.url,
                 ownsPageTitle,
                 result match
                   case LiveEventHookResult.Halt(_, reply) => reply
                   case _                                  => None
               )
    yield result match
      case _: LiveEventHookResult.Halt[Model] => ClientEventInterception.Halt(draft)
      case _                                  => ClientEventInterception.Continue(draft)

  private def runEventTurn[Msg, Model](
    initial: TurnDraft[Msg, RootState[Msg, Model]],
    metadata: RootConnectionMetadata,
    lifecycle: RootLifecycle[Msg, Model],
    owner: OwnerId,
    message: Msg,
    ownsPageTitle: Boolean
  ): Task[TurnDraft[Msg, RootState[Msg, Model]]] =
    val state = initial.model
    for
      journal <- rootJournal(owner, state, initial)
      context = RootMessageContext[Msg, Model](metadata, state.url, journal)
      hooked <- runEventHooks(state.hooks.event, state.model, message, context)
      model  <- hooked match
                 case Hooked.Halt(value)     => ZIO.succeed(value)
                 case Hooked.Continue(value) =>
                   ZIO.suspend(lifecycle.handleMessage(value, context, message))
      draft <- rootDraft(
                 state,
                 lifecycle,
                 journal,
                 model,
                 state.url,
                 ownsPageTitle = ownsPageTitle,
                 reply = initial.reply
               )
    yield draft

  private def rootJournal[Msg, Model](
    owner: OwnerId,
    state: RootState[Msg, Model],
    initial: TurnDraft[Msg, RootState[Msg, Model]]
  ): Task[RootTurnJournal] =
    RootTurnJournal.make(
      owner,
      state.hooks,
      state.flash,
      initial.effects.clientEvents,
      initial.componentUpdates,
      initial.navigation,
      initial.resourceOperations,
      state.streams,
      ownerEpoch = Epoch.initial,
      initialUploads = state.uploads,
      initialUploadCommit = initial.uploadCommit,
      initialUploadRollback = initial.uploadRollback
    )

  private def rootDraft[Msg, Model](
    state: RootState[Msg, Model],
    lifecycle: RootLifecycle[Msg, Model],
    journal: RootTurnJournal,
    model: Model,
    url: URL,
    ownsPageTitle: Boolean,
    reply: Option[Json] = None
  ): UIO[TurnDraft[Msg, RootState[Msg, Model]]] =
    for
      navigation         <- journal.navigationWithFlash
      hooks              <- journal.hookRegistry[Msg, Model]
      flash              <- journal.flash.get
      clientEvents       <- journal.clientEvents.get
      componentUpdates   <- journal.componentUpdates.get
      resourceOperations <- journal.resourceOperationSnapshot
      streams            <- journal.streamSnapshot
      uploadState        <- journal.uploadSnapshot
      (uploads, uploadCommit, uploadRollback) = uploadState
      pageTitle                               = lifecycle.pageTitle(model)
    yield TurnDraft(
      RootState(model, url, hooks, flash, pageTitle, streams, uploads),
      url = Some(url),
      navigation = navigation,
      effects = SessionEffects(
        Option.when(ownsPageTitle)(titleChange(state.pageTitle, pageTitle)).flatten,
        clientEvents
      ),
      componentUpdates = componentUpdates,
      resourceOperations = resourceOperations,
      uploadCommit = uploadCommit,
      uploadRollback = uploadRollback,
      reply = reply
    )

  private def runMessageTurn[Msg, Model](
    state: RootState[Msg, Model],
    metadata: RootConnectionMetadata,
    lifecycle: RootLifecycle[Msg, Model],
    owner: OwnerId,
    message: Msg,
    select: RootHookRegistry[Msg, Model] => Vector[RootHookRegistry.Event[Msg, Model]]
  ): Task[TurnDraft[Msg, RootState[Msg, Model]]] =
    for
      journal <- rootJournal(owner, state, TurnDraft(state))
      context = RootMessageContext[Msg, Model](metadata, state.url, journal)
      hooked <- runEventHooks(select(state.hooks), state.model, message, context)
      model  <- hooked match
                 case Hooked.Halt(value)     => ZIO.succeed(value)
                 case Hooked.Continue(value) =>
                   ZIO.suspend(
                     lifecycle.handleMessage(value, context, message)
                   )
      draft <- rootDraft(state, lifecycle, journal, model, state.url, ownsPageTitle = true)
    yield draft

  private def runEventHooks[Msg, Model](
    hooks: Vector[RootHookRegistry.Event[Msg, Model]],
    initial: Model,
    message: Msg,
    context: MessageContext[Msg, Model]
  ): Task[Hooked[Model]] =
    hooks.foldLeft[Task[Hooked[Model]]](ZIO.succeed(Hooked.Continue(initial))) { (effect, hook) =>
      effect.flatMap {
        case halted: Hooked.Halt[Model] => ZIO.succeed(halted)
        case Hooked.Continue(model)     =>
          hook.invoke(model, message, context).map {
            case LiveHookResult.Continue(next) => Hooked.Continue(next)
            case LiveHookResult.Halt(next)     => Hooked.Halt(next)
          }
      }
    }

  private def runParamsHooks[Msg, Model](
    registry: RootHookRegistry[Msg, Model],
    initial: Model,
    url: URL,
    context: ParamsContext[Msg, Model]
  ): Task[Hooked[Model]] =
    registry.params.foldLeft[Task[Hooked[Model]]](ZIO.succeed(Hooked.Continue(initial))) {
      (effect, hook) =>
        effect.flatMap {
          case halted: Hooked.Halt[Model] => ZIO.succeed(halted)
          case Hooked.Continue(model)     =>
            hook.invoke(model, url, context).map {
              case LiveHookResult.Continue(next) => Hooked.Continue(next)
              case LiveHookResult.Halt(next)     => Hooked.Halt(next)
            }
        }
    }

  private def runAsyncTurn[Msg, Model](
    state: RootState[Msg, Model],
    metadata: RootConnectionMetadata,
    lifecycle: RootLifecycle[Msg, Model],
    owner: OwnerId,
    event: LiveAsyncEvent[Msg],
    mappedMessage: Option[Msg]
  ): Task[TurnDraft[Msg, RootState[Msg, Model]]] =
    for
      journal <- rootJournal(owner, state, TurnDraft(state))
      context = RootMessageContext[Msg, Model](metadata, state.url, journal)
      hooked <- state.hooks.async.foldLeft[Task[Hooked[Model]]](
                  ZIO.succeed(Hooked.Continue(state.model))
                ) { (effect, hook) =>
                  effect.flatMap {
                    case halted: Hooked.Halt[Model] => ZIO.succeed(halted)
                    case Hooked.Continue(model)     =>
                      hook.invoke(model, event, context).map {
                        case LiveHookResult.Continue(next) => Hooked.Continue(next)
                        case LiveHookResult.Halt(next)     => Hooked.Halt(next)
                      }
                  }
                }
      model <- hooked match
                 case Hooked.Halt(value)     => ZIO.succeed(value)
                 case Hooked.Continue(value) =>
                   mappedMessage.orElse(
                     event.result match
                       case LiveAsyncResult.Succeeded(message) => Some(message)
                       case _                                  => None
                   ) match
                     case Some(message) =>
                       ZIO.suspend(lifecycle.handleMessage(value, context, message))
                     case None => ZIO.succeed(value)
      draft <- rootDraft(state, lifecycle, journal, model, state.url, ownsPageTitle = true)
    yield draft

  private def runBrowserHooks[Msg, Model](
    hooks: Vector[RootHookRegistry.Browser[Msg, Model]],
    committedModel: Model,
    raw: String,
    context: MessageContext[Msg, Model]
  ): Task[Model] =
    hooks
      .foldLeft[Task[Either[Unit, Model]]](ZIO.succeed(Right(committedModel))) { (effect, hook) =>
        effect.flatMap {
          case malformed @ Left(_) => ZIO.succeed(malformed)
          case Right(model)        =>
            hook.invoke(model, raw, context) match
              case Right(next) => next.map(Right(_))
              case Left(_)     =>
                ZIO.logWarning("root browser event payload was malformed") *>
                  ZIO.succeed(Left(()))
        }
      }.map(_.fold(_ => committedModel, identity))

  private def runRawHooks[Msg, Model](
    hooks: Vector[RootHookRegistry.Raw[Msg, Model]],
    initial: Model,
    event: LiveEvent,
    context: MessageContext[Msg, Model]
  ): Task[LiveEventHookResult[Model]] =
    hooks.foldLeft[Task[LiveEventHookResult[Model]]](
      ZIO.succeed(LiveEventHookResult.cont(initial))
    ) { (effect, hook) =>
      effect.flatMap {
        case halted: LiveEventHookResult.Halt[Model] => ZIO.succeed(halted)
        case LiveEventHookResult.Continue(model)     => hook.invoke(model, event, context)
      }
    }
end RootConnection
