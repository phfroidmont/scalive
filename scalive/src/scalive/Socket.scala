package scalive

import scala.reflect.ClassTag

import zio.*
import zio.Queue
import zio.http.URL
import zio.stream.ZStream

import scalive.WebSocketMessage.Payload
import scalive.socket.SocketBootstrap
import scalive.socket.SocketInbound
import scalive.socket.SocketOutbound
import scalive.socket.SocketUploadProtocol

final case class Socket[Msg, Model] private (
  id: String,
  token: String,
  inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
  livePatch: (String, WebSocketMessage.Meta) => Task[Payload.Reply],
  allowUpload: Payload.AllowUpload => Task[Payload.Reply],
  progressUpload: Payload.Progress => Task[Payload.Reply],
  uploadJoin: (String, String) => Task[Payload.Reply],
  uploadChunk: (String, Chunk[Byte]) => Task[Payload.Reply],
  outbox: ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)],
  shutdown: UIO[Unit])

object Socket:
  def start[Msg: ClassTag, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    tokenConfig: TokenConfig = TokenConfig.default,
    initialUrl: URL = URL.root,
    initialFlash: Map[String, String] = Map.empty
  ): RIO[Scope, Socket[Msg, Model]] =
    ZIO.logAnnotate("lv", id) {
      for
        state <- SocketBootstrap.initializeRuntime(
                   lv,
                   ctx,
                   meta,
                   tokenConfig,
                   initialUrl,
                   initialFlash
                 )
        clientFiber <- SocketInbound.startClientFiber(state)
        serverFiber <- SocketOutbound.startServerFiber(state)
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
        outbox = SocketOutbound.buildOutbox(state)
        stop   = SocketOutbound.buildShutdown(state, clientFiber, serverFiber)
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
        stop
      )
    }
end Socket
