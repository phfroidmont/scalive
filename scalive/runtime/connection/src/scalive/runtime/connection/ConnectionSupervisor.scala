package scalive.runtime.connection

import zio.*
import zio.http.URL

import scalive.BindingPayload
import scalive.LiveEvent
import scalive.render.BindingId
import scalive.render.EvaluatedTree
import scalive.render.RenderDelta
import scalive.runtime.contracts.*
import scalive.runtime.kernel.{RuntimeObserver, SessionEffects}
import scalive.runtime.resources.HostedWorkerId
import scalive.runtime.resources.UploadPreflightView
import scalive.runtime.resources.UploadRegistryError
import scalive.runtime.topology.DetachedStickyNestedLifecycle
import scalive.upload.UploadClientMetadata
import scalive.upload.UploadEntryRef
import scalive.upload.UploadRef

/** A protocol-neutral handle for one heterogeneously typed connected lifecycle. */
sealed private[scalive] trait ConnectedLifecycle:
  def lifecycle: LifecycleId
  def epoch: Epoch
  def topic: NestedTopic
  def domId: String

  def browserEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    event: Option[LiveEvent] = None
  ): IO[ConnectionError, Unit]

  def submitBrowserEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    event: Option[LiveEvent] = None
  ): IO[ConnectionError, Unit]

  def componentEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    event: LiveEvent
  ): IO[ConnectionError, Unit]

  def submitComponentEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    event: LiveEvent
  ): IO[ConnectionError, Unit]

  def message(command: CommandId, value: Any): IO[ConnectionError, Unit]

  def preflightUpload(
    command: CommandId,
    component: Option[ComponentInstanceId],
    ref: UploadRef,
    selected: Vector[(UploadEntryRef, UploadClientMetadata)]
  ): IO[ConnectionError, Either[UploadRegistryError, UploadPreflightView]]

  def syncUploadSelection(
    component: Option[ComponentInstanceId],
    ref: UploadRef,
    selected: Vector[(UploadEntryRef, UploadClientMetadata)]
  ): IO[ConnectionError, Either[UploadRegistryError, UploadPreflightView]]

  def admitUpload(
    component: Option[ComponentInstanceId],
    uploadRef: UploadRef,
    entryRef: UploadEntryRef,
    generation: Long
  ): IO[ConnectionError, Either[UploadAdmissionError, HostedWorkerId]]

  def uploadChunk(worker: HostedWorkerId, data: Chunk[Byte]): IO[UploadChunkError, Int]
  def leaveUpload(worker: HostedWorkerId): IO[ConnectionError, Unit]

  def progressUpload(
    command: CommandId,
    component: Option[ComponentInstanceId],
    uploadRef: UploadRef,
    entryRef: UploadEntryRef,
    progress: Int
  ): IO[ConnectionError, Either[UploadRegistryError, Unit]]

  def syncUploadProgress(
    component: Option[ComponentInstanceId],
    uploadRef: UploadRef,
    entryRef: UploadEntryRef,
    progress: Int
  ): IO[ConnectionError, Either[UploadRegistryError, Unit]]

  def patch(command: CommandId, destination: URL): IO[ConnectionError, Unit]
  def internalPatch(destination: URL): IO[ConnectionError, Unit]
  def synchronizeUrl(destination: URL): IO[ConnectionError, Unit]
  def componentForToken(token: Object): IO[ConnectionError, Option[ComponentInstanceId]]
  def destroyComponents(tokens: Vector[Object]): IO[ConnectionError, Unit]
  def tree: IO[ConnectionError, EvaluatedTree]
  def awaitFailure: UIO[ConnectionError]
  def pollFailure: UIO[Option[ConnectionError]]
  def awaitClosed: UIO[Unit]
  def close: UIO[Unit]
  def abort(error: ConnectionError): UIO[Unit]
  def correlateBrowserTrace(
    command: CommandId,
    joinReference: Option[String],
    messageReference: Option[String]
  ): UIO[Unit]
  def cancelTrace(command: CommandId): Unit
end ConnectedLifecycle

