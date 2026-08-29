package scalive.runtime.resources

import zio.Promise
import zio.Task
import zio.UIO
import zio.ZIO

/** Protocol-neutral reply data attached to an upload control turn. */
private[scalive] trait UploadControlReply

final private[scalive] case class UploadControlError(reason: String) extends UploadControlReply

final private[scalive] case class UploadMutationResult[+A](
  registry: UploadRegistry,
  value: A,
  commit: UploadRetirementPlan = UploadRetirementPlan.empty,
  rollback: UploadRetirementPlan = UploadRetirementPlan.empty,
  reply: Option[UploadControlReply] = None,
  afterCommit: Task[Unit] = ZIO.unit)

/** One typed upload control transition serialized by the lifecycle kernel. */
final private[scalive] class UploadMutation[A] private (
  operation: UploadRegistry => Task[UploadMutationResult[A]],
  response: Promise[Throwable, A],
  guardedFallback: Option[A],
  guardWhen: UploadRegistry => Boolean):

  private[scalive] def execute(registry: UploadRegistry): Task[UploadMutationResult[A]] =
    operation(registry)

  private[scalive] def complete(result: UploadMutationResult[A]): UIO[Unit] =
    result.afterCommit.exit.flatMap {
      case zio.Exit.Success(_)     => response.succeed(result.value).unit
      case zio.Exit.Failure(cause) => response.failCause(cause).unit
    }

  def await: Task[A] = response.await

  private[scalive] def isGuarded: Boolean = guardedFallback.nonEmpty

  private[scalive] def requiresConnectedTurnGuard(registry: UploadRegistry): Boolean =
    isGuarded && guardWhen(registry)

  private[scalive] def completeGuarded: UIO[Unit] =
    ZIO.foreachDiscard(guardedFallback)(value => response.succeed(value).unit)

private[scalive] object UploadMutation:
  def make[A](
    operation: UploadRegistry => Task[UploadMutationResult[A]]
  ): UIO[UploadMutation[A]] =
    Promise.make[Throwable, A].map(new UploadMutation(operation, _, None, _ => false))

  def guarded[A](
    fallback: A
  )(
    operation: UploadRegistry => Task[UploadMutationResult[A]]
  ): UIO[UploadMutation[A]] =
    guardedWhen(fallback)(_ => true)(operation)

  def guardedWhen[A](
    fallback: A
  )(
    guardWhen: UploadRegistry => Boolean
  )(
    operation: UploadRegistry => Task[UploadMutationResult[A]]
  ): UIO[UploadMutation[A]] =
    Promise.make[Throwable, A].map(new UploadMutation(operation, _, Some(fallback), guardWhen))

  def succeed[A](
    operation: UploadRegistry => UploadMutationResult[A]
  ): UIO[UploadMutation[A]] = make(registry => ZIO.succeed(operation(registry)))
