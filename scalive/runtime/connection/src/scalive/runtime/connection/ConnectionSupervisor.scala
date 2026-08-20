package scalive.runtime.connection

import zio.*
import zio.http.URL

import scalive.BindingPayload
import scalive.render.BindingId
import scalive.render.EvaluatedTree
import scalive.render.RenderDelta
import scalive.runtime.contracts.*
import scalive.runtime.kernel.SessionEffects
import scalive.runtime.resources.UploadPreflightView
import scalive.runtime.resources.UploadRegistryError
import scalive.runtime.resources.HostedWorkerId
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
    eventName: Option[String] = None,
    rawJson: Option[String] = None
  ): IO[ConnectionError, Unit]

  def componentEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit]

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

  def patch(command: CommandId, destination: URL): IO[ConnectionError, Unit]
  def internalPatch(destination: URL): IO[ConnectionError, Unit]
  def synchronizeUrl(destination: URL): IO[ConnectionError, Unit]
  def componentForToken(token: Object): IO[ConnectionError, Option[ComponentInstanceId]]
  def tree: IO[ConnectionError, EvaluatedTree]
  def awaitFailure: UIO[ConnectionError]
  def pollFailure: UIO[Option[ConnectionError]]
  def awaitClosed: UIO[Unit]
  def close: UIO[Unit]
  def abort(error: ConnectionError): UIO[Unit]
end ConnectedLifecycle

private[connection] object ConnectedLifecycle:
  def apply[Msg, Model](
    connection: RootConnection[Msg, Model],
    topic0: NestedTopic,
    domId0: String
  ): ConnectedLifecycle =
    new ConnectedLifecycle:
      val lifecycle: LifecycleId = connection.lifecycle
      val epoch: Epoch           = connection.epoch
      val topic: NestedTopic     = topic0
      val domId: String          = domId0

      def browserEvent(
        command: CommandId,
        binding: BindingId,
        payload: BindingPayload,
        eventName: Option[String],
        rawJson: Option[String]
      ): IO[ConnectionError, Unit] =
        (eventName, rawJson) match
          case (Some(name), Some(raw)) =>
            connection.offerNamedEvent(command, binding, payload, name, raw)
          case _ => connection.offerEvent(command, binding, payload)

      def componentEvent(
        command: CommandId,
        component: ComponentInstanceId,
        binding: BindingId,
        payload: BindingPayload,
        eventName: String,
        rawJson: String
      ): IO[ConnectionError, Unit] =
        connection.offerComponentNamedEvent(
          command,
          component,
          binding,
          payload,
          eventName,
          rawJson
        )

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

      def patch(command: CommandId, destination: URL): IO[ConnectionError, Unit] =
        connection.offerPatch(command, destination)

      def internalPatch(destination: URL): IO[ConnectionError, Unit] =
        connection.offerInternalPatch(destination)

      def synchronizeUrl(destination: URL): IO[ConnectionError, Unit] =
        connection.synchronizeUrl(destination)

      def componentForToken(
        token: Object
      ): IO[ConnectionError, Option[ComponentInstanceId]] = connection.componentForToken(token)

      def tree: IO[ConnectionError, EvaluatedTree]  = connection.inspectTree
      def awaitFailure: UIO[ConnectionError]        = connection.awaitFailure
      def pollFailure: UIO[Option[ConnectionError]] = connection.pollFailure
      def awaitClosed: UIO[Unit]                    = connection.awaitClosed
      def close: UIO[Unit]                          = connection.close
      def abort(error: ConnectionError): UIO[Unit]  = connection.abort(error)
end ConnectedLifecycle

