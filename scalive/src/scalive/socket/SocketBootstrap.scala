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
    initialUrl: URL,
    initialFlash: Map[String, String]
  ): Task[RuntimeState[Msg, Model]] =
    for
      inbox           <- Queue.bounded[(WebSocketMessage.Payload.Event, WebSocketMessage.Meta)](4)
      asyncQueue      <- Queue.unbounded[LiveAsyncCompletion]
      outHub          <- Hub.unbounded[(WebSocketMessage.Payload, WebSocketMessage.Meta)]
      uploadRef       <- Ref.make(UploadRuntimeState.empty)
      streamRef       <- Ref.make(StreamRuntimeState.empty)
      clientEventsRef <- Ref.make(Vector.empty[Diff.Event])
      titleRef        <- Ref.make(Option.empty[String])
      flashRef        <- Ref.make(FlashRuntimeState(initialFlash))
      asyncTasksRef   <- Ref.make(LiveAsyncRuntimeState.empty)
      navigationRef   <- Ref.make(Option.empty[LiveNavigationCommand])
      componentsRef   <- Ref.make(ComponentRuntimeState.empty)
      runtimeCtx = ctx.copy(
                     uploads = new SocketUploadRuntime(uploadRef),
                     streams = new SocketStreamRuntime(streamRef),
                     clientEvents = new SocketClientEventRuntime(clientEventsRef),
                     navigation = new SocketNavigationRuntime(navigationRef),
                     title = new SocketTitleRuntime(titleRef),
                     flash = new SocketFlashRuntime(flashRef),
                     async = new SocketAsyncRuntime(
                       asyncQueue,
                       asyncTasksRef,
                       LiveAsyncOwner.Root
                     ),
                     components = new SocketComponentUpdateRuntime(componentsRef)
                   )
      _               <- SocketFlashRuntime.resetNavigation(flashRef)
      _               <- navigationRef.set(None)
      initModel       <- LiveIO.toZIO(lv.mount).provide(ZLayer.succeed(runtimeCtx))
      mountNavigation <- navigationRef.getAndSet(None)
      (bootstrapModel, bootstrapPayloads, bootstrapUrl) <-
        runInitialLifecycle(
          lv,
          runtimeCtx,
          navigationRef,
          flashRef,
          tokenConfig,
          initModel,
          initialUrl,
          mountNavigation
        )
      initRoot <-
        SocketComponentRuntime.renderRoot(lv.render(bootstrapModel), componentsRef, runtimeCtx)
      initCompiled = RenderSnapshot.compile(initRoot)
      initView     = RenderedView(
                   compiled = initCompiled,
                   bindings = BindingRegistry.collect[Any](initCompiled)
                 )
      ref           <- Ref.make((bootstrapModel, initView))
      currentUrlRef <- Ref.make(bootstrapUrl)
      rawInitDiff = TreeDiff.initial(initCompiled)
      initEvents <- SocketClientEventRuntime.drain(clientEventsRef)
      initTitle  <- titleRef.getAndSet(None)
      initDiff = SocketModelRuntime.withTitle(
                   SocketModelRuntime.withClientEvents(rawInitDiff, initEvents),
                   initTitle
                 )
      _                <- SocketFlashRuntime.resetNavigation(flashRef)
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
      asyncQueue = asyncQueue,
      outHub = outHub,
      ref = ref,
      currentUrlRef = currentUrlRef,
      lvStreamRef = lvStreamRef,
      navigationRef = navigationRef,
      uploadRef = uploadRef,
      streamRef = streamRef,
      clientEventsRef = clientEventsRef,
      titleRef = titleRef,
      flashRef = flashRef,
      asyncTasksRef = asyncTasksRef,
      componentsRef = componentsRef,
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
    flashRef: Ref[FlashRuntimeState],
    tokenConfig: TokenConfig,
    initialModel: Model,
    initialUrl: URL,
    initialNavigation: Option[LiveNavigationCommand]
  ): Task[(Model, Chunk[WebSocketMessage.Payload], URL)] =
    def loop(
      model: Model,
      url: URL,
      redirectCount: Int,
      payloads: Chunk[WebSocketMessage.Payload],
      preserveNavigationFlash: Boolean = false
    ): Task[(Model, Chunk[WebSocketMessage.Payload], URL)] =
      for
        _ <- ZIO.unless(preserveNavigationFlash)(SocketFlashRuntime.resetNavigation(flashRef))
        _ <- navigationRef.set(None)
        nextModel <- LiveIO
                       .toZIO(LiveViewParamsRuntime.runHandleParams(lv, model, url))
                       .provide(ZLayer.succeed(runtimeCtx))
        navigation <- navigationRef.getAndSet(None)
        result     <- navigation match
                    case None =>
                      ZIO.succeed((nextModel, payloads, url))
                    case Some(_) if redirectCount >= MaxBootstrapRedirects =>
                      ZIO
                        .logWarning("Too many redirects while applying initial handleParams")
                        .as((nextModel, payloads :+ WebSocketMessage.Payload.Error, url))
                    case Some(command) =>
                      command match
                        case LiveNavigationCommand.PushPatch(to) =>
                          handleBootstrapPatch(
                            nextModel,
                            url,
                            redirectCount,
                            payloads,
                            to,
                            WebSocketMessage.LivePatchKind.Push
                          )
                        case LiveNavigationCommand.ReplacePatch(to) =>
                          handleBootstrapPatch(
                            nextModel,
                            url,
                            redirectCount,
                            payloads,
                            to,
                            WebSocketMessage.LivePatchKind.Replace
                          )
                        case LiveNavigationCommand.PushNavigate(to) =>
                          handleBootstrapLiveRedirect(
                            nextModel,
                            url,
                            payloads,
                            to,
                            WebSocketMessage.LivePatchKind.Push
                          )
                        case LiveNavigationCommand.ReplaceNavigate(to) =>
                          handleBootstrapLiveRedirect(
                            nextModel,
                            url,
                            payloads,
                            to,
                            WebSocketMessage.LivePatchKind.Replace
                          )
                        case LiveNavigationCommand.Redirect(to) =>
                          handleBootstrapRedirect(nextModel, url, payloads, to)
      yield result
      end for
    end loop

    def handleBootstrapPatch(
      nextModel: Model,
      url: URL,
      redirectCount: Int,
      payloads: Chunk[WebSocketMessage.Payload],
      to: String,
      kind: WebSocketMessage.LivePatchKind
    ): Task[(Model, Chunk[WebSocketMessage.Payload], URL)] =
      LivePatchUrl.resolve(to, url) match
        case Right(nextUrl) =>
          SocketFlashRuntime.commitNavigation(flashRef) *>
            loop(
              nextModel,
              nextUrl,
              redirectCount + 1,
              payloads :+ WebSocketMessage.Payload.LiveNavigation(nextUrl.encode, kind),
              preserveNavigationFlash = true
            )
        case Left(error) =>
          ZIO
            .logWarning(
              s"Could not decode bootstrap navigation URL '$to': $error"
            )
            .as((nextModel, payloads, url))

    def handleBootstrapLiveRedirect(
      nextModel: Model,
      url: URL,
      payloads: Chunk[WebSocketMessage.Payload],
      to: String,
      kind: WebSocketMessage.LivePatchKind
    ): Task[(Model, Chunk[WebSocketMessage.Payload], URL)] =
      LivePatchUrl.resolve(to, url) match
        case Right(nextUrl) =>
          SocketFlashRuntime.navigationValues(flashRef).map { flash =>
            val token = FlashToken.encode(tokenConfig, flash)
            (
              nextModel,
              payloads :+ WebSocketMessage.Payload.LiveRedirect(nextUrl.encode, kind, token),
              url
            )
          }
        case Left(error) =>
          ZIO
            .logWarning(
              s"Could not decode bootstrap navigation URL '$to': $error"
            )
            .as((nextModel, payloads, url))

    def handleBootstrapRedirect(
      nextModel: Model,
      url: URL,
      payloads: Chunk[WebSocketMessage.Payload],
      to: String
    ): Task[(Model, Chunk[WebSocketMessage.Payload], URL)] =
      LivePatchUrl.resolve(to, url) match
        case Right(nextUrl) =>
          SocketFlashRuntime.navigationValues(flashRef).map { flash =>
            val token = FlashToken.encode(tokenConfig, flash)
            (nextModel, payloads :+ WebSocketMessage.Payload.Redirect(nextUrl.encode, token), url)
          }
        case Left(error) =>
          ZIO
            .logWarning(
              s"Could not decode bootstrap navigation URL '$to': $error"
            )
            .as((nextModel, payloads, url))

    initialNavigation match
      case Some(LiveNavigationCommand.PushPatch(to)) =>
        handleBootstrapPatch(
          initialModel,
          initialUrl,
          0,
          Chunk.empty,
          to,
          WebSocketMessage.LivePatchKind.Push
        )
      case Some(LiveNavigationCommand.ReplacePatch(to)) =>
        handleBootstrapPatch(
          initialModel,
          initialUrl,
          0,
          Chunk.empty,
          to,
          WebSocketMessage.LivePatchKind.Replace
        )
      case Some(LiveNavigationCommand.PushNavigate(to)) =>
        handleBootstrapLiveRedirect(
          initialModel,
          initialUrl,
          Chunk.empty,
          to,
          WebSocketMessage.LivePatchKind.Push
        )
      case Some(LiveNavigationCommand.ReplaceNavigate(to)) =>
        handleBootstrapLiveRedirect(
          initialModel,
          initialUrl,
          Chunk.empty,
          to,
          WebSocketMessage.LivePatchKind.Replace
        )
      case Some(LiveNavigationCommand.Redirect(to)) =>
        handleBootstrapRedirect(initialModel, initialUrl, Chunk.empty, to)
      case None =>
        loop(initialModel, initialUrl, 0, Chunk.empty)
    end match
  end runInitialLifecycle
end SocketBootstrap
