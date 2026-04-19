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
      result <-
        normalize(state.lv.handleParams(modelVar.currentValue, params, parsedUri), state.ctx)
      _ <- result match
             case ParamsResult.Continue(model) =>
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
             case ParamsResult.PushPatch(model, to) =>
               handleLivePatchRedirect(
                 modelVar,
                 el,
                 model,
                 to,
                 LivePatchKind.Push,
                 meta,
                 state
               )
             case ParamsResult.ReplacePatch(model, to) =>
               handleLivePatchRedirect(
                 modelVar,
                 el,
                 model,
                 to,
                 LivePatchKind.Replace,
                 meta,
                 state
               )
    yield ()

  private def handleClientEvent[Msg, Model](
    event: Payload.Event,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      _              <- SocketUploadProtocol.syncUploadRuntimeFromEvent(event, state)
      (modelVar, el) <- state.ref.get
      hookResult     <- normalize(
                      state.lv.handleHook(modelVar.currentValue, event.event, event.value),
                      state.ctx
                    )
      _ <- hookResult match
             case HookResult.Halt(hookModel, reply) =>
               SocketModelRuntime.applyHookHalt(modelVar, el, hookModel, reply, meta, state)
             case HookResult.Continue(hookModel) =>
               SocketModelRuntime.applyBoundEvent(modelVar, el, hookModel, event, meta, state)
    yield ()

  private def handleLivePatchRedirect[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    model: Model,
    to: String,
    kind: LivePatchKind,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
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
