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
  reply: Option[UploadControlReply] = None)

/** One typed upload control transition serialized by the lifecycle kernel. */
final private[scalive] class UploadMutation[A] private (
  operation: UploadRegistry => Task[UploadMutationResult[A]],
  response: Promise[Throwable, A]):

  private[scalive] def execute(registry: UploadRegistry): Task[UploadMutationResult[A]] =
    operation(registry).tap(result => response.succeed(result.value).unit)

  def await: Task[A] = response.await

private[scalive] object UploadMutation:
  def make[A](
    operation: UploadRegistry => Task[UploadMutationResult[A]]
  ): UIO[UploadMutation[A]] =
    Promise.make[Throwable, A].map(new UploadMutation(operation, _))

  def succeed[A](
    operation: UploadRegistry => UploadMutationResult[A]
  ): UIO[UploadMutation[A]] = make(registry => ZIO.succeed(operation(registry)))
