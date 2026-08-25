package scalive.runtime.topology

import scalive.runtime.contracts.Epoch
import scalive.runtime.contracts.LifecycleId
import scalive.runtime.contracts.NestedLifecycleRequirement
import scalive.runtime.contracts.NestedRegistration
import scalive.runtime.contracts.NestedRegistrationEpoch
import scalive.runtime.contracts.NestedRegistrationId
import scalive.runtime.contracts.NestedTopic
import scalive.runtime.contracts.NestedTopologyError
import scalive.runtime.contracts.TurnRevision

final private[scalive] case class NestedRegistrationCandidate(
  requirement: NestedLifecycleRequirement,
  topic: NestedTopic)

final private[scalive] case class AttachedNestedLifecycle(
  lifecycle: LifecycleId,
  epoch: Epoch)

private[scalive] enum NestedAttachmentError:
  case RegistrationUnavailable(registration: NestedRegistrationId)
  case RegistrationAlreadyAttached(registration: NestedRegistrationId)
  case LifecycleAlreadyAttached(lifecycle: LifecycleId, epoch: Epoch)
  case AttachedLifecycleMismatch(registration: NestedRegistrationId)

private[scalive] enum NestedTopologyActivationError:
  case StalePlan(
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch,
    preparedBaseDirectRegistrationIds: Vector[NestedRegistrationId],
    currentDirectRegistrationIds: Vector[NestedRegistrationId])

final private[scalive] case class NestedTopologyActivation(
  state: NestedTopologyState,
  revokedRegistrationIds: Vector[NestedRegistrationId],
  childLifecycleIdsToRetire: Vector[LifecycleId])

final private[scalive] case class DetachedStickyNestedLifecycle(
  registration: NestedRegistration,
  child: AttachedNestedLifecycle)

final private[scalive] case class NestedTopologyNavigation(
  state: NestedTopologyState,
  revokedRegistrationIds: Vector[NestedRegistrationId],
  detachedStickyChildren: Vector[DetachedStickyNestedLifecycle],
  childLifecycleIdsToRetire: Vector[LifecycleId])

/** Immutable active nested-registration forest.
  *
  * Entries are kept flat: an attached lifecycle identifies the exact parent of registrations below
  * it. This makes subtree revocation explicit without introducing child runtime handles into the
  * topology core.
  */
