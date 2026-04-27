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
      .runForeach((event, meta) =>
        state.lifecycleLock.withPermit(handleClientEvent(event, meta, state))
      )
      .fork

  def handleLivePatch[Msg, Model](
    url: String,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Payload.Reply] =
    state.lifecycleLock.withPermit {
      for
        (currentModel, rendered) <- state.ref.get
        currentUrl               <- state.currentUrlRef.get
        decodedUrl               <- ZIO
                        .fromEither(LivePatchUrl.resolve(url, currentUrl))
                        .mapError(error => new IllegalArgumentException(error))
        _                   <- SocketFlashRuntime.commitNavigation(state.flashRef)
        _                   <- state.currentUrlRef.set(decodedUrl)
        (model, navigation) <-
          SocketModelRuntime.captureNavigation(state, resetFlash = false)(
            LiveViewParamsRuntime.runHandleParams(state.lv, currentModel, decodedUrl, state.ctx)
          )
        diffOpt <- navigation match
                     case Some(
                           command @ (LiveNavigationCommand.PushPatch(_) |
                           LiveNavigationCommand.ReplacePatch(_))
                         ) =>
                       followPatchRedirects(rendered, model, command, meta, state)
                     case Some(command) =>
                       handleNavigationCommand(rendered, model, command, meta, state).as(None)
                     case None =>
                       for
                         _    <- state.patchRedirectCountRef.set(0)
                         diff <-
                           SocketModelRuntime.updateModelAndSubscriptions(rendered, model, state)
                         _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
                       yield Some(diff)
        reply = diffOpt match
                  case Some(diff) if !diff.isEmpty =>
                    Payload.okReply(LiveResponse.Diff(diff))
                  case _ =>
                    Payload.okReply(LiveResponse.Empty)
      yield reply
    }

  private def followPatchRedirects[Msg, Model](
    rendered: RenderedView,
    model: Model,
    command: LiveNavigationCommand,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Option[Diff]] =
    def loop(currentModel: Model, currentCommand: LiveNavigationCommand): Task[Option[Diff]] =
      currentCommand match
        case LiveNavigationCommand.PushPatch(to) =>
          continue(currentModel, to, LivePatchKind.Push)
        case LiveNavigationCommand.ReplacePatch(to) =>
          continue(currentModel, to, LivePatchKind.Replace)
        case other =>
          handleNavigationCommand(rendered, currentModel, other, meta, state).as(None)

    def continue(
      currentModel: Model,
      to: String,
      kind: LivePatchKind
    ): Task[Option[Diff]] =
      for
        _          <- SocketFlashRuntime.commitNavigation(state.flashRef)
        currentUrl <- state.currentUrlRef.get
        nextUrl    <- ZIO
                     .fromEither(LivePatchUrl.resolve(to, currentUrl))
                     .mapError(error => new IllegalArgumentException(error))
        redirectCount <- state.patchRedirectCountRef.updateAndGet(_ + 1)
        _      <- state.ref.update { case (_, currentRendered) => (currentModel, currentRendered) }
        result <-
          if redirectCount > 20 then
            SocketModelRuntime
              .publishPayload(
                Payload.Error,
                meta.copy(joinRef = None, messageRef = None),
                state
              ).as(None)
          else
            for
              _                       <- state.currentUrlRef.set(nextUrl)
              (nextModel, navigation) <-
                SocketModelRuntime.captureNavigation(state, resetFlash = false)(
                  LiveViewParamsRuntime.runHandleParams(
                    state.lv,
                    currentModel,
                    nextUrl,
                    state.ctx
                  )
                )
              diffOpt <- navigation match
                           case Some(
                                 nextCommand @ (LiveNavigationCommand.PushPatch(_) |
                                 LiveNavigationCommand.ReplacePatch(_))
                               ) =>
                             loop(nextModel, nextCommand)
                           case Some(nextCommand) =>
                             handleNavigationCommand(rendered, nextModel, nextCommand, meta, state)
                               .as(None)
                           case None =>
                             for
                               _    <- state.patchRedirectCountRef.set(0)
                               diff <- SocketModelRuntime.updateModelAndSubscriptions(
                                         rendered,
                                         nextModel,
                                         state
                                       )
                               _ <- SocketModelRuntime.publishPayload(
                                      Payload.LiveNavigation(nextUrl.encode, kind),
                                      meta.copy(messageRef = None),
                                      state
                                    )
                               _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
                             yield Some(diff)
            yield diffOpt
      yield result

    loop(model, command)
  end followPatchRedirects

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
          _ <- SocketAsyncRuntime.interruptOwners(
                 state.asyncTasksRef,
                 destroyedCids.map(LiveAsyncOwner.Component(_))
               )
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
          _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
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
    val _ = rendered

    def resolve(to: String): Task[String] =
      state.currentUrlRef.get.flatMap(currentUrl =>
        ZIO
          .fromEither(LivePatchUrl.resolve(to, currentUrl))
          .map(_.encode)
          .mapError(error => new IllegalArgumentException(error))
      )

    def flashToken: UIO[Option[String]] =
      SocketFlashRuntime
        .navigationValues(state.flashRef)
        .map(FlashToken.encode(state.tokenConfig, _))

    def publish(payload: Payload): UIO[Unit] =
      SocketModelRuntime.publishPayload(payload, meta.copy(messageRef = None), state)

    command match
      case LiveNavigationCommand.PushPatch(to) =>
        for
          resolvedTo    <- resolve(to)
          redirectCount <- state.patchRedirectCountRef.updateAndGet(_ + 1)
          _ <- state.ref.update { case (_, currentRendered) => (model, currentRendered) }
          _ <-
            if redirectCount > 20 then
              SocketModelRuntime.publishPayload(
                Payload.Error,
                meta.copy(joinRef = None, messageRef = None),
                state
              )
            else publish(Payload.LiveNavigation(resolvedTo, LivePatchKind.Push))
        yield ()
      case LiveNavigationCommand.ReplacePatch(to) =>
        for
          resolvedTo    <- resolve(to)
          redirectCount <- state.patchRedirectCountRef.updateAndGet(_ + 1)
          _ <- state.ref.update { case (_, currentRendered) => (model, currentRendered) }
          _ <-
            if redirectCount > 20 then
              SocketModelRuntime.publishPayload(
                Payload.Error,
                meta.copy(joinRef = None, messageRef = None),
                state
              )
            else publish(Payload.LiveNavigation(resolvedTo, LivePatchKind.Replace))
        yield ()
      case LiveNavigationCommand.PushNavigate(to) =>
        for
          resolvedTo <- resolve(to)
          token      <- flashToken
          _          <- state.ref.update { case (_, currentRendered) => (model, currentRendered) }
          _          <- publish(Payload.LiveRedirect(resolvedTo, LivePatchKind.Push, token))
          _          <- SocketFlashRuntime.resetNavigation(state.flashRef)
        yield ()
      case LiveNavigationCommand.ReplaceNavigate(to) =>
        for
          resolvedTo <- resolve(to)
          token      <- flashToken
          _          <- state.ref.update { case (_, currentRendered) => (model, currentRendered) }
          _          <- publish(Payload.LiveRedirect(resolvedTo, LivePatchKind.Replace, token))
          _          <- SocketFlashRuntime.resetNavigation(state.flashRef)
        yield ()
      case LiveNavigationCommand.Redirect(to) =>
        for
          resolvedTo <- resolve(to)
          token      <- flashToken
          _          <- state.ref.update { case (_, currentRendered) => (model, currentRendered) }
          _          <- publish(Payload.Redirect(resolvedTo, token))
          _          <- SocketFlashRuntime.resetNavigation(state.flashRef)
        yield ()
    end match
  end handleNavigationCommand
end SocketInbound
