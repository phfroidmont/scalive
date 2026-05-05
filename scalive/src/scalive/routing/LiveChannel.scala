package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import scalive.WebSocketMessage.JoinErrorReason
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Meta
import scalive.WebSocketMessage.Payload

private[scalive] enum NestedJoinResult:
  case Joined
  case JoinedWithReply(reply: Payload.Reply)
  case Rejected(reason: JoinErrorReason)
  case NotNested

final private[scalive] class LiveChannel(
  sockets: SubscriptionRef[Map[String, Socket[?, ?]]],
  uploadOwners: Ref[Map[String, String]],
  nestedEntries: Ref[Map[String, NestedLiveViewEntry]],
  tokenConfig: TokenConfig,
  private[scalive] val connectAuthorized: Boolean):
  def diffsStream: ZStream[Any, Nothing, (Payload, Meta)] =
    sockets.changes
      .map(m =>
        ZStream
          .mergeAllUnbounded()(m.values.map(_.outbox).toList*)
      ).flatMapParSwitch(1)(identity)

  def join[Msg, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    initialUrl: URL,
    initialFlash: Map[String, String] = Map.empty,
    renderRoot: Option[(Model, URL) => HtmlElement[Msg]] = None
  )(using ClassTag[Msg]
  ): RIO[Scope, Unit] =
    val rootRenderer = renderRoot.getOrElse((model: Model, _: URL) => lv.render(model))
    sockets
      .updateZIO { m =>
        m.get(id) match
          case Some(socket) =>
            socket.shutdown *>
              Socket
                .start(
                  id,
                  token,
                  lv,
                  ctx,
                  meta,
                  tokenConfig,
                  initialUrl,
                  initialFlash,
                  Some(rootRenderer)
                )
                .map(m.updated(id, _))
          case None =>
            Socket
              .start(
                id,
                token,
                lv,
                ctx,
                meta,
                tokenConfig,
                initialUrl,
                initialFlash,
                Some(rootRenderer)
              )
              .map(m.updated(id, _))
      }.flatMap(_ => ZIO.logDebug(s"LiveView joined $id"))
  end join

  def nestedRuntime(parentTopic: String): NestedLiveViewRuntime =
    nestedRuntime(parentTopic, parentTopic.stripPrefix("lv:"))

  private def nestedRuntime(parentTopic: String, parentDomId: String): NestedLiveViewRuntime =
    new SocketNestedLiveViewRuntime(parentTopic, parentDomId, tokenConfig, nestedEntries)

  private[scalive] def nestedEntry(topic: String): UIO[Option[NestedLiveViewEntry]] =
    nestedEntries.get.map(_.get(topic))

  private[scalive] def socket(id: String): UIO[Option[Socket[?, ?]]] =
    sockets.get.map(_.get(id))

  private def rootTopic(entries: Map[String, NestedLiveViewEntry], topic: String): String =
    entries.get(topic) match
      case Some(entry) => rootTopic(entries, entry.parentTopic)
      case None        => topic

  private def takeNestedNavigationFlash(id: String): UIO[Map[String, String]] =
    for
      entries        <- nestedEntries.get
      currentSockets <- sockets.get
      descendantTopics = entries.keysIterator
                           .filter(topic => rootTopic(entries, topic) == id)
                           .toList
      flashes <- ZIO.foreach(descendantTopics)(topic =>
                   currentSockets
                     .get(topic)
                     .fold(ZIO.succeed(Map.empty[String, String]))(_.takeNavigationFlash)
                 )
    yield flashes.foldLeft(Map.empty[String, String])(_ ++ _)

  def joinNested(
    topic: String,
    token: String,
    staticChanged: Boolean,
    meta: WebSocketMessage.Meta,
    initialUrl: URL
  ): RIO[Scope, Option[JoinErrorReason]] =
    tryJoinNested(topic, token, staticChanged, meta, Some(initialUrl)).map {
      case NestedJoinResult.Rejected(reason) => Some(reason)
      case _                                 => None
    }

  private[scalive] def tryJoinNested(
    topic: String,
    token: String,
    staticChanged: Boolean,
    meta: WebSocketMessage.Meta,
    initialUrl: Option[URL]
  ): RIO[Scope, NestedJoinResult] =
    nestedEntries.get.flatMap { entries =>
      entries.get(topic) match
        case Some(entry) if isAuthorizedNestedJoin(topic, token) =>
          resolveNestedInitialUrl(entry, initialUrl).flatMap {
            case Some(url) =>
              val ctx = LiveContext(
                staticChanged = staticChanged,
                nestedLiveViews = nestedRuntime(topic, entry.id)
              )
              sockets
                .modifyZIO { m =>
                  m.get(topic) match
                    case Some(socket) if entry.sticky =>
                      socket.stickyRejoinReply
                        .tap(_ => ZIO.logDebug(s"Rejoined sticky LiveView $topic"))
                        .map(reply => NestedJoinResult.JoinedWithReply(reply) -> m)
                    case Some(socket) =>
                      socket.shutdown *>
                        entry
                          .start(ctx, meta, url).map(socket =>
                            NestedJoinResult.Joined -> m.updated(topic, socket)
                          )
                    case None =>
                      entry
                        .start(ctx, meta, url).map(socket =>
                          NestedJoinResult.Joined -> m.updated(topic, socket)
                        )
                }
            case None =>
              ZIO.succeed(NestedJoinResult.Rejected(JoinErrorReason.Stale))
          }
        case Some(_) =>
          ZIO.succeed(NestedJoinResult.Rejected(JoinErrorReason.Unauthorized))
        case None =>
          ZIO.succeed(NestedJoinResult.NotNested)
    }

  private def resolveNestedInitialUrl(
    entry: NestedLiveViewEntry,
    initialUrl: Option[URL]
  ): UIO[Option[URL]] =
    initialUrl match
      case some @ Some(_) => ZIO.succeed(some)
      case None           =>
        sockets.get.flatMap { m =>
          m.get(entry.parentTopic) match
            case Some(parent) => parent.currentUrl.map(Some(_))
            case None         => ZIO.none
        }

  private def isAuthorizedNestedJoin(topic: String, token: String): Boolean =
    Token
      .verify[String](tokenConfig.secret, token, tokenConfig.maxAge)
      .toOption
      .exists { case (tokenTopic, payload) => tokenTopic == topic && payload == "nested" }

  def leave(id: String): UIO[Unit] =
    nestedEntries.get.flatMap { entries =>
      entries.get(id) match
        case Some(entry) if entry.sticky =>
          ZIO.logDebug(s"Detached sticky LiveView $id")
        case _ =>
          for
            childIds <- nestedEntries.modify { entries =>
                          val children = entries.collect {
                            case (topic, entry) if entry.parentTopic == id && !entry.sticky => topic
                          }.toSet
                          (children, entries -- children - id)
                        }
            leavingIds = childIds + id
            _ <- uploadOwners.update(_.filterNot { case (_, ownerId) =>
                   leavingIds.contains(ownerId)
                 })
            _ <- sockets.updateZIO { m =>
                   val children     = childIds.flatMap(m.get)
                   val stopChildren = ZIO.foreachDiscard(children)(_.shutdown)
                   m.get(id) match
                     case Some(socket) =>
                       for
                         _ <- stopChildren
                         _ <- socket.shutdown
                         _ <- ZIO.logDebug(s"Left LiveView $id")
                       yield m -- childIds - id
                     case None =>
                       stopChildren *>
                         ZIO.logDebug(s"Ignoring leave for unknown LiveView $id").as(m -- childIds)
                 }
          yield ()
    }

  def event(id: String, event: Payload.Event, meta: WebSocketMessage.Meta): UIO[Unit] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) =>
          socket.inbox.offer(event -> meta).unit
        case None => ZIO.unit
    }

  def livePatch(id: String, url: String, meta: WebSocketMessage.Meta): Task[Payload.Reply] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) =>
          for
            flash <- takeNestedNavigationFlash(id)
            _     <- ZIO.when(flash.nonEmpty)(socket.replaceNavigationFlash(flash))
            reply <- socket.livePatch(url, meta)
          yield reply
        case None => ZIO.succeed(Payload.okReply(LiveResponse.Empty))
    }

  def allowUpload(id: String, payload: Payload.AllowUpload): Task[Payload.Reply] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) =>
          socket.allowUpload(payload).tap {
            case Payload.Reply(_, LiveResponse.UploadPreflightSuccess(_, _, entries, _)) =>
              uploadOwners
                .update(current => current ++ entries.keys.map(entryRef => s"lvu:$entryRef" -> id))
            case _ => ZIO.unit
          }
        case None =>
          ZIO.succeed(
            Payload.okReply(LiveResponse.UploadPreflightFailure(payload.ref, List.empty))
          )
    }

  def progressUpload(id: String, payload: Payload.Progress): Task[Payload.Reply] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) => socket.progressUpload(payload)
        case None         => ZIO.succeed(Payload.okReply(LiveResponse.Empty))
    }

  def uploadJoin(uploadTopic: String, token: String): Task[Payload.Reply] =
    uploadOwners.get.flatMap { owners =>
      owners.get(uploadTopic) match
        case Some(ownerId) =>
          sockets.get.flatMap { socketMap =>
            socketMap.get(ownerId) match
              case Some(socket) => socket.uploadJoin(uploadTopic, token)
              case None         =>
                ZIO.succeed(
                  Payload.errorReply(
                    LiveResponse.UploadJoinError(WebSocketMessage.UploadJoinErrorReason.Disallowed)
                  )
                )
          }
        case None =>
          ZIO.succeed(
            Payload.errorReply(
              LiveResponse.UploadJoinError(WebSocketMessage.UploadJoinErrorReason.InvalidToken)
            )
          )
    }

  def uploadChunk(uploadTopic: String, bytes: Chunk[Byte]): Task[Payload.Reply] =
    uploadOwners.get.flatMap { owners =>
      owners.get(uploadTopic) match
        case Some(ownerId) =>
          sockets.get.flatMap { socketMap =>
            socketMap.get(ownerId) match
              case Some(socket) => socket.uploadChunk(uploadTopic, bytes)
              case None         =>
                ZIO.succeed(
                  Payload.errorReply(
                    LiveResponse.UploadChunkError(
                      WebSocketMessage.UploadChunkErrorReason.Disallowed
                    )
                  )
                )
          }
        case None =>
          ZIO.succeed(
            Payload.errorReply(
              LiveResponse.UploadChunkError(WebSocketMessage.UploadChunkErrorReason.Disallowed)
            )
          )
    }

end LiveChannel

private[scalive] object LiveChannel:
  def make(tokenConfig: TokenConfig, connectAuthorized: Boolean = true): UIO[LiveChannel] =
    for
      sockets      <- SubscriptionRef.make(Map.empty[String, Socket[?, ?]])
      uploadOwners <- Ref.make(Map.empty[String, String])
      nested       <- Ref.make(Map.empty[String, NestedLiveViewEntry])
    yield new LiveChannel(sockets, uploadOwners, nested, tokenConfig, connectAuthorized)
