package scalive.upload

import zio.*
import zio.json.ast.Json
import zio.test.*

object LiveUploadSpec extends ZIOSpecDefault:
  override def spec = suite("LiveUploadSpec")(
    suite("LiveUploadAccept")(
      test("rejects empty, blank, duplicate, and malformed filters") {
        val invalid = List(
          LiveUploadAccept.validated(Nil),
          LiveUploadAccept.validated(List("   ")),
          LiveUploadAccept.validated(List(".png", " .png ")),
          LiveUploadAccept.validated(List("png")),
          LiveUploadAccept.validated(List(".")),
          LiveUploadAccept.validated(List("image/")),
          LiveUploadAccept.validated(List("image/png/extra"))
        )
        val onlyBlank = scala.util.Try(LiveUploadAccept.only(" "))

        assertTrue(invalid.forall(_.isLeft), onlyBlank.isFailure)
      },
      test("renders extensions, exact media types, and media wildcards") {
        val accept = LiveUploadAccept.only(" .png ", "image/png", "image/*")

        assertTrue(accept.toHtmlValue == ".png,image/png,image/*")
      }
    ),
    suite("LiveUploadDef")(
      test("eager factories validate configuration") {
        val result = scala.util.Try(
          LiveUploadDef.inMemory("", LiveUploadAccept.Any)
        )
        assertTrue(result.isFailure)
      },
      test("validated supports dynamic configuration") {
        val valid = LiveUploadDef.validated(
          "avatar",
          LiveUploadAccept.only(".png"),
          LiveUploadDestination.inMemory
        )
        val invalid = LiveUploadDef.validated(
          "avatar",
          LiveUploadAccept.Any,
          LiveUploadDestination.inMemory,
          maxEntries = 0
        )
        assertTrue(valid.isRight, invalid.isLeft)
      },
      test("hosted writers retain typed state and result") {
        final case class WriterState(bytes: Int)
        val writer = new LiveUploadWriter[WriterState, Int]:
          def init(client: UploadClientMetadata) = ZIO.succeed(WriterState(0))
          def writeChunk(data: Chunk[Byte], state: WriterState) =
            ZIO.succeed(state.copy(bytes = state.bytes + data.length))
          def complete(state: WriterState) = ZIO.succeed(state.bytes)
          def abort(state: WriterState, reason: LiveUploadAbortReason) = ZIO.unit
          def discard(result: Int) = ZIO.unit
          override def metadata(result: Int) = Json.Obj("bytes" -> Json.Num(result))

        val definition = LiveUploadDef.hosted("avatar", LiveUploadAccept.Any, writer)
        assertTrue(definition.name == "avatar")
      },
      test("external destinations retain the uploader result type") {
        val uploader = new LiveUploadExternalUploader[String]:
          def preflight(client: UploadClientMetadata) =
            ZIO.succeed(
              LiveExternalUploadResult.Ready(
                ExternalUploadClientConfig(Json.Obj("uploader" -> Json.Str("test"))),
                "external-result"
              )
            )

        val definition: LiveUploadDef[String] =
          LiveUploadDef.external("avatar", LiveUploadAccept.Any, uploader)
        assertTrue(definition.name == "avatar")
      },
      test("external client configuration requires an uploader") {
        val missing = ExternalUploadClientConfig.validated(Json.Obj.empty)
        val blank = ExternalUploadClientConfig.validated(
          Json.Obj("uploader" -> Json.Str(""))
        )
        val valid = ExternalUploadClientConfig.validated(
          Json.Obj("uploader" -> Json.Str("s3"))
        )
        assertTrue(missing.isLeft, blank.isLeft, valid.isRight)
      }
    ),
    suite("LiveUploadError")(
      test("maps known and external JSON errors") {
        val external = Json.Obj("uploader" -> Json.Str("s3"))
        assertTrue(
          LiveUploadError.fromJson(Json.Str("too_large")) == LiveUploadError.TooLarge,
          LiveUploadError.fromJson(external) == LiveUploadError.External(external),
          LiveUploadError.toJson(LiveUploadError.WriterFailure("writer_error")) == Json.Str(
            "writer_error"
          )
        )
      }
    ),
    test("runtime snapshots cannot be publicly constructed or copied") {
      val constructorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        new LiveUpload[Int]("name", null, LiveUploadAccept.Any, 1, 1, 1, 1, false, false, Nil, Nil)
      """)
      val copyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def copy(upload: LiveUpload[Int]) = upload.copy(name = "other")
      """)
      assertTrue(constructorErrors.nonEmpty, copyErrors.nonEmpty)
    }
  )
