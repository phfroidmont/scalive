package scalive.runtime.connection

import java.security.SecureRandom
import java.util.Base64

import zio.*

import scalive.runtime.contracts.LifecycleId
import scalive.runtime.contracts.RuntimeCleanup
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
              handle.abort(reason).catchAllCause(_ => logFailure("retire"))
          ).unit
      }

  private def run: UIO[Unit] =
    queue.take
      .flatMap { request =>
        process(request).catchAllCause { cause =>
          if cause.isInterruptedOnly then ZIO.interrupt
          else
            logFailure("process") *>
              fail(
                "worker_error",
                UploadChunkError.WriterFailed("worker_error"),
                Some(request.response)
              )
        }
      }.forever.catchAllCause { cause =>
        if cause.isInterruptedOnly then ZIO.unit
        else
          logFailure("loop") *>
            fail("worker_error", UploadChunkError.WriterFailed("worker_error"), None)
      }

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
              val reason = writerFailureReason(cause)
              logFailure("write") *>
                fail(
                  reason,
                  UploadChunkError.WriterFailed(reason),
                  Some(request.response)
                ).ignore
            ,
            _ =>
              received.set(next) *>
                (if next == expectedBytes then finish(request.response)
                 else request.response.succeed(progress(next)).unit)
          )
    }

  private def finish(response: Promise[UploadChunkError, Int]): UIO[Unit] =
    handle.complete.foldCauseZIO(
      cause =>
        val reason = writerFailureReason(cause)
        logFailure("complete") *>
          fail(
            reason,
            UploadChunkError.WriterFailed(reason),
            Some(response)
          ).ignore
      ,
      completion =>
        callbacks
          .complete(completion).foldCauseZIO(
            _ =>
              logFailure("completion transition") *>
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
            .abort(LiveUploadAbortReason.Failed(reason)).catchAllCause(_ => logFailure("abort")) *>
            callbacks.fail(reason).catchAllCause(_ => logFailure("failure callback")) *>
            failQueued(error) *>
            queue.shutdown *>
            completeResponse
        else completeResponse
      }

  private def progress(bytes: Long): Int =
    if expectedBytes <= 0L then 100
    else math.min(99, ((bytes * 100L) / expectedBytes).toInt)

  private def writerFailureReason(cause: Cause[Throwable]): String =
    cause.failureOption
      .collect { case error: LiveUploadWriterError => error.reason }
      .getOrElse("writer_error")

  private def failQueued(error: UploadChunkError): UIO[Unit] =
    queue.takeAll.flatMap(ZIO.foreachDiscard(_)(_.response.fail(error).unit))

  private def interruptLoop: UIO[Unit] =
    fiber.get.flatMap(ZIO.foreachDiscard(_)(_.interrupt.unit))

  private def logFailure(stage: String): UIO[Unit] =
    ZIO.logWarning(s"upload worker $stage failed")
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
    callbacks: UploadWorkerCallbacks,
    scope: Scope
  ): UIO[UploadEntryWorker] =
    ZIO.uninterruptible {
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
        running <- worker.run.interruptible.forkIn(scope)
        _       <- fiberRef.set(Some(running))
      yield worker
    }
end UploadEntryWorker

/** Connection-owned execution boundary for upload workers and cleanup instructions. */
final private[connection] class UploadRuntime private (
  state: Ref.Synchronized[UploadRuntime.State],
  workerScope: Scope.Closeable):
  import UploadRuntime.*

  def reserve(worker: HostedUploadWorker): UIO[Boolean] =
    state.modify { current =>
      if current.closed || current.workers.contains(worker.id) then false -> current
      else
        true -> current.copy(workers =
          current.workers.updated(worker.id, WorkerSlot.Pending(worker))
        )
    }

  def activate(worker: UploadEntryWorker): UIO[Boolean] =
    state.modify { current =>
      current.workers.get(worker.id) match
        case _ if current.closed         => false -> current
        case Some(WorkerSlot.Pending(_)) =>
          true -> current.copy(
            workers = current.workers.updated(worker.id, WorkerSlot.Active(worker))
          )
        case _ => false -> current
    }

  def active(id: HostedWorkerId): UIO[Option[UploadEntryWorker]] =
    state.get.map(_.workers.get(id).collect { case WorkerSlot.Active(worker) => worker })

  private def remove(id: HostedWorkerId): UIO[Option[WorkerSlot]] =
    state.modify(current =>
      current.workers.get(id) -> current.copy(workers = current.workers.removed(id))
    )

  def startEntry(
    handle: HostedUploadWorker,
    expectedBytes: Long,
    maxChunkBytes: Int,
    capacity: Int,
    callbacks: UploadWorkerCallbacks
  ): UIO[UploadEntryWorker] =
    UploadEntryWorker.start(
      handle,
      expectedBytes,
      maxChunkBytes,
      capacity,
      callbacks,
      workerScope
    )

  def forget(id: HostedWorkerId): UIO[Boolean] =
    remove(id).map(_.nonEmpty)

  def removeAndRetire(id: HostedWorkerId, reason: LiveUploadAbortReason): UIO[Unit] =
    remove(id).flatMap(ZIO.foreachDiscard(_)(_.retire(reason)))

  def retire(plan: UploadRetirementPlan): UIO[Unit] =
    RuntimeCleanup.all(plan.instructions.map {
      case UploadRetirementInstruction.Hosted(id, reason) =>
        removeAndRetire(id, reason)
      case UploadRetirementInstruction.Cleanup(operation) =>
        operation.run.catchAllCause(_ => ZIO.logWarning("upload destination cleanup failed"))
    })

  def close(registry: UploadRegistry, lifecycle: LifecycleId): UIO[Unit] =
    state
      .modify(current =>
        current.workers.values.toVector -> State(closed = true, Map.empty)
      ).flatMap { remaining =>
        RuntimeCleanup.all(
          Vector(
            retire(registry.retireLifecycle(lifecycle).retirement),
            RuntimeCleanup.all(
              remaining.map(_.retire(LiveUploadAbortReason.SocketShutdown))
            ),
            workerScope.close(Exit.unit)
          )
        )
      }
end UploadRuntime

private[connection] object UploadRuntime:
  final private case class State(closed: Boolean, workers: Map[HostedWorkerId, WorkerSlot])

  sealed private trait WorkerSlot:
    def retire(reason: LiveUploadAbortReason): UIO[Unit]

  private object WorkerSlot:
    final case class Pending(worker: HostedUploadWorker) extends WorkerSlot:
      def retire(reason: LiveUploadAbortReason): UIO[Unit] = worker
        .abort(reason).catchAllCause(_ => ZIO.logWarning("pending upload worker cleanup failed"))
    final case class Active(worker: UploadEntryWorker) extends WorkerSlot:
      def retire(reason: LiveUploadAbortReason): UIO[Unit] = worker.retire(reason)

  private val random = new SecureRandom()

  def make: ZIO[Scope, Nothing, UploadRuntime] =
    for
      state <- Ref.Synchronized.make(State(closed = false, Map.empty))
      scope <- Scope.make
      _     <- ZIO.addFinalizer(scope.close(Exit.unit))
    yield new UploadRuntime(state, scope)

  def freshRef: UIO[UploadRef] = ZIO.succeed {
    val bytes = new Array[Byte](18)
    random.nextBytes(bytes)
    UploadRef(Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
  }
