package scalive.runtime.contracts

import zio.IO
import zio.UIO

import scalive.LiveView

/** Deferred child construction crossing the render/runtime boundary once. */
sealed private[scalive] trait NestedLifecycleFactory:
  type Message
  type Model

  def create(): LiveView[Message, Model]

private[scalive] object NestedLifecycleFactory:
  def apply[Msg, Model](factory: () => LiveView[Msg, Model]): NestedLifecycleFactory =
    Value(factory)

  final private case class Value[Msg0, Model0](factory: () => LiveView[Msg0, Model0])
      extends NestedLifecycleFactory:
    type Message = Msg0
    type Model   = Model0

    def create(): LiveView[Msg0, Model0] = factory()

final private[scalive] case class NestedLifecycleRequirement(
  applicationId: String,
  sticky: Boolean,
  linkParentOnCrash: Boolean,
  factory: NestedLifecycleFactory)

opaque type NestedTopic = String

private[scalive] object NestedTopic:
  def apply(value: String): NestedTopic = value

  extension (topic: NestedTopic) def value: String = topic

opaque type NestedJoinCredential = String

private[scalive] object NestedJoinCredential:
  def apply(value: String): NestedJoinCredential = value

  extension (credential: NestedJoinCredential) def value: String = credential

opaque type NestedStaticCredential = String

private[scalive] object NestedStaticCredential:
  def apply(value: String): NestedStaticCredential = value

  extension (credential: NestedStaticCredential) def value: String = credential

final private[scalive] case class NestedCredentialClaims(
  registration: NestedRegistrationId,
  registrationEpoch: NestedRegistrationEpoch,
  parentLifecycle: LifecycleId,
  parentEpoch: Epoch,
  topic: NestedTopic,
  childLifecycle: Option[LifecycleId] = None)

final private[scalive] case class IssuedNestedCredentials(
  join: NestedJoinCredential,
  static: Option[NestedStaticCredential])

/** Transport-owned credential issuance used only after exact topology identity allocation. */
trait NestedCredentialIssuer:
  def issue(claims: NestedCredentialClaims): UIO[IssuedNestedCredentials]

/** Active or candidate registration metadata without Phoenix field names. */
final private[scalive] case class NestedRegistration(
  id: NestedRegistrationId,
  epoch: NestedRegistrationEpoch,
  parentLifecycle: LifecycleId,
  parentEpoch: Epoch,
  parentRevision: TurnRevision,
  applicationId: String,
  topic: NestedTopic,
  sticky: Boolean,
  linkParentOnCrash: Boolean,
  factory: NestedLifecycleFactory)

/** Candidate answer consumed by the renderer in declaration order. */
final private[scalive] case class NestedRegistrationResolution(
  registration: NestedRegistrationId,
  instanceToken: Object,
  applicationId: String,
  parentDomId: String,
  topic: NestedTopic,
  joinCredential: NestedJoinCredential,
  staticCredential: Option[NestedStaticCredential],
  sticky: Boolean,
  loading: Boolean)

enum NestedTopologyError:
  case DuplicateApplicationId(applicationId: String)
  case InvalidApplicationId(applicationId: String)
  case IdentityUnavailable(error: RuntimeIdentityError)
  case StaleParent(lifecycle: LifecycleId, epoch: Epoch)
  case PreparationRejected(details: String)

/** Inactive connection-owned topology reservation prepared for one session turn. */
trait PreparedNestedTopology:
  def resolutions: Vector[NestedRegistrationResolution]

  /** Makes candidate registrations visible and old registrations stale. */
  def activate: UIO[Unit]

  /** Releases an inactive preparation. */
  def release: UIO[Unit]

  /** Closes lifecycles retired by successful activation. */
  def retire: UIO[Unit]

/** Kernel-facing preparation port implemented by the connection owner. */
trait NestedTopologyPreparer:
  def prepare(
    parentLifecycle: LifecycleId,
    parentEpoch: Epoch,
    parentRevision: TurnRevision,
    requirements: Vector[NestedLifecycleRequirement]
  ): IO[NestedTopologyError, PreparedNestedTopology]

private[scalive] object NestedTopologyPreparer:
  val unavailable: NestedTopologyPreparer = new NestedTopologyPreparer:
    def prepare(
      parentLifecycle: LifecycleId,
      parentEpoch: Epoch,
      parentRevision: TurnRevision,
      requirements: Vector[NestedLifecycleRequirement]
    ): IO[NestedTopologyError, PreparedNestedTopology] =
      if requirements.isEmpty then zio.ZIO.succeed(PreparedNestedTopology.empty)
      else zio.ZIO.fail(NestedTopologyError.PreparationRejected("nested topology is unavailable"))

private[scalive] object PreparedNestedTopology:
  val empty: PreparedNestedTopology = new PreparedNestedTopology:
    val resolutions: Vector[NestedRegistrationResolution] = Vector.empty
    val activate: UIO[Unit]                               = zio.ZIO.unit
    val release: UIO[Unit]                                = zio.ZIO.unit
    val retire: UIO[Unit]                                 = zio.ZIO.unit