private[connection] object ConnectedLifecycle:
  def apply[Msg, Model](
    connection: RootConnection[Msg, Model],
    topic0: NestedTopic,
    domId0: String,
    observer: RuntimeObserver
  ): ConnectedLifecycle =
    new ConnectedLifecycle:
      val lifecycle: LifecycleId = connection.lifecycle
      val epoch: Epoch           = connection.epoch
      val topic: NestedTopic     = topic0
      val domId: String          = domId0

      def correlateBrowserTrace(
        command: CommandId,
        joinReference: Option[String],
        messageReference: Option[String]
      ): UIO[Unit] =
        observer.correlate(
          command,
          lifecycle,
          topic.value,
          joinReference,
          messageReference
        )

      def cancelTrace(command: CommandId): Unit = observer.cancel(command)

      def browserEvent(
        command: CommandId,
        binding: BindingId,
        payload: BindingPayload,
        event: Option[LiveEvent]
      ): IO[ConnectionError, Unit] =
        event match
          case Some(value) => connection.offerRawEvent(command, binding, payload, value)
          case None        => connection.offerEvent(command, binding, payload)

      def submitBrowserEvent(
        command: CommandId,
        binding: BindingId,
        payload: BindingPayload,
        event: Option[LiveEvent]
      ): IO[ConnectionError, Unit] =
        event match
          case Some(value) => connection.submitRawEvent(command, binding, payload, value)
          case None        => connection.submitEvent(command, binding, payload)

      def componentEvent(
        command: CommandId,
        component: ComponentInstanceId,
        binding: BindingId,
        payload: BindingPayload,
        event: LiveEvent
      ): IO[ConnectionError, Unit] =
        connection.offerComponentRawEvent(
          command,
          component,
          binding,
          payload,
          event
        )

      def submitComponentEvent(
        command: CommandId,
        component: ComponentInstanceId,
        binding: BindingId,
        payload: BindingPayload,
        event: LiveEvent
      ): IO[ConnectionError, Unit] =
        connection.submitComponentRawEvent(
          command,
          component,
          binding,
          payload,
          event
        )

      def message(command: CommandId, value: Any): IO[ConnectionError, Unit] =
        connection.offerMessage(command, value.asInstanceOf[Msg])

      def preflightUpload(
        command: CommandId,
        component: Option[ComponentInstanceId],
        ref: UploadRef,
        selected: Vector[(UploadEntryRef, UploadClientMetadata)]
      ): IO[ConnectionError, Either[UploadRegistryError, UploadPreflightView]] =
        connection.preflightUpload(command, component, ref, selected)

      def syncUploadSelection(
        component: Option[ComponentInstanceId],
        ref: UploadRef,
        selected: Vector[(UploadEntryRef, UploadClientMetadata)]
      ): IO[ConnectionError, Either[UploadRegistryError, UploadPreflightView]] =
        connection.syncUploadSelection(component, ref, selected)

      def admitUpload(
        component: Option[ComponentInstanceId],
        uploadRef: UploadRef,
        entryRef: UploadEntryRef,
        generation: Long
      ): IO[ConnectionError, Either[UploadAdmissionError, HostedWorkerId]] =
        connection.admitUpload(component, uploadRef, entryRef, generation)

      def uploadChunk(worker: HostedWorkerId, data: Chunk[Byte]): IO[UploadChunkError, Int] =
        connection.uploadChunk(worker, data)
      def leaveUpload(worker: HostedWorkerId): IO[ConnectionError, Unit] =
        connection.leaveUpload(worker)

      def progressUpload(
        command: CommandId,
        component: Option[ComponentInstanceId],
        uploadRef: UploadRef,
        entryRef: UploadEntryRef,
        progress: Int
      ): IO[ConnectionError, Either[UploadRegistryError, Unit]] =
        connection.progressUpload(command, component, uploadRef, entryRef, progress)

      def syncUploadProgress(
        component: Option[ComponentInstanceId],
        uploadRef: UploadRef,
        entryRef: UploadEntryRef,
        progress: Int
      ): IO[ConnectionError, Either[UploadRegistryError, Unit]] =
        connection.syncUploadProgress(component, uploadRef, entryRef, progress)

      def patch(command: CommandId, destination: URL): IO[ConnectionError, Unit] =
        connection.offerPatch(command, destination)

      def internalPatch(destination: URL): IO[ConnectionError, Unit] =
        connection.offerInternalPatch(destination)

      def synchronizeUrl(destination: URL): IO[ConnectionError, Unit] =
        connection.synchronizeUrl(destination)

      def componentForToken(
        token: Object
      ): IO[ConnectionError, Option[ComponentInstanceId]] = connection.componentForToken(token)

      def destroyComponents(tokens: Vector[Object]): IO[ConnectionError, Unit] =
        connection.destroyComponents(tokens)

      def tree: IO[ConnectionError, EvaluatedTree]  = connection.inspectTree
      def awaitFailure: UIO[ConnectionError]        = connection.awaitFailure
      def pollFailure: UIO[Option[ConnectionError]] = connection.pollFailure
      def awaitClosed: UIO[Unit]                    = connection.awaitClosed
      def close: UIO[Unit]                          = connection.close
      def abort(error: ConnectionError): UIO[Unit]  = connection.abort(error)
end ConnectedLifecycle

