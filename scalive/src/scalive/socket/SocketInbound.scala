package scalive
package socket

import java.net.URI

import zio.*
import zio.http.QueryParams
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.LiveResponse
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
      (currentModel, rendered) <- state.ref.get
      parsedUri                <- ZIO.attempt(URI.create(url))
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
            .toZIO(state.lv.handleParams(currentModel, params, parsedUri))
            .provide(ZLayer.succeed(state.ctx))
        )
      _ <- navigation match
             case Some(command) =>
               handleNavigationCommand(rendered, model, command, meta, state)
             case None =>
               for
                 _    <- state.patchRedirectCountRef.set(0)
                 diff <- SocketModelRuntime.updateModelAndSubscriptions(rendered, model, state)
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
    event.event match
      case "cids_will_destroy" =>
        SocketModelRuntime.publishPayload(Payload.okReply(LiveResponse.Empty), meta, state)
      case "cids_destroyed" =>
        for
          activeCids <- state.componentCidsRef.get
          requestedCids = parseCids(event.value)
          destroyedCids = requestedCids.intersect(activeCids)
          _ <- state.componentCidsRef.update(_ -- destroyedCids)
          response = Json.Obj(
                       "cids" -> Json.Arr(destroyedCids.toSeq.sorted.map(Json.Num(_))*)
                     )
          _ <- SocketModelRuntime.publishPayload(
                 Payload.okReply(LiveResponse.Raw(response)),
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
    rendered: RenderedView[Msg],
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
      _             <- SocketModelRuntime.updateModelAndSubscriptions(rendered, model, state)
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
