package scalive

import java.net.URI
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.Random
import scala.util.Try

import zio.*
import zio.Queue
import zio.http.QueryParams
import zio.json.*
import zio.json.ast.Json
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Payload.given
import scalive.WebSocketMessage.UploadChunkErrorReason
import scalive.WebSocketMessage.UploadJoinErrorReason
import scalive.WebSocketMessage.UploadJoinToken

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
  private val UploadRefLength = 12

  final private case class UploadConfigState(
    name: String,
    ref: String,
    options: LiveUploadOptions,
    errors: List[(String, Json)] = Nil,
    entryOrder: Vector[String] = Vector.empty,
    cancelledRefs: Set[String] = Set.empty)

  final private case class UploadEntryState(
    uploadName: String,
    uploadRef: String,
    ref: String,
    name: String,
    contentType: String,
    size: Long,
    relativePath: Option[String],
    lastModified: Option[Long],
    clientMeta: Option[Json],
    token: Option[String],
    joined: Boolean,
    bytes: Chunk[Byte],
    progress: Int,
    preflighted: Boolean,
    cancelled: Boolean,
    valid: Boolean,
    errors: List[Json],
    externalMeta: Option[Json.Obj],
    writer: LiveUploadWriter,
    writerState: Option[Any],
    writerMeta: Option[Json.Obj],
    writerClosed: Boolean)

  final private case class UploadRuntimeState(
    configs: Map[String, UploadConfigState],
    refsToNames: Map[String, String],
    entries: Map[String, UploadEntryState],
    tokens: Map[String, String]):
    def configByRef(ref: String): Option[UploadConfigState] =
      refsToNames.get(ref).flatMap(configs.get)

    def removeUploadByRef(uploadRef: String): UploadRuntimeState =
      refsToNames.get(uploadRef) match
        case Some(name) => removeUploadByName(name)
        case None       => this

    def removeUploadByName(name: String): UploadRuntimeState =
      configs.get(name) match
        case Some(config) =>
          val nextBase = copy(
            configs = configs.removed(name),
            refsToNames = refsToNames.removed(config.ref)
          )
          nextBase.removeEntries(config.entryOrder.toSet)
        case None => this

    def removeEntry(entryRef: String): UploadRuntimeState =
      removeEntries(Set(entryRef))

    def removeEntries(entryRefs: Set[String]): UploadRuntimeState =
      if entryRefs.isEmpty then this
      else
        val nextConfigs = configs.view
          .mapValues(config =>
            config.copy(entryOrder = config.entryOrder.filterNot(entryRefs.contains))
          )
          .toMap
        UploadRuntimeState(
          configs = nextConfigs,
          refsToNames = refsToNames,
          entries = entries -- entryRefs,
          tokens = tokens.filterNot { case (_, ref) => entryRefs.contains(ref) }
        )
  end UploadRuntimeState

  final private case class RuntimeState[Msg, Model](
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
    outHub: Hub[(Payload, WebSocketMessage.Meta)],
    ref: Ref[(Var[Model], HtmlElement)],
    lvStreamRef: SubscriptionRef[ZStream[Any, Nothing, Msg]],
    uploadRef: Ref[UploadRuntimeState],
    patchRedirectCountRef: Ref[Int],
    initDiff: Diff)

  def start[Msg, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta
  ): RIO[Scope, Socket[Msg, Model]] =
    ZIO.logAnnotate("lv", id) {
      for
        state       <- initializeRuntime(lv, ctx, meta)
        clientFiber <- startClientFiber(state)
        serverFiber <- startServerFiber(state)
        livePatch =
          (url: String, patchMeta: WebSocketMessage.Meta) => handleLivePatch(url, patchMeta, state)
        allowUpload    = (payload: Payload.AllowUpload) => handleAllowUpload(payload, state)
        progressUpload = (payload: Payload.Progress) => handleProgressUpload(payload, state)
        uploadJoin     = (uploadTopic: String, uploadToken: String) =>
                       handleUploadJoin(uploadTopic, uploadToken, state)
        uploadChunk =
          (uploadTopic: String, bytes: Chunk[Byte]) => handleUploadChunk(uploadTopic, bytes, state)
        outbox = buildOutbox(state)
        stop   = buildShutdown(state, clientFiber, serverFiber)
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

  private def initializeRuntime[Msg, Model](
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta
  ): Task[RuntimeState[Msg, Model]] =
    for
      inbox     <- Queue.bounded[(Payload.Event, WebSocketMessage.Meta)](4)
      outHub    <- Hub.unbounded[(Payload, WebSocketMessage.Meta)]
      uploadRef <- Ref.make(UploadRuntimeState(Map.empty, Map.empty, Map.empty, Map.empty))
      runtimeCtx = ctx.copy(uploads = new LiveContext.Uploads:
                     def allow(name: String, options: LiveUploadOptions): Task[LiveUpload] =
                       for
                         validatedOptions <- validateUploadOptions(name, options)
                         ref              <- ZIO.succeed(randomUploadRef())
                         result           <- uploadRef.modify { current =>
                                     current.configs.get(name) match
                                       case Some(existing) if existing.entryOrder.nonEmpty =>
                                         Left(
                                           new IllegalArgumentException(
                                             s"Upload $name still has active entries"
                                           )
                                         ) -> current
                                       case _ =>
                                         val base   = current.removeUploadByName(name)
                                         val config = UploadConfigState(
                                           name = name,
                                           ref = ref,
                                           options = validatedOptions
                                         )
                                         val next = base.copy(
                                           configs = base.configs.updated(name, config),
                                           refsToNames = base.refsToNames.updated(ref, name)
                                         )
                                         Right(buildLiveUpload(next, config)) -> next
                                   }
                         upload <- ZIO.fromEither(result)
                       yield upload

                     def disallow(name: String): Task[Unit] =
                       uploadRef
                         .modify { current =>
                           current.configs.get(name) match
                             case Some(config) if config.entryOrder.nonEmpty =>
                               Left(
                                 new IllegalArgumentException(
                                   s"Upload $name still has active entries"
                                 )
                               ) ->
                                 current
                             case Some(_) =>
                               Right(()) -> current.removeUploadByName(name)
                             case None =>
                               Left(
                                 new IllegalArgumentException(s"Upload $name is not allowed")
                               ) -> current
                         }
                         .flatMap(ZIO.fromEither(_))

                     def get(name: String): UIO[Option[LiveUpload]] =
                       uploadRef.get.map(state =>
                         state.configs.get(name).map(config => buildLiveUpload(state, config))
                       )

                     def cancel(name: String, entryRef: String): Task[Unit] =
                       for
                         state <- uploadRef.get
                         entry <- ZIO
                                    .fromOption(state.entries.get(entryRef))
                                    .orElseFail(
                                      new IllegalArgumentException(
                                        s"No upload entry found for ref $entryRef"
                                      )
                                    )
                         _ <-
                           if entry.uploadName != name then
                             ZIO.fail(
                               new IllegalArgumentException(
                                 s"Upload entry $entryRef does not belong to upload $name"
                               )
                             )
                           else
                             closeWriter(entry, LiveUploadWriterCloseReason.Cancel).ignore *>
                               uploadRef.update { current =>
                                 val removed = current.removeEntry(entryRef)
                                 removed.configs.get(name) match
                                   case Some(config) =>
                                     val nextErrors =
                                       Option
                                         .when(
                                           config.entryOrder.length > config.options.maxEntries
                                         )(
                                           config.ref -> Json.Str("too_many_files")
                                         )
                                         .toList
                                     val nextConfig = config.copy(
                                       cancelledRefs = config.cancelledRefs + entryRef,
                                       errors = nextErrors
                                     )
                                     removed.copy(
                                       configs = removed.configs.updated(
                                         name,
                                         nextConfig
                                       )
                                     )
                                   case None => removed
                               }.unit
                       yield ()

                     def consumeCompleted(name: String): UIO[List[LiveUploadedEntry]] =
                       for
                         state <- uploadRef.get
                         refs = state.configs
                                  .get(name)
                                  .map(_.entryOrder)
                                  .getOrElse(Vector.empty)
                                  .filter(ref => state.entries.get(ref).exists(isUploadEntryDone))
                         consumed <- ZIO.foreach(refs)(consumeEntry(uploadRef, _))
                       yield consumed.flatten.toList

                     def consume(entryRef: String): UIO[Option[LiveUploadedEntry]] =
                       consumeEntry(uploadRef, entryRef)

                     def drop(entryRef: String): UIO[Unit] =
                       for
                         state <- uploadRef.get
                         _     <- state.entries.get(entryRef) match
                                case Some(entry) =>
                                  closeWriter(entry, LiveUploadWriterCloseReason.Cancel).ignore
                                case None => ZIO.unit
                         _ <- uploadRef.update(_.removeEntry(entryRef)).unit
                       yield ())
      initModel <- normalize(lv.init, runtimeCtx)
      modelVar = Var(initModel)
      el       = lv.view(modelVar)
      ref <- Ref.make((modelVar, el))
      initDiff = el.diff(trackUpdates = false)
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
      uploadRef = uploadRef,
      patchRedirectCountRef = patchRedirectCountRef,
      initDiff = initDiff
    )

  private def startClientFiber[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): RIO[Scope, Fiber.Runtime[Throwable, Unit]] =
    ZStream
      .fromQueue(state.inbox)
      .runForeach((event, meta) => handleClientEvent(event, meta, state))
      .fork

  private def handleClientEvent[Msg, Model](
    event: Payload.Event,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      _              <- syncUploadRuntimeFromEvent(event, state)
      (modelVar, el) <- state.ref.get
      hookResult     <- normalize(
                      state.lv.handleHook(modelVar.currentValue, event.event, event.value),
                      state.ctx
                    )
      _ <- hookResult match
             case HookResult.Halt(hookModel, reply) =>
               applyHookHalt(modelVar, el, hookModel, reply, meta, state)
             case HookResult.Continue(hookModel) =>
               applyBoundEvent(modelVar, el, hookModel, event, meta, state)
    yield ()

  private def handleLivePatch[Msg, Model](
    url: String,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      (modelVar, el) <- state.ref.get
      parsedUri      <- ZIO.attempt(URI.create(url))
      params = QueryParams
                 .decode(Option(parsedUri.getRawQuery).getOrElse(""))
                 .map
                 .iterator
                 .map { case (key, values) =>
                   key -> values.lastOption.getOrElse("")
                 }
                 .toMap
      result <-
        normalize(state.lv.handleParams(modelVar.currentValue, params, parsedUri), state.ctx)
      _ <- result match
             case ParamsResult.Continue(model) =>
               for
                 _    <- state.patchRedirectCountRef.set(0)
                 diff <- updateModelAndSubscriptions(modelVar, el, model, state)
                 _    <- ZIO.when(!diff.isEmpty)(
                        publishPayload(Payload.Diff(diff), meta.copy(messageRef = None), state)
                      )
               yield ()
             case ParamsResult.PushPatch(model, to) =>
               handleLivePatchRedirect(
                 modelVar,
                 el,
                 model,
                 to,
                 LivePatchKind.Push,
                 meta,
                 state
               )
             case ParamsResult.ReplacePatch(model, to) =>
               handleLivePatchRedirect(
                 modelVar,
                 el,
                 model,
                 to,
                 LivePatchKind.Replace,
                 meta,
                 state
               )
    yield ()

  private def handleLivePatchRedirect[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    model: Model,
    to: String,
    kind: LivePatchKind,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      redirectCount <- state.patchRedirectCountRef.updateAndGet(_ + 1)
      _             <- updateModelAndSubscriptions(modelVar, el, model, state)
      _             <-
        if redirectCount > 20 then
          publishPayload(Payload.Error, meta.copy(messageRef = None), state)
        else
          publishPayload(
            Payload.LiveNavigation(to, kind),
            meta.copy(messageRef = None),
            state
          ) *> handleLivePatch(to, meta, state)
    yield ()

  private def validateUploadOptions(
    name: String,
    options: LiveUploadOptions
  ): Task[LiveUploadOptions] =
    if name.isEmpty then ZIO.fail(new IllegalArgumentException("Upload name must not be empty"))
    else if options.maxEntries <= 0 then
      ZIO.fail(new IllegalArgumentException(s"Upload $name maxEntries must be > 0"))
    else if options.maxFileSize <= 0 then
      ZIO.fail(new IllegalArgumentException(s"Upload $name maxFileSize must be > 0"))
    else if options.chunkSize <= 0 then
      ZIO.fail(new IllegalArgumentException(s"Upload $name chunkSize must be > 0"))
    else if options.chunkTimeout <= 0 then
      ZIO.fail(new IllegalArgumentException(s"Upload $name chunkTimeout must be > 0"))
    else
      options.accept match
        case LiveUploadAccept.Exactly(values) if values.isEmpty =>
          ZIO.fail(new IllegalArgumentException(s"Upload $name accept list must not be empty"))
        case _ =>
          ZIO.succeed(options)

  private def randomUploadRef(length: Int = UploadRefLength): String =
    val bytes   = Random.nextBytes(length)
    val encoded = Base64
      .getUrlEncoder()
      .withoutPadding()
      .encodeToString(bytes)
    if encoded.length >= length then encoded.take(length)
    else encoded

  private def buildLiveUpload(
    state: UploadRuntimeState,
    config: UploadConfigState
  ): LiveUpload =
    LiveUpload(
      name = config.name,
      ref = config.ref,
      accept = config.options.accept,
      maxEntries = config.options.maxEntries,
      maxFileSize = config.options.maxFileSize,
      chunkSize = config.options.chunkSize,
      chunkTimeout = config.options.chunkTimeout,
      autoUpload = config.options.autoUpload,
      external = config.options.external.nonEmpty,
      entries = config.entryOrder.flatMap(state.entries.get).map(toLiveUploadEntry).toList,
      errors = config.errors.map(_._2).map(LiveUploadError.fromJson)
    )

  private def consumeEntry(
    uploadRef: Ref[UploadRuntimeState],
    entryRef: String
  ): UIO[Option[LiveUploadedEntry]] =
    uploadRef.modify { current =>
      current.entries.get(entryRef) match
        case Some(entry) if isUploadEntryDone(entry) && entry.valid && !entry.cancelled =>
          val uploadedEntry = LiveUploadedEntry(
            ref = entry.ref,
            name = entry.name,
            contentType = entry.contentType,
            bytes = entry.bytes,
            meta = entry.externalMeta.orElse(entry.writerMeta).getOrElse(Json.Obj.empty)
          )
          Some(uploadedEntry) -> current.removeEntry(entryRef)
        case _ =>
          None -> current
    }

  private def handleAllowUpload[Msg, Model](
    payload: Payload.AllowUpload,
    state: RuntimeState[Msg, Model]
  ): Task[Payload.Reply] =
    for
      current <- state.uploadRef.get
      reply   <- current.configByRef(payload.ref) match
                 case None =>
                   ZIO.succeed(
                     Payload.errorReply(
                       LiveResponse.UploadPreflightFailure(
                         payload.ref,
                         List(payload.ref -> "disallowed")
                       )
                     )
                   )
                 case Some(config) =>
                   val activeEntries =
                     payload.entries.filterNot(entry => config.cancelledRefs.contains(entry.ref))
                   val baseState        = current.removeUploadByRef(payload.ref)
                   val validationErrors =
                     validateUploadEntries(activeEntries, config.options).groupMap(_._1)(_._2)
                   val preflightEntries = activeEntries.map(entry =>
                     entry.ref -> UploadEntryState(
                       uploadName = config.name,
                       uploadRef = payload.ref,
                       ref = entry.ref,
                       name = entry.name,
                       contentType = entry.`type`,
                       size = entry.size,
                       relativePath = entry.relative_path,
                       lastModified = entry.last_modified,
                       clientMeta = entry.meta,
                       token = None,
                       joined = false,
                       bytes = Chunk.empty,
                       progress = 0,
                       preflighted = true,
                       cancelled = false,
                       valid = true,
                       errors = Nil,
                       externalMeta = None,
                       writer = config.options.writer,
                       writerState = None,
                       writerMeta = None,
                       writerClosed = false
                     )
                   )

                   for
                     processed <- ZIO.foreach(preflightEntries) { case (entryRef, entryState) =>
                                    validationErrors.get(entryRef) match
                                      case Some(errors) =>
                                        ZIO.succeed(
                                          entryRef -> entryState.copy(
                                            valid = false,
                                            errors = errors
                                          )
                                        )
                                      case None =>
                                        config.options.external match
                                          case Some(uploader) =>
                                            uploader
                                              .preflight(toExternalUploadEntry(entryState))
                                              .provide(ZLayer.succeed(state.ctx))
                                              .either
                                              .map {
                                                case Right(LiveExternalUploadResult.Ok(meta))
                                                    if hasExternalUploader(meta) =>
                                                  entryRef -> entryState
                                                    .copy(externalMeta = Some(meta))
                                                case Right(LiveExternalUploadResult.Ok(_)) =>
                                                  entryRef -> entryState.copy(
                                                    valid = false,
                                                    errors =
                                                      List(Json.Str("external_client_failure"))
                                                  )
                                                case Right(LiveExternalUploadResult.Error(meta)) =>
                                                  entryRef -> entryState.copy(
                                                    valid = false,
                                                    errors = List(meta)
                                                  )
                                                case Left(_) =>
                                                  entryRef -> entryState.copy(
                                                    valid = false,
                                                    errors =
                                                      List(Json.Str("external_client_failure"))
                                                  )
                                              }
                                          case None =>
                                            val tokenPayload: UploadJoinToken =
                                              UploadJoinToken(
                                                state.meta.topic,
                                                payload.ref,
                                                entryRef
                                              )
                                            val token =
                                              Token.sign[UploadJoinToken](
                                                "secret",
                                                state.meta.topic,
                                                tokenPayload
                                              )
                                            ZIO.succeed(
                                              entryRef -> entryState.copy(token = Some(token))
                                            )
                                  }
                     entriesMap      = processed.toMap
                     responseEntries =
                       entriesMap.values.collect {
                         case entry if entry.valid && entry.externalMeta.nonEmpty =>
                           entry.ref -> entry.externalMeta.get
                         case entry if entry.valid && entry.token.nonEmpty =>
                           entry.ref -> Json.Str(entry.token.get)
                       }.toMap
                     responseErrors =
                       entriesMap.values.collect {
                         case entry if entry.errors.nonEmpty => entry.ref -> entry.errors
                       }.toMap
                     tokenToRef =
                       entriesMap.values
                         .flatMap(entry => entry.token.map(token => token -> entry.ref)).toMap
                     globalErrors =
                       Option
                         .when(activeEntries.length > config.options.maxEntries)(
                           config.ref -> Json.Str("too_many_files")
                         )
                         .toList
                     nextConfig = config.copy(
                                    errors = globalErrors,
                                    entryOrder = activeEntries.map(_.ref).toVector
                                  )
                     nextState = baseState.copy(
                                   configs = baseState.configs.updated(config.name, nextConfig),
                                   refsToNames =
                                     baseState.refsToNames.updated(config.ref, config.name),
                                   entries = baseState.entries ++ entriesMap,
                                   tokens = baseState.tokens ++ tokenToRef
                                 )
                     _ <- state.uploadRef.set(nextState)
                   yield Payload.okReply(
                     LiveResponse.UploadPreflightSuccess(
                       ref = payload.ref,
                       config = WebSocketMessage.UploadClientConfig(
                         max_file_size = config.options.maxFileSize,
                         max_entries = config.options.maxEntries,
                         chunk_size = config.options.chunkSize,
                         chunk_timeout = config.options.chunkTimeout
                       ),
                       entries = responseEntries,
                       errors = responseErrors
                     )
                   )
                   end for
    yield reply

  private def handleProgressUpload[Msg, Model](
    payload: Payload.Progress,
    state: RuntimeState[Msg, Model]
  ): Task[Payload.Reply] =
    for
      maybeUpdated <- state.uploadRef.get.flatMap { current =>
                        current.entries.get(payload.entry_ref) match
                          case Some(entry) if entry.uploadRef == payload.ref =>
                            payload.progress match
                              case Json.Num(progress) =>
                                val normalized =
                                  Try(progress.intValueExact()).toOption
                                    .map(p => p.max(0).min(100))
                                    .getOrElse(entry.progress)
                                val next = entry.copy(
                                  progress = normalized,
                                  valid = true,
                                  errors = Nil
                                )
                                val updateEffect =
                                  if normalized >= 100 then
                                    closeWriter(next, LiveUploadWriterCloseReason.Done)
                                  else ZIO.succeed(next)

                                updateEffect.flatMap { updated =>
                                  state.uploadRef
                                    .update { st =>
                                      st.copy(entries =
                                        st.entries.updated(payload.entry_ref, updated)
                                      )
                                    }.as(Some(updated))
                                }
                              case obj: Json.Obj =>
                                val errorJson =
                                  obj.fields
                                    .collectFirst { case ("error", Json.Str(reason)) =>
                                      Json.Str(reason)
                                    }.getOrElse(obj)
                                closeWriter(
                                  entry.copy(valid = false, errors = List(errorJson)),
                                  LiveUploadWriterCloseReason.Error(progressToParamValue(errorJson))
                                ).flatMap { updated =>
                                  state.uploadRef
                                    .update { st =>
                                      st.copy(entries =
                                        st.entries.updated(payload.entry_ref, updated)
                                      )
                                    }.as(Some(updated))
                                }
                              case _ => ZIO.none
                          case _ => ZIO.none
                      }
      _ <- maybeUpdated match
             case Some(updated) => runUploadProgressCallback(payload.ref, updated, state)
             case None          => ZIO.unit
      reply <- payload.event match
                 case Some(eventRef) if eventRef.nonEmpty =>
                   applyUploadProgressBinding(eventRef, payload, state)
                 case _ =>
                   ZIO.succeed(Payload.okReply(LiveResponse.Empty))
    yield reply

  private def handleUploadJoin[Msg, Model](
    uploadTopic: String,
    token: String,
    state: RuntimeState[Msg, Model]
  ): Task[Payload.Reply] =
    Token.verify[UploadJoinToken]("secret", token, 7.days) match
      case Left(_) =>
        ZIO.succeed(
          Payload.errorReply(LiveResponse.UploadJoinError(UploadJoinErrorReason.InvalidToken))
        )
      case Right((signedLiveViewId, joinToken)) =>
        if signedLiveViewId != state.meta.topic || joinToken.liveViewTopic != state.meta.topic then
          ZIO.succeed(
            Payload.errorReply(LiveResponse.UploadJoinError(UploadJoinErrorReason.InvalidToken))
          )
        else if uploadTopic != s"lvu:${joinToken.entryRef}" then
          ZIO.succeed(
            Payload.errorReply(LiveResponse.UploadJoinError(UploadJoinErrorReason.Disallowed))
          )
        else
          for
            current <- state.uploadRef.get
            reply   <- current.entries.get(joinToken.entryRef) match
                       case Some(entry)
                           if entry.uploadRef == joinToken.uploadRef && entry.token.contains(
                             token
                           ) =>
                         if entry.joined then
                           ZIO.succeed(
                             Payload.errorReply(
                               LiveResponse.UploadJoinError(UploadJoinErrorReason.AlreadyRegistered)
                             )
                           )
                         else
                           ensureWriterState(entry).either.flatMap {
                             case Right(withWriter) =>
                               state.uploadRef
                                 .update { st =>
                                   st.copy(
                                     entries = st.entries.updated(
                                       joinToken.entryRef,
                                       withWriter.copy(joined = true, valid = true, errors = Nil)
                                     )
                                   )
                                 }.as(Payload.okReply(LiveResponse.Empty))
                             case Left(_) =>
                               val errored = entry.copy(
                                 valid = false,
                                 errors = List(Json.Str("writer_error"))
                               )
                               state.uploadRef
                                 .update { st =>
                                   st.copy(entries =
                                     st.entries.updated(joinToken.entryRef, errored)
                                   )
                                 }.as(
                                   Payload.errorReply(
                                     LiveResponse.UploadJoinError(UploadJoinErrorReason.WriterError)
                                   )
                                 )
                           }
                       case _ =>
                         ZIO.succeed(
                           Payload.errorReply(
                             LiveResponse.UploadJoinError(UploadJoinErrorReason.Disallowed)
                           )
                         )
          yield reply

  private def handleUploadChunk[Msg, Model](
    uploadTopic: String,
    bytes: Chunk[Byte],
    state: RuntimeState[Msg, Model]
  ): Task[Payload.Reply] =
    val entryRef = uploadTopic.stripPrefix("lvu:")
    for
      current <- state.uploadRef.get
      reply   <- current.entries.get(entryRef) match
                 case Some(entry) if entry.joined =>
                   val updatedSize = entry.bytes.length + bytes.length
                   if updatedSize > entry.size then
                     ZIO.succeed(
                       Payload.errorReply(
                         LiveResponse.UploadChunkError(
                           reason = UploadChunkErrorReason.FileSizeLimitExceeded,
                           limit = Some(entry.size)
                         )
                       )
                     )
                   else
                     ensureWriterState(entry).either.flatMap {
                       case Right(withWriter) =>
                         withWriter.writer
                           .writeChunk(bytes, withWriter.writerState.getOrElse(()))
                           .either
                           .flatMap {
                             case Right(nextWriterState) =>
                               val updatedEntry = withWriter.copy(
                                 bytes = withWriter.bytes ++ bytes,
                                 writerState = Some(nextWriterState)
                               )
                               state.uploadRef
                                 .update { st =>
                                   st.copy(entries = st.entries.updated(entryRef, updatedEntry))
                                 }.as(Payload.okReply(LiveResponse.Empty))
                             case Left(_) =>
                               closeWriter(
                                 withWriter.copy(
                                   valid = false,
                                   errors = List(Json.Str("writer_error"))
                                 ),
                                 LiveUploadWriterCloseReason.Error("writer_error")
                               ).flatMap { errored =>
                                 state.uploadRef
                                   .update { st =>
                                     st.copy(entries = st.entries.updated(entryRef, errored))
                                   }.as(
                                     Payload.errorReply(
                                       LiveResponse
                                         .UploadChunkError(UploadChunkErrorReason.WriterError)
                                     )
                                   )
                               }
                           }
                       case Left(_) =>
                         ZIO.succeed(
                           Payload.errorReply(
                             LiveResponse.UploadChunkError(UploadChunkErrorReason.WriterError)
                           )
                         )
                     }
                   end if
                 case Some(_) =>
                   ZIO.succeed(
                     Payload.errorReply(
                       LiveResponse.UploadChunkError(UploadChunkErrorReason.Disallowed)
                     )
                   )
                 case None =>
                   ZIO.succeed(
                     Payload.errorReply(
                       LiveResponse.UploadChunkError(UploadChunkErrorReason.Disallowed)
                     )
                   )
    yield reply
    end for
  end handleUploadChunk

  private def applyUploadProgressBinding[Msg, Model](
    eventRef: String,
    payload: Payload.Progress,
    state: RuntimeState[Msg, Model]
  ): Task[Payload.Reply] =
    for
      (modelVar, el) <- state.ref.get
      reply          <- el.findBinding(eventRef) match
                 case Some(binding) =>
                   for
                     params = progressPayloadToParams(payload)
                     updatedModel <-
                       normalize(state.lv.update(modelVar.currentValue)(binding(params)), state.ctx)
                     diff <- updateModelAndSubscriptions(modelVar, el, updatedModel, state)
                   yield
                     if diff.isEmpty then Payload.okReply(LiveResponse.Empty)
                     else Payload.okReply(LiveResponse.Diff(diff))
                 case None =>
                   ZIO.logWarning(
                     s"upload_progress binding missing for ref=$eventRef"
                   ) *>
                     ZIO.succeed(Payload.okReply(LiveResponse.Empty))
    yield reply

  private def progressPayloadToParams(payload: Payload.Progress): Map[String, String] =
    val base = Map(
      "ref"       -> payload.ref,
      "entry_ref" -> payload.entry_ref,
      "progress"  -> progressToParamValue(payload.progress)
    )
    payload.progress match
      case obj: Json.Obj =>
        obj.fields
          .collectFirst { case ("error", Json.Str(reason)) =>
            base.updated("error", reason)
          }.getOrElse(base)
      case _ => base

  private def progressToParamValue(progress: Json): String =
    progress match
      case Json.Num(v)  => Try(v.intValueExact()).toOption.map(_.toString).getOrElse(v.toString)
      case Json.Str(v)  => v
      case Json.Bool(v) => v.toString
      case Json.Null    => ""
      case other        => other.toJson

  private def syncUploadRuntimeFromEvent[Msg, Model](
    event: Payload.Event,
    state: RuntimeState[Msg, Model]
  ): UIO[Unit] =
    event.uploads match
      case Some(uploadsJson) =>
        uploadsJson.as[Map[String, List[WebSocketMessage.UploadPreflightEntry]]] match
          case Left(error) =>
            ZIO.logWarning(s"Could not decode uploads payload: $error")
          case Right(uploads) =>
            state.uploadRef.update { current =>
              uploads.foldLeft(current) { case (runtime, (uploadRef, entries)) =>
                runtime.configByRef(uploadRef) match
                  case Some(config) =>
                    val visibleEntries =
                      entries.filterNot(entry => config.cancelledRefs.contains(entry.ref))
                    val cleared          = runtime.removeEntries(config.entryOrder.toSet)
                    val validationErrors =
                      validateUploadEntries(visibleEntries, config.options).groupMap(_._1)(_._2)
                    val syncedEntries =
                      visibleEntries
                        .map(entry =>
                          entry.ref -> UploadEntryState(
                            uploadName = config.name,
                            uploadRef = uploadRef,
                            ref = entry.ref,
                            name = entry.name,
                            contentType = entry.`type`,
                            size = entry.size,
                            relativePath = entry.relative_path,
                            lastModified = entry.last_modified,
                            clientMeta = entry.meta,
                            token = None,
                            joined = false,
                            bytes = Chunk.empty,
                            progress = 0,
                            preflighted = false,
                            cancelled = false,
                            valid = validationErrors.get(entry.ref).isEmpty,
                            errors = validationErrors.getOrElse(entry.ref, Nil),
                            externalMeta = None,
                            writer = config.options.writer,
                            writerState = None,
                            writerMeta = None,
                            writerClosed = false
                          )
                        ).toMap
                    val globalErrors =
                      Option
                        .when(visibleEntries.length > config.options.maxEntries)(
                          config.ref -> Json.Str("too_many_files")
                        )
                        .toList
                    val nextConfig = config.copy(
                      errors = globalErrors,
                      entryOrder = visibleEntries.map(_.ref).toVector
                    )
                    cleared.copy(
                      configs = cleared.configs.updated(config.name, nextConfig),
                      refsToNames = cleared.refsToNames.updated(config.ref, config.name),
                      entries = cleared.entries ++ syncedEntries
                    )
                  case None =>
                    runtime
              }
            }.unit
      case None =>
        ZIO.unit

  private def validateUploadEntries(
    entries: List[WebSocketMessage.UploadPreflightEntry],
    options: LiveUploadOptions
  ): List[(String, Json)] =
    entries.zipWithIndex.flatMap { case (entry, index) =>
      if index >= options.maxEntries then Some(entry.ref -> Json.Str("too_many_files"))
      else if entry.size > options.maxFileSize then Some(entry.ref -> Json.Str("too_large"))
      else if !isAcceptedUploadEntry(entry, options.accept) then
        Some(entry.ref -> Json.Str("not_accepted"))
      else None
    }

  private def isAcceptedUploadEntry(
    entry: WebSocketMessage.UploadPreflightEntry,
    accept: LiveUploadAccept
  ): Boolean =
    accept match
      case LiveUploadAccept.Any              => true
      case LiveUploadAccept.Exactly(filters) =>
        val normalizedName = entry.name.toLowerCase
        val normalizedType = entry.`type`.toLowerCase
        filters.exists { filter =>
          val normalizedFilter = filter.toLowerCase
          if normalizedFilter.startsWith(".") then normalizedName.endsWith(normalizedFilter)
          else if normalizedFilter.endsWith("/*") then
            val prefix = normalizedFilter.dropRight(1)
            normalizedType.startsWith(prefix)
          else normalizedType == normalizedFilter
        }

  private def hasExternalUploader(meta: Json.Obj): Boolean =
    meta.fields.exists {
      case ("uploader", Json.Str(value)) => value.nonEmpty
      case _                             => false
    }

  private def toExternalUploadEntry(entry: UploadEntryState): LiveExternalUploadEntry =
    LiveExternalUploadEntry(
      ref = entry.ref,
      name = entry.name,
      relativePath = entry.relativePath,
      size = entry.size,
      contentType = entry.contentType,
      lastModified = entry.lastModified,
      clientMeta = entry.clientMeta
    )

  private def ensureWriterState(entry: UploadEntryState): Task[UploadEntryState] =
    entry.writerState match
      case Some(_) => ZIO.succeed(entry)
      case None    =>
        entry.writer
          .init(entry.uploadName, toExternalUploadEntry(entry))
          .map(state => entry.copy(writerState = Some(state)))

  private def closeWriter(
    entry: UploadEntryState,
    reason: LiveUploadWriterCloseReason
  ): Task[UploadEntryState] =
    if entry.writerClosed then ZIO.succeed(entry)
    else
      entry.writerState match
        case Some(state) =>
          entry.writer
            .close(state, reason)
            .either
            .map {
              case Right(closedState) =>
                entry.copy(
                  writerState = Some(closedState),
                  writerMeta = Some(entry.writer.meta(closedState)),
                  writerClosed = true
                )
              case Left(_) =>
                entry.copy(writerClosed = true)
            }
        case None => ZIO.succeed(entry.copy(writerClosed = true))

  private def runUploadProgressCallback[Msg, Model](
    uploadRef: String,
    entry: UploadEntryState,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      runtime <- state.uploadRef.get
      _       <- runtime
             .configByRef(uploadRef)
             .flatMap(_.options.progress)
             .map(callback =>
               callback
                 .onProgress(entry.uploadName, toLiveUploadEntry(entry))
                 .provide(ZLayer.succeed(state.ctx))
             )
             .getOrElse(ZIO.unit)
    yield ()

  private def isUploadEntryDone(entry: UploadEntryState): Boolean =
    entry.progress >= 100 || entry.bytes.length == entry.size

  private def toLiveUploadEntry(entry: UploadEntryState): LiveUploadEntry =
    LiveUploadEntry(
      ref = entry.ref,
      clientName = entry.name,
      clientRelativePath = entry.relativePath,
      clientSize = entry.size,
      clientType = entry.contentType,
      clientLastModified = entry.lastModified,
      progress = entry.progress,
      preflighted = entry.preflighted,
      done = isUploadEntryDone(entry),
      cancelled = entry.cancelled,
      valid = entry.valid && entry.errors.isEmpty,
      errors = entry.errors.map(LiveUploadError.fromJson),
      meta = entry.externalMeta.orElse(entry.writerMeta)
    )

  private def applyHookHalt[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    hookModel: Model,
    reply: Option[zio.json.ast.Json],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      diff <- updateModelAndSubscriptions(modelVar, el, hookModel, state)
      payload = hookReplyPayload(reply, diff)
      _ <- publishPayload(payload, meta, state)
    yield ()

  private def applyBoundEvent[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    hookModel: Model,
    event: Payload.Event,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    el.findBinding(event.event) match
      case Some(binding) =>
        for
          updatedModel <- normalize(state.lv.update(hookModel)(binding(event.params)), state.ctx)
          diff         <- updateModelAndSubscriptions(modelVar, el, updatedModel, state)
          _            <- publishPayload(Payload.okReply(LiveResponse.Diff(diff)), meta, state)
        yield ()
      case None =>
        ZIO.logWarning(s"Ignoring unknown binding ID ${event.event}") *>
          publishPayload(Payload.okReply(LiveResponse.Empty), meta, state)

  private def updateModelAndSubscriptions[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    model: Model,
    state: RuntimeState[Msg, Model]
  ): Task[Diff] =
    for
      _ = modelVar.set(model)
      _ <- state.lvStreamRef.set(
             state.lv.subscriptions(model).provideLayer(ZLayer.succeed(state.ctx))
           )
      diff = el.diff()
    yield diff

  private def hookReplyPayload(
    reply: Option[zio.json.ast.Json],
    diff: Diff
  ): Payload =
    reply match
      case Some(replyValue) =>
        Payload.okReply(LiveResponse.HookReply(replyValue, Option.when(!diff.isEmpty)(diff)))
      case None if !diff.isEmpty =>
        Payload.okReply(LiveResponse.Diff(diff))
      case None =>
        Payload.okReply(LiveResponse.Empty)

  private def startServerFiber[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): RIO[Scope, Fiber.Runtime[Throwable, Unit]] =
    serverMsgStream(state).runForeach((msg, meta) => handleServerMsg(msg, meta, state)).fork

  private def serverMsgStream[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (Msg, WebSocketMessage.Meta)] =
    (ZStream.fromZIO(state.lvStreamRef.get) ++ state.lvStreamRef.changes)
      .flatMapParSwitch(1, 1)(identity)
      .map(_ -> state.meta.copy(messageRef = None, eventType = "diff"))

  private def handleServerMsg[Msg, Model](
    msg: Msg,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      (modelVar, el) <- state.ref.get
      updatedModel   <- normalize(state.lv.update(modelVar.currentValue)(msg), state.ctx)
      diff           <- updateModelAndSubscriptions(modelVar, el, updatedModel, state)
      _              <- publishPayload(Payload.Diff(diff), meta, state)
    yield ()

  private def publishPayload[Msg, Model](
    payload: Payload,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): UIO[Unit] =
    state.outHub.publish(payload -> meta).unit

  private def buildOutbox[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)] =
    ZStream.succeed(
      Payload.okReply(LiveResponse.InitDiff(state.initDiff)) -> state.meta
    ) ++ ZStream
      .unwrapScoped(ZStream.fromHubScoped(state.outHub)).filterNot {
        case (Payload.Diff(diff), _) => diff.isEmpty
        case _                       => false
      }

  private def buildShutdown[Msg, Model](
    state: RuntimeState[Msg, Model],
    clientFiber: Fiber.Runtime[Throwable, Unit],
    serverFiber: Fiber.Runtime[Throwable, Unit]
  ): UIO[Unit] =
    state.outHub.publish(Payload.Close -> state.meta) *>
      state.inbox.shutdown *>
      state.outHub.shutdown *>
      clientFiber.interrupt.unit *>
      serverFiber.interrupt.unit
end Socket
