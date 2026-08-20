package scalive.runtime.connection

import java.security.SecureRandom
import java.util.Base64

import zio.*

import scalive.runtime.contracts.LifecycleId
import scalive.runtime.resources.*
import scalive.upload.*

private[scalive] enum UploadChunkError:
  case QueueOverflow(capacity: Int)
  case ChunkTooLarge(maxBytes: Int, actualBytes: Int)
  case DeclaredSizeExceeded(expectedBytes: Long, receivedBytes: Long)
  case WriterFailed(reason: String)
  case Closed

private[scalive] enum UploadAdmissionError:
  case Rejected(error: UploadRegistryError)
  case StaleGeneration(expected: Long, actual: Long)
  case WriterInitializationFailed
  case RegistrationConflict

final private[connection] case class UploadWorkerCallbacks(
  complete: HostedUploadCompletion => Task[Unit],
  fail: String => UIO[Unit])

/** One admitted hosted entry. Bulk chunks stop here and never enter lifecycle ingress. */
final private[connection] class UploadEntryWorker private (
  val id: HostedWorkerId,
  handle: HostedUploadWorker,
  expectedBytes: Long,
  maxChunkBytes: Int,
  capacity: Int,
  queue: Queue[UploadEntryWorker.Request],
  received: Ref[Long],
  terminal: Ref.Synchronized[Boolean],
  callbacks: UploadWorkerCallbacks,
  fiber: Ref[Option[Fiber.Runtime[Nothing, Unit]]]):
  import UploadEntryWorker.*

  def offer(data: Chunk[Byte]): IO[UploadChunkError, Int] =
    if data.length > maxChunkBytes then
      val error = UploadChunkError.ChunkTooLarge(maxChunkBytes, data.length)
      fail("chunk_too_large", error, None) *> ZIO.fail(error)
    else
      for
        closed   <- terminal.get
        _        <- ZIO.fail(UploadChunkError.Closed).when(closed)
        response <- Promise.make[UploadChunkError, Int]
        accepted <- queue.offer(Request(data, response))
        _        <- fail("queue_overflow", UploadChunkError.QueueOverflow(capacity), Some(response))
               .unless(accepted)
        result <- response.await
      yield result

  def retire(reason: LiveUploadAbortReason): UIO[Unit] =
    terminal
      .modify {
        case false => true  -> true
        case true  => false -> true
      }.flatMap { claimed =>
        ZIO
          .when(claimed)(
            interruptLoop *>
              failQueued(UploadChunkError.Closed) *>
              queue.shutdown *>
              handle.abort(reason).catchAllCause(cause => logFailure("retire", cause))
          ).unit
      }

  private def run: UIO[Unit] =
    queue.take.flatMap(process).forever.catchAllCause(_ => ZIO.unit)

  private def process(request: Request): UIO[Unit] =
    received.get.flatMap { previous =>
      val next = previous + request.data.length.toLong
      if next > expectedBytes then
        fail(
          "declared_size_exceeded",
          UploadChunkError.DeclaredSizeExceeded(expectedBytes, next),
          Some(request.response)
        ).ignore
      else
        handle
          .write(request.data).foldCauseZIO(
            cause =>
              logFailure("write", cause) *>
                fail(
                  "writer_error",
                  UploadChunkError.WriterFailed("writer_error"),
                  Some(request.response)
                ).ignore,
            _ =>
              received.set(next) *>
                (if next == expectedBytes then finish(request.response)
                 else request.response.succeed(progress(next)).unit)
          )
    }

  private def finish(response: Promise[UploadChunkError, Int]): UIO[Unit] =
    handle.complete.foldCauseZIO(
      cause =>
        logFailure("complete", cause) *>
          fail(
            "writer_error",
            UploadChunkError.WriterFailed("writer_error"),
            Some(response)
          ).ignore,
      completion =>
        callbacks
          .complete(completion).foldCauseZIO(
            cause =>
              logFailure("completion transition", cause) *>
                handle.abort(LiveUploadAbortReason.Failed("completion_transition")).ignore *>
                response.fail(UploadChunkError.WriterFailed("completion_transition")).unit,
            _ =>
              terminal.set(true) *>
                response.succeed(100).unit *>
                failQueued(UploadChunkError.Closed) *>
                queue.shutdown
          )
    )

  private def fail(
    reason: String,
    error: UploadChunkError,
    response: Option[Promise[UploadChunkError, Int]]
  ): UIO[Unit] =
    terminal
      .modify {
        case false => true  -> true
        case true  => false -> true
      }.flatMap { claimed =>
        val completeResponse = ZIO.foreachDiscard(response)(_.fail(error).unit)
        if claimed then
          handle
            .abort(LiveUploadAbortReason.Failed(reason)).catchAllCause(cause =>
              logFailure("abort", cause)
            ) *>
            callbacks.fail(reason) *>
            completeResponse *>
            failQueued(error) *>
            queue.shutdown
        else completeResponse
      }

  private def progress(bytes: Long): Int =
    if expectedBytes <= 0L then 100
    else math.min(99, ((bytes * 100L) / expectedBytes).toInt)

  private def failQueued(error: UploadChunkError): UIO[Unit] =
    queue.takeAll.flatMap(ZIO.foreachDiscard(_)(_.response.fail(error).unit))

  private def interruptLoop: UIO[Unit] =
    fiber.get.flatMap(ZIO.foreachDiscard(_)(_.interrupt.unit))

  private def logFailure(stage: String, cause: Cause[Throwable]): UIO[Unit] =
    ZIO.logWarningCause(
      s"upload worker $stage failed upload=${id.uploadRef.value} entry=${id.entryRef.value}",
      cause
    )
