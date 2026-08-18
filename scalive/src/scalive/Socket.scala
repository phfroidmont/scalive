package scalive

import scala.reflect.ClassTag

import zio.*
import zio.Queue
import zio.http.URL
import zio.stream.ZStream

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.socket.SocketBootstrap
import scalive.socket.SocketCrashRuntime
import scalive.socket.SocketFlashRuntime
import scalive.socket.SocketInbound
import scalive.socket.SocketOutbound
import scalive.socket.SocketUploadProtocol

final private[scalive] case class Socket[Msg, Model] private (
  id: String,
  token: String,
  joinRef: Option[Int],
  nestedGeneration: Option[Long],
  inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
  livePatch: (String, WebSocketMessage.Meta) => Task[Payload.Reply],
  allowUpload: Payload.AllowUpload => Task[Payload.Reply],
  progressUpload: Payload.Progress => Task[Payload.Reply],
  uploadJoin: (String, String) => Task[Payload.Reply],
  uploadChunk: (String, Chunk[Byte]) => Task[Payload.Reply],
  outbox: ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)],
  private[scalive] val initReply: Payload.Reply,
  private[scalive] val stickyRejoinReply: UIO[Payload.Reply],
  private[scalive] val renderedHtml: UIO[String],
  private[scalive] val currentUrl: UIO[URL],
  private[scalive] val takeNavigationFlash: UIO[Map[String, String]],
  private[scalive] val replaceNavigationFlash: Map[String, String] => UIO[Unit],
  private[scalive] val crash: UIO[Unit],
  shutdown: UIO[Unit])

