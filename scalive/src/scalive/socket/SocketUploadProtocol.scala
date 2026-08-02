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
                   val incomingRefs     = activeEntries.map(_.ref).toSet
                   val baseState        = current.removeEntries(incomingRefs)
                   val validationErrors =
                     SocketUploadValidation.validationErrorsByEntry(
                       activeEntries,
                       config.definition
                     )
                   val preflightEntries = activeEntries.map(entry =>
                     entry.ref -> SocketUploadEntries.buildUploadEntryState(
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
                                          entryRef -> SocketUploadEntries.withEntryErrors(
                                            entryState,
                                            errors
                                          )
                                        )
                                      case None =>
                                        if config.definition.destination.external then
                                          config.definition.destination
                                            .preflight(
                                              SocketUploadShared.clientMetadata(entryState)
                                            )
                                            .either
                                            .map {
                                              case Right(
                                                    Some(
                                                      LiveExternalUploadResult.Ready(config, result)
                                                    )
                                                  ) =>
                                                entryRef -> entryState
                                                  .copy(
                                                    externalMeta = Some(config.json),
                                                    completedResult = Some(result)
                                                  )
                                              case Right(
                                                    Some(LiveExternalUploadResult.Error(meta))
                                                  ) =>
                                                entryRef -> SocketUploadEntries.withEntryErrors(
                                                  entryState,
                                                  List(LiveUploadError.External(meta))
                                                )
                                              case Right(None) | Left(_) =>
                                                entryRef -> SocketUploadEntries.withEntryErrors(
                                                  entryState,
                                                  List(LiveUploadError.ExternalClientFailure)
                                                )
                                            }
                                        else
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
                     entryOrder =
                       config.entryOrder.filterNot(incomingRefs.contains) ++ activeEntries.map(
                         _.ref
                       )
                     globalErrors =
                       Option
                         .when(entryOrder.length > config.definition.maxEntries)(
                           config.ref -> SocketUploadValidation.errorJson(
                             LiveUploadError.TooManyFiles
                           )
                         )
                         .toList
                     nextConfig = config.copy(
                                    errors = globalErrors,
                                    entryOrder = entryOrder
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
                         max_file_size = config.definition.maxFileSize,
                         max_entries = config.definition.maxEntries,
                         chunk_size = config.definition.chunkSize,
                         chunk_timeout = config.definition.chunkTimeout.toMillis.toInt
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
  ): Task[Payload.Reply] = state.lifecycleLock.withPermit {
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
                                    if next.completedResult.nonEmpty then ZIO.succeed(next)
                                    else SocketUploadShared.complete(next)
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
                                val failed = entry.copy(valid = false, errors = List(errorJson))
                                SocketUploadShared
                                  .cleanupEntry(
                                    failed,
                                    LiveUploadAbortReason.Failed(
                                      SocketUploadProgressBinding.progressToParamValue(errorJson)
                                    )
                                  ).flatMap { _ =>
                                    val updated = failed.copy(destinationState = None)
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
             case Some(updated) =>
               SocketUploadProgressBinding.runUploadProgressCallback(payload.ref, updated, state)
             case None => ZIO.unit
      reply <- payload.event match
                 case Some(eventRef) if eventRef.nonEmpty =>
                   SocketUploadProgressBinding.applyUploadProgressBinding(
                     eventRef,
                     payload,
                     state
                   )
                 case _ =>
                   ZIO.succeed(Payload.okReply(LiveResponse.Empty))
    yield reply
  }

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
                           SocketUploadShared.ensureDestinationState(entry).either.flatMap {
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
                                 errors = List(
                                   SocketUploadValidation.errorJson(
                                     SocketUploadValidation.WriterError
                                   )
                                 )
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
                   val updatedSize = entry.bytesReceived + bytes.length
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
                     SocketUploadShared.ensureDestinationState(entry).either.flatMap {
                       case Right(withWriter) =>
                         withWriter.destinationState match
                           case Some(writerState) =>
                             withWriter.destination
                               .writeChunk(bytes, writerState)
                               .either
                               .flatMap {
                                 case Right(nextWriterState) =>
                                   val progress =
                                     if withWriter.size <= 0 then 100
                                     else ((updatedSize * 100) / withWriter.size).toInt.min(100)
                                   val writtenEntry = withWriter.copy(
                                     bytesReceived = updatedSize,
                                     progress = progress,
                                     destinationState = Some(nextWriterState)
                                   )
                                   val finish =
                                     if updatedSize == withWriter.size then
                                       SocketUploadShared.complete(writtenEntry)
                                     else ZIO.succeed(writtenEntry)
                                   finish.flatMap(updatedEntry =>
                                     state.uploadRef
                                       .update { st =>
                                         st.copy(entries =
                                           st.entries.updated(entryRef, updatedEntry)
                                         )
                                       }.as(Payload.okReply(LiveResponse.Empty))
                                   )
                                 case Left(_) =>
                                   val failed = withWriter.copy(
                                     valid = false,
                                     errors = List(
                                       SocketUploadValidation.errorJson(
                                         SocketUploadValidation.WriterError
                                       )
                                     )
                                   )
                                   SocketUploadShared
                                     .cleanupEntry(
                                       failed,
                                       LiveUploadAbortReason.Failed(
                                         SocketUploadValidation.WriterErrorReason
                                       )
                                     ).flatMap { _ =>
                                       val errored = failed.copy(destinationState = None)
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
                    val visibleRefs   = visibleEntries.map(_.ref).toSet
                    val preservedRefs = config.entryOrder.filterNot(visibleRefs.contains)
                    val cleared       = runtime.removeEntries(
                      config.entryOrder.filterNot(preservedRefs.contains).toSet
                    )
                    val validationErrors = SocketUploadValidation.validationErrorsByEntry(
                      visibleEntries,
                      config.definition
                    )
                    val syncedEntries =
                      visibleEntries
                        .map(entry =>
                          val errors = validationErrors.getOrElse(entry.ref, Nil)
                          val synced = runtime.entries.get(entry.ref) match
                            case Some(existing)
                                if existing.uploadRef == uploadRef && existing.preflighted =>
                              existing
                            case _ =>
                              SocketUploadEntries.buildUploadEntryState(
                                config = config,
                                uploadRef = uploadRef,
                                entry = entry,
                                preflighted = false,
                                valid = errors.isEmpty,
                                errors = errors
                              )
                          entry.ref -> synced
                        ).toMap
                    val globalErrors =
                      Option
                        .when(visibleEntries.length > config.definition.maxEntries)(
                          config.ref -> SocketUploadValidation.errorJson(
                            LiveUploadError.TooManyFiles
                          )
                        )
                        .toList
                    val nextConfig = config.copy(
                      errors = globalErrors,
                      entryOrder = preservedRefs ++ visibleEntries.map(_.ref)
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
end SocketUploadProtocol
