package scalive.upload

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
    )
  )
