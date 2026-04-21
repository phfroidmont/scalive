package scalive
package socket

import scala.reflect.ClassTag

import zio.*
import zio.stream.SubscriptionRef

import scalive.*

private[scalive] object SocketBootstrap:
  def initializeRuntime[Msg: ClassTag, Model](
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    tokenConfig: TokenConfig
  ): Task[RuntimeState[Msg, Model]] =
    for
      inbox           <- Queue.bounded[(WebSocketMessage.Payload.Event, WebSocketMessage.Meta)](4)
      outHub          <- Hub.unbounded[(WebSocketMessage.Payload, WebSocketMessage.Meta)]
      uploadRef       <- Ref.make(UploadRuntimeState.empty)
      streamRef       <- Ref.make(StreamRuntimeState.empty)
      clientEventsRef <- Ref.make(Vector.empty[Diff.Event])
      titleRef        <- Ref.make(Option.empty[String])
      navigationRef   <- Ref.make(Option.empty[LiveNavigationCommand])
      runtimeCtx = ctx.copy(
                     uploads = new SocketUploadRuntime(uploadRef),
                     streams = new SocketStreamRuntime(streamRef),
                     clientEvents = new SocketClientEventRuntime(clientEventsRef),
                     navigation = new SocketNavigationRuntime(navigationRef),
                     title = new SocketTitleRuntime(titleRef)
                   )
      initModel <- LiveIO.toZIO(lv.init).provide(ZLayer.succeed(runtimeCtx))
      initEl   = lv.view(initModel)
      initView = RenderedView(
                   el = initEl,
                   bindings = BindingRegistry.collect[Msg](initEl)
                 )
      ref <- Ref.make((initModel, initView))
      rawInitDiff = TreeDiff.initial(initEl)
      initEvents <- SocketClientEventRuntime.drain(clientEventsRef)
      initTitle  <- titleRef.getAndSet(None)
      initDiff = SocketModelRuntime.withTitle(
                   SocketModelRuntime.withClientEvents(rawInitDiff, initEvents),
                   initTitle
                 )
      componentCidsRef <- Ref.make(
                            initDiff match
                              case Diff.Tag(_, _, _, _, _, components, _, _) => components.keySet
                              case _                                         => Set.empty[Int]
                          )
      _           <- SocketStreamRuntime.prune(streamRef)
      lvStreamRef <-
        SubscriptionRef.make(lv.subscriptions(initModel).provideLayer(ZLayer.succeed(runtimeCtx)))
      patchRedirectCountRef <- Ref.make(0)
    yield RuntimeState(
      lv = lv,
      msgClassTag = summon[ClassTag[Msg]],
      ctx = runtimeCtx,
      meta = meta,
      tokenConfig = tokenConfig,
      inbox = inbox,
      outHub = outHub,
      ref = ref,
      lvStreamRef = lvStreamRef,
      navigationRef = navigationRef,
      uploadRef = uploadRef,
      streamRef = streamRef,
      clientEventsRef = clientEventsRef,
      titleRef = titleRef,
      componentCidsRef = componentCidsRef,
      patchRedirectCountRef = patchRedirectCountRef,
      initDiff = initDiff
    )
end SocketBootstrap