final private[scalive] case class NestedTopologyState private (
  private val entries: Vector[NestedTopologyState.Entry],
  private val latestEpochs: Map[(LifecycleId, String), NestedRegistrationEpoch]):
  import NestedTopologyState.*

  def registrations: Vector[NestedRegistration] = entries.map(_.registration)

  def registration(id: NestedRegistrationId): Option[NestedRegistration] =
    entries.find(_.registration.id == id).map(_.registration)

  def attachedChild(id: NestedRegistrationId): Option[AttachedNestedLifecycle] =
    entries.find(_.registration.id == id).flatMap(_.child)

  /** Exact active-registration validation for connection admission. */
  def validate(
    id: NestedRegistrationId,
    registrationEpoch: NestedRegistrationEpoch,
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch
  ): Option[NestedRegistration] =
    entries
      .find { entry =>
        val registration = entry.registration
        registration.id == id &&
        registration.epoch == registrationEpoch &&
        registration.parentLifecycle == parentLifecycle &&
        registration.parentEpoch == parentEpoch
      }
      .map(_.registration)

  /** Resolves a disconnected bootstrap identity to the connected registration at the same exact
    * topology coordinates. The registration epoch still makes replacement claims stale.
    */
  def validateBootstrap(
    registrationEpoch: NestedRegistrationEpoch,
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch,
    topic: NestedTopic
  ): Option[NestedRegistration] =
    entries
      .find { entry =>
        val registration = entry.registration
        registration.epoch == registrationEpoch &&
        registration.parentLifecycle == parentLifecycle &&
        registration.parentEpoch == parentEpoch &&
        registration.topic == topic
      }
      .map(_.registration)

  def isActive(
    id: NestedRegistrationId,
    registrationEpoch: NestedRegistrationEpoch,
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch
  ): Boolean =
    validate(id, registrationEpoch, parentLifecycle, parentEpoch).nonEmpty

  /** Computes a candidate topology. No active state is changed until the caller chooses the plan's
    * `state` value.
    */
  def prepare(
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch,
    parentRevision: TurnRevision,
    candidates: Vector[NestedRegistrationCandidate]
  ): Either[NestedTopologyError, NestedTopologyPlan] =
    validateApplicationIds(candidates).flatMap { _ =>
      val previous = entries.filter(_.registration.parentLifecycle == parentLifecycle)

      val prepared = candidates.foldLeft[Either[NestedTopologyError, Prepared]](
        Right(Prepared(Vector.empty, latestEpochs))
      ) { (result, candidate) =>
        result.flatMap { prepared =>
          val existing =
            previous.find(_.registration.applicationId == candidate.requirement.applicationId)
          existing match
            case Some(entry)
                if compatible(entry.registration, parentEpoch, candidate.requirement) =>
              val retained = entry.copy(registration =
                retainedRegistration(entry.registration, parentRevision, candidate)
              )
              Right(prepared.copy(entries = prepared.entries :+ retained))
            case _ =>
              allocateRegistration(
                parentLifecycle,
                parentEpoch,
                parentRevision,
                candidate,
                prepared.latestEpochs
              ).map { case (entry, epochs) =>
                Prepared(prepared.entries :+ entry, epochs)
              }
        }
      }

      prepared.map { candidateState =>
        val retainedIds     = candidateState.entries.iterator.map(_.registration.id).toSet
        val directlyRevoked =
          previous.filterNot(entry => retainedIds.contains(entry.registration.id))
        val revoked    = collectRevokedSubtrees(directlyRevoked)
        val revokedIds = revoked.iterator.map(_.registration.id).toSet
        val unaffected = entries.filterNot { entry =>
          entry.registration.parentLifecycle == parentLifecycle || revokedIds.contains(
            entry.registration.id
          )
        }
        val nextEntries = unaffected ++ candidateState.entries
        val nextEpochs  = revoked.foldLeft(candidateState.latestEpochs) { (epochs, entry) =>
          remember(epochs, entry.registration)
        }

        NestedTopologyPlan(
          state = NestedTopologyState(nextEntries, nextEpochs),
          parentLifecycle = parentLifecycle,
          parentEpoch = parentEpoch,
          preparedBaseDirectRegistrationIds = previous.map(_.registration.id),
          candidateRegistrations = candidateState.entries.map(_.registration),
          revokedRegistrationIds = revoked.map(_.registration.id),
          childLifecycleIdsToRetire = revoked.flatMap(_.child.map(_.lifecycle)).distinct
        )
      }
    }

  /** Activates a plan against this state, merging attachments which arrived after preparation and
    * deriving retirement from the current forest.
    */
  def activate(
    plan: NestedTopologyPlan
  ): Either[NestedTopologyActivationError, NestedTopologyActivation] =
    val currentDirect    = entries.filter(_.registration.parentLifecycle == plan.parentLifecycle)
    val currentDirectIds = currentDirect.map(_.registration.id)
    val preparedBaseIds  = plan.preparedBaseDirectRegistrationIds

    if currentDirectIds.toSet != preparedBaseIds.toSet then
      Left(
        NestedTopologyActivationError.StalePlan(
          plan.parentLifecycle,
          plan.parentEpoch,
          preparedBaseIds,
          currentDirectIds
        )
      )
    else
      val retainedIds     = plan.candidateRegistrations.iterator.map(_.id).toSet
      val directlyRevoked =
        currentDirect.filterNot(entry => retainedIds.contains(entry.registration.id))
      val revoked    = collectRevokedSubtrees(directlyRevoked)
      val revokedIds = revoked.iterator.map(_.registration.id).toSet
      val unaffected = entries.filterNot { entry =>
        entry.registration.parentLifecycle == plan.parentLifecycle || revokedIds.contains(
          entry.registration.id
        )
      }
      val installed = plan.candidateRegistrations.map { registration =>
        val currentChild = currentDirect
          .find(_.registration.id == registration.id)
          .flatMap(_.child)
        Entry(registration, currentChild)
      }
      val rememberedCandidates = plan.candidateRegistrations.foldLeft(latestEpochs)(remember)
      val nextEpochs           = revoked.foldLeft(rememberedCandidates) { (epochs, entry) =>
        remember(epochs, entry.registration)
      }

      Right(
        NestedTopologyActivation(
          state = NestedTopologyState(unaffected ++ installed, nextEpochs),
          revokedRegistrationIds = revoked.map(_.registration.id),
          childLifecycleIdsToRetire = revoked.flatMap(_.child.map(_.lifecycle)).distinct
        )
      )
    end if
  end activate

  /** Installs an exact child identity once; stale or duplicate joins are rejected. */
  def attach(
    registrationId: NestedRegistrationId,
    registrationEpoch: NestedRegistrationEpoch,
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch,
    childLifecycle: LifecycleId,
    childEpoch: Epoch
  ): Either[NestedAttachmentError, NestedTopologyState] =
    val position = entries.indexWhere { entry =>
      val registration = entry.registration
      registration.id == registrationId &&
      registration.epoch == registrationEpoch &&
      registration.parentLifecycle == parentLifecycle &&
      registration.parentEpoch == parentEpoch
    }

    if position < 0 then Left(NestedAttachmentError.RegistrationUnavailable(registrationId))
    else if entries(position).child.nonEmpty then
      Left(NestedAttachmentError.RegistrationAlreadyAttached(registrationId))
    else if entries.exists(_.child.contains(AttachedNestedLifecycle(childLifecycle, childEpoch)))
    then Left(NestedAttachmentError.LifecycleAlreadyAttached(childLifecycle, childEpoch))
    else
      val attached =
        entries(position).copy(child = Some(AttachedNestedLifecycle(childLifecycle, childEpoch)))
      Right(copy(entries = entries.updated(position, attached)))

  /** Removes only the exact child while retaining its active registration. */
  def detach(
    registrationId: NestedRegistrationId,
    childLifecycle: LifecycleId,
    childEpoch: Epoch
  ): Either[NestedAttachmentError, NestedTopologyState] =
    val position = entries.indexWhere(_.registration.id == registrationId)
    if position < 0 then Left(NestedAttachmentError.RegistrationUnavailable(registrationId))
    else
      entries(position).child match
        case Some(AttachedNestedLifecycle(`childLifecycle`, `childEpoch`)) =>
          Right(copy(entries = entries.updated(position, entries(position).copy(child = None))))
        case _ => Left(NestedAttachmentError.AttachedLifecycleMismatch(registrationId))

  /** Revokes one navigating parent while retaining the subtrees of its attached sticky children.
    * Ordinary reconciliation never uses this path, so conditional removal still retires sticky
    * children.
    */
  def detachParentForNavigation(
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch
  ): NestedTopologyNavigation =
    val direct = entries.filter(entry =>
      entry.registration.parentLifecycle == parentLifecycle &&
        entry.registration.parentEpoch == parentEpoch
    )
    val sticky     = direct.filter(_.registration.sticky)
    val navigation = collectNavigation(direct.filterNot(_.registration.sticky))
    val removed    = sticky ++ navigation.removed
    val removedIds = removed.iterator.map(_.registration.id).toSet
    val detached   = sticky.flatMap(entry =>
      entry.child.map(DetachedStickyNestedLifecycle(entry.registration, _))
    ) ++ navigation.detached
    val nextEpochs = removed.foldLeft(latestEpochs) { (epochs, entry) =>
      remember(epochs, entry.registration)
    }

    NestedTopologyNavigation(
      state = NestedTopologyState(
        entries.filterNot(entry => removedIds(entry.registration.id)),
        nextEpochs
      ),
      revokedRegistrationIds = removed.map(_.registration.id),
      detachedStickyChildren = detached,
      childLifecycleIdsToRetire = navigation.lifecycleIdsToRetire.distinct
    )

  def detachStickyForNavigation(
    registrationId: NestedRegistrationId,
    childLifecycle: LifecycleId,
    childEpoch: Epoch
  ): Option[NestedTopologyNavigation] =
    entries
      .find(entry =>
        entry.registration.id == registrationId &&
          entry.registration.sticky &&
          entry.child.contains(AttachedNestedLifecycle(childLifecycle, childEpoch))
      ).map { entry =>
        NestedTopologyNavigation(
          state = NestedTopologyState(
            entries.filterNot(_.registration.id == registrationId),
            remember(latestEpochs, entry.registration)
          ),
          revokedRegistrationIds = Vector(registrationId),
          detachedStickyChildren = Vector(
            DetachedStickyNestedLifecycle(
              entry.registration,
              AttachedNestedLifecycle(childLifecycle, childEpoch)
            )
          ),
          childLifecycleIdsToRetire = Vector.empty
        )
      }

  private def collectRevokedSubtrees(roots: Vector[Entry]): Vector[Entry] =
    def loop(pending: Vector[Entry], collected: Vector[Entry]): Vector[Entry] =
      pending.headOption match
        case None        => collected
        case Some(entry) =>
          val descendants = entry.child.toVector.flatMap { child =>
            entries.filter { candidate =>
              candidate.registration.parentLifecycle == child.lifecycle &&
              candidate.registration.parentEpoch == child.epoch
            }
          }
          loop(pending.tail ++ descendants, collected :+ entry)

    loop(roots, Vector.empty)

  private def collectNavigation(roots: Vector[Entry]): NavigationCollection =
    def loop(
      pending: Vector[Entry],
      removed: Vector[Entry],
      detached: Vector[DetachedStickyNestedLifecycle],
      lifecycleIdsToRetire: Vector[LifecycleId]
    ): NavigationCollection =
      pending.headOption match
        case None => NavigationCollection(removed, detached, lifecycleIdsToRetire)
        case Some(entry) if entry.registration.sticky =>
          val sticky =
            entry.child.map(DetachedStickyNestedLifecycle(entry.registration, _)).toVector
          loop(pending.tail, removed :+ entry, detached ++ sticky, lifecycleIdsToRetire)
        case Some(entry) =>
          val children    = entry.child.toVector
          val descendants = children.flatMap { child =>
            entries.filter(candidate =>
              candidate.registration.parentLifecycle == child.lifecycle &&
                candidate.registration.parentEpoch == child.epoch
            )
          }
          loop(
            pending.tail ++ descendants,
            removed :+ entry,
            detached,
            lifecycleIdsToRetire ++ children.map(_.lifecycle)
          )

    loop(roots, Vector.empty, Vector.empty, Vector.empty)