private[scalive] object Socket:
  def start[Msg: ClassTag, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    tokenConfig: TokenConfig = TokenConfig.default,
    initialUrl: URL = URL.root,
    initialFlash: Map[String, String] = Map.empty,
    paramsRuntime: LiveRouteParamsRuntime[?, Msg, Model] =
      LiveRouteParamsRuntime.none[Any, Msg, Model],
    enqueueInitReply: Boolean = true,
    onCrash: UIO[Unit] = ZIO.unit,
    ownsPageTitle: Boolean = true,
    runtimeTrace: RuntimeTrace = RuntimeTrace.Disabled,
    nestedGeneration: Option[Long] = None
  ): RIO[Scope, Socket[Msg, Model]] =
    startWithRoot(
      id,
      token,
      lv,
      ctx,
      meta,
      tokenConfig,
      initialUrl,
      initialFlash,
      input => lv.view(input.map(_._1)),
      paramsRuntime,
      enqueueInitReply,
      onCrash,
      ownsPageTitle,
      runtimeTrace,
      nestedGeneration
    )

  def start[Msg: ClassTag, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    tokenConfig: TokenConfig,
    initialUrl: URL,
    initialFlash: Map[String, String],
    rootView: Signal[(Model, URL)] => HtmlElement[Msg],
    paramsRuntime: LiveRouteParamsRuntime[?, Msg, Model],
    enqueueInitReply: Boolean,
    onCrash: UIO[Unit],
    ownsPageTitle: Boolean,
    runtimeTrace: RuntimeTrace,
    nestedGeneration: Option[Long]
  ): RIO[Scope, Socket[Msg, Model]] =
    startWithRoot(
      id,
      token,
      lv,
      ctx,
      meta,
      tokenConfig,
      initialUrl,
      initialFlash,
      rootView,
      paramsRuntime,
      enqueueInitReply,
      onCrash,
      ownsPageTitle,
      runtimeTrace,
      nestedGeneration
    )

  private def startWithRoot[Msg: ClassTag, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    tokenConfig: TokenConfig,
    initialUrl: URL,
    initialFlash: Map[String, String],
    rootView: Signal[(Model, URL)] => HtmlElement[Msg],
    paramsRuntime: LiveRouteParamsRuntime[?, Msg, Model],
    enqueueInitReply: Boolean,
    onCrash: UIO[Unit],
    ownsPageTitle: Boolean,
    runtimeTrace: RuntimeTrace,
    nestedGeneration: Option[Long]
  ): RIO[Scope, Socket[Msg, Model]] =
    val traceOperation = RuntimeTraceOperation.resolve(
      runtimeTrace,
      meta,
      RuntimeTraceOperationKind.Join
    )
    val tracedMeta = RuntimeTraceOperation.attach(meta, traceOperation)
    ZIO.logAnnotate("lv", id) {
      for
        _ <- RuntimeTraceOperation.event(
               traceOperation,
               RuntimeTraceStage.SocketJoin,
               "Socket join started"
             )
        state <- SocketBootstrap.initializeRuntime(
                   lv,
                   ctx,
                   tracedMeta,
                   tokenConfig,
                   initialUrl,
                   initialFlash,
                   paramsRuntime,
                   onCrash,
                   ownsPageTitle,
                   runtimeTrace,
                   rootView
                 )
        clientFiber <- SocketInbound.startClientFiber(state)
        serverFiber <- SocketOutbound.startServerFiber(state)
        stop = SocketOutbound.buildShutdown(state, clientFiber, serverFiber)
        _ <- ZIO.addFinalizerExit(_ => stop.exit.unit)
        initReply    = Payload.okReply(LiveResponse.InitDiff(state.initDiff))
        initPayloads =
          if enqueueInitReply then (initReply -> state.meta) +: state.bootstrapPayloads.toList
          else state.bootstrapPayloads.toList
        _ <- ZIO.foreachDiscard(
               initPayloads
             )(state.outQueue.offer(_))
        livePatch =
          (url: String, patchMeta: WebSocketMessage.Meta) =>
            SocketInbound.handleLivePatch(url, patchMeta, state)
        allowUpload =
          (payload: Payload.AllowUpload) => SocketUploadProtocol.handleAllowUpload(payload, state)
        progressUpload =
          (payload: Payload.Progress) => SocketUploadProtocol.handleProgressUpload(payload, state)
        uploadJoin = (uploadTopic: String, uploadToken: String) =>
                       SocketUploadProtocol.handleUploadJoin(uploadTopic, uploadToken, state)
        uploadChunk = (uploadTopic: String, bytes: Chunk[Byte]) =>
                        SocketUploadProtocol.handleUploadChunk(uploadTopic, bytes, state)
        outbox            = SocketOutbound.buildOutbox(state)
        stickyRejoinReply = state.lifecycleLock.withPermit {
                              state.ref.get.map { case (_, rendered) =>
                                Payload.okReply(
                                  LiveResponse.InitDiff(
                                    if state.ownsPageTitle then
                                      socket.SocketModelRuntime.withTitle(
                                        TreeDiff.initial(rendered.compiled),
                                        Some(rendered.pageTitle.getOrElse(""))
                                      )
                                    else TreeDiff.initial(rendered.compiled)
                                  )
                                )
                              }
                            }
        renderedHtml = state.lifecycleLock.withPermit {
                         state.ref.get.map { case (_, rendered) =>
                           RenderSnapshot.renderHtml(rendered.compiled)
                         }
                       }
        currentUrl             = state.currentUrlRef.get
        takeNavigationFlash    = SocketFlashRuntime.takeNavigation(state.flashRef)
        replaceNavigationFlash = (flash: Map[String, String]) =>
                                   SocketFlashRuntime.replaceNavigation(state.flashRef, flash)
        crash = SocketCrashRuntime.crash(state, s"LiveView $id crashed by linked child")
      yield Socket[Msg, Model](
        id,
        token,
        meta.joinRef,
        nestedGeneration,
        state.inbox,
        livePatch,
        allowUpload,
        progressUpload,
        uploadJoin,
        uploadChunk,
        outbox,
        initReply,
        stickyRejoinReply,
        renderedHtml,
        currentUrl,
        takeNavigationFlash,
        replaceNavigationFlash,
        crash,
        stop
      )
    }
  end startWithRoot
end Socket
