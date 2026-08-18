package scalive.runtime.contracts

import java.util.concurrent.atomic.AtomicLong

enum RuntimeIdentityError:
  case Exhausted(identity: String)

opaque type LifecycleId = Long

object LifecycleId:
  private val counter = AtomicLong(0L)

  private[scalive] def fresh(): Either[RuntimeIdentityError, LifecycleId] =
    RuntimeIdentityAllocator.next(counter, "lifecycle identity")(identity => identity)

  private[scalive] def apply(value: Long): LifecycleId = value

  extension (identity: LifecycleId) def value: Long = identity

opaque type Epoch = Long

object Epoch:
  private[scalive] val initial: Epoch = 1L

  private[scalive] def next(epoch: Epoch): Either[RuntimeIdentityError, Epoch] =
    if epoch == Long.MaxValue then Left(RuntimeIdentityError.Exhausted("epoch"))
    else Right(epoch + 1L)

  private[scalive] def apply(value: Long): Epoch = value

  extension (epoch: Epoch) def value: Long = epoch

opaque type CommandId = Long

object CommandId:
  private val counter = AtomicLong(0L)

  private[scalive] def fresh(): Either[RuntimeIdentityError, CommandId] =
    RuntimeIdentityAllocator.next(counter, "command identity")(identity => identity)

  private[scalive] def apply(value: Long): CommandId = value

  extension (identity: CommandId) def value: Long = identity

opaque type TurnId = Long

object TurnId:
  private val counter = AtomicLong(0L)

  private[scalive] def fresh(): Either[RuntimeIdentityError, TurnId] =
    RuntimeIdentityAllocator.next(counter, "turn identity")(identity => identity)

  private[scalive] def apply(value: Long): TurnId = value

  extension (identity: TurnId) def value: Long = identity

opaque type TurnRevision = Long

object TurnRevision:
  private val counter = AtomicLong(0L)

  private[scalive] def fresh(): Either[RuntimeIdentityError, TurnRevision] =
    RuntimeIdentityAllocator.next(counter, "turn revision")(revision => revision)

  private[scalive] def apply(value: Long): TurnRevision = value

  extension (revision: TurnRevision) def value: Long = revision

private object RuntimeIdentityAllocator:
  def next[A](
    counter: AtomicLong,
    identity: String
  )(
    wrap: Long => A
  ): Either[RuntimeIdentityError, A] =
    var allocated = false
    var value     = 0L

    while !allocated do
      val previous = counter.get()
      if previous == Long.MaxValue then return Left(RuntimeIdentityError.Exhausted(identity))

      value = previous + 1L
      allocated = counter.compareAndSet(previous, value)

    Right(wrap(value))
