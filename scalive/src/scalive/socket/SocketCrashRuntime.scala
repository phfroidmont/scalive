package scalive
package socket

import zio.*

import scalive.WebSocketMessage.Payload

private[scalive] object SocketCrashRuntime:
  def crash[Msg, Model](
    state: RuntimeState[Msg, Model],
    message: String,
    cause: Option[Cause[Throwable]] = None
  ): UIO[Unit] =
    state.crashedRef
      .modify {
        case true  => true  -> true
        case false => false -> true
      }.flatMap {
        case true  => ZIO.unit
        case false =>
          val log = cause match
            case Some(value) => ZIO.logErrorCause(message, value)
            case None        => ZIO.logWarning(message)

          log *>
            state.outQueue.offer(Payload.Error -> state.meta.copy(messageRef = None)).unit *>
            state.subscriptionsRef.set(Map.empty) *>
            SocketAsyncRuntime.interruptAll(state.asyncTasksRef) *>
            state.inbox.shutdown *>
            state.asyncQueue.shutdown *>
            state.onCrash
      }