end NestedTopologyState

private[scalive] object NestedTopologyState:
  final private case class Entry(
    registration: NestedRegistration,
    child: Option[AttachedNestedLifecycle])

  final private case class Prepared(
    entries: Vector[Entry],
    latestEpochs: Map[(LifecycleId, String), NestedRegistrationEpoch])

  final private case class NavigationCollection(
    removed: Vector[Entry],
    detached: Vector[DetachedStickyNestedLifecycle],
    lifecycleIdsToRetire: Vector[LifecycleId])

  val empty: NestedTopologyState = NestedTopologyState(Vector.empty, Map.empty)

  private def validateApplicationIds(
    candidates: Vector[NestedRegistrationCandidate]
  ): Either[NestedTopologyError, Unit] =
    val ids = candidates.map(_.requirement.applicationId)
    ids.find(_.trim.isEmpty) match
      case Some(id) => Left(NestedTopologyError.InvalidApplicationId(id))
      case None     =>
        ids.groupBy(identity).collectFirst {
          case (id, occurrences) if occurrences.size > 1 => id
        } match
          case Some(id) => Left(NestedTopologyError.DuplicateApplicationId(id))
          case None     => Right(())

  private def compatible(
    registration: NestedRegistration,
    parentEpoch: Epoch,
    requirement: NestedLifecycleRequirement
  ): Boolean =
    registration.parentEpoch == parentEpoch &&
      registration.applicationId == requirement.applicationId &&
      registration.sticky == requirement.sticky

  private def retainedRegistration(
    registration: NestedRegistration,
    parentRevision: TurnRevision,
    candidate: NestedRegistrationCandidate
  ): NestedRegistration =
    registration.copy(
      parentRevision = parentRevision,
      linkParentOnCrash = candidate.requirement.linkParentOnCrash,
      factory = candidate.requirement.factory
    )

  private def allocateRegistration(
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch,
    parentRevision: TurnRevision,
    candidate: NestedRegistrationCandidate,
    epochs: Map[(LifecycleId, String), NestedRegistrationEpoch]
  ): Either[NestedTopologyError, (Entry, Map[(LifecycleId, String), NestedRegistrationEpoch])] =
    val applicationId = candidate.requirement.applicationId
    val nextEpoch     = epochs.get(parentLifecycle -> applicationId) match
      case Some(epoch) => NestedRegistrationEpoch.next(epoch)
      case None        => Right(NestedRegistrationEpoch.initial)

    for
      epoch <- nextEpoch.left.map(NestedTopologyError.IdentityUnavailable.apply)
      id    <- NestedRegistrationId.fresh().left.map(NestedTopologyError.IdentityUnavailable.apply)
    yield
      val registration = NestedRegistration(
        id = id,
        epoch = epoch,
        parentLifecycle = parentLifecycle,
        parentEpoch = parentEpoch,
        parentRevision = parentRevision,
        applicationId = applicationId,
        topic = candidate.topic,
        sticky = candidate.requirement.sticky,
        linkParentOnCrash = candidate.requirement.linkParentOnCrash,
        factory = candidate.requirement.factory
      )
      Entry(registration, None) -> epochs.updated(parentLifecycle -> applicationId, epoch)

  private def remember(
    epochs: Map[(LifecycleId, String), NestedRegistrationEpoch],
    registration: NestedRegistration
  ): Map[(LifecycleId, String), NestedRegistrationEpoch] =
    val key = registration.parentLifecycle -> registration.applicationId
    epochs.get(key) match
      case Some(epoch) if epoch.value >= registration.epoch.value => epochs
      case _ => epochs.updated(key, registration.epoch)
end NestedTopologyState

/** Immutable, inert result of reconciling one parent's candidate graph. */
final private[scalive] case class NestedTopologyPlan(
  state: NestedTopologyState,
  parentLifecycle: LifecycleId,
  parentEpoch: Epoch,
  preparedBaseDirectRegistrationIds: Vector[NestedRegistrationId],
  candidateRegistrations: Vector[NestedRegistration],
  revokedRegistrationIds: Vector[NestedRegistrationId],
  childLifecycleIdsToRetire: Vector[LifecycleId])
