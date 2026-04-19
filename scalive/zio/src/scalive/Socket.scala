package scalive

import zio.*
import zio.Queue
import zio.stream.ZStream

import scalive.WebSocketMessage.Payload
import scalive.socket.SocketClientOps
import scalive.socket.SocketInit
import scalive.socket.SocketServerOps
import scalive.socket.SocketUploadProtocol

final case class Socket[Msg, Model] private (
  id: String,
  token: String,
  inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
  livePatch: (String, WebSocketMessage.Meta) => Task[Unit],
  allowUpload: Payload.AllowUpload => Task[Payload.Reply],
  progressUpload: Payload.Progress => Task[Payload.Reply],
  uploadJoin: (String, String) => Task[Payload.Reply],
  uploadChunk: (String, Chunk[Byte]) => Task[Payload.Reply],
  outbox: ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)],
  shutdown: UIO[Unit])

object Socket:
  def start[Msg, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta
  ): RIO[Scope, Socket[Msg, Model]] =
    ZIO.logAnnotate("lv", id) {
      for
        state       <- SocketInit.initializeRuntime(lv, ctx, meta)
        clientFiber <- SocketClientOps.startClientFiber(state)
        serverFiber <- SocketServerOps.startServerFiber(state)
        livePatch =
          (url: String, patchMeta: WebSocketMessage.Meta) =>
            SocketClientOps.handleLivePatch(url, patchMeta, state)
        allowUpload =
          (payload: Payload.AllowUpload) => SocketUploadProtocol.handleAllowUpload(payload, state)
        progressUpload =
          (payload: Payload.Progress) => SocketUploadProtocol.handleProgressUpload(payload, state)
        uploadJoin = (uploadTopic: String, uploadToken: String) =>
                       SocketUploadProtocol.handleUploadJoin(uploadTopic, uploadToken, state)
        uploadChunk = (uploadTopic: String, bytes: Chunk[Byte]) =>
                        SocketUploadProtocol.handleUploadChunk(uploadTopic, bytes, state)
        outbox = SocketServerOps.buildOutbox(state)
        stop   = SocketServerOps.buildShutdown(state, clientFiber, serverFiber)
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
