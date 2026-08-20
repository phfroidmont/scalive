package scalive.runtime.connection

import zio.IO
import zio.Semaphore
import zio.UIO
import zio.ZIO

import scalive.LiveView
import scalive.runtime.contracts.*
import scalive.runtime.topology.AttachedNestedLifecycle
import scalive.runtime.topology.NestedTopologyNavigation
import scalive.runtime.topology.NestedRegistrationCandidate
import scalive.runtime.topology.NestedTopologyPlan
import scalive.runtime.topology.NestedTopologyState

private[scalive] enum NestedJoinAdmissionError:
  case RegistrationUnavailable(registration: NestedRegistrationId)
  case RegistrationAlreadyPending(registration: NestedRegistrationId)
  case RegistrationAlreadyAttached(registration: NestedRegistrationId)

/** The single existential boundary between admission and child construction. */
sealed private[scalive] trait NestedJoinReservation:
  type Message
  type Model

  def registration: NestedRegistration
  def create(): LiveView[Message, Model]

private[connection] object NestedJoinReservation:
  def apply(registration0: NestedRegistration): NestedJoinReservation =
    Value(registration0, registration0.factory)

  final private case class Value[Message0, Model0](
    registration: NestedRegistration,
    factory: NestedLifecycleFactory { type Message = Message0; type Model = Model0 })
      extends NestedJoinReservation:
    type Message = Message0
    type Model   = Model0

    def create(): LiveView[Message0, Model0] = factory.create()

