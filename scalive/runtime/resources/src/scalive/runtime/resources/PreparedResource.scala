package scalive.runtime.resources

import zio.Exit
import zio.IO
import zio.Promise
import zio.Ref
import zio.UIO
import zio.ZIO

/** A resource acquired for a candidate turn and held behind a closed activation gate. */
final private[scalive] class PreparedResource private (
  lifecycle: Ref.Synchronized[PreparedResource.Lifecycle],
  activation: Promise[PreparedResource.Closed.type, Unit],
  closeResult: Promise[Nothing, Exit[Nothing, Unit]],
  finalizer: UIO[Unit]):
  import PreparedResource.*

  /** Waits until commit activates this resource, or fails when it is retired or closed first. */
  def awaitActivation: IO[Closed.type, Unit] = activation.await

  /** Opens the activation gate once. Later activation attempts are no-ops. */
  def activate: UIO[Unit] =
    lifecycle
      .modify {
        case current @ Lifecycle(State.Inactive, false) =>
          (true, current.copy(state = State.Active))
        case current => (false, current)
      }
      .flatMap(activated => ZIO.when(activated)(activation.succeed(()).unit).unit)

  /** Prevents future activation and makes an active resource stale before it is finalized. */
  def markStale: UIO[Unit] =
    lifecycle
      .modify {
        case current @ Lifecycle(State.Inactive | State.Active, false) =>
          (current.state == State.Inactive, current.copy(state = State.Stale))
        case current => (false, current)
      }
      .flatMap(closeGate => ZIO.when(closeGate)(activation.fail(Closed).unit).unit)

  /** Closes the gate and runs the resource finalizer exactly once. */
  def close: UIO[Unit] = ZIO.uninterruptible {
    lifecycle
      .modify {
        case current @ Lifecycle(_, false) =>
          val closingState = current.state match
            case State.Active | State.Stale    => State.Stale
            case State.Inactive | State.Closed => State.Closed
          (Some(current.state), Lifecycle(closingState, closing = true))
        case current => (None, current)
      }
      .flatMap {
        case Some(previousState) =>
          for
            _      <- ZIO.when(previousState == State.Inactive)(activation.fail(Closed).unit)
            result <- finalizer.exit
            _      <- lifecycle.update(_.copy(state = State.Closed))
            _      <- closeResult.succeed(result)
            _      <- restore(result)
          yield ()
        case None => closeResult.await.flatMap(restore)
      }
  }

  /** Rolls back an inactive candidate without closing a resource retained by a later commit. */
  def discard: UIO[Unit] = ZIO.uninterruptible {
    lifecycle
      .modify {
        case current @ Lifecycle(State.Inactive, false) =>
          true -> current.copy(state = State.Stale)
        case current => false -> current
      }.flatMap { discarded =>
        ZIO.when(discarded)(activation.fail(Closed).unit *> close).unit
      }
  }

  /** Observable lifecycle state for runtime integration and module tests. */
  def state: UIO[State] = lifecycle.get.map(_.state)
end PreparedResource

private[scalive] object PreparedResource:
  enum State:
    case Inactive, Active, Stale, Closed

  case object Closed

  final private case class Lifecycle(state: State, closing: Boolean)

  private def restore(result: Exit[Nothing, Unit]): UIO[Unit] = result match
    case Exit.Success(())    => ZIO.unit
    case Exit.Failure(cause) => ZIO.failCause(cause)

  /** Builds a prepared handle around an already-acquired resource's infallible finalizer. */
  def make(finalizer: UIO[Unit]): UIO[PreparedResource] =
    for
      lifecycle   <- Ref.Synchronized.make(Lifecycle(State.Inactive, closing = false))
      activation  <- Promise.make[Closed.type, Unit]
      closeResult <- Promise.make[Nothing, Exit[Nothing, Unit]]
    yield new PreparedResource(lifecycle, activation, closeResult, finalizer)

/** A finite, immutable collection used by commit and retirement tails. */
final private[scalive] case class PreparedResources(values: Vector[PreparedResource]):
  def activate: UIO[Unit]  = ZIO.foreachDiscard(values)(_.activate)
  def markStale: UIO[Unit] = ZIO.foreachDiscard(values)(_.markStale)
  def close: UIO[Unit]     =
    ZIO.foreach(values)(_.close.exit).flatMap { exits =>
      val failures = exits.collect { case Exit.Failure(cause) => cause }
      failures.reduceOption(_ ++ _).fold[UIO[Unit]](ZIO.unit)(ZIO.failCause(_))
    }

private[scalive] object PreparedResources:
  val empty: PreparedResources = PreparedResources(Vector.empty)

/** Registers each prepared resource with its candidate owner as soon as it is acquired. */
final private[scalive] class PreparedResourceRegistry private (
  resources: Ref.Synchronized[Vector[PreparedResource]],
  registerFinalizer: UIO[Unit] => UIO[Unit]):

  /** Creates and registers a prepared resource before exposing it to the caller. */
  def prepare(finalizer: UIO[Unit]): UIO[PreparedResource] = ZIO.uninterruptible {
    resources.modifyZIO { current =>
      PreparedResource.make(finalizer).flatMap { resource =>
        registerFinalizer(resource.close)
          .as((resource, current :+ resource))
          .onError(_ => resource.close)
      }
    }
  }

  /** Returns all resources registered so far in registration order. */
  def result: UIO[PreparedResources] = resources.get.map(PreparedResources(_))

private[scalive] object PreparedResourceRegistry:
  def make(registerFinalizer: UIO[Unit] => UIO[Unit]): UIO[PreparedResourceRegistry] =
    Ref.Synchronized
      .make(Vector.empty[PreparedResource]).map(new PreparedResourceRegistry(_, registerFinalizer))