end UploadEntryWorker

private object UploadEntryWorker:
  final private case class Request(
    data: Chunk[Byte],
    response: Promise[UploadChunkError, Int])

  def start(
    handle: HostedUploadWorker,
    expectedBytes: Long,
    maxChunkBytes: Int,
    capacity: Int,
    callbacks: UploadWorkerCallbacks
  ): UIO[UploadEntryWorker] =
    for
      queue    <- Queue.dropping[Request](capacity)
      received <- Ref.make(0L)
      terminal <- Ref.Synchronized.make(false)
      fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
      worker = new UploadEntryWorker(
                 handle.id,
                 handle,
                 expectedBytes,
                 maxChunkBytes,
                 capacity,
                 queue,
                 received,
                 terminal,
                 callbacks,
                 fiberRef
               )
      running <- worker.run.forkDaemon
      _       <- fiberRef.set(Some(running))
    yield worker
end UploadEntryWorker

/** Connection-owned execution boundary for upload workers and cleanup instructions. */
final private[connection] class UploadRuntime private (
  workers: Ref.Synchronized[Map[HostedWorkerId, UploadRuntime.WorkerSlot]]):
  import UploadRuntime.*

  def reserve(worker: HostedUploadWorker): UIO[Boolean] =
    workers.modify { current =>
      if current.contains(worker.id) then false -> current
      else true -> current.updated(worker.id, WorkerSlot.Pending(worker))
    }

  def activate(worker: UploadEntryWorker): UIO[Boolean] =
    workers.modify { current =>
      current.get(worker.id) match
        case Some(WorkerSlot.Pending(_)) =>
          true -> current.updated(worker.id, WorkerSlot.Active(worker))
        case _ => false -> current
    }

  def active(id: HostedWorkerId): UIO[Option[UploadEntryWorker]] =
    workers.get.map(_.get(id).collect { case WorkerSlot.Active(worker) => worker })

  private def remove(id: HostedWorkerId): UIO[Option[WorkerSlot]] =
    workers.modify(current => current.get(id) -> current.removed(id))

  def forget(id: HostedWorkerId): UIO[Boolean] =
    remove(id).map(_.nonEmpty)

  def removeAndRetire(id: HostedWorkerId, reason: LiveUploadAbortReason): UIO[Unit] =
    remove(id).flatMap(ZIO.foreachDiscard(_)(_.retire(reason)))

  def retire(plan: UploadRetirementPlan): UIO[Unit] =
    ZIO.foreachDiscard(plan.instructions) {
      case UploadRetirementInstruction.Hosted(id, reason) =>
        removeAndRetire(id, reason)
      case UploadRetirementInstruction.Cleanup(operation) =>
        operation.run.catchAllCause(cause =>
          ZIO.logWarningCause("upload destination cleanup failed", cause)
        )
    }

  def close(registry: UploadRegistry, lifecycle: LifecycleId): UIO[Unit] =
    retire(registry.retireLifecycle(lifecycle).retirement) *>
      workers
        .getAndSet(Map.empty).flatMap(remaining =>
          ZIO.foreachDiscard(remaining.values)(_.retire(LiveUploadAbortReason.SocketShutdown))
        )
end UploadRuntime

private[connection] object UploadRuntime:
  sealed private trait WorkerSlot:
    def retire(reason: LiveUploadAbortReason): UIO[Unit]

  private object WorkerSlot:
    final case class Pending(worker: HostedUploadWorker) extends WorkerSlot:
      def retire(reason: LiveUploadAbortReason): UIO[Unit] = worker
        .abort(reason).catchAllCause(cause =>
          ZIO.logWarningCause("pending upload worker cleanup failed", cause)
        )
    final case class Active(worker: UploadEntryWorker) extends WorkerSlot:
      def retire(reason: LiveUploadAbortReason): UIO[Unit] = worker.retire(reason)

  private val random = new SecureRandom()

  def make: UIO[UploadRuntime] =
    Ref.Synchronized.make(Map.empty[HostedWorkerId, WorkerSlot]).map(new UploadRuntime(_))

  def freshRef: UIO[UploadRef] = ZIO.succeed {
    val bytes = new Array[Byte](18)
    random.nextBytes(bytes)
    UploadRef(Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
  }
