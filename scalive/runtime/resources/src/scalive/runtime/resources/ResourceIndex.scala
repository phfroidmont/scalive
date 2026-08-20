package scalive.runtime.resources

import scalive.runtime.contracts.ComponentInstanceId
import scalive.runtime.contracts.Epoch
import scalive.runtime.contracts.LifecycleId
import scalive.runtime.contracts.RuntimeIdentityError

private[scalive] enum OwnerId:
  case Root(lifecycle: LifecycleId)
  case Component(lifecycle: LifecycleId, component: ComponentInstanceId)

private[scalive] enum ResourceKey:
  case Async(name: String)
  case Subscription(name: String)
  case Stream(name: String)
  case Client(name: String)
  case Upload(name: String)

final private[scalive] case class ResourceToken private (
  owner: OwnerId,
  ownerEpoch: Epoch,
  key: ResourceKey,
  generation: Long):

  def advance(epoch: Epoch = ownerEpoch): Either[RuntimeIdentityError, ResourceToken] =
    if generation == Long.MaxValue then Left(RuntimeIdentityError.Exhausted("resource generation"))
    else Right(ResourceToken.unsafe(owner, epoch, key, generation + 1L))

private[scalive] object ResourceToken:
  def initial(owner: OwnerId, ownerEpoch: Epoch, key: ResourceKey): ResourceToken =
    unsafe(owner, ownerEpoch, key, 1L)

  def from(
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: ResourceKey,
    generation: Long
  ): Option[ResourceToken] =
    Option.when(generation > 0L)(unsafe(owner, ownerEpoch, key, generation))

  private def unsafe(
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: ResourceKey,
    generation: Long
  ): ResourceToken =
    new ResourceToken(owner, ownerEpoch, key, generation)

final private[scalive] case class ResourceIndex[A] private (
  private val entries: Vector[ResourceIndex.Entry[A]],
  private val latestTokens: Map[(OwnerId, ResourceKey), ResourceToken]):
  import ResourceIndex.*

  def get(owner: OwnerId, key: ResourceKey): Option[A] =
    entry(owner, key).map(_.handle)

  def replace(
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: ResourceKey,
    handle: A
  ): Either[RuntimeIdentityError, Replacement[A]] =
    nextToken(owner, ownerEpoch, key).map(token => install(token, handle))

  def nextToken(
    owner: OwnerId,
    ownerEpoch: Epoch,
    key: ResourceKey
  ): Either[RuntimeIdentityError, ResourceToken] =
    latestTokens.get(owner -> key) match
      case Some(current) => current.advance(ownerEpoch)
      case None          => Right(ResourceToken.initial(owner, ownerEpoch, key))

  def install(token: ResourceToken, handle: A): Replacement[A] =
    val position =
      entries.indexWhere(entry => entry.token.owner == token.owner && entry.token.key == token.key)
    val previous    = entries.lift(position)
    val nextEntry   = Entry(token, handle)
    val nextEntries =
      if position < 0 then entries :+ nextEntry else entries.updated(position, nextEntry)
    val nextTokens = latestTokens.updated(token.owner -> token.key, token)
    Replacement(ResourceIndex(nextEntries, nextTokens), token, previous.map(_.handle))

  def isCurrent(token: ResourceToken): Boolean =
    entry(token.owner, token.key).exists(_.token == token)

  def current(token: ResourceToken): Option[A] =
    entry(token.owner, token.key).filter(_.token == token).map(_.handle)

  def remove(owner: OwnerId, key: ResourceKey): Removal[A] =
    val (removed, retained) = entries.partition { entry =>
      entry.token.owner == owner && entry.token.key == key
    }
    Removal(ResourceIndex(retained, latestTokens), removed.headOption.map(_.handle))

  def removeOwner(owner: OwnerId): OwnerRemoval[A] =
    val (removed, retained) = entries.partition(_.token.owner == owner)
    val retainedTokens      = latestTokens.filterNot(_._1._1 == owner)
    OwnerRemoval(ResourceIndex(retained, retainedTokens), removed.map(_.handle))

  def values: Vector[A] = entries.map(_.handle)

  def owners: Vector[OwnerId] = entries.map(_.token.owner).distinct

  private def entry(owner: OwnerId, key: ResourceKey): Option[Entry[A]] =
    entries.find(entry => entry.token.owner == owner && entry.token.key == key)
end ResourceIndex

private[scalive] object ResourceIndex:
  final case class Replacement[A](
    index: ResourceIndex[A],
    token: ResourceToken,
    replaced: Option[A])
  final case class Removal[A](index: ResourceIndex[A], removed: Option[A])
  final case class OwnerRemoval[A](index: ResourceIndex[A], removed: Vector[A])

  final private case class Entry[A](token: ResourceToken, handle: A)

  def empty[A]: ResourceIndex[A] = ResourceIndex(Vector.empty, Map.empty)