/** Connection-owned, in-memory nested registration and admission runtime. */
final private[scalive] class NestedTopologyRuntime private (
  credentialIssuer: NestedCredentialIssuer,
  topicFor: String => NestedTopic,
  retireLifecycle: LifecycleId => UIO[Unit],
  gate: Semaphore):
  import NestedTopologyRuntime.*

  private var topology: NestedTopologyState                             = NestedTopologyState.empty
  private var metadata: Map[NestedRegistrationId, ActiveMetadata]       = Map.empty
  private var pending: Map[NestedRegistrationId, NestedJoinReservation] = Map.empty
  private var bootstrapChildLifecycles: Map[(LifecycleId, String), LifecycleId] = Map.empty
  private var closed: Boolean                                                   = false

  def seedChildLifecycles(
    parentLifecycle: LifecycleId,
    children: Map[String, LifecycleId]
  ): UIO[Unit] =
    withGate {
      bootstrapChildLifecycles ++= children.map { case (applicationId, childLifecycle) =>
        (parentLifecycle -> applicationId) -> childLifecycle
      }
      ZIO.unit
    }

  def clearChildLifecycles(parentLifecycle: LifecycleId): UIO[Unit] =
    withGate {
      bootstrapChildLifecycles = bootstrapChildLifecycles.filterNot { case ((parent, _), _) =>
        parent == parentLifecycle
      }
      ZIO.unit
    }

  def preparer(parentDomId: String, loading: Boolean): NestedTopologyPreparer =
    new NestedTopologyPreparer:
      def prepare(
        parentLifecycle: LifecycleId,
        parentEpoch: Epoch,
        parentRevision: TurnRevision,
        requirements: Vector[NestedLifecycleRequirement]
      ): IO[NestedTopologyError, PreparedNestedTopology] =
        prepareTopology(
          parentDomId,
          loading,
          parentLifecycle,
          parentEpoch,
          parentRevision,
          requirements
        )

  private def prepareTopology(
    parentDomId: String,
    loading: Boolean,
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch,
    parentRevision: TurnRevision,
    requirements: Vector[NestedLifecycleRequirement]
  ): IO[NestedTopologyError, PreparedNestedTopology] =
    withGate {
      if closed then ZIO.fail(NestedTopologyError.StaleParent(parentLifecycle, parentEpoch))
      else
        val candidates = requirements.map(requirement =>
          NestedRegistrationCandidate(requirement, topicFor(requirement.applicationId))
        )

        ZIO
          .fromEither(
            topology.prepare(parentLifecycle, parentEpoch, parentRevision, candidates)
          ).flatMap { plan =>
            ZIO
              .foreach(plan.candidateRegistrations) { registration =>
                metadata.get(registration.id) match
                  case Some(retained) => ZIO.succeed(registration -> retained)
                  case None           =>
                    val childLifecycle =
                      if registration.epoch == NestedRegistrationEpoch.initial then
                        bootstrapChildLifecycles.get(
                          registration.parentLifecycle -> registration.applicationId
                        )
                      else None
                    val claims = claimsFor(registration, childLifecycle)
                    credentialIssuer
                      .issue(claims)
                      .map(credentials => registration -> ActiveMetadata(new Object(), credentials))
              }.map { candidateMetadata =>
                val resolutions = candidateMetadata.map { case (registration, active) =>
                  NestedRegistrationResolution(
                    registration = registration.id,
                    instanceToken = active.instanceToken,
                    applicationId = registration.applicationId,
                    parentDomId = parentDomId,
                    topic = registration.topic,
                    joinCredential = active.credentials.join,
                    staticCredential = active.credentials.static,
                    sticky = registration.sticky,
                    loading = loading
                  )
                }
                val metadataById = candidateMetadata.map { case (registration, active) =>
                  registration.id -> active
                }.toMap
                new Prepared(plan, metadataById, resolutions)
              }
          }
    }

  def reserveJoin(
    claims: NestedCredentialClaims
  ): IO[NestedJoinAdmissionError, NestedJoinReservation] =
    withGate {
      val exact = topology
        .validate(
          claims.registration,
          claims.registrationEpoch,
          claims.parentLifecycle,
          claims.parentEpoch
        )
        .filter(_.topic == claims.topic)
      val registration = exact.orElse(
        claims.childLifecycle.flatMap(_ =>
          topology.validateBootstrap(
            claims.registrationEpoch,
            claims.parentLifecycle,
            claims.parentEpoch,
            claims.topic
          )
        )
      )

      registration match
        case None => ZIO.fail(NestedJoinAdmissionError.RegistrationUnavailable(claims.registration))
        case Some(active) if topology.attachedChild(active.id).nonEmpty =>
          ZIO.fail(NestedJoinAdmissionError.RegistrationAlreadyAttached(active.id))
        case Some(active) if pending.contains(active.id) =>
          ZIO.fail(NestedJoinAdmissionError.RegistrationAlreadyPending(active.id))
        case Some(active) if metadata.contains(active.id) =>
          val reservation = NestedJoinReservation(active)
          pending = pending.updated(active.id, reservation)
          ZIO.succeed(reservation)
        case Some(_) =>
          ZIO.fail(NestedJoinAdmissionError.RegistrationUnavailable(claims.registration))
    }

  def completeJoin(
    reservation: NestedJoinReservation,
    childLifecycle: LifecycleId,
    childEpoch: Epoch
  ): UIO[Boolean] =
    withGate {
      val registration = reservation.registration
      val isPending    = pending.get(registration.id).exists(_ eq reservation)
      if !isPending then ZIO.succeed(false)
      else
        pending = pending.removed(registration.id)
        topology
          .attach(
            registration.id,
            registration.epoch,
            registration.parentLifecycle,
            registration.parentEpoch,
            childLifecycle,
            childEpoch
          )
          .fold(
            _ => ZIO.succeed(false),
            attached =>
              topology = attached
              ZIO.succeed(true)
          )
    }

  def beginJoin(reservation: NestedJoinReservation): UIO[Boolean] =
    withGate {
      val registration = reservation.registration
      val active       = topology
        .validate(
          registration.id,
          registration.epoch,
          registration.parentLifecycle,
          registration.parentEpoch
        ).exists(_.topic == registration.topic)
      ZIO.succeed(active && pending.get(registration.id).exists(_ eq reservation))
    }

  def cancelJoin(reservation: NestedJoinReservation): UIO[Unit] =
    withGate {
      val registrationId = reservation.registration.id
      if pending.get(registrationId).exists(_ eq reservation) then
        pending = pending.removed(registrationId)
      ZIO.unit
    }

  def registration(id: NestedRegistrationId): UIO[Option[NestedRegistration]] =
    withGate(ZIO.succeed(topology.registration(id)))

  def registration(claims: NestedCredentialClaims): UIO[Option[NestedRegistration]] =
    withGate {
      ZIO.succeed(
        topology
          .validate(
            claims.registration,
            claims.registrationEpoch,
            claims.parentLifecycle,
            claims.parentEpoch
          )
          .filter(_.topic == claims.topic)
      )
    }

  def attachedChild(id: NestedRegistrationId): UIO[Option[AttachedNestedLifecycle]] =
    withGate(ZIO.succeed(topology.attachedChild(id)))

  def detachChild(
    registrationId: NestedRegistrationId,
    childLifecycle: LifecycleId,
    childEpoch: Epoch
  ): UIO[Option[NestedRegistration]] =
    withGate {
      topology.registration(registrationId) match
        case None               => ZIO.none
        case Some(registration) =>
          topology.detach(registrationId, childLifecycle, childEpoch) match
            case Left(_)        => ZIO.none
            case Right(updated) =>
              topology = updated
              ZIO.some(registration)
    }

  def activeRegistrations: UIO[Vector[NestedRegistration]] =
    withGate(ZIO.succeed(topology.registrations))

  def revokeParent(parentLifecycle: LifecycleId, parentEpoch: Epoch): UIO[Unit] =
    withGate {
      val direct = topology.registrations.filter(_.parentLifecycle == parentLifecycle)
      if closed || direct.isEmpty || direct.exists(_.parentEpoch != parentEpoch) then
        ZIO.succeed(Vector.empty[LifecycleId])
      else
        topology
          .prepare(parentLifecycle, parentEpoch, direct.head.parentRevision, Vector.empty)
          .flatMap(topology.activate)
          .fold(
            error => ZIO.die(new IllegalStateException(s"nested parent revocation failed: $error")),
            activation =>
              installActivation(activation.state, activation.revokedRegistrationIds)
              ZIO.succeed(activation.childLifecycleIdsToRetire)
          )
    }.flatMap(retireAll)

  def detachParentForNavigation(
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch
  ): UIO[NestedTopologyNavigation] =
    withGate {
      val navigation = topology.detachParentForNavigation(parentLifecycle, parentEpoch)
      installActivation(navigation.state, navigation.revokedRegistrationIds)
      ZIO.succeed(navigation)
    }

  def detachStickyForNavigation(
    registrationId: NestedRegistrationId,
    childLifecycle: LifecycleId,
    childEpoch: Epoch
  ): UIO[Option[NestedTopologyNavigation]] =
    withGate {
      topology.detachStickyForNavigation(registrationId, childLifecycle, childEpoch) match
        case None             => ZIO.none
        case Some(navigation) =>
          installActivation(navigation.state, navigation.revokedRegistrationIds)
          ZIO.some(navigation)
    }

  def close: UIO[Unit] =
    withGate {
      if closed then ZIO.succeed(Vector.empty[LifecycleId])
      else
        closed = true
        val children = topology.registrations
          .flatMap(registration =>
            topology.attachedChild(registration.id).map(_.lifecycle)
          ).distinct
        topology = NestedTopologyState.empty
        metadata = Map.empty
        pending = Map.empty
        bootstrapChildLifecycles = Map.empty
        ZIO.succeed(children)
    }.flatMap(retireAll)

  private def installActivation(
    state: NestedTopologyState,
    revoked: Vector[NestedRegistrationId],
    candidates: Map[NestedRegistrationId, ActiveMetadata] = Map.empty
  ): Unit =
    topology = state
    val revokedSet = revoked.toSet
    metadata = (metadata -- revokedSet) ++ candidates
    pending = pending -- revokedSet

  private def retireAll(lifecycles: Vector[LifecycleId]): UIO[Unit] =
    ZIO.foreachDiscard(lifecycles.distinct)(retireLifecycle)

  private def withGate[E, A](effect: => IO[E, A]): IO[E, A] =
    gate.withPermit(ZIO.suspendSucceed(effect))

  final private class Prepared(
    plan: NestedTopologyPlan,
    candidates: Map[NestedRegistrationId, ActiveMetadata],
    val resolutions: Vector[NestedRegistrationResolution])
      extends PreparedNestedTopology:
    private var status: PreparationStatus            = PreparationStatus.Fresh
    private var retiredChildren: Vector[LifecycleId] = Vector.empty

    val activate: UIO[Unit] = withGate {
      status match
        case PreparationStatus.Fresh if closed =>
          ZIO.die(new IllegalStateException("nested topology runtime is closed"))
        case PreparationStatus.Fresh =>
          topology.activate(plan) match
            case Left(error) =>
              ZIO.die(new IllegalStateException(s"stale nested topology activation: $error"))
            case Right(activation) =>
              installActivation(
                activation.state,
                activation.revokedRegistrationIds,
                candidates
              )
              retiredChildren = activation.childLifecycleIdsToRetire
              status = PreparationStatus.Activated
              ZIO.unit
        case _ => ZIO.unit
    }

    val release: UIO[Unit] = withGate {
      if status == PreparationStatus.Fresh then status = PreparationStatus.Released
      ZIO.unit
    }

    val retire: UIO[Unit] = withGate {
      if status == PreparationStatus.Activated then
        status = PreparationStatus.Retired
        ZIO.succeed(retiredChildren)
      else ZIO.succeed(Vector.empty[LifecycleId])
    }.flatMap(retireAll)
  end Prepared
end NestedTopologyRuntime

private[scalive] object NestedTopologyRuntime:
  final private case class ActiveMetadata(
    instanceToken: Object,
    credentials: IssuedNestedCredentials)

  private enum PreparationStatus:
    case Fresh, Activated, Released, Retired

  def make(
    credentialIssuer: NestedCredentialIssuer,
    topicFor: String => NestedTopic,
    retireLifecycle: LifecycleId => UIO[Unit]
  ): UIO[NestedTopologyRuntime] =
    Semaphore
      .make(1L).map(gate =>
        new NestedTopologyRuntime(credentialIssuer, topicFor, retireLifecycle, gate)
      )

  private def claimsFor(
    registration: NestedRegistration,
    childLifecycle: Option[LifecycleId]
  ): NestedCredentialClaims =
    NestedCredentialClaims(
      registration = registration.id,
      registrationEpoch = registration.epoch,
      parentLifecycle = registration.parentLifecycle,
      parentEpoch = registration.parentEpoch,
      topic = registration.topic,
      childLifecycle = childLifecycle
    )
end NestedTopologyRuntime
