package scalive
package socket

import zio.*
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.Payload

private[scalive] object SocketOutbound:
  private enum ServerEvent[+Msg]:
    case Message(value: Msg)
    case Async(value: LiveAsyncCompletion)
    case ComponentOutput(value: ComponentOutputMessage)

  private enum MessageHookStage[+Msg]:
    case Info
    case Async(name: String, result: LiveAsyncResult[Msg])

  def startServerFiber[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): RIO[Scope, Fiber.Runtime[Throwable, Unit]] =
    serverEventStream(state)
      .runForeach((event, meta) =>
        val kind = event match
          case ServerEvent.Message(_)         => RuntimeTraceOperationKind.ServerMessage
          case ServerEvent.Async(_)           => RuntimeTraceOperationKind.AsyncCompletion
          case ServerEvent.ComponentOutput(_) => RuntimeTraceOperationKind.ServerMessage
        val operation  = RuntimeTraceOperation.resolve(state.runtimeTrace, meta, kind)
        val tracedMeta = RuntimeTraceOperation.attach(meta, operation)
        handleServerEvent(event, tracedMeta, state).catchAllCause(cause =>
          RuntimeTraceOperation.event(
            operation,
            RuntimeTraceStage.Crash,
            "Server message operation crashed"
          ) *>
            SocketCrashRuntime.crash(
              state,
              s"LiveView ${state.meta.topic} server message crashed",
              Some(cause)
            )
        )
      ).fork

  def buildOutbox[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)] =
    ZStream
      .fromQueue(state.outQueue).filterNot {
        case (Payload.Diff(diff), _) => diff.isEmpty
        case _                       => false
      }

  def buildShutdown[Msg, Model](
    state: RuntimeState[Msg, Model],
    clientFiber: Fiber.Runtime[Throwable, Unit],
    serverFiber: Fiber.Runtime[Throwable, Unit]
  ): UIO[Unit] =
    ZIO.uninterruptible(
      state.outQueue.offer(Payload.Close -> state.meta) *>
        SocketAsyncRuntime.interruptAll(state.asyncTasksRef) *>
        SocketUploadRuntime.shutdown(state.uploadRef) *>
        state.inbox.shutdown *>
        state.asyncQueue.shutdown *>
        state.componentOutputQueue.shutdown *>
        state.outQueue.shutdown *>
        clientFiber.interrupt.unit *>
        serverFiber.interrupt.unit
    )

  private def serverEventStream[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (ServerEvent[Msg], WebSocketMessage.Meta)] =
    val messages = SocketSubscriptionRuntime
      .stream(state.subscriptionsRef)
      .map(
        ServerEvent.Message(_) -> state.meta.copy(
          messageRef = None,
          eventType = "diff",
          traceOperation = RuntimeTraceOperation.Disabled
        )
      )
    val async = ZStream
      .fromQueue(state.asyncQueue)
      .map(
        ServerEvent.Async(_) -> state.meta.copy(
          messageRef = None,
          eventType = "diff",
          traceOperation = RuntimeTraceOperation.Disabled
        )
      )
    val componentOutputs = ZStream
      .fromQueue(state.componentOutputQueue)
      .map(
        ServerEvent.ComponentOutput(_) -> state.meta.copy(
          messageRef = None,
          eventType = "diff",
          traceOperation = RuntimeTraceOperation.Disabled
        )
      )
    messages.merge(async).merge(componentOutputs)
  end serverEventStream

  private def handleServerEvent[Msg, Model](
    event: ServerEvent[Msg],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    state.lifecycleLock.withPermit {
      event match
        case ServerEvent.Message(msg) => handleServerMsg(msg, meta, state, MessageHookStage.Info)
        case ServerEvent.Async(completion)       => handleAsyncCompletion(completion, meta, state)
        case ServerEvent.ComponentOutput(output) => handleComponentOutput(output, meta, state)
    }

  private def handleComponentOutput[Msg, Model](
    output: ComponentOutputMessage,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    output.owner match
      case ComponentOutputOwner.Root =>
        state.msgClassTag.unapply(output.value) match
          case Some(msg) => handleServerMsg(msg, meta, state, MessageHookStage.Info)
          case None      =>
            ZIO.logWarning(
              s"Ignoring component output ${output.value.getClass.getName}: expected ${state.msgClassTag.runtimeClass.getName}"
            )
      case ComponentOutputOwner.Component(cid) =>
        for
          (_, rendered) <- state.ref.get
          _             <- SocketComponentRuntime
                 .handleComponentServerMessage(cid, output.value, rendered, meta, state)
                 .unit
        yield ()

  private def handleServerMsg[Msg, Model](
    msg: Msg,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model],
    hookStage: MessageHookStage[Msg]
  ): Task[Unit] =
    for
      _ <- RuntimeTraceOperation.message(
             meta.traceOperation,
             RuntimeTraceStage.TypedMessage,
             "Server delivered a typed message",
             msg
           )
      _ <- RuntimeTraceOperation.event(
             meta.traceOperation,
             RuntimeTraceStage.Lifecycle,
             "Server message lifecycle and handler started"
           )
      (currentModel, rendered)   <- state.ref.get
      (updatedModel, navigation) <-
        SocketModelRuntime.captureNavigation(state)(
          runMessageHooks(currentModel, msg, hookStage, state.ctx).flatMap {
            case LiveHookResult.Continue(hookModel) =>
              state.lv
                .handleMessage(hookModel, state.ctx.messageContext[Msg, Model])(msg)
                .map(LiveHookResult.Continue(_))
            case halt @ LiveHookResult.Halt(_) => ZIO.succeed(halt)
          }
        )
      hookModel = updatedModel match
                    case LiveHookResult.Halt(value)     => value
                    case LiveHookResult.Continue(value) => value
      _ <- navigation match
             case Some(command) =>
               state.patchRedirectCountRef.set(0) *>
                 SocketInbound.handleNavigationCommand(hookModel, command, meta, state)
             case None =>
               for
                 diff <- SocketModelRuntime.updateModelAndSubscriptions(
                           rendered,
                           hookModel,
                           state,
                           meta.traceOperation
                         )
                 _ <- SocketModelRuntime.publishPayload(Payload.Diff(diff), meta, state)
                 _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
               yield ()
      _ <- RuntimeTraceOperation.event(
             meta.traceOperation,
             RuntimeTraceStage.Lifecycle,
             "Server message lifecycle and handler completed"
           )
    yield ()

  private def runMessageHooks[Msg, Model](
    model: Model,
    msg: Msg,
    hookStage: MessageHookStage[Msg],
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    hookStage match
      case MessageHookStage.Info =>
        ctx.hooks.runInfo(model, msg, ctx)
      case MessageHookStage.Async(name, result) =>
        ctx.hooks.runAsync(
          model,
          LiveAsyncEvent(AsyncKey[Any](name), result),
          ctx
        )

  private def handleAsyncCompletion[Msg, Model](
    completion: LiveAsyncCompletion,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    completion.owner match
      case LiveAsyncOwner.Root =>
        completion.event match
          case LiveAsyncCompletionEvent.Succeeded(name, message) =>
            handleRootAsyncMessage[Msg, Model](
              name,
              message,
              LiveAsyncResult.Succeeded(message),
              meta,
              state
            )
          case LiveAsyncCompletionEvent.Failed(name, cause, message) =>
            handleRootAsyncMessage[Msg, Model](
              name,
              message,
              LiveAsyncResult.Failed(cause),
              meta,
              state
            )
          case LiveAsyncCompletionEvent.Cancelled(name, reason, message) =>
            handleRootAsyncMessage[Msg, Model](
              name,
              message,
              LiveAsyncResult.Cancelled(reason),
              meta,
              state
            )
          case LiveAsyncCompletionEvent.MappingFailed(name, cause) =>
            handleUnmappedAsyncFailure(name, cause, meta, state)
      case LiveAsyncOwner.Component(cid) =>
        completion.event match
          case LiveAsyncCompletionEvent.Succeeded(name, message) =>
            for
              (_, rendered) <- state.ref.get
              _             <- SocketComponentRuntime.handleComponentAsyncSuccess(
                     cid,
                     name,
                     message,
                     rendered,
                     meta,
                     state
                   )
            yield ()
          case LiveAsyncCompletionEvent.Failed(name, cause, message) =>
            for
              (_, rendered) <- state.ref.get
              _             <- SocketComponentRuntime.handleComponentAsyncFailure(
                     cid,
                     name,
                     cause,
                     message,
                     rendered,
                     meta,
                     state
                   )
            yield ()
          case LiveAsyncCompletionEvent.Cancelled(name, reason, message) =>
            for
              (_, rendered) <- state.ref.get
              _             <- SocketComponentRuntime.handleComponentAsyncCancelled(
                     cid,
                     name,
                     reason,
                     message,
                     rendered,
                     meta,
                     state
                   )
            yield ()
          case LiveAsyncCompletionEvent.MappingFailed(name, cause) =>
            for
              (_, rendered) <- state.ref.get
              _             <- SocketComponentRuntime.handleComponentAsyncMappingFailure(
                     cid,
                     name,
                     cause,
                     rendered,
                     meta,
                     state
                   )
            yield ()

  private def handleRootAsyncMessage[Msg, Model](
    name: String,
    message: Any,
    result: LiveAsyncResult[Any],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    state.msgClassTag.unapply(message) match
      case Some(msg) =>
        val typedResult = result match
          case LiveAsyncResult.Succeeded(_)      => LiveAsyncResult.Succeeded(msg)
          case LiveAsyncResult.Failed(cause)     => LiveAsyncResult.Failed(cause)
          case LiveAsyncResult.Cancelled(reason) => LiveAsyncResult.Cancelled(reason)
        handleServerMsg[Msg, Model](
          msg,
          meta,
          state,
          MessageHookStage.Async(name, typedResult)
        )
      case None =>
        ZIO.logWarning(
          s"Ignoring async message ${message.getClass.getName}: expected ${state.msgClassTag.runtimeClass.getName}"
        )

  private def handleUnmappedAsyncFailure[Msg, Model](
    name: String,
    cause: Throwable,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      _ <- ZIO.logErrorCause(
             s"Async task '$name' could not map its result to a message",
             Cause.fail(cause)
           )
      (currentModel, rendered)   <- state.ref.get
      (updatedModel, navigation) <-
        SocketModelRuntime.captureNavigation(state)(
          state.ctx.hooks.runAsync(
            currentModel,
            LiveAsyncEvent(AsyncKey[Any](name), LiveAsyncResult.Failed(cause)),
            state.ctx
          )
        )
      model = updatedModel match
                case LiveHookResult.Continue(value) => value
                case LiveHookResult.Halt(value)     => value
      _ <- navigation match
             case Some(command) =>
               state.patchRedirectCountRef.set(0) *>
                 SocketInbound.handleNavigationCommand(model, command, meta, state)
             case None =>
               for
                 diff <- SocketModelRuntime.updateModelAndSubscriptions(
                           rendered,
                           model,
                           state,
                           meta.traceOperation
                         )
                 _ <- SocketModelRuntime.publishPayload(Payload.Diff(diff), meta, state)
                 _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
               yield ()
    yield ()
end SocketOutbound
