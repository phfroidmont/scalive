package scalive
package socket

import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.stream.SubscriptionRef

import scalive.*

private[scalive] object SocketBootstrap:
  def initializeRuntime[Msg: ClassTag, Model](
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    tokenConfig: TokenConfig,
    initialUrl: URL
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
      (bootstrapModel, bootstrapPayloads) <-
        runInitialLifecycle(lv, runtimeCtx, navigationRef, initModel, initialUrl)
      initCompiled = RenderSnapshot.compile(lv.view(bootstrapModel))
      initView     = RenderedView(
                   compiled = initCompiled,
                   bindings = BindingRegistry.collect[Msg](initCompiled)
                 )
      ref <- Ref.make((bootstrapModel, initView))
      rawInitDiff = TreeDiff.initial(initCompiled)
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
        SubscriptionRef.make(
          lv.subscriptions(bootstrapModel).provideLayer(ZLayer.succeed(runtimeCtx))
        )
      patchRedirectCountRef <- Ref.make(0)
      bootstrapPayloadEnvelopes =
        bootstrapPayloads.map(_ -> meta.copy(messageRef = None))
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
      bootstrapPayloads = bootstrapPayloadEnvelopes,
      initDiff = initDiff
    )

  private val MaxBootstrapRedirects = 20

  private def runInitialLifecycle[Msg, Model](
    lv: LiveView[Msg, Model],
    runtimeCtx: LiveContext,
    navigationRef: Ref[Option[LiveNavigationCommand]],
    initialModel: Model,
    initialUrl: URL
  ): Task[(Model, Chunk[WebSocketMessage.Payload])] =
    def loop(
      model: Model,
      url: URL,
      redirectCount: Int,
      payloads: Chunk[WebSocketMessage.Payload]
    ): Task[(Model, Chunk[WebSocketMessage.Payload])] =
      val parsed = LiveParams.fromUrl(url)
      for
        _         <- navigationRef.set(None)
        nextModel <- LiveIO
                       .toZIO(lv.handleParams(model, parsed.params, parsed.uri))
                       .provide(ZLayer.succeed(runtimeCtx))
        navigation <- navigationRef.getAndSet(None)
        result     <- navigation match
                    case None =>
                      ZIO.succeed(nextModel -> payloads)
                    case Some(_) if redirectCount >= MaxBootstrapRedirects =>
                      ZIO
                        .logWarning("Too many redirects while applying initial handleParams")
                        .as(nextModel -> (payloads :+ WebSocketMessage.Payload.Error))
                    case Some(command) =>
                      val (to, kind) = command match
                        case LiveNavigationCommand.PushPatch(value) =>
                          value -> WebSocketMessage.LivePatchKind.Push
                        case LiveNavigationCommand.ReplacePatch(value) =>
                          value -> WebSocketMessage.LivePatchKind.Replace
                      URL.decode(to) match
                        case Right(nextUrl) =>
                          loop(
                            nextModel,
                            nextUrl,
                            redirectCount + 1,
                            Chunk(WebSocketMessage.Payload.LiveNavigation(to, kind))
                          )
                        case Left(error) =>
                          ZIO
                            .logWarning(
                              s"Could not decode bootstrap navigation URL '$to': $error"
                            )
                            .as(nextModel -> payloads)
      yield result
      end for
    end loop

    loop(initialModel, initialUrl, 0, Chunk.empty)
  end runInitialLifecycle
end SocketBootstrap
