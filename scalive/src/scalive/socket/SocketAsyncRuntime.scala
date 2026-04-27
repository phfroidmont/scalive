package scalive
package socket

import zio.*

final private[scalive] class SocketAsyncRuntime(
  private val queue: Queue[LiveAsyncCompletion],
  private val tasksRef: Ref[LiveAsyncRuntimeState],
  private val owner: LiveAsyncOwner)
    extends LiveAsyncRuntime:
  def start[A, Msg](
    name: String,
    mode: AsyncStartMode
  )(
    effect: Task[A]
  )(
    toMsg: LiveAsyncResult[A] => Msg
  ): UIO[Unit] =
    startTask(name, mode)(effect)(result => LiveAsyncCompletionEvent.Message(toMsg(result)))

  def startAssign[A, Model](
    name: String,
    mode: AsyncStartMode
  )(
    effect: Task[A]
  )(
    update: (Model, LiveAsyncResult[A]) => Model
  ): UIO[Unit] =
    startTask(name, mode)(effect)(result =>
      LiveAsyncCompletionEvent.Assign(model => update(model.asInstanceOf[Model], result))
    )

  private def startTask[A](
    name: String,
    mode: AsyncStartMode
  )(
    effect: Task[A]
  )(
    toEvent: LiveAsyncResult[A] => LiveAsyncCompletionEvent
  ): UIO[Unit] =
    val id = LiveAsyncTaskId(owner, name)

    for
      token <- Random.nextUUID.map(_.toString)
      start <- Promise.make[Nothing, Unit]
      fiber <-
        (start.await *> effect.exit.flatMap(exit => complete(id, token, exit, toEvent))).forkDaemon
      decision <- tasksRef.modify { current =>
                    current.tasks.get(id) match
                      case Some(_) if mode == AsyncStartMode.KeepExisting =>
                        StartDecision.KeepExisting -> current
                      case existing =>
                        val task = LiveAsyncTaskState(
                          token = token,
                          fiber = fiber,
                          cancelledEvent = reason => toEvent(LiveAsyncResult.Cancelled(reason))
                        )
                        StartDecision.Start(existing.map(_.fiber)) ->
                          current.copy(tasks = current.tasks.updated(id, task))
                  }
      _ <- decision match
             case StartDecision.KeepExisting    => fiber.interrupt.unit
             case StartDecision.Start(previous) =>
               ZIO.foreachDiscard(previous)(_.interrupt.forkDaemon) *> start.succeed(()).unit
    yield ()
  end startTask

  def cancel(name: String, reason: Option[String]): UIO[Unit] =
    val id = LiveAsyncTaskId(owner, name)

    for
      task <- tasksRef.modify { current =>
                current.tasks.get(id) -> current.copy(tasks = current.tasks.removed(id))
              }
      _ <- task match
             case Some(value) =>
               value.fiber.interrupt.forkDaemon *>
                 queue.offer(LiveAsyncCompletion(owner, value.cancelledEvent(reason))).unit
             case None => ZIO.unit
    yield ()

  private def complete[A](
    id: LiveAsyncTaskId,
    token: String,
    exit: Exit[Throwable, A],
    toEvent: LiveAsyncResult[A] => LiveAsyncCompletionEvent
  ): UIO[Unit] =
    val event = toEvent(toResult(exit))

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

  private def toResult[A](exit: Exit[Throwable, A]): LiveAsyncResult[A] =
    exit match
      case Exit.Success(value) => LiveAsyncResult.Ok(value)
      case Exit.Failure(cause) => LiveAsyncResult.Failed(cause)

  private enum StartDecision:
    case KeepExisting
    case Start(previous: Option[Fiber.Runtime[Nothing, Unit]])
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
        ZIO.foreachDiscard(state.tasks.values)(_.fiber.interrupt.forkDaemon).unit
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
        .flatMap(tasks => ZIO.foreachDiscard(tasks)(_.fiber.interrupt.forkDaemon).unit)