/** Owns all connected LiveView lifecycles belonging to one physical connection. */
final private[scalive] class ConnectionSupervisor private (
  connectionId: ConnectionId,
  config: ConnectionConfig,
  topology: NestedTopologyRuntime,
  supervisorScope: Scope.Closeable,
  gate: Semaphore,
  observer: RuntimeObserver):
  import ConnectionSupervisor.*

  private var state: State                                                 = State.empty
  private var retirements: Map[ConnectedLifecycle, Promise[Nothing, Unit]] = Map.empty

  def startRootLifecycle[Msg, Model](
    lifecycle: RootLifecycle[Msg, Model],
    metadata: RootConnectionMetadata,
    domId: String,
    topic: NestedTopic,
    loading: Boolean,
    sink: ConnectionOutput => Task[Unit],
    requestedLifecycle: Option[LifecycleId] = None,
    bootstrapChildLifecycles: Map[String, LifecycleId] = Map.empty
  ): IO[StartError, ConnectedLifecycle] =
    val seed = ZIO.foreachDiscard(requestedLifecycle)(parentLifecycle =>
      topology.seedChildLifecycles(parentLifecycle, bootstrapChildLifecycles)
    )
    val start = startSlot(topic, Some(domId), parent = None).flatMap { slot =>
      startInSlot(slot) { childScope =>
        childScope
          .extend(
            RootConnection.startLifecycle(
              config,
              metadata,
              lifecycle,
              sink,
              topology.preparer(domId, loading),
              ownsPageTitle = true,
              requestedLifecycle = requestedLifecycle,
              providedConnection = Some(connectionId),
              observer = observer,
              topic = Some(topic)
            )
          ).map(connection => ConnectedLifecycle(connection, topic, domId, observer))
      }(entry => installRoot(slot, entry))
    }
    seed *> start.ensuring(
      ZIO.foreachDiscard(requestedLifecycle)(topology.clearChildLifecycles)
    )
  end startRootLifecycle

  /** Reserves only an already committed exact topology registration. */
  def reserveNested(
    claims: NestedCredentialClaims
  ): IO[NestedJoinAdmissionError, NestedJoinReservation] =
    gate.withPermit(ZIO.succeed(state.closed)).flatMap {
      case true =>
        ZIO.fail(NestedJoinAdmissionError.RegistrationUnavailable(claims.registration))
      case false => topology.reserveJoin(claims)
    }

  def startNested(
    reservation: NestedJoinReservation,
    inheritedUrl: URL,
    metadata: RootConnectionMetadata,
    domId: String,
    loading: Boolean,
    sink: ConnectionOutput => Task[Unit],
    failureNotifier: NestedFailureNotifier = NestedFailureNotifier.noop,
    reattach: Boolean = false,
    requestedLifecycle: Option[LifecycleId] = None
  ): IO[StartError, ConnectedLifecycle] =
    val started = for
      active <- topology.registration(reservation.registration.id)
      _      <- ZIO
             .fail(StartError.RegistrationRevoked(reservation.registration.id)).unless(
               active.exists(sameCoordinates(_, reservation.registration))
             )
      retained <-
        if reattach && reservation.registration.sticky then
          reattachNested(reservation, inheritedUrl, sink, failureNotifier)
        else ZIO.none
      handle <- retained match
                  case Some(handle) => ZIO.succeed(handle)
                  case None         =>
                    retireDetachedForTopic(reservation.registration.topic) *>
                      startFreshNested(
                        reservation,
                        inheritedUrl,
                        metadata,
                        domId,
                        loading,
                        sink,
                        failureNotifier,
                        requestedLifecycle
                      )
    yield handle
    started.catchAll {
      case error @ StartError.ConnectionFailed(connectionError) =>
        topology.cancelJoin(reservation) *>
          linkParentAfterStartFailure(
            reservation.registration,
            connectionError,
            failureNotifier.onStart
          ).flatMap { linked =>
            if linked then ZIO.fail(StartError.LinkedConnectionFailed(connectionError))
            else ZIO.fail(error)
          }
      case error => topology.cancelJoin(reservation) *> ZIO.fail(error)
    }
  end startNested

  private def startFreshNested(
    reservation: NestedJoinReservation,
    inheritedUrl: URL,
    metadata: RootConnectionMetadata,
    domId: String,
    loading: Boolean,
    sink: ConnectionOutput => Task[Unit],
    failureNotifier: NestedFailureNotifier,
    requestedLifecycle: Option[LifecycleId]
  ): IO[StartError, ConnectedLifecycle] =
    for
      admitted <- topology.beginJoin(reservation)
      _        <- ZIO
             .fail(StartError.RegistrationRevoked(reservation.registration.id)).unless(admitted)
      slot <- startSlot(
                reservation.registration.topic,
                rootDomId = None,
                parent = Some(
                  reservation.registration.parentLifecycle -> reservation.registration.parentEpoch
                )
              )
      handle <- startInSlot(slot) { childScope =>
                  for
                    buffer <- BufferedActivationSink.make(
                                config.writerCapacity,
                                sink,
                                capacity =>
                                  IllegalStateException(
                                    s"nested startup output exceeded capacity $capacity"
                                  )
                              )
                    output <- RebindableSink.make(buffer.offer)
                    view   <-
                      ZIO
                        .attempt(reservation.create()).mapError(ConnectionError.SinkFailed.apply)
                    connection <- childScope.extend(
                                    RootConnection.startLifecycle(
                                      config,
                                      metadata,
                                      RootLifecycle.ordinary(view, inheritedUrl),
                                      output.offer,
                                      topology.preparer(domId, loading),
                                      ownsPageTitle = false,
                                      requestedLifecycle = requestedLifecycle,
                                      providedConnection = Some(connectionId),
                                      observer = observer,
                                      closeAfterNavigate = !reservation.registration.sticky,
                                      topic = Some(reservation.registration.topic)
                                    )
                                  )
                    connected = ConnectedLifecycle(
                                  connection,
                                  reservation.registration.topic,
                                  domId,
                                  observer
                                )
                  yield PendingNested(connected, buffer, output, failureNotifier)
                }(pending => installNested(slot, reservation, pending))
    yield handle

  private def reattachNested(
    reservation: NestedJoinReservation,
    inheritedUrl: URL,
    sink: ConnectionOutput => Task[Unit],
    failureNotifier: NestedFailureNotifier
  ): IO[StartError, Option[ConnectedLifecycle]] =
    gate
      .withPermit {
        val registration = reservation.registration
        if state.closed then ZIO.fail(StartError.Closed)
        else if !state.entries
            .get(registration.parentLifecycle).exists(_.handle.epoch == registration.parentEpoch)
        then
          ZIO.fail(
            StartError.ParentUnavailable(registration.parentLifecycle, registration.parentEpoch)
          )
        else
          state.entries.values.collectFirst {
            case entry @ Entry(
                  _,
                  _,
                  EntryOwnership.DetachedSticky(applicationId, topic, _)
                ) if applicationId == registration.applicationId && topic == registration.topic =>
              entry
          } match
            case None        => ZIO.none
            case Some(entry) =>
              entry.ownership match
                case EntryOwnership.DetachedSticky(_, _, output) =>
                  state = state.copy(
                    entries = state.entries.updated(
                      entry.handle.lifecycle,
                      entry.copy(
                        ownership = EntryOwnership.Reattaching(
                          registration,
                          output,
                          failureNotifier
                        )
                      )
                    ),
                    byTopic = state.byTopic.removed(entry.handle.topic)
                  )
                  ZIO.some(ReattachSlot(entry, registration, output))
                case _ => ZIO.none
        end if
      }.flatMap {
        case None       => ZIO.none
        case Some(slot) =>
          val entry        = slot.entry
          val registration = slot.registration
          val output       = slot.output
          (for
            _       <- output.detach
            failure <- entry.handle.pollFailure
            _ <- ZIO.foreachDiscard(failure)(error => ZIO.fail(StartError.ConnectionFailed(error)))
            attached <- topology.completeJoin(
                          reservation,
                          entry.handle.lifecycle,
                          entry.handle.epoch
                        )
            _ <- ZIO
                   .fail(StartError.RegistrationRevoked(registration.id)).unless(attached)
            _ <-
              entry.handle.synchronizeUrl(inheritedUrl).mapError(StartError.ConnectionFailed.apply)
            tree <- entry.handle.tree.mapError(StartError.ConnectionFailed.apply)
            _    <-
              output
                .attach(
                  sink,
                  ConnectionOutput.Joined(RenderDelta.Replace(tree), SessionEffects())
                ).mapError(error => StartError.ConnectionFailed(ConnectionError.SinkFailed(error)))
            installed <- gate.withPermit {
                           state.entries.get(entry.handle.lifecycle) match
                             case Some(
                                   current @ Entry(
                                     _,
                                     _,
                                     EntryOwnership.Reattaching(
                                       active,
                                       activeOutput,
                                       activeNotifier
                                     )
                                   )
                                 )
                                 if current.handle.epoch == entry.handle.epoch &&
                                   sameCoordinates(
                                     active,
                                     registration
                                   ) && (activeOutput eq output) =>
                               state = state.copy(
                                 entries = state.entries.updated(
                                   current.handle.lifecycle,
                                   current.copy(
                                     ownership = EntryOwnership.Attached(
                                       registration,
                                       output,
                                       activeNotifier
                                     )
                                   )
                                 ),
                                 byTopic = state.byTopic.updated(
                                   current.handle.topic,
                                   current.handle.lifecycle
                                 )
                               )
                               ZIO.succeed(true)
                             case _ => ZIO.succeed(false)
                         }
            _ <- ZIO.fail(StartError.Closed).unless(installed)
          yield Some(entry.handle)).onError(_ => rollbackReattachment(reservation, slot))
      }

  def lifecycleForTopic(topic: NestedTopic): UIO[Option[ConnectedLifecycle]] =
    gate.withPermit(ZIO.succeed(state.byTopic.get(topic).flatMap(state.entries.get).map(_.handle)))

  def lifecycle(
    lifecycle: LifecycleId,
    epoch: Epoch
  ): UIO[Option[ConnectedLifecycle]] =
    gate.withPermit(
      ZIO.succeed(state.entries.get(lifecycle).filter(_.handle.epoch == epoch).map(_.handle))
    )

  def routeEvent(
    topic: NestedTopic,
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    event: Option[LiveEvent] = None
  ): IO[ConnectionError, Boolean] =
    route(topic)(_.browserEvent(command, binding, payload, event))

  def routeComponentEvent(
    topic: NestedTopic,
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    event: LiveEvent
  ): IO[ConnectionError, Boolean] =
    route(topic)(_.componentEvent(command, component, binding, payload, event))

  def routePatch(
    topic: NestedTopic,
    command: CommandId,
    destination: URL
  ): IO[ConnectionError, Boolean] = route(topic)(_.patch(command, destination))

  def routeLeave(topic: NestedTopic): UIO[LeaveResult] =
    gate
      .withPermit {
        ZIO.succeed(
          state.byTopic
            .get(topic).flatMap(state.entries.get).map(entry => entry.handle -> entry.ownership)
        )
      }.flatMap {
        case None                                => ZIO.succeed(LeaveResult.UnknownTopic)
        case Some((handle, EntryOwnership.Root)) =>
          retireRootForNavigation(handle).map {
            case true  => LeaveResult.Left
            case false => LeaveResult.UnknownTopic
          }
        case Some((handle, EntryOwnership.Attached(registration, output, _)))
            if registration.sticky =>
          detachStickyForNavigation(handle, registration, output).map {
            case true  => LeaveResult.Left
            case false => LeaveResult.UnknownTopic
          }
        case Some((handle, EntryOwnership.DetachedSticky(_, _, output))) =>
          detachRetainedChannel(handle, output).as(LeaveResult.Left)
        case Some((handle, _)) =>
          retire(handle.lifecycle, handle.epoch).as(LeaveResult.Left)
      }

  def routeNavigationLeave(topic: NestedTopic): UIO[LeaveResult] =
    gate
      .withPermit {
        state.byTopic.get(topic).flatMap(state.entries.get) match
          case Some(Entry(handle, _, EntryOwnership.Root)) => ZIO.some(handle)
          case _                                           => ZIO.none
      }.flatMap {
        case None       => ZIO.succeed(LeaveResult.UnknownTopic)
        case Some(root) => retireRootForNavigation(root).as(LeaveResult.Left)
      }

  def close: UIO[Unit] =
    gate
      .withPermit {
        if state.closed then
          ZIO.succeed(
            (
              Vector.empty[Entry],
              Vector.empty[Scope.Closeable],
              Vector.empty[Promise[Nothing, Unit]],
              false
            )
          )
        else
          val entries = state.entries.values.toVector
          val starts  = state.startingScopes.values.toVector
          val waiters = retirements.values.toVector
          state = State.empty.copy(closed = true)
          retirements = Map.empty
          ZIO.succeed((entries, starts, waiters, true))
      }.flatMap { case (entries, starts, waiters, first) =>
        if !first then ZIO.unit
        else
          RuntimeCleanup.all(
            Vector(
              topology.close,
              RuntimeCleanup.all(entries.map(closeEntry)),
              RuntimeCleanup.all(starts.map(_.close(Exit.unit))),
              supervisorScope.close(Exit.unit)
            )
          ) *> ZIO.foreachDiscard(waiters)(_.succeed(()))
      }

  private def route(
    topic: NestedTopic
  )(
    run: ConnectedLifecycle => IO[ConnectionError, Unit]
  ): IO[ConnectionError, Boolean] =
    lifecycleForTopic(topic).flatMap {
      case None         => ZIO.succeed(false)
      case Some(handle) => run(handle).as(true)
    }

  private def startSlot(
    topic: NestedTopic,
    rootDomId: Option[String],
    parent: Option[(LifecycleId, Epoch)]
  ): IO[StartError, StartSlot] =
    ZIO.uninterruptible {
      for
        token  <- ZIO.succeed(new Object())
        scope  <- Scope.make
        result <- gate
                    .withPermit {
                      if state.closed then ZIO.fail(StartError.Closed)
                      else if state.entries.values.exists(_.handle.topic == topic) ||
                        state.startingTopics.contains(topic)
                      then ZIO.fail(StartError.DuplicateTopic(topic))
                      else if rootDomId.nonEmpty &&
                        (state.root.nonEmpty || state.startingRoot ||
                          state.entries.values
                            .exists(entry => rootDomId.contains(entry.handle.domId)))
                      then ZIO.fail(StartError.DuplicateRoot(rootDomId.get))
                      else if parent.exists { case (id, epoch) =>
                          !state.entries.get(id).exists(_.handle.epoch == epoch)
                        }
                      then ZIO.fail(StartError.ParentUnavailable(parent.get._1, parent.get._2))
                      else
                        state = state.copy(
                          startingTopics = state.startingTopics + topic,
                          startingRoot = state.startingRoot || rootDomId.nonEmpty,
                          startingScopes = state.startingScopes.updated(token, scope)
                        )
                        ZIO.succeed(StartSlot(token, topic, rootDomId.nonEmpty, scope))
                    }.onError(_ => scope.close(Exit.unit))
      yield result
    }

  private def startInSlot[A](
    slot: StartSlot
  )(
    start: Scope.Closeable => IO[ConnectionError, A]
  )(
    install: A => IO[StartError, ConnectedLifecycle]
  ): IO[StartError, ConnectedLifecycle] =
    ZIO.uninterruptibleMask { restore =>
      restore(start(slot.scope))
        .mapError(StartError.ConnectionFailed.apply)
        .flatMap(install)
        .onExit {
          case Exit.Success(_) => ZIO.unit
          case _               => releaseSlot(slot) *> slot.scope.close(Exit.unit)
        }
    }

  private def installRoot(
    slot: StartSlot,
    handle: ConnectedLifecycle
  ): IO[StartError, ConnectedLifecycle] =
    gate
      .withPermit {
        if !slotIsCurrent(slot) || state.closed then ZIO.fail(StartError.Closed)
        else if state.root.nonEmpty || state.entries.contains(handle.lifecycle) then
          ZIO.fail(StartError.DuplicateRoot(handle.domId))
        else
          val entry = Entry(handle, slot.scope, EntryOwnership.Root)
          installEntry(slot, entry, root = true)
          ZIO.succeed(handle)
      }.tap(handle => monitor(handle)).tapError(_ => slot.scope.close(Exit.unit))

  private def installNested(
    slot: StartSlot,
    reservation: NestedJoinReservation,
    pending: PendingNested
  ): IO[StartError, ConnectedLifecycle] =
    val installed = gate
      .withPermit {
        val handle       = pending.handle
        val registration = reservation.registration
        if !slotIsCurrent(slot) || state.closed then ZIO.fail(StartError.Closed)
        else if !state.entries
            .get(registration.parentLifecycle).exists(_.handle.epoch == registration.parentEpoch)
        then
          ZIO.fail(
            StartError.ParentUnavailable(registration.parentLifecycle, registration.parentEpoch)
          )
        else if state.entries.contains(handle.lifecycle) || state.byTopic.contains(handle.topic)
        then ZIO.fail(StartError.DuplicateTopic(handle.topic))
        else
          handle.pollFailure.flatMap {
            case Some(error) => ZIO.fail(StartError.ConnectionFailed(error))
            case None        =>
              topology.completeJoin(reservation, handle.lifecycle, handle.epoch).flatMap {
                case false => ZIO.fail(StartError.RegistrationRevoked(registration.id))
                case true  =>
                  val entry = Entry(
                    handle,
                    slot.scope,
                    EntryOwnership.Attached(
                      registration,
                      pending.output,
                      pending.failureNotifier
                    )
                  )
                  installEntry(slot, entry, root = false)
                  ZIO.succeed(handle)
              }
          }
      }
    installed
      .flatMap(handle =>
        pending.buffer.activate
          .mapError(error => StartError.ConnectionFailed(ConnectionError.SinkFailed(error)))
          .as(handle)
      ).tap(handle => monitor(handle)).tapError { _ =>
        topology.cancelJoin(reservation) *>
          topology
            .detachChild(
              reservation.registration.id,
              pending.handle.lifecycle,
              pending.handle.epoch
            ).unit *>
          removeInstalled(pending.handle) *>
          slot.scope.close(Exit.unit)
      }
  end installNested

  private def rollbackReattachment(
    reservation: NestedJoinReservation,
    slot: ReattachSlot
  ): UIO[Unit] =
    val entry        = slot.entry
    val registration = slot.registration
    val output       = slot.output
    topology.cancelJoin(reservation) *>
      topology
        .detachChild(registration.id, entry.handle.lifecycle, entry.handle.epoch).unit *>
      gate.withPermit {
        state.entries.get(entry.handle.lifecycle) match
          case Some(
                current @ Entry(
                  _,
                  _,
                  EntryOwnership.Reattaching(active, activeOutput, _)
                )
              )
              if current.handle.epoch == entry.handle.epoch &&
                sameCoordinates(active, registration) && (activeOutput eq output) =>
            state = state.copy(
              entries = state.entries.updated(
                current.handle.lifecycle,
                current.copy(
                  ownership = EntryOwnership.DetachedSticky(
                    registration.applicationId,
                    registration.topic,
                    output
                  )
                )
              ),
              byTopic = state.byTopic.removed(current.handle.topic)
            )
          case _ => ()
        ZIO.unit
      } *> output.detach
  end rollbackReattachment

  private def installEntry(slot: StartSlot, entry: Entry, root: Boolean): Unit =
    state = state.copy(
      entries = state.entries.updated(entry.handle.lifecycle, entry),
      byTopic = state.byTopic.updated(entry.handle.topic, entry.handle.lifecycle),
      root = if root then Some(entry.handle.lifecycle) else state.root,
      startingTopics = state.startingTopics - slot.topic,
      startingRoot = if slot.root then false else state.startingRoot,
      startingScopes = state.startingScopes.removed(slot.token)
    )

  private def monitor(handle: ConnectedLifecycle): UIO[Unit] =
    supervisorScope.extend(
      (handle.awaitClosed *> handle.pollFailure.flatMap(error =>
        terminal(handle, error)
      )).forkScoped.unit
    )

  private def terminal(
    handle: ConnectedLifecycle,
    failure: Option[ConnectionError]
  ): UIO[Unit] =
    val ownership = gate.withPermit(
      ZIO.succeed(
        state.entries
          .get(handle.lifecycle).filter(entry => entry.handle eq handle).map(_.ownership)
      )
    )
    ownership.flatMap {
      case Some(EntryOwnership.Root) if failure.isEmpty =>
        retireRootForNavigation(handle).unit
      case _ =>
        claimRetirement(handle).flatMap {
          case Left(awaitRetirement) => awaitRetirement
          case Right(claim)          =>
            detach(claim.entry)
              .flatMap { activeRegistration =>
                topology.revokeParent(handle.lifecycle, handle.epoch) *>
                  closeEntry(claim.entry) *>
                  ZIO.foreachDiscard(
                    activeRegistration.filter(_._1.linkParentOnCrash).zip(failure)
                  ) { case ((registration, notifier), childFailure) =>
                    notifier.onRuntime(childFailure) *>
                      failParent(registration, handle.lifecycle, childFailure)
                  }
              }.ensuring(completeRetirement(claim))
        }
    }
  end terminal

  private def linkParentAfterStartFailure(
    registration: NestedRegistration,
    failure: ConnectionError,
    notify: ConnectionError => UIO[Unit]
  ): UIO[Boolean] =
    topology.registration(registration.id).flatMap {
      case Some(active) if sameCoordinates(active, registration) && active.linkParentOnCrash =>
        notify(failure) *>
          lifecycle(active.parentLifecycle, active.parentEpoch)
            .flatMap {
              case Some(parent) =>
                parent.abort(ConnectionError.LinkedChildJoinFailed(active.id, failure))
              case None => ZIO.unit
            }.as(true)
      case _ => ZIO.succeed(false)
    }

  private def failParent(
    registration: NestedRegistration,
    child: LifecycleId,
    failure: ConnectionError
  ): UIO[Unit] =
    lifecycle(registration.parentLifecycle, registration.parentEpoch).flatMap {
      case Some(parent) => parent.abort(ConnectionError.LinkedChildFailed(child, failure))
      case None         => ZIO.unit
    }

  private def retire(lifecycle: LifecycleId, epoch: Epoch): UIO[Unit] =
    gate
      .withPermit {
        ZIO.succeed(
          state.entries.get(lifecycle).filter(_.handle.epoch == epoch).map(_.handle)
        )
      }.flatMap(ZIO.foreachDiscard(_)(retireHandle))

  private def retireHandle(handle: ConnectedLifecycle): UIO[Unit] =
    claimRetirement(handle).flatMap {
      case Left(awaitRetirement) => awaitRetirement
      case Right(claim)          =>
        (detach(claim.entry).unit *>
          topology.revokeParent(claim.entry.handle.lifecycle, claim.entry.handle.epoch) *>
          closeEntry(claim.entry)).ensuring(completeRetirement(claim))
    }

  private def claimRetirement(
    handle: ConnectedLifecycle
  ): UIO[Either[UIO[Unit], RetirementClaim]] =
    Promise.make[Nothing, Unit].flatMap { completion =>
      gate.withPermit {
        state.entries.get(handle.lifecycle) match
          case Some(entry) if entry.handle eq handle =>
            val activeCompletion = retirements.getOrElse(handle, completion)
            removeEntry(entry)
            retirements = retirements.updated(handle, activeCompletion)
            ZIO.right(RetirementClaim(entry, activeCompletion))
          case _ =>
            ZIO.left(retirements.get(handle).fold[UIO[Unit]](ZIO.unit)(_.await))
      }
    }

  private def completeRetirement(claim: RetirementClaim): UIO[Unit] =
    claim.completion.succeed(()).unit *> gate.withPermit {
      val handle = claim.entry.handle
      if retirements.get(handle).contains(claim.completion) then
        retirements = retirements.removed(handle)
      ZIO.unit
    }

  private def retireRootForNavigation(handle: ConnectedLifecycle): UIO[Boolean] =
    gate
      .withPermit {
        state.entries.get(handle.lifecycle) match
          case Some(entry @ Entry(_, _, EntryOwnership.Root)) if entry.handle eq handle =>
            removeEntry(entry)
            ZIO.some(entry)
          case _ => ZIO.none
      }.flatMap {
        case None       => ZIO.succeed(false)
        case Some(root) =>
          topology
            .detachParentForNavigation(root.handle.lifecycle, root.handle.epoch).flatMap {
              navigation =>
                detachStickyEntries(navigation.detachedStickyChildren) *>
                  ZIO.foreachDiscard(navigation.childLifecycleIdsToRetire)(retireById) *>
                  closeEntry(root)
            }.as(true)
      }

  private def detachStickyForNavigation(
    handle: ConnectedLifecycle,
    registration: NestedRegistration,
    output: RebindableSink
  ): UIO[Boolean] =
    topology
      .detachStickyForNavigation(registration.id, handle.lifecycle, handle.epoch).flatMap {
        case None    => ZIO.succeed(false)
        case Some(_) =>
          gate
            .withPermit {
              state.entries.get(handle.lifecycle) match
                case Some(
                      entry @ Entry(
                        _,
                        _,
                        EntryOwnership.Attached(active, activeOutput, _)
                      )
                    )
                    if entry.handle.epoch == handle.epoch && active == registration &&
                      (activeOutput eq output) =>
                  state = state.copy(
                    entries = state.entries.updated(
                      handle.lifecycle,
                      entry.copy(
                        ownership = EntryOwnership.DetachedSticky(
                          registration.applicationId,
                          registration.topic,
                          output
                        )
                      )
                    ),
                    byTopic = state.byTopic.removed(handle.topic)
                  )
                  ZIO.succeed(true)
                case _ => ZIO.succeed(false)
            }.flatMap {
              case true  => output.detach.as(true)
              case false => ZIO.succeed(false)
            }
      }

  private def detachRetainedChannel(
    handle: ConnectedLifecycle,
    output: RebindableSink
  ): UIO[Unit] =
    gate
      .withPermit {
        state.entries.get(handle.lifecycle) match
          case Some(Entry(active, _, EntryOwnership.DetachedSticky(_, _, activeOutput)))
              if active.epoch == handle.epoch && (activeOutput eq output) =>
            state = state.copy(byTopic = state.byTopic.removed(handle.topic))
            ZIO.succeed(true)
          case _ => ZIO.succeed(false)
      }.flatMap(detached => ZIO.when(detached)(output.detach).unit)

  private def retireDetachedForTopic(topic: NestedTopic): UIO[Unit] =
    gate
      .withPermit {
        ZIO.succeed(
          state.entries.values.collectFirst {
            case Entry(handle, _, EntryOwnership.DetachedSticky(_, detachedTopic, _))
                if detachedTopic == topic =>
              handle.lifecycle -> handle.epoch
          }
        )
      }.flatMap(ZIO.foreachDiscard(_) { case (lifecycle, epoch) =>
        retire(lifecycle, epoch)
      })

  private def removeExact(handle: ConnectedLifecycle): UIO[Option[Entry]] =
    gate
      .withPermit {
        state.entries.get(handle.lifecycle) match
          case Some(entry) if entry.handle.epoch == handle.epoch =>
            removeEntry(entry)
            ZIO.some(entry)
          case _ => ZIO.none
      }

  private def removeInstalled(handle: ConnectedLifecycle): UIO[Unit] =
    removeExact(handle).unit

  private def removeEntry(entry: Entry): Unit =
    state = state.copy(
      entries = state.entries.removed(entry.handle.lifecycle),
      byTopic = state.byTopic.removed(entry.handle.topic),
      root = state.root.filterNot(_ == entry.handle.lifecycle)
    )

  private def detach(
    entry: Entry
  ): UIO[Option[(NestedRegistration, NestedFailureNotifier)]] = entry.ownership match
    case EntryOwnership.Attached(registration, _, notifier) =>
      topology
        .detachChild(registration.id, entry.handle.lifecycle, entry.handle.epoch).map(
          _.map(_ -> notifier)
        )
    case EntryOwnership.Reattaching(registration, _, notifier) =>
      topology
        .detachChild(registration.id, entry.handle.lifecycle, entry.handle.epoch).map(
          _.map(_ -> notifier)
        )
    case EntryOwnership.Root | EntryOwnership.DetachedSticky(_, _, _) => ZIO.none

  private def detachStickyEntries(
    detached: Vector[DetachedStickyNestedLifecycle]
  ): UIO[Unit] =
    gate
      .withPermit {
        detached.foreach { value =>
          state.entries.get(value.child.lifecycle) match
            case Some(
                  entry @ Entry(
                    _,
                    _,
                    EntryOwnership.Attached(registration, output, _)
                  )
                )
                if entry.handle.epoch == value.child.epoch && registration == value.registration =>
              val ownership = EntryOwnership.DetachedSticky(
                registration.applicationId,
                registration.topic,
                output
              )
              state = state.copy(
                entries = state.entries.updated(
                  entry.handle.lifecycle,
                  entry.copy(ownership = ownership)
                )
              )
            case _ => ()
        }
        ZIO.unit
      }

  private def closeEntry(entry: Entry): UIO[Unit] =
    RuntimeCleanup.all(Vector(entry.handle.close, entry.scope.close(Exit.unit)))

  private def releaseSlot(slot: StartSlot): UIO[Unit] = gate.withPermit {
    if slotIsCurrent(slot) then
      state = state.copy(
        startingTopics = state.startingTopics - slot.topic,
        startingRoot = if slot.root then false else state.startingRoot,
        startingScopes = state.startingScopes.removed(slot.token)
      )
    ZIO.unit
  }

  private def slotIsCurrent(slot: StartSlot): Boolean =
    state.startingScopes.get(slot.token).contains(slot.scope)

  private def sameCoordinates(
    left: NestedRegistration,
    right: NestedRegistration
  ): Boolean =
    left.id == right.id &&
      left.epoch == right.epoch &&
      left.parentLifecycle == right.parentLifecycle &&
      left.parentEpoch == right.parentEpoch &&
      left.topic == right.topic

  private[connection] def retireById(lifecycle: LifecycleId): UIO[Unit] =
    gate
      .withPermit {
        state.entries.get(lifecycle) match
          case None        => ZIO.none
          case Some(entry) => ZIO.some(entry.handle.epoch)
      }.flatMap(ZIO.foreachDiscard(_)(epoch => retire(lifecycle, epoch)))

  private[scalive] def retireLifecycle(handle: ConnectedLifecycle): UIO[Unit] =
    retireHandle(handle)

  private[scalive] def retireTerminatedLifecycle(handle: ConnectedLifecycle): UIO[Unit] =
    handle.awaitClosed *> handle.pollFailure.flatMap(terminal(handle, _))

  private[scalive] def awaitRetirement(handle: ConnectedLifecycle): UIO[Unit] =
    Promise.make[Nothing, Unit].flatMap { candidate =>
      gate.withPermit {
        val await = state.entries.get(handle.lifecycle) match
          case Some(entry) if entry.handle eq handle =>
            val completion = retirements.getOrElse(handle, candidate)
            retirements = retirements.updated(handle, completion)
            completion.await
          case _ => retirements.get(handle).fold[UIO[Unit]](ZIO.unit)(_.await)
        ZIO.succeed(await)
      }.flatten
    }
