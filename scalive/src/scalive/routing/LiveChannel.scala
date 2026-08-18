package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import scalive.WebSocketMessage.JoinErrorReason
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Meta
import scalive.WebSocketMessage.Payload

private[scalive] enum NestedJoinResult:
  case Joined
  case JoinedWithReply(reply: Payload.Reply)
  case FailedWithReply(reply: Payload.Reply)
  case Rejected(reason: JoinErrorReason)
  case NotNested

private[scalive] enum NestedJoinReservation:
  case Result(result: NestedJoinResult)
  case Rejoin(socket: Socket[?, ?])
  case Start(entry: NestedLiveViewEntry, previousSocket: Option[Socket[?, ?]])

final private[scalive] class LiveChannel(
  sockets: SubscriptionRef[Map[String, Socket[?, ?]]],
  uploadOwners: Ref[Map[String, String]],
  nestedEntries: Ref[Map[String, NestedLiveViewEntry]],
  nestedRenderPlans: Ref[Map[String, Map[String, NestedLiveViewEntry]]],
  nestedGeneration: Ref[Map[String, Long]],
  nestedJoins: Ref[Map[String, Long]],
  topologyLock: Semaphore,
  rootJoinLock: Semaphore,
  tokenConfig: TokenConfig,
  private[scalive] val connectAuthorized: Boolean,
  private[scalive] val csrfToken: Option[String],
  private[scalive] val runtimeTrace: RuntimeTrace):
  def diffsStream: ZStream[Any, Nothing, (Payload, Meta)] =
    ZStream.unwrapScoped {
      for
        outQueue  <- Queue.unbounded[(Payload, Meta)]
        fibersRef <- Ref.make(
                       Map.empty[
                         String,
                         (Socket[?, ?], Fiber.Runtime[Nothing, Unit])
                       ]
                     )
        _ <- ZIO.addFinalizer(outQueue.shutdown)
        _ <- sockets.changes
               .runForeach(syncOutboxFibers(_, outQueue, fibersRef))
               .forkScoped
      yield ZStream.fromQueue(outQueue)
    }

  private def syncOutboxFibers(
    socketsById: Map[String, Socket[?, ?]],
    outQueue: Queue[(Payload, Meta)],
    fibersRef: Ref[Map[String, (Socket[?, ?], Fiber.Runtime[Nothing, Unit])]]
  ): URIO[Scope, Unit] =
    fibersRef.get.flatMap { currentFibers =>
      val removedIds  = currentFibers.keySet -- socketsById.keySet
      val replacedIds = socketsById.collect {
        case (id, socket) if currentFibers.get(id).exists { case (currentSocket, _) =>
              currentSocket.asInstanceOf[AnyRef] ne socket.asInstanceOf[AnyRef]
            } =>
          id
      }.toSet
      val stoppedIds = removedIds ++ replacedIds
      val retained   = currentFibers -- stoppedIds
      val newSockets = socketsById.filter { case (id, _) => !retained.contains(id) }

      for
        _       <- ZIO.foreachDiscard(stoppedIds)(id => currentFibers(id)._2.interrupt)
        started <- ZIO.foreach(newSockets.toSeq) { case (id, socket) =>
                     socket.outbox
                       .runForeach(payload => outQueue.offer(payload).unit).forkScoped
                       .map(fiber => id -> (socket -> fiber))
                   }
        _ <- fibersRef.set(retained ++ started)
      yield ()
    }

  def join[Msg, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    initialUrl: URL,
    initialFlash: Map[String, String] = Map.empty,
    paramsRuntime: LiveRouteParamsRuntime[?, Msg, Model] =
      LiveRouteParamsRuntime.none[Any, Msg, Model]
  )(using ClassTag[Msg]
  ): RIO[Scope, Unit] =
    joinWithRoot(
      id,
      token,
      lv,
      ctx,
      meta,
      initialUrl,
      initialFlash,
      input => lv.view(input.map(_._1)),
      paramsRuntime
    )

  def join[Msg, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    initialUrl: URL,
    initialFlash: Map[String, String],
    rootView: Signal[(Model, URL)] => HtmlElement[Msg],
    paramsRuntime: LiveRouteParamsRuntime[?, Msg, Model]
  )(using ClassTag[Msg]
  ): RIO[Scope, Unit] =
    joinWithRoot(id, token, lv, ctx, meta, initialUrl, initialFlash, rootView, paramsRuntime)

  private def joinWithRoot[Msg: ClassTag, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    initialUrl: URL,
    initialFlash: Map[String, String],
    rootView: Signal[(Model, URL)] => HtmlElement[Msg],
    paramsRuntime: LiveRouteParamsRuntime[?, Msg, Model]
  ): RIO[Scope, Unit] =
    rootJoinLock
      .withPermit {
        for
          previous <- sockets.modify(current => current.get(id) -> current.removed(id))
          _        <- ZIO.foreachDiscard(previous)(_.shutdown)
          socket   <- Socket.start(
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
                      enqueueInitReply = true,
                      onCrash = ZIO.unit,
                      ownsPageTitle = true,
                      runtimeTrace = runtimeTrace,
                      nestedGeneration = None
                    )
          _ <- sockets.update(_.updated(id, socket))
        yield ()
      }.flatMap(_ => ZIO.logDebug(s"LiveView joined $id"))
  end joinWithRoot

  def nestedRuntime(
    parentTopic: String,
    loadingOnInitialRender: Boolean = false
  ): NestedLiveViewRuntime =
    nestedRuntime(parentTopic, parentTopic.stripPrefix("lv:"), loadingOnInitialRender)

  private def nestedRuntime(
    parentTopic: String,
    parentDomId: String,
    loadingOnInitialRender: Boolean
  ): NestedLiveViewRuntime =
    new SocketNestedLiveViewRuntime(
      parentTopic,
      parentDomId,
      tokenConfig,
      nestedEntries,
      nestedRenderPlans,
      nestedGeneration,
      sockets,
      uploadOwners,
      nestedJoins,
      topologyLock,
      runtimeTrace,
      loadingOnInitialRender
    )

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
    initialUrl: URL,
    connectParams: Map[String, Json] = Map.empty
  ): RIO[Scope, Option[JoinErrorReason]] =
    tryJoinNested(
      topic,
      token,
      staticChanged,
      meta,
      Some(initialUrl),
      connectParams = connectParams
    )
      .map {
        case NestedJoinResult.Rejected(reason) => Some(reason)
        case _                                 => None
      }

  private[scalive] def tryJoinNested(
    topic: String,
    token: String,
    staticChanged: Boolean,
    meta: WebSocketMessage.Meta,
    initialUrl: Option[URL],
    enqueueInitReply: Boolean = true,
    connectParams: Map[String, Json] = Map.empty
  ): RIO[Scope, NestedJoinResult] =
    for
      entryOption <- nestedEntries.get.map(_.get(topic))
      reservation <- entryOption match
                       case None =>
                         ZIO.succeed(
                           NestedJoinReservation.Result(NestedJoinResult.NotNested)
                         )
                       case Some(entry) if !isAuthorizedNestedJoin(entry, topic, token) =>
                         ZIO.succeed(
                           NestedJoinReservation.Result(
                             NestedJoinResult.Rejected(JoinErrorReason.Unauthorized)
                           )
                         )
                       case Some(entry) =>
                         resolveNestedInitialUrl(entry, initialUrl).flatMap {
                           case None =>
                             ZIO.succeed(
                               NestedJoinReservation.Result(
                                 NestedJoinResult.Rejected(JoinErrorReason.Stale)
                               )
                             )
                           case Some(_) => reserveNestedJoin(topic, entry)
                         }
      result <- reservation match
                  case NestedJoinReservation.Result(result) => ZIO.succeed(result)
                  case NestedJoinReservation.Rejoin(socket) =>
                    socket.stickyRejoinReply
                      .tap(_ => ZIO.logDebug(s"Rejoined sticky LiveView $topic"))
                      .map(NestedJoinResult.JoinedWithReply(_))
                  case NestedJoinReservation.Start(entry, previousSocket) =>
                    val ctx = LiveContext(
                      staticChanged = staticChanged,
                      connectParams = connectParams,
                      csrfToken = csrfToken,
                      nestedLiveViews = nestedRuntime(
                        topic,
                        entry.id,
                        loadingOnInitialRender(connectParams)
                      )
                    )
                    for
                      _   <- ZIO.foreachDiscard(previousSocket)(_.shutdown)
                      url <- resolveNestedInitialUrl(entry, initialUrl)
                               .someOrFail(
                                 new IllegalStateException(
                                   s"Nested LiveView $topic lost its parent URL while joining"
                                 )
                               ).onError(_ => clearNestedJoinReservation(topic, entry.generation))
                      result <- startReservedNestedSocket(
                                  entry,
                                  topic,
                                  ctx,
                                  meta,
                                  url,
                                  enqueueInitReply
                                )
                    yield result
    yield result

  private def reserveNestedJoin(
    topic: String,
    entry: NestedLiveViewEntry
  ): UIO[NestedJoinReservation] =
    topologyLock.withPermit {
      for
        currentEntries <- nestedEntries.get
        currentSockets <- sockets.get
        joins          <- nestedJoins.get
        reservation    <- currentEntries.get(topic) match
                         case Some(current) if current.generation == entry.generation =>
                           currentSockets.get(topic) match
                             case Some(socket) if current.sticky =>
                               ZIO.succeed(NestedJoinReservation.Rejoin(socket))
                             case _ if joins.contains(topic) =>
                               ZIO.succeed(
                                 NestedJoinReservation.Result(
                                   NestedJoinResult.Rejected(JoinErrorReason.Stale)
                                 )
                               )
                             case previous =>
                               nestedJoins.update(_.updated(topic, entry.generation)) *>
                                 sockets.set(currentSockets.removed(topic)) *>
                                 ZIO.succeed(NestedJoinReservation.Start(entry, previous))
                         case _ =>
                           ZIO.succeed(
                             NestedJoinReservation.Result(
                               NestedJoinResult.Rejected(JoinErrorReason.Stale)
                             )
                           )
      yield reservation
    }

  private def joinedResult(socket: Socket[?, ?], enqueueInitReply: Boolean): NestedJoinResult =
    if enqueueInitReply then NestedJoinResult.Joined
    else NestedJoinResult.JoinedWithReply(socket.initReply)

  private def startReservedNestedSocket(
    entry: NestedLiveViewEntry,
    topic: String,
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    url: URL,
    enqueueInitReply: Boolean
  ): RIO[Scope, NestedJoinResult] =
    val onCrash = linkedParentCrash(topic, entry.generation)
    entry
      .start(ctx, meta, url, enqueueInitReply, onCrash)
      .foldCauseZIO(
        cause =>
          clearNestedJoinReservation(topic, entry.generation) *>
            ZIO.logErrorCause(s"Nested LiveView $topic failed to join", cause) *>
            linkedParentJoinFailureCrash(entry, onCrash).as(
              NestedJoinResult.FailedWithReply(Payload.errorReply(LiveResponse.Empty))
            ),
        socket =>
          topologyLock
            .withPermit {
              for
                entries <- nestedEntries.get
                joins   <- nestedJoins.get
                accepted = entries
                             .get(topic).exists(_.generation == entry.generation) &&
                             joins.get(topic).contains(entry.generation)
                _ <- nestedJoins.update { current =>
                       if current.get(topic).contains(entry.generation) then current.removed(topic)
                       else current
                     }
                _ <- ZIO.when(accepted)(sockets.update(_.updated(topic, socket)))
              yield accepted
            }.flatMap { accepted =>
              if accepted then ZIO.succeed(joinedResult(socket, enqueueInitReply))
              else socket.shutdown.as(NestedJoinResult.Rejected(JoinErrorReason.Stale))
            }
      )
  end startReservedNestedSocket

  private def clearNestedJoinReservation(topic: String, generation: Long): UIO[Unit] =
    topologyLock.withPermit(
      nestedJoins.update { current =>
        if current.get(topic).contains(generation) then current.removed(topic) else current
      }
    )

  private def linkedParentCrash(topic: String, generation: Long): UIO[Unit] =
    nestedEntries.get.flatMap { entries =>
      entries.get(topic) match
        case Some(entry) if entry.generation == generation && entry.linkParentOnCrash =>
          sockets.get.flatMap(current => current.get(entry.parentTopic).fold(ZIO.unit)(_.crash))
        case _ => ZIO.unit
    }

  private def linkedParentJoinFailureCrash(
    entry: NestedLiveViewEntry,
    onCrash: UIO[Unit]
  ): UIO[Unit] =
    if entry.linkParentOnCrash then (ZIO.sleep(10.millis) *> onCrash).forkDaemon.unit
    else ZIO.unit

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

  private def isAuthorizedNestedJoin(
    entry: NestedLiveViewEntry,
    topic: String,
    token: String
  ): Boolean =
    Token
      .verify[String](tokenConfig.secret, token, tokenConfig.maxAge)
      .toOption
      .exists { case (tokenTopic, payload) =>
        tokenTopic == topic && payload == s"nested:${entry.generation}"
      }

  private def loadingOnInitialRender(connectParams: Map[String, Json]): Boolean =
    connectParams.get("_mounts").exists {
      case Json.Num(value) => value.signum() > 0
      case Json.Str(value) => value.toIntOption.exists(_ > 0)
      case _               => false
    }

  def leave(id: String, joinRef: Option[Int] = None): UIO[Unit] =
    topologyLock
      .withPermit {
        for
          entries        <- nestedEntries.get
          currentSockets <- sockets.get
          socket = currentSockets.get(id)
          stale  = joinRef.nonEmpty && socket.exists(_.joinRef != joinRef)
          result <-
            if stale then ZIO.succeed((Vector.empty[Socket[?, ?]], Set.empty[String], true, false))
            else
              val currentEntry  = entries.get(id)
              val matchingEntry = currentEntry
                .exists(entry => socket.flatMap(_.nestedGeneration).contains(entry.generation))
              if currentEntry.exists(_.sticky) && matchingEntry then
                ZIO.succeed((Vector.empty[Socket[?, ?]], Set.empty[String], false, true))
              else
                val removeCurrentEntry = currentEntry.isEmpty || matchingEntry
                val ownsChildTopology  = currentEntry.isEmpty || matchingEntry
                val childRoots         =
                  if ownsChildTopology then
                    entries.collect {
                      case (topic, entry) if entry.parentTopic == id && !entry.sticky => topic
                    }.toSet
                  else Set.empty[String]
                val currentRoot = Option.when(currentEntry.nonEmpty && removeCurrentEntry)(id).toSet
                val removedEntryIds = NestedLiveViewTopology.subtree(
                  entries,
                  childRoots ++ currentRoot
                )
                val socketIds     = removedEntryIds + id
                val socketsToStop = socketIds.toVector.flatMap(currentSockets.get)
                nestedEntries.update(_ -- removedEntryIds) *>
                  sockets.set(currentSockets -- socketIds) *>
                  uploadOwners.update(_.filterNot { case (_, ownerId) =>
                    socketIds.contains(ownerId)
                  }) *>
                  ZIO.succeed((socketsToStop, socketIds, false, false))
        yield result
      }.flatMap { case (socketsToStop, removedIds, stale, sticky) =>
        if stale then ZIO.logDebug(s"Ignoring stale leave for LiveView $id")
        else if sticky then ZIO.logDebug(s"Detached sticky LiveView $id")
        else
          ZIO.foreachDiscard(socketsToStop)(_.shutdown) *>
            (if removedIds.contains(id) then ZIO.logDebug(s"Left LiveView $id")
             else ZIO.logDebug(s"Ignoring leave for unknown LiveView $id"))
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

  def traceTopic(topic: String): UIO[String] =
    if topic.startsWith("lvu:") then uploadOwners.get.map(_.getOrElse(topic, topic))
    else ZIO.succeed(topic)

end LiveChannel

private[scalive] object LiveChannel:
  def make(tokenConfig: TokenConfig, connectAuthorized: Boolean = true): UIO[LiveChannel] =
    make(tokenConfig, connectAuthorized, csrfToken = None, RuntimeTrace.Disabled)

  def make(tokenConfig: TokenConfig, csrfToken: Option[String]): UIO[LiveChannel] =
    make(tokenConfig, csrfToken.isDefined, csrfToken, RuntimeTrace.Disabled)

  def make(
    tokenConfig: TokenConfig,
    csrfToken: Option[String],
    runtimeTrace: RuntimeTrace
  ): UIO[LiveChannel] =
    make(tokenConfig, csrfToken.isDefined, csrfToken, runtimeTrace)

  private def make(
    tokenConfig: TokenConfig,
    connectAuthorized: Boolean,
    csrfToken: Option[String],
    runtimeTrace: RuntimeTrace
  ): UIO[LiveChannel] =
    for
      sockets      <- SubscriptionRef.make(Map.empty[String, Socket[?, ?]])
      uploadOwners <- Ref.make(Map.empty[String, String])
      nested       <- Ref.make(Map.empty[String, NestedLiveViewEntry])
      renderPlans  <- Ref.make(Map.empty[String, Map[String, NestedLiveViewEntry]])
      generation   <- Ref.make(Map.empty[String, Long])
      nestedJoins  <- Ref.make(Map.empty[String, Long])
      topologyLock <- Semaphore.make(1)
      rootJoinLock <- Semaphore.make(1)
    yield new LiveChannel(
      sockets,
      uploadOwners,
      nested,
      renderPlans,
      generation,
      nestedJoins,
      topologyLock,
      rootJoinLock,
      tokenConfig,
      connectAuthorized,
      csrfToken,
      runtimeTrace
    )
end LiveChannel
