package scalive
package socket

import scala.util.Try

import zio.*
import zio.json.*
import zio.json.ast.Json

import scalive.*
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Payload.given
import scalive.WebSocketMessage.UploadChunkErrorReason
import scalive.WebSocketMessage.UploadJoinErrorReason
import scalive.WebSocketMessage.UploadJoinToken

private[scalive] object SocketUploadProtocol:
  def handleAllowUpload[Msg, Model](
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
                   val validationErrors = validationErrorsByEntry(activeEntries, config.options)
                   val preflightEntries = activeEntries.map(entry =>
                     entry.ref -> buildUploadEntryState(
                       config = config,
                       uploadRef = payload.ref,
                       entry = entry,
                       preflighted = true,
                       valid = true,
                       errors = Nil
                     )
                   )

                   for
                     processed <- ZIO.foreach(preflightEntries) { case (entryRef, entryState) =>
                                    validationErrors.get(entryRef) match
                                      case Some(errors) =>
                                        ZIO.succeed(
                                          entryRef -> withEntryErrors(entryState, errors)
                                        )
                                      case None =>
                                        config.options.external match
                                          case Some(uploader) =>
                                            uploader
                                              .preflight(
                                                SocketUploadShared.toExternalUploadEntry(entryState)
                                              )
                                              .provide(ZLayer.succeed(state.ctx))
                                              .either
                                              .map {
                                                case Right(LiveExternalUploadResult.Ok(meta))
                                                    if hasExternalUploader(meta) =>
                                                  entryRef -> entryState
                                                    .copy(externalMeta = Some(meta))
                                                case Right(LiveExternalUploadResult.Ok(_)) =>
                                                  entryRef -> withEntryErrors(
                                                    entryState,
                                                    List(LiveUploadError.ExternalClientFailure)
                                                  )
                                                case Right(LiveExternalUploadResult.Error(meta)) =>
                                                  entryRef -> withEntryErrors(
                                                    entryState,
                                                    List(LiveUploadError.External(meta))
                                                  )
                                                case Left(_) =>
                                                  entryRef -> withEntryErrors(
                                                    entryState,
                                                    List(LiveUploadError.ExternalClientFailure)
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
                                                state.tokenConfig.secret,
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
                     globalErrors =
                       Option
                         .when(activeEntries.length > config.options.maxEntries)(
                           config.ref -> errorJson(LiveUploadError.TooManyFiles)
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
                                   entries = baseState.entries ++ entriesMap
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

  def handleProgressUpload[Msg, Model](
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
                                    SocketUploadShared.closeWriter(
                                      next,
                                      LiveUploadWriterCloseReason.Done
                                    )
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
                                SocketUploadShared
                                  .closeWriter(
                                    entry.copy(valid = false, errors = List(errorJson)),
                                    LiveUploadWriterCloseReason.Error(
                                      progressToParamValue(errorJson)
                                    )
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

  def handleUploadJoin[Msg, Model](
    uploadTopic: String,
    token: String,
    state: RuntimeState[Msg, Model]
  ): Task[Payload.Reply] =
    Token.verify[UploadJoinToken](state.tokenConfig.secret, token, state.tokenConfig.maxAge) match
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
                               LiveResponse.UploadJoinError(
                                 UploadJoinErrorReason.AlreadyRegistered
                               )
                             )
                           )
                         else
                           SocketUploadShared.ensureWriterState(entry).either.flatMap {
                             case Right(withWriter) =>
                               state.uploadRef
                                 .update { st =>
                                   st.copy(
                                     entries = st.entries.updated(
                                       joinToken.entryRef,
                                       withWriter.copy(
                                         joined = true,
                                         valid = true,
                                         errors = Nil
                                       )
                                     )
                                   )
                                 }.as(Payload.okReply(LiveResponse.Empty))
                             case Left(_) =>
                               val errored = entry.copy(
                                 valid = false,
                                 errors = List(errorJson(WriterError))
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

  def handleUploadChunk[Msg, Model](
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
                     SocketUploadShared.ensureWriterState(entry).either.flatMap {
                       case Right(withWriter) =>
                         withWriter.writerState match
                           case Some(writerState) =>
                             withWriter.writer
                               .writeChunk(bytes, writerState)
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
                                   SocketUploadShared
                                     .closeWriter(
                                       withWriter.copy(
                                         valid = false,
                                         errors = List(errorJson(WriterError))
                                       ),
                                       LiveUploadWriterCloseReason.Error(WriterErrorReason)
                                     ).flatMap { errored =>
                                       state.uploadRef
                                         .update { st =>
                                           st.copy(entries = st.entries.updated(entryRef, errored))
                                         }.as(
                                           Payload.errorReply(
                                             LiveResponse.UploadChunkError(
                                               UploadChunkErrorReason.WriterError
                                             )
                                           )
                                         )
                                     }
                               }
                           case None =>
                             ZIO.succeed(
                               Payload.errorReply(
                                 LiveResponse.UploadChunkError(UploadChunkErrorReason.WriterError)
                               )
                             )
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

  def syncUploadRuntimeFromEvent[Msg, Model](
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
                    val validationErrors = validationErrorsByEntry(visibleEntries, config.options)
                    val syncedEntries    =
                      visibleEntries
                        .map(entry =>
                          val errors = validationErrors.getOrElse(entry.ref, Nil)
                          entry.ref -> buildUploadEntryState(
                            config = config,
                            uploadRef = uploadRef,
                            entry = entry,
                            preflighted = false,
                            valid = errors.isEmpty,
                            errors = errors
                          )
                        ).toMap
                    val globalErrors =
                      Option
                        .when(visibleEntries.length > config.options.maxEntries)(
                          config.ref -> errorJson(LiveUploadError.TooManyFiles)
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

  private def applyUploadProgressBinding[Msg, Model](
    eventRef: String,
    payload: Payload.Progress,
    state: RuntimeState[Msg, Model]
  ): Task[Payload.Reply] =
    for
      (currentModel, rendered) <- state.ref.get
      reply                    <- rendered.bindings.get(eventRef) match
                 case Some(binding) =>
                   binding(progressPayloadToParams(payload)) match
                     case Right(ComponentMessage(cid, message)) if payload.cid.contains(cid) =>
                       SocketComponentRuntime
                         .handleComponentMessage(cid, message, rendered, state.meta, state)
                         .as(Payload.okReply(LiveResponse.Empty))
                     case Right(ComponentMessage(cid, _)) =>
                       ZIO.logWarning(
                         s"upload_progress binding '$eventRef' targets component $cid without matching event cid"
                       ) *>
                         ZIO.succeed(Payload.okReply(LiveResponse.Empty))
                     case Right(message) =>
                       state.msgClassTag.unapply(message) match
                         case Some(parentMessage) =>
                           for
                             (updatedModel, navigation) <-
                               SocketModelRuntime.captureNavigation(state)(
                                 LiveIO
                                   .toZIO(state.lv.handleMessage(currentModel)(parentMessage))
                                   .provide(ZLayer.succeed(state.ctx))
                               )
                             reply <- navigation match
                                        case Some(command) =>
                                          state.patchRedirectCountRef.set(0) *>
                                            SocketInbound
                                              .handleNavigationCommand(
                                                rendered,
                                                updatedModel,
                                                command,
                                                state.meta,
                                                state
                                              )
                                              .as(Payload.okReply(LiveResponse.Empty))
                                        case None =>
                                          SocketModelRuntime
                                            .updateModelAndSubscriptions(
                                              rendered,
                                              updatedModel,
                                              state
                                            )
                                            .map(diff =>
                                              if diff.isEmpty then
                                                Payload.okReply(LiveResponse.Empty)
                                              else Payload.okReply(LiveResponse.Diff(diff))
                                            )
                           yield reply
                         case None =>
                           ZIO
                             .logWarning(
                               s"upload_progress binding type mismatch for ref=$eventRef: expected ${state.msgClassTag.runtimeClass.getName}"
                             ).as(Payload.okReply(LiveResponse.Empty))
                     case Left(error) =>
                       ZIO.logWarning(
                         s"upload_progress binding type mismatch for ref=$eventRef: $error"
                       ) *>
                         ZIO.succeed(Payload.okReply(LiveResponse.Empty))
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

  private def validateUploadEntries(
    entries: List[WebSocketMessage.UploadPreflightEntry],
    options: LiveUploadOptions
  ): List[(String, LiveUploadError)] =
    entries.zipWithIndex.flatMap { case (entry, index) =>
      if index >= options.maxEntries then Some(entry.ref -> LiveUploadError.TooManyFiles)
      else if entry.size > options.maxFileSize then Some(entry.ref -> LiveUploadError.TooLarge)
      else if !isAcceptedUploadEntry(entry, options.accept) then
        Some(entry.ref -> LiveUploadError.NotAccepted)
      else None
    }

  private def validationErrorsByEntry(
    entries: List[WebSocketMessage.UploadPreflightEntry],
    options: LiveUploadOptions
  ): Map[String, List[LiveUploadError]] =
    validateUploadEntries(entries, options).groupMap(_._1)(_._2)

  private def buildUploadEntryState(
    config: UploadConfigState,
    uploadRef: String,
    entry: WebSocketMessage.UploadPreflightEntry,
    preflighted: Boolean,
    valid: Boolean,
    errors: List[LiveUploadError]
  ): UploadEntryState =
    UploadEntryState(
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
      preflighted = preflighted,
      valid = valid,
      errors = errors.map(errorJson),
      externalMeta = None,
      writer = config.options.writer,
      writerState = None,
      writerMeta = None,
      writerClosed = false
    )

  private def withEntryErrors(
    entry: UploadEntryState,
    errors: List[LiveUploadError]
  ): UploadEntryState =
    entry.copy(valid = false, errors = errors.map(errorJson))

  private def errorJson(error: LiveUploadError): Json =
    LiveUploadError.toJson(error)

  private val WriterErrorReason = "writer_error"
  private val WriterError       = LiveUploadError.WriterFailure(WriterErrorReason)

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
                 .onProgress(entry.uploadName, SocketUploadShared.toLiveUploadEntry(entry))
                 .provide(ZLayer.succeed(state.ctx))
             )
             .getOrElse(ZIO.unit)
    yield ()
end SocketUploadProtocol
