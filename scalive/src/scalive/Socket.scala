package scalive

import scala.reflect.ClassTag

import zio.*
import zio.Queue
import zio.http.URL
import zio.stream.ZStream

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.socket.SocketBootstrap
import scalive.socket.SocketFlashRuntime
import scalive.socket.SocketInbound
import scalive.socket.SocketOutbound
import scalive.socket.SocketUploadProtocol

final private[scalive] case class Socket[Msg, Model] private (
  id: String,
  token: String,
  inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
  livePatch: (String, WebSocketMessage.Meta) => Task[Payload.Reply],
  allowUpload: Payload.AllowUpload => Task[Payload.Reply],
  progressUpload: Payload.Progress => Task[Payload.Reply],
  uploadJoin: (String, String) => Task[Payload.Reply],
  uploadChunk: (String, Chunk[Byte]) => Task[Payload.Reply],
  outbox: ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)],
  private[scalive] val initReply: Payload.Reply,
  private[scalive] val stickyRejoinReply: UIO[Payload.Reply],
  private[scalive] val currentUrl: UIO[URL],
  private[scalive] val takeNavigationFlash: UIO[Map[String, String]],
  private[scalive] val replaceNavigationFlash: Map[String, String] => UIO[Unit],
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
    renderRoot: Option[(Model, URL) => HtmlElement[Msg]] = None,
    enqueueInitReply: Boolean = true
  ): RIO[Scope, Socket[Msg, Model]] =
    val rootRenderer = renderRoot.getOrElse((model: Model, _: URL) => lv.render(model))
    ZIO.logAnnotate("lv", id) {
      for
        state <- SocketBootstrap.initializeRuntime(
                   lv,
                   ctx,
                   meta,
                   tokenConfig,
                   initialUrl,
                   initialFlash,
                   rootRenderer
                 )
        clientFiber <- SocketInbound.startClientFiber(state)
        serverFiber <- SocketOutbound.startServerFiber(state)
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
                                  LiveResponse.InitDiff(TreeDiff.initial(rendered.compiled))
                                )
                              }
                            }
        currentUrl             = state.currentUrlRef.get
        takeNavigationFlash    = SocketFlashRuntime.takeNavigation(state.flashRef)
        replaceNavigationFlash = (flash: Map[String, String]) =>
                                   SocketFlashRuntime.replaceNavigation(state.flashRef, flash)
        stop = SocketOutbound.buildShutdown(state, clientFiber, serverFiber)
        _ <- ZIO.addFinalizerExit(_ => stop.exit.unit)
      yield Socket[Msg, Model](
        id,
        token,
        state.inbox,
        livePatch,
        allowUpload,
        progressUpload,
        uploadJoin,
        uploadChunk,
        outbox,
        initReply,
        stickyRejoinReply,
        currentUrl,
        takeNavigationFlash,
        replaceNavigationFlash,
        stop
      )
    }
  end start
end Socket
