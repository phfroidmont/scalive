package scalive
package socket

import zio.*

final private[scalive] class SocketAsyncRuntime(
  private val queue: Queue[LiveAsyncCompletion],
  private val tasksRef: Ref[LiveAsyncRuntimeState],
  private val owner: LiveAsyncOwner)
    extends LiveAsyncRuntime:
  def start[A, Msg](name: String)(effect: Task[A])(toMsg: A => Msg): UIO[Unit] =
    val id = LiveAsyncTaskId(owner, name)

    for
      token <- Random.nextUUID.map(_.toString)
      start <- Promise.make[Nothing, Unit]
      fiber <-
        (start.await *> effect.exit.flatMap(exit =>
          complete(id, token, exit, name, toMsg)
        )).forkDaemon
      previous <- tasksRef.modify { current =>
                    val previous = current.tasks.get(id).map(_.fiber)
                    val task     = LiveAsyncTaskState(token = token, fiber = fiber)
                    previous -> current.copy(tasks = current.tasks.updated(id, task))
                  }
      _ <- ZIO.foreachDiscard(previous)(_.interrupt.forkDaemon) *> start.succeed(()).unit
    yield ()

  def cancel(name: String): UIO[Unit] =
    val id = LiveAsyncTaskId(owner, name)
    tasksRef
      .modify { current =>
        current.tasks.get(id) -> current.copy(tasks = current.tasks.removed(id))
      }.flatMap {
        case Some(task) => task.fiber.interrupt.unit
        case None       => ZIO.unit
      }

  private def complete[A, Msg](
    id: LiveAsyncTaskId,
    token: String,
    exit: Exit[Throwable, A],
    name: String,
    toMsg: A => Msg
  ): UIO[Unit] =
    val event = exit match
      case Exit.Success(value) =>
        try LiveAsyncCompletionEvent.Succeeded(name, toMsg(value))
        catch case error: Throwable => LiveAsyncCompletionEvent.Failed(name, error)
      case Exit.Failure(cause) =>
        LiveAsyncCompletionEvent.Failed(name, cause.squash)

    for
      active <- tasksRef.modify { current =>
                  current.tasks.get(id) match
                    case Some(task) if task.token == token =>
                      true -> current.copy(tasks = current.tasks.removed(id))
                    case _ =>
                      false -> current
                }
      _ <-
        if active then queue.offer(LiveAsyncCompletion(owner, event)).unit
        else ZIO.unit
    yield ()
end SocketAsyncRuntime

private[scalive] object SocketAsyncRuntime:
  def scoped(runtime: LiveAsyncRuntime, owner: LiveAsyncOwner): LiveAsyncRuntime =
    runtime match
      case socket: SocketAsyncRuntime =>
        new SocketAsyncRuntime(socket.queue, socket.tasksRef, owner)
      case other => other

  def interruptAll(ref: Ref[LiveAsyncRuntimeState]): UIO[Unit] =
    ref
      .getAndSet(LiveAsyncRuntimeState.empty).flatMap(state =>
        ZIO.foreachDiscard(state.tasks.values)(_.fiber.interrupt.unit).unit
      )

  def interruptOwners(
    ref: Ref[LiveAsyncRuntimeState],
    owners: Set[LiveAsyncOwner]
  ): UIO[Unit] =
    if owners.isEmpty then ZIO.unit
    else
      ref
        .modify { current =>
          val (removed, kept) = current.tasks.partition { case (id, _) =>
            owners.contains(id.owner)
          }
          removed.values -> current.copy(tasks = kept)
        }
        .flatMap(tasks => ZIO.foreachDiscard(tasks)(_.fiber.interrupt.unit).unit)
