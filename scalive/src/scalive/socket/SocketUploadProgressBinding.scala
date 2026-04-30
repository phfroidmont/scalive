package scalive
package socket

import scala.util.Try

import zio.*
import zio.json.*
import zio.json.ast.Json

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

private[scalive] object SocketUploadProgressBinding:
  def applyUploadProgressBinding[Msg, Model](
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
                         .handleComponentMessage(
                           cid,
                           message,
                           uploadProgressEvent(eventRef, payload),
                           rendered,
                           state.meta,
                           state
                         )
                         .as(Payload.okReply(LiveResponse.Empty))
                     case Right(ComponentMessage(cid, _)) =>
                       ZIO.logWarning(
                         s"upload_progress binding '$eventRef' targets component $cid without matching event cid"
                       ) *>
                         ZIO.succeed(Payload.okReply(LiveResponse.Empty))
                     case Right(ComponentTargetMessage(componentClass, message)) =>
                       payload.cid match
                         case Some(cid) =>
                           SocketComponentRuntime
                             .handleComponentTargetMessage(
                               componentClass,
                               cid,
                               message,
                               uploadProgressEvent(eventRef, payload),
                               rendered,
                               state.meta,
                               state
                             )
                             .as(Payload.okReply(LiveResponse.Empty))
                         case None =>
                           ZIO.logWarning(
                             s"upload_progress binding '$eventRef' targets ${componentClass.getName} without event cid"
                           ) *>
                             ZIO.succeed(Payload.okReply(LiveResponse.Empty))
                     case Right(message) =>
                       state.msgClassTag.unapply(message) match
                         case Some(parentMessage) =>
                           for
                             (updatedModel, navigation) <-
                               SocketModelRuntime.captureNavigation(state)(
                                 LiveIO
                                   .toZIO(
                                     state.lv.handleMessage(currentModel)(
                                       parentMessage
                                     )
                                   )
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

  def progressToParamValue(progress: Json): String =
    progress match
      case Json.Num(v)  => Try(v.intValueExact()).toOption.map(_.toString).getOrElse(v.toString)
      case Json.Str(v)  => v
      case Json.Bool(v) => v.toString
      case Json.Null    => ""
      case other        => other.toJson

  def runUploadProgressCallback[Msg, Model](
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

  private def uploadProgressEvent(eventRef: String, payload: Payload.Progress): LiveEvent =
    LiveEvent(
      kind = payload.event.getOrElse("progress"),
      bindingId = eventRef,
      value = payload.progress,
      params = Map.empty,
      cid = payload.cid,
      meta = None
    )

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
end SocketUploadProgressBinding
