package scalive
package socket

import java.net.URI

import zio.*
import zio.http.QueryParams
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.Payload

private[scalive] object SocketInbound:
  def startClientFiber[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): RIO[Scope, Fiber.Runtime[Throwable, Unit]] =
    ZStream
      .fromQueue(state.inbox)
      .runForeach((event, meta) => handleClientEvent(event, meta, state))
      .fork

  def handleLivePatch[Msg, Model](
    url: String,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      (modelVar, el) <- state.ref.get
      parsedUri      <- ZIO.attempt(URI.create(url))
      params = QueryParams
                 .decode(Option(parsedUri.getRawQuery).getOrElse(""))
                 .map
                 .iterator
                 .map { case (key, values) =>
                   key -> values.lastOption.getOrElse("")
                 }
                 .toMap
      (model, navigation) <-
        SocketModelRuntime.captureNavigation(state)(
          LiveIO
            .toZIO(state.lv.handleParams(modelVar.currentValue, params, parsedUri))
            .provide(ZLayer.succeed(state.ctx))
        )
      _ <- navigation match
             case Some(command) =>
               handleNavigationCommand(modelVar, el, model, command, meta, state)
             case None =>
               for
                 _    <- state.patchRedirectCountRef.set(0)
                 diff <- SocketModelRuntime.updateModelAndSubscriptions(modelVar, el, model, state)
                 _    <- ZIO.when(!diff.isEmpty)(
                        SocketModelRuntime.publishPayload(
                          Payload.Diff(diff),
                          meta.copy(messageRef = None),
                          state
                        )
                      )
               yield ()
    yield ()

  private def handleClientEvent[Msg, Model](
    event: Payload.Event,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      _                             <- SocketUploadProtocol.syncUploadRuntimeFromEvent(event, state)
      (modelVar, el)                <- state.ref.get
      (interceptResult, navigation) <-
        SocketModelRuntime.captureNavigation(state)(
          LiveIO
            .toZIO(state.lv.interceptEvent(modelVar.currentValue, event.event, event.value))
            .provide(ZLayer.succeed(state.ctx))
        )
      _ <- interceptResult match
             case InterceptResult.Halt(interceptModel, reply) =>
               SocketModelRuntime.applyInterceptHalt(
                 modelVar,
                 el,
                 interceptModel,
                 reply,
                 navigation,
                 meta,
                 state
               )
             case InterceptResult.Continue(interceptModel) =>
               SocketModelRuntime.applyBoundEvent(
                 modelVar,
                 el,
                 interceptModel,
                 event,
                 navigation,
                 meta,
                 state
               )
    yield ()

  def handleNavigationCommand[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    model: Model,
    command: LiveNavigationCommand,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    val (to, kind) =
      command match
        case LiveNavigationCommand.PushPatch(value)    => value -> LivePatchKind.Push
        case LiveNavigationCommand.ReplacePatch(value) => value -> LivePatchKind.Replace

    for
      redirectCount <- state.patchRedirectCountRef.updateAndGet(_ + 1)
      _             <- SocketModelRuntime.updateModelAndSubscriptions(modelVar, el, model, state)
      _             <-
        if redirectCount > 20 then
          SocketModelRuntime.publishPayload(Payload.Error, meta.copy(messageRef = None), state)
        else
          SocketModelRuntime.publishPayload(
            Payload.LiveNavigation(to, kind),
            meta.copy(messageRef = None),
            state
          ) *> handleLivePatch(to, meta, state)
    yield ()
end SocketInbound
