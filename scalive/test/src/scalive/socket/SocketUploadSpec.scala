package scalive.socket

import scalive.*

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Payload.given
import scalive.WebSocketMessage.ReplyStatus

object SocketUploadSpec extends ZIOSpecDefault:

  private val uploadName = "avatar"
  private val meta       = WebSocketMessage.Meta(None, None, topic = "lv:test", eventType = "event")

  private def makeSocket(
    options: LiveUploadOptions,
    allowedUploadPromise: Promise[Throwable, LiveUpload],
    snapshots: Queue[Option[LiveUpload]]
  ) =
    Socket.start(
      "id",
      "token",
      makeLiveView(options, allowedUploadPromise, snapshots),
      LiveContext(staticChanged = false),
      meta
    )

  private def makeLiveView(
    options: LiveUploadOptions,
    allowedUploadPromise: Promise[Throwable, LiveUpload],
    snapshots: Queue[Option[LiveUpload]]
  ) =
    new LiveView[Unit, Unit]:
      def init =
        for
          upload <- LiveContext.allowUpload(uploadName, options)
          _      <- allowedUploadPromise.succeed(upload).ignore
        yield ()

      def update(model: Unit) = _ => model

      def view(model: Dyn[Unit]): HtmlElement =
        div("upload")

      def subscriptions(model: Unit) = ZStream.empty

      override def interceptEvent(model: Unit, event: String, value: Json) =
        event match
          case "capture" =>
            LiveContext.upload(uploadName)
              .flatMap(upload => snapshots.offer(upload))
              .as(InterceptResult.halt(model))
          case "cancel"  =>
            entryRefFromHookValue(value) match
              case Some(entryRef) =>
                (LiveContext.cancelUpload(uploadName, entryRef) *> LiveContext.upload(uploadName))
                  .flatMap(upload => snapshots.offer(upload))
                  .as(InterceptResult.halt(model))
              case None           =>
                LiveContext.upload(uploadName)
                  .flatMap(upload => snapshots.offer(upload))
                  .as(InterceptResult.halt(model))
          case _         =>
            ZIO.succeed(InterceptResult.cont(model))

  private def entryRefFromHookValue(value: Json): Option[String] =
    value match
      case obj: Json.Obj =>
        obj.fields.collectFirst {
          case ("entry_ref", Json.Str(ref)) => ref
        }
      case _             => None

  private def preflightEntry(ref: String, name: String, size: Long = 10L) =
    WebSocketMessage.UploadPreflightEntry(
      ref = ref,
      name = name,
      relative_path = None,
      size = size,
      `type` = "text/plain",
      last_modified = None,
      meta = None
    )

  private def captureEvent(uploads: Option[Json]): Payload.Event =
    Payload.Event(
      `type` = "click",
      event = "capture",
      value = Json.Obj.empty,
      uploads = uploads,
      cid = None,
      meta = None
    )

  private def cancelEvent(entryRef: String): Payload.Event =
    Payload.Event(
      `type` = "click",
      event = "cancel",
      value = Json.Obj("entry_ref" -> Json.Str(entryRef)),
      uploads = None,
      cid = None,
      meta = None
    )

  private def waitForAllowedUpload(promise: Promise[Throwable, LiveUpload]): Task[LiveUpload] =
    Live.live(
      promise.await.timeoutFail(new RuntimeException("Timed out waiting for allowed upload"))(5.seconds)
    )

  private def waitForSnapshot(snapshots: Queue[Option[LiveUpload]]): Task[LiveUpload] =
    Live.live(
      snapshots.take
        .timeoutFail(new RuntimeException("Timed out waiting for upload snapshot"))(5.seconds)
    )
      .flatMap(upload => ZIO.fromOption(upload).orElseFail(new RuntimeException("Upload not found")))

  private def uploadsJson(uploadRef: String, entries: List[WebSocketMessage.UploadPreflightEntry]): Task[Json] =
    ZIO.fromEither(
      Map(uploadRef -> entries).toJsonAST.left.map(error => new RuntimeException(error))
    )

  override def spec = suite("SocketUploadSpec")(
    test("syncs uploads from event payload before hook execution") {
      val options = LiveUploadOptions(accept = LiveUploadAccept.Any, maxEntries = 1)
      for
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload]
        snapshots            <- Queue.unbounded[Option[LiveUpload]]
        socket               <- makeSocket(options, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        entries = List(
                    preflightEntry("entry-1", name = "a.txt"),
                    preflightEntry("entry-2", name = "b.txt")
                  )
        encodedUploads <- uploadsJson(upload.ref, entries)
        _              <- socket.inbox.offer(captureEvent(Some(encodedUploads)) -> meta)
        synced         <- waitForSnapshot(snapshots)
      yield
        val syncedShapeOk =
          synced.entries match
            case first :: second :: Nil =>
              first.ref == "entry-1" &&
              !first.preflighted &&
              first.progress == 0 &&
              first.valid &&
              second.ref == "entry-2" &&
              second.errors.contains(LiveUploadError.TooManyFiles) &&
              !second.valid
            case _                      =>
              false

        assertTrue(
          synced.ref == upload.ref,
          syncedShapeOk,
          synced.errors.contains(LiveUploadError.TooManyFiles)
        )
    },
    test("cancelled refs are ignored by later preflight") {
      val options = LiveUploadOptions(accept = LiveUploadAccept.Any, maxEntries = 1)
      for
        allowedUploadPromise <- Promise.make[Throwable, LiveUpload]
        snapshots            <- Queue.unbounded[Option[LiveUpload]]
        socket               <- makeSocket(options, allowedUploadPromise, snapshots)
        upload               <- waitForAllowedUpload(allowedUploadPromise)
        entries = List(
                    preflightEntry("entry-1", name = "a.txt"),
                    preflightEntry("entry-2", name = "b.txt")
                  )
        firstReply <- socket.allowUpload(Payload.AllowUpload(upload.ref, entries, None))
        _          <- socket.inbox.offer(cancelEvent("entry-2") -> meta)
        afterCancel <- waitForSnapshot(snapshots)
        secondReply <- socket.allowUpload(Payload.AllowUpload(upload.ref, entries, None))
        _           <- socket.inbox.offer(captureEvent(None) -> meta)
        afterSecond <- waitForSnapshot(snapshots)
      yield
        val firstReplyHasEntryError =
          firstReply match
            case Payload.Reply(
                  ReplyStatus.Ok,
                  LiveResponse.UploadPreflightSuccess(_, _, _, errors)
                ) => errors.contains("entry-2")
            case _ => false

        val secondReplyFilteredCancelled =
          secondReply match
            case Payload.Reply(
                  ReplyStatus.Ok,
                  LiveResponse.UploadPreflightSuccess(_, _, responseEntries, responseErrors)
                ) => responseEntries.keySet == Set("entry-1") && responseErrors.isEmpty
            case _ => false

        assertTrue(
          firstReplyHasEntryError,
          afterCancel.entries.map(_.ref) == List("entry-1"),
          afterCancel.errors.isEmpty,
          secondReplyFilteredCancelled,
          afterSecond.entries.map(_.ref) == List("entry-1"),
          afterSecond.errors.isEmpty
        )
    }
  )
