package scalive

import zio.*

/** Framework-owned control of physical LiveView connections. */
final class LiveConnections[Id] private[scalive] (
  state: Ref[LiveConnections.State[Id]],
  publish: Id => Task[Unit]):
  /** Closes every connection registered under `id`, so each browser must pass admission again.
    *
    * Connections register under the ID that [[LiveSessionBuilder.withAdmission]] extracts from
    * signed session claims. Connections on this node close first; with
    * [[LiveConnections.distributed]], the ID is then published to the other nodes, and the call
    * fails only when that publication fails. Disconnecting an ID with no connections succeeds.
    */
  def disconnect(id: Id): Task[Unit] =
    disconnectLocal(id) *> publish(id)

  private[scalive] def begin(
    id: Id,
    connection: LiveConnections.ConnectionKey,
    disconnect: UIO[Unit]
  ): IO[LiveConnections.BindingConflict[Id], LiveConnections.Admission[Id]] =
    ZIO.suspendSucceed {
      val token = new LiveConnections.AdmissionToken[Id](connection)

      state
        .modify[Either[LiveConnections.BindingConflict[Id], LiveConnections.Admission[Id]]] {
          current =>
            current.bindings.get(connection) match
              case None =>
                val binding = LiveConnections.Binding(
                  id,
                  token,
                  disconnect,
                  committed = false,
                  signaled = false
                )
                Right(LiveConnections.Admission.Begun(token)) -> current.copy(
                  bindings = current.bindings.updated(connection, binding)
                )
              case Some(binding) if binding.id == id =>
                Right(LiveConnections.Admission.Existing(binding.token)) -> current
              case Some(binding) =>
                Left(LiveConnections.BindingConflict(binding.id, id)) -> current
        }.flatMap(ZIO.fromEither(_))
    }

  private[scalive] def commit(token: LiveConnections.AdmissionToken[Id]): UIO[Unit] =
    state.update { current =>
      current.bindings.get(token.connection) match
        case Some(binding) if binding.token.eq(token) && !binding.committed =>
          current.copy(bindings =
            current.bindings.updated(token.connection, binding.copy(committed = true))
          )
        case _ => current
    }

  private[scalive] def rollback(token: LiveConnections.AdmissionToken[Id]): UIO[Unit] =
    state.update { current =>
      current.bindings.get(token.connection) match
        case Some(binding) if binding.token.eq(token) && !binding.committed =>
          current.copy(bindings = current.bindings.removed(token.connection))
        case _ => current
    }

  private[scalive] def remove(token: LiveConnections.AdmissionToken[Id]): UIO[Unit] =
    state.update { current =>
      current.bindings.get(token.connection) match
        case Some(binding) if binding.token.eq(token) =>
          current.copy(bindings = current.bindings.removed(token.connection))
        case _ => current
    }

  private[scalive] def bindingCount: UIO[Int] = state.get.map(_.bindings.size)

  private[scalive] def disconnectLocal(id: Id): UIO[Unit] =
    state
      .modify { current =>
        val (updated, controls) = current.bindings.values.foldLeft(
          current.bindings -> List.empty[UIO[Unit]]
        ) { case ((bindings, controls), binding) =>
          if binding.id == id && !binding.signaled then
            bindings.updated(
              binding.token.connection,
              binding.copy(signaled = true)
            )           -> (binding.disconnect :: controls)
          else bindings -> controls
        }

        controls -> current.copy(bindings = updated)
      }
      .flatMap(controls => ZIO.foreachDiscard(controls)(_.exit)).uninterruptible
end LiveConnections

/** Layers that provide [[LiveConnections]], and an accessor for its `disconnect`. */
object LiveConnections:
  /** Closes every connection registered under `id`, so each browser must pass admission again.
    *
    * This form uses the `LiveConnections[Id]` service from the ZIO environment.
    */
  def disconnect[Id: Tag](id: Id): ZIO[LiveConnections[Id], Throwable, Unit] =
    ZIO.serviceWithZIO[LiveConnections[Id]](_.disconnect(id))

  /** Provides connection control for a single backend instance.
    *
    * `disconnect` closes connections on this node only. Use [[distributed]] when several instances
    * serve the same sessions.
    */
  def local[Id: Tag]: ULayer[LiveConnections[Id]] =
    ZLayer.fromZIO(make(_ => ZIO.unit))

  /** Provides connection control that also reaches other nodes through a [[LiveDisconnectBus]].
    *
    * `disconnect` closes connections on this node and then publishes the ID on the bus. IDs
    * received from the bus close matching connections on this node without being published again.
    * The layer stays subscribed for as long as its scope is open.
    */
  def distributed[Id: Tag]: ZLayer[LiveDisconnectBus[Id], Throwable, LiveConnections[Id]] =
    ZLayer.scoped {
      for
        bus         <- ZIO.service[LiveDisconnectBus[Id]]
        connections <- make(bus.publish)
        _           <- bus.subscribe(connections.disconnectLocal)
      yield connections
    }

  final private[scalive] class ConnectionKey

  final private[scalive] class AdmissionToken[Id] private[scalive] (
    private[scalive] val connection: ConnectionKey)

  private[scalive] enum Admission[Id]:
    case Begun(admissionToken: AdmissionToken[Id])
    case Existing(admissionToken: AdmissionToken[Id])

    def token: AdmissionToken[Id] = this match
      case Begun(token)    => token
      case Existing(token) => token

    def bindingAlreadyExisted: Boolean = this match
      case Begun(_)    => false
      case Existing(_) => true

  final private[scalive] case class BindingConflict[Id](existingId: Id, attemptedId: Id)
      extends Exception("A physical LiveView connection cannot bind to more than one session ID")

  final private[scalive] case class Binding[Id](
    id: Id,
    token: AdmissionToken[Id],
    disconnect: UIO[Unit],
    committed: Boolean,
    signaled: Boolean)

  final private[scalive] case class State[Id](bindings: Map[ConnectionKey, Binding[Id]])

  private[scalive] def make[Id](publish: Id => Task[Unit]): UIO[LiveConnections[Id]] =
    Ref.make(State[Id](Map.empty)).map(state => new LiveConnections(state, publish))
end LiveConnections