/** Owns all connected LiveView lifecycles belonging to one physical connection. */
final private[scalive] class ConnectionSupervisor private (
  config: ConnectionConfig,
  topology: NestedTopologyRuntime,
  supervisorScope: Scope.Closeable,
  gate: Semaphore):
  import ConnectionSupervisor.*

  private var state: State = State.empty

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
              requestedLifecycle = requestedLifecycle
            )
          ).map(connection => ConnectedLifecycle(connection, topic, domId))
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
    reattach: Boolean = false,
    requestedLifecycle: Option[LifecycleId] = None
  ): IO[StartError, ConnectedLifecycle] =
    (for
      active <- topology.registration(reservation.registration.id)
      _      <- ZIO
             .fail(StartError.RegistrationRevoked(reservation.registration.id)).unless(
               active.exists(sameCoordinates(_, reservation.registration))
             )
      retained <-
        if reattach && reservation.registration.sticky then
          reattachNested(reservation, inheritedUrl, sink)
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
                        requestedLifecycle
                      )
    yield handle).tapError { error =>
      topology.cancelJoin(reservation) *>
        (error match
          case StartError.ConnectionFailed(connectionError) =>
            linkParentAfterStartFailure(reservation.registration, connectionError)
          case _ => ZIO.unit)
    }

  private def startFreshNested(
    reservation: NestedJoinReservation,
    inheritedUrl: URL,
    metadata: RootConnectionMetadata,
    domId: String,
    loading: Boolean,
    sink: ConnectionOutput => Task[Unit],
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
                    buffer <- StartupBuffer.make(config.writerCapacity, sink)
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
                                      requestedLifecycle = requestedLifecycle
                                    )
                                  )
                    connected = ConnectedLifecycle(
                                  connection,
                                  reservation.registration.topic,
                                  domId
                                )
                  yield PendingNested(connected, buffer, output)
                }(pending => installNested(slot, reservation, pending))
    yield handle

  private def reattachNested(
    reservation: NestedJoinReservation,
    inheritedUrl: URL,
    sink: ConnectionOutput => Task[Unit]
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
                        ownership = EntryOwnership.Reattaching(registration, output)
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
                                     EntryOwnership.Reattaching(active, activeOutput)
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
                                     ownership = EntryOwnership.Attached(registration, output)
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
    eventName: Option[String] = None,
    rawJson: Option[String] = None
  ): IO[ConnectionError, Boolean] =
    route(topic)(_.browserEvent(command, binding, payload, eventName, rawJson))

  def routeComponentEvent(
    topic: NestedTopic,
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Boolean] =
    route(topic)(
      _.componentEvent(command, component, binding, payload, eventName, rawJson)
    )

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
        case Some((handle, EntryOwnership.Attached(registration, output))) if registration.sticky =>
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
          ZIO.succeed((Vector.empty[Entry], Vector.empty[Scope.Closeable], false))
        else
          val entries = state.entries.values.toVector
          val starts  = state.startingScopes.values.toVector
          state = State.empty.copy(closed = true)
          ZIO.succeed((entries, starts, true))
      }.flatMap { case (entries, starts, first) =>
        if !first then ZIO.unit
        else
          topology.close *>
            ZIO.foreachDiscard(entries)(closeEntry) *>
            ZIO.foreachDiscard(starts)(_.close(Exit.unit)) *>
            supervisorScope.close(Exit.unit)
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
                    EntryOwnership.Attached(registration, pending.output)
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
          case Some(current @ Entry(_, _, EntryOwnership.Reattaching(active, activeOutput)))
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
          .get(handle.lifecycle).filter(_.handle.epoch == handle.epoch).map(_.ownership)
      )
    )
    ownership.flatMap {
      case Some(EntryOwnership.Root) if failure.isEmpty =>
        retireRootForNavigation(handle).unit
      case _ =>
        removeExact(handle).flatMap {
          case None        => ZIO.unit
          case Some(entry) =>
            detach(entry).flatMap { activeRegistration =>
              topology.revokeParent(handle.lifecycle, handle.epoch) *>
                closeEntry(entry) *>
                ZIO.foreachDiscard(
                  activeRegistration.filter(_.linkParentOnCrash).zip(failure)
                ) { case (registration, childFailure) =>
                  failParent(registration, handle.lifecycle, childFailure)
                }
            }
        }
    }

  private def linkParentAfterStartFailure(
    registration: NestedRegistration,
    failure: ConnectionError
  ): UIO[Unit] =
    topology.registration(registration.id).flatMap {
      case Some(active) if sameCoordinates(active, registration) && active.linkParentOnCrash =>
        lifecycle(active.parentLifecycle, active.parentEpoch).flatMap {
          case Some(parent) =>
            parent.abort(ConnectionError.LinkedChildJoinFailed(active.id, failure))
          case None => ZIO.unit
        }
      case _ => ZIO.unit
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
        state.entries.get(lifecycle) match
          case Some(entry) if entry.handle.epoch == epoch =>
            removeEntry(entry)
            ZIO.some(entry)
          case _ => ZIO.none
      }.flatMap {
        case None        => ZIO.unit
        case Some(entry) =>
          detach(entry).unit *>
            topology.revokeParent(entry.handle.lifecycle, entry.handle.epoch) *>
            closeEntry(entry)
      }

  private def retireRootForNavigation(handle: ConnectedLifecycle): UIO[Boolean] =
    gate
      .withPermit {
        state.entries.get(handle.lifecycle) match
          case Some(entry @ Entry(_, _, EntryOwnership.Root))
              if entry.handle.epoch == handle.epoch =>
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
                case Some(entry @ Entry(_, _, EntryOwnership.Attached(active, activeOutput)))
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

  private def detach(entry: Entry): UIO[Option[NestedRegistration]] = entry.ownership match
    case EntryOwnership.Attached(registration, _) =>
      topology
        .detachChild(registration.id, entry.handle.lifecycle, entry.handle.epoch)
    case EntryOwnership.Reattaching(registration, _) =>
      topology
        .detachChild(registration.id, entry.handle.lifecycle, entry.handle.epoch)
    case EntryOwnership.Root | EntryOwnership.DetachedSticky(_, _, _) => ZIO.none

  private def detachStickyEntries(
    detached: Vector[DetachedStickyNestedLifecycle]
  ): UIO[Unit] =
    gate
      .withPermit {
        detached.foreach { value =>
          state.entries.get(value.child.lifecycle) match
            case Some(entry @ Entry(_, _, EntryOwnership.Attached(registration, output)))
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
    entry.handle.close *> entry.scope.close(Exit.unit)

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
    retire(handle.lifecycle, handle.epoch)
end ConnectionSupervisor

private[scalive] object ConnectionSupervisor:
  enum StartError:
    case Closed
    case DuplicateTopic(topic: NestedTopic)
    case DuplicateRoot(domId: String)
    case ParentUnavailable(lifecycle: LifecycleId, epoch: Epoch)
    case RegistrationRevoked(registration: NestedRegistrationId)
    case ConnectionFailed(error: ConnectionError)

  enum LeaveResult:
    case Left
    case UnknownTopic

  private enum EntryOwnership:
    case Root
    case Attached(registration: NestedRegistration, output: RebindableSink)
    case Reattaching(registration: NestedRegistration, output: RebindableSink)
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
    buffer: StartupBuffer,
    output: RebindableSink)

  final private case class ReattachSlot(
    entry: Entry,
    registration: NestedRegistration,
    output: RebindableSink)

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

  final private class StartupBuffer(
    capacity: Int,
    destination: ConnectionOutput => Task[Unit],
    gate: Semaphore):
    private var outputs: Vector[ConnectionOutput] = Vector.empty
    private var active: Boolean                   = false

    def offer(output: ConnectionOutput): Task[Unit] = gate.withPermit {
      if active then destination(output)
      else if outputs.size >= capacity then
        ZIO.fail(IllegalStateException(s"nested startup output exceeded capacity $capacity"))
      else
        outputs = outputs :+ output
        ZIO.unit
    }

    def activate: Task[Unit] = gate.withPermit {
      ZIO.foreachDiscard(outputs)(destination) *> ZIO.succeed {
        outputs = Vector.empty
        active = true
      }
    }

  private object StartupBuffer:
    def make(
      capacity: Int,
      destination: ConnectionOutput => Task[Unit]
    ): UIO[StartupBuffer] =
      Semaphore.make(1L).map(new StartupBuffer(capacity, destination, _))

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
    topicFor: String => NestedTopic
  ): ZIO[Scope, Nothing, ConnectionSupervisor] =
    for
      scope    <- Scope.make
      gate     <- Semaphore.make(1L)
      owner    <- Ref.make(Option.empty[ConnectionSupervisor])
      topology <- NestedTopologyRuntime.make(
                    credentialIssuer,
                    topicFor,
                    lifecycle => owner.get.flatMap(ZIO.foreachDiscard(_)(_.retireById(lifecycle)))
                  )
      supervisor = new ConnectionSupervisor(config, topology, scope, gate)
      _ <- owner.set(Some(supervisor))
      _ <- ZIO.addFinalizer(supervisor.close)
    yield supervisor

end ConnectionSupervisor