end ConnectionSupervisor

private[scalive] object ConnectionSupervisor:
  final private[scalive] case class NestedFailureNotifier(
    onStart: ConnectionError => UIO[Unit],
    onRuntime: ConnectionError => UIO[Unit])

  private[scalive] object NestedFailureNotifier:
    val noop: NestedFailureNotifier = NestedFailureNotifier(_ => ZIO.unit, _ => ZIO.unit)

  enum StartError:
    case Closed
    case DuplicateTopic(topic: NestedTopic)
    case DuplicateRoot(domId: String)
    case ParentUnavailable(lifecycle: LifecycleId, epoch: Epoch)
    case RegistrationRevoked(registration: NestedRegistrationId)
    case ConnectionFailed(error: ConnectionError)
    case LinkedConnectionFailed(error: ConnectionError)

  enum LeaveResult:
    case Left
    case UnknownTopic

  private enum EntryOwnership:
    case Root
    case Attached(
      registration: NestedRegistration,
      output: RebindableSink,
      failureNotifier: NestedFailureNotifier)
    case Reattaching(
      registration: NestedRegistration,
      output: RebindableSink,
      failureNotifier: NestedFailureNotifier)
    case DetachedSticky(applicationId: String, topic: NestedTopic, output: RebindableSink)

  final private case class Entry(
    handle: ConnectedLifecycle,
    scope: Scope.Closeable,
    ownership: EntryOwnership)

  final private case class StartSlot(
    token: Object,
    topic: NestedTopic,
    root: Boolean,
    scope: Scope.Closeable)

  final private case class PendingNested(
    handle: ConnectedLifecycle,
    buffer: BufferedActivationSink[ConnectionOutput],
    output: RebindableSink,
    failureNotifier: NestedFailureNotifier)

  final private case class ReattachSlot(
    entry: Entry,
    registration: NestedRegistration,
    output: RebindableSink)

  final private case class RetirementClaim(
    entry: Entry,
    completion: Promise[Nothing, Unit])

  final private case class State(
    entries: Map[LifecycleId, Entry],
    byTopic: Map[NestedTopic, LifecycleId],
    root: Option[LifecycleId],
    startingTopics: Set[NestedTopic],
    startingRoot: Boolean,
    startingScopes: Map[Object, Scope.Closeable],
    closed: Boolean)

  private object State:
    val empty: State = State(Map.empty, Map.empty, None, Set.empty, false, Map.empty, false)

  final private class RebindableSink private (
    gate: Semaphore,
    private var destination: Option[ConnectionOutput => Task[Unit]]):

    def offer(output: ConnectionOutput): Task[Unit] = gate.withPermit {
      destination.fold[Task[Unit]](ZIO.unit)(_(output))
    }

    def detach: UIO[Unit] = gate.withPermit(ZIO.succeed {
      destination = None
    })

    def attach(
      next: ConnectionOutput => Task[Unit],
      initial: ConnectionOutput
    ): Task[Unit] = gate.withPermit {
      next(initial) *> ZIO.succeed {
        destination = Some(next)
      }
    }

  private object RebindableSink:
    def make(destination: ConnectionOutput => Task[Unit]): UIO[RebindableSink] =
      Semaphore.make(1L).map(new RebindableSink(_, Some(destination)))

  def make(
    config: ConnectionConfig,
    credentialIssuer: NestedCredentialIssuer,
    topicFor: String => NestedTopic,
    observer: RuntimeObserver = RuntimeObserver.logging
  ): ZIO[Scope, Nothing, ConnectionSupervisor] =
    for
      connectionId <- ZIO
                        .fromEither(ConnectionId.fresh()).orDieWith(error =>
                          IllegalStateException(error.toString)
                        )
      scope    <- Scope.make
      gate     <- Semaphore.make(1L)
      owner    <- Ref.make(Option.empty[ConnectionSupervisor])
      topology <- NestedTopologyRuntime.make(
                    credentialIssuer,
                    topicFor,
                    lifecycle => owner.get.flatMap(ZIO.foreachDiscard(_)(_.retireById(lifecycle)))
                  )
      supervisor = new ConnectionSupervisor(connectionId, config, topology, scope, gate, observer)
      _ <- owner.set(Some(supervisor))
      _ <- ZIO.addFinalizer(supervisor.close)
    yield supervisor

end ConnectionSupervisor
