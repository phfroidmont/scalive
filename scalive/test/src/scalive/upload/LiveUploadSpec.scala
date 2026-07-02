package scalive.upload

import zio.*
import zio.json.ast.Json
import zio.test.*

object LiveUploadSpec extends ZIOSpecDefault:

  override def spec = suite("LiveUploadSpec")(
    suite("LiveUploadError.fromJson")(
      test("maps known string reason") {
        val result = LiveUploadError.fromJson(Json.Str("too_large"))
        assertTrue(result == LiveUploadError.TooLarge)
      },
      test("maps object reason when present") {
        val result = LiveUploadError.fromJson(
          Json.Obj(
            "reason" -> Json.Str("not_accepted"),
            "message" -> Json.Str("Invalid file type")
          )
        )
        assertTrue(result == LiveUploadError.NotAccepted)
      },
      test("keeps object metadata as external error") {
        val externalMeta = Json.Obj(
          "uploader" -> Json.Str("s3"),
          "url" -> Json.Str("https://example.com")
        )
        val result       = LiveUploadError.fromJson(externalMeta)
        assertTrue(result == LiveUploadError.External(externalMeta))
      },
      test("stringifies unknown json payloads") {
        val payload = Json.Bool(true)
        val result  = LiveUploadError.fromJson(payload)
        assertTrue(result == LiveUploadError.Unknown(payload.toString))
      }
    ),
    suite("LiveUploadError.toJson")(
      test("serializes writer failure reason") {
        val result = LiveUploadError.toJson(LiveUploadError.WriterFailure("writer_error"))
        assertTrue(result == Json.Str("writer_error"))
      }
    ),
    suite("LiveUploadWriterState")(
      test("custom writers can construct and inspect public state") {
        final case class WriterState(chunks: Int, closed: Boolean)

        val writer = new LiveUploadWriter:
          def init(uploadName: String, entry: LiveExternalUploadEntry): Task[LiveUploadWriterState] =
            ZIO.succeed(LiveUploadWriterState(WriterState(0, closed = false)))

          def meta(state: LiveUploadWriterState): Json.Obj =
            val chunks = state.valueAs[WriterState].map(_.chunks).getOrElse(-1)
            Json.Obj("chunks" -> Json.Num(BigDecimal(chunks)))

          def writeChunk(data: Chunk[Byte], state: LiveUploadWriterState): Task[LiveUploadWriterState] =
            val next = state.valueAs[WriterState].get.copy(chunks = data.length)
            ZIO.succeed(LiveUploadWriterState(next))

          def close(
            state: LiveUploadWriterState,
            reason: LiveUploadWriterCloseReason
          ): Task[LiveUploadWriterState] =
            val next = state.valueAs[WriterState].get.copy(closed = true)
            ZIO.succeed(LiveUploadWriterState(next))

        val entry = LiveExternalUploadEntry(
          ref = "0",
          name = "avatar.png",
          relativePath = None,
          size = 3,
          contentType = "image/png",
          lastModified = None,
          clientMeta = None
        )

        for
          initial <- writer.init("avatar", entry)
          written <- writer.writeChunk(Chunk[Byte](1, 2, 3), initial)
          closed  <- writer.close(written, LiveUploadWriterCloseReason.Done)
        yield assertTrue(
          initial.valueAs[WriterState] == Some(WriterState(0, closed = false)),
          written.valueAs[WriterState] == Some(WriterState(3, closed = false)),
          closed.valueAs[WriterState] == Some(WriterState(3, closed = true)),
          writer.meta(written) == Json.Obj("chunks" -> Json.Num(BigDecimal(3)))
        )
      }
    )
  )
