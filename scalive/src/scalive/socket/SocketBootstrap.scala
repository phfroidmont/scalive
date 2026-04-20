package scalive
package socket

import zio.*
import zio.stream.SubscriptionRef

import scalive.*

private[scalive] object SocketBootstrap:
  def initializeRuntime[Msg, Model](
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta
  ): Task[RuntimeState[Msg, Model]] =
    for
      inbox           <- Queue.bounded[(WebSocketMessage.Payload.Event, WebSocketMessage.Meta)](4)
      outHub          <- Hub.unbounded[(WebSocketMessage.Payload, WebSocketMessage.Meta)]
      uploadRef       <- Ref.make(UploadRuntimeState.empty)
      streamRef       <- Ref.make(StreamRuntimeState.empty)
      clientEventsRef <- Ref.make(Vector.empty[Diff.Event])
      navigationRef   <- Ref.make(Option.empty[LiveNavigationCommand])
      runtimeCtx = ctx.copy(
                     uploads = new SocketUploadRuntime(uploadRef),
                     streams = new SocketStreamRuntime(streamRef),
                     clientEvents = new SocketClientEventRuntime(clientEventsRef),
                     navigation = new SocketNavigationRuntime(navigationRef)
                   )
      initModel <- LiveIO.toZIO(lv.init).provide(ZLayer.succeed(runtimeCtx))
      modelVar = Var(initModel)
      el       = lv.view(modelVar)
      ref <- Ref.make((modelVar, el))
      rawInitDiff = el.diff(trackUpdates = false)
      initEvents <- SocketClientEventRuntime.drain(clientEventsRef)
      initDiff = SocketModelRuntime.withClientEvents(rawInitDiff, initEvents)
      _           <- SocketStreamRuntime.prune(streamRef)
      lvStreamRef <-
        SubscriptionRef.make(lv.subscriptions(initModel).provideLayer(ZLayer.succeed(runtimeCtx)))
      patchRedirectCountRef <- Ref.make(0)
    yield RuntimeState(
      lv = lv,
      ctx = runtimeCtx,
      meta = meta,
      inbox = inbox,
      outHub = outHub,
      ref = ref,
      lvStreamRef = lvStreamRef,
      navigationRef = navigationRef,
      uploadRef = uploadRef,
      streamRef = streamRef,
      clientEventsRef = clientEventsRef,
      patchRedirectCountRef = patchRedirectCountRef,
      initDiff = initDiff
    )
end SocketBootstrap
