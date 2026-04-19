package scalive

import utest.*
import zio.json.ast.Json

object LiveUploadSpec extends TestSuite:

  val tests = Tests {
    test("LiveUploadError.fromJson") {
      test("maps known string reason") {
        val result = LiveUploadError.fromJson(Json.Str("too_large"))
        assert(result == LiveUploadError.TooLarge)
      }

      test("maps object reason when present") {
        val result = LiveUploadError.fromJson(
          Json.Obj(
            "reason" -> Json.Str("not_accepted"),
            "message" -> Json.Str("Invalid file type")
          )
        )
        assert(result == LiveUploadError.NotAccepted)
      }

      test("keeps object metadata as external error") {
        val externalMeta = Json.Obj(
          "uploader" -> Json.Str("s3"),
          "url" -> Json.Str("https://example.com")
        )
        val result       = LiveUploadError.fromJson(externalMeta)
        assert(result == LiveUploadError.External(externalMeta))
      }

      test("stringifies unknown json payloads") {
        val payload = Json.Bool(true)
        val result  = LiveUploadError.fromJson(payload)
        assert(result == LiveUploadError.Unknown(payload.toString))
      }
    }

    test("LiveUploadError.toJson") {
      test("serializes writer failure reason") {
        val result = LiveUploadError.toJson(LiveUploadError.WriterFailure("writer_error"))
        assert(result == Json.Str("writer_error"))
      }
    }
  }
