package scalive
package socket

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.LiveResponse
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
  ): Task[Payload.Reply] =
    for
      (currentModel, rendered) <- state.ref.get
      currentUrl               <- state.currentUrlRef.get
      decodedUrl               <- ZIO
                      .fromEither(LivePatchUrl.resolve(url, currentUrl))
                      .mapError(error => new IllegalArgumentException(error))
      _                   <- state.currentUrlRef.set(decodedUrl)
      (model, navigation) <-
        SocketModelRuntime.captureNavigation(state)(
          LiveIO
            .toZIO(LiveViewParamsRuntime.runHandleParams(state.lv, currentModel, decodedUrl))
            .provide(ZLayer.succeed(state.ctx))
        )
      diffOpt <- navigation match
                   case Some(command) =>
                     handleNavigationCommand(rendered, model, command, meta, state).as(None)
                   case None =>
                     for
                       _    <- state.patchRedirectCountRef.set(0)
                       diff <-
                         SocketModelRuntime.updateModelAndSubscriptions(rendered, model, state)
                     yield Some(diff)
      reply = diffOpt match
                case Some(diff) if !diff.isEmpty =>
                  Payload.okReply(LiveResponse.Diff(diff))
                case _ =>
                  Payload.okReply(LiveResponse.Empty)
    yield reply

  private def handleClientEvent[Msg, Model](
    event: Payload.Event,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    event.event match
      case "cids_will_destroy" =>
        SocketModelRuntime.publishPayload(Payload.okReply(LiveResponse.Empty), meta, state)
      case "cids_destroyed" =>
        for
          activeCids <- state.componentCidsRef.get
          requestedCids = parseCids(event.value)
          destroyedCids = requestedCids.intersect(activeCids)
          _ <- state.componentCidsRef.update(_ -- destroyedCids)
          _ <- state.componentsRef.update(_.removeCids(destroyedCids))
          _ <- SocketStreamRuntime.removeComponentScopes(state.streamRef, destroyedCids)
          response = Json.Obj(
                       "cids" -> Json.Arr(destroyedCids.toSeq.sorted.map(Json.Num(_))*)
                     )
          _ <- SocketModelRuntime.publishPayload(
                 Payload.okReply(LiveResponse.Raw(response)),
                 meta,
                 state
               )
        yield ()
      case "lv:clear-flash" =>
        for
          (currentModel, rendered) <- state.ref.get
          _                        <- event.params.get("key") match
                 case Some(kind) => state.ctx.flash.clear(kind)
                 case None       => state.ctx.flash.clearAll
          diff <- SocketModelRuntime.updateModelAndSubscriptions(rendered, currentModel, state)
          _    <- SocketModelRuntime.publishPayload(
                 if diff.isEmpty then Payload.okReply(LiveResponse.Empty)
                 else Payload.okReply(LiveResponse.Diff(diff)),
                 meta,
                 state
               )
        yield ()
      case _ =>
        for
          _                        <- SocketUploadProtocol.syncUploadRuntimeFromEvent(event, state)
          (currentModel, rendered) <- state.ref.get
          (interceptResult, navigation) <-
            SocketModelRuntime.captureNavigation(state)(
              LiveIO
                .toZIO(state.lv.interceptEvent(currentModel, event.event, event.value))
                .provide(ZLayer.succeed(state.ctx))
            )
          _ <- interceptResult match
                 case InterceptResult.Halt(interceptModel, reply) =>
                   SocketModelRuntime.applyInterceptHalt(
                     rendered,
                     interceptModel,
                     reply,
                     navigation,
                     meta,
                     state
                   )
                 case InterceptResult.Continue(interceptModel) =>
                   SocketModelRuntime.applyBoundEvent(
                     rendered,
                     interceptModel,
                     event,
                     navigation,
                     meta,
                     state
                   )
        yield ()

  private def parseCids(value: Json): Set[Int] =
    value match
      case Json.Obj(fields) =>
        fields
          .collectFirst { case ("cids", Json.Arr(items)) =>
            items.flatMap {
              case Json.Num(v) => Some(v.intValue)
              case Json.Str(v) => v.toIntOption
              case _           => None
            }.toSet
          }.getOrElse(Set.empty)
      case _ => Set.empty

  def handleNavigationCommand[Msg, Model](
    rendered: RenderedView,
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
      currentUrl  <- state.currentUrlRef.get
      resolvedUrl <- ZIO
                       .fromEither(LivePatchUrl.resolve(to, currentUrl))
                       .mapError(error => new IllegalArgumentException(error))
      resolvedTo = resolvedUrl.encode
      redirectCount <- state.patchRedirectCountRef.updateAndGet(_ + 1)
      _             <- SocketModelRuntime.updateModelAndSubscriptions(rendered, model, state)
      _             <-
        if redirectCount > 20 then
          SocketModelRuntime.publishPayload(
            Payload.Error,
            meta.copy(joinRef = None, messageRef = None),
            state
          )
        else
          SocketModelRuntime.publishPayload(
            Payload.LiveNavigation(resolvedTo, kind),
            meta.copy(messageRef = None),
            state
          ) *> handleLivePatch(resolvedTo, meta, state)
    yield ()
  end handleNavigationCommand
end SocketInbound
