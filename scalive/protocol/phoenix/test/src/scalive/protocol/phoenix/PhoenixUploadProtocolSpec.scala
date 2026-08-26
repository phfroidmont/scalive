package scalive.protocol.phoenix

import java.nio.charset.StandardCharsets

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PhoenixUploadProtocolSpec extends ZIOSpecDefault:
  private val joinRef = PhoenixRef.Value("7")
  private val ref     = PhoenixRef.Value("8")

  private def envelope(topic: String, event: String, payload: Json): Json =
    Json.Arr(Json.Str("7"), Json.Str("8"), Json.Str(topic), Json.Str(event), payload)

  override def spec = suite("PhoenixUploadProtocolSpec")(
    test("decodes upload joins and leaves as distinct inbound messages") {
      val join = envelope(
        "lvu:entry-1",
        "phx_join",
        Json.Obj("token" -> Json.Str("opaque.signed.token"))
      )
      val leave = envelope("lvu:entry-1", "phx_leave", Json.Obj.empty)
      val rootShaped = envelope(
        "lvu:entry-1",
        "phx_join",
        Json.Obj(
          "session" -> Json.Str("session"),
          "params"  -> Json.Obj.empty
        )
      )

      assertTrue(
        PhoenixProtocol.decode(join) == Right(
          PhoenixInbound.UploadJoin(
            joinRef,
            ref,
            "lvu:entry-1",
            "entry-1",
            "opaque.signed.token"
          )
        ),
        PhoenixProtocol.decode(leave) == Right(
          PhoenixInbound.UploadLeave(joinRef, ref, "lvu:entry-1", "entry-1")
        ),
        PhoenixProtocol.decode(rootShaped).isLeft,
        PhoenixProtocol.decode(envelope("lvu:", "phx_join", Json.Obj("token" -> Json.Str("x")))).isLeft,
        PhoenixProtocol.decode(
          envelope("lvu:entry-1", "phx_leave", Json.Obj("reason" -> Json.Str("bye")))
        ).isLeft
      )
    },
    test("decodes the canonical allow_upload preflight fixture") {
      val fixture =
        """["7","8","lv:root","allow_upload",{"ref":"phx-upload","entries":[{"ref":"0","name":"résumé.txt","relative_path":"docs/résumé.txt","size":12,"type":"text/plain","last_modified":1720000000000,"meta":{"checksum":"abc"}}],"cid":3}]"""

      assertTrue(
        PhoenixProtocol.decode(fixture.fromJson[Json].toOption.get).exists {
          case PhoenixInbound.AllowUpload(_, _, _, payload) =>
            payload == PhoenixUploadPreflight(
              "phx-upload",
              Vector(
                PhoenixUploadEntry(
                  "0",
                  "résumé.txt",
                  Some("docs/résumé.txt"),
                  12L,
                  "text/plain",
                  Some(1720000000000L),
                  Some(Json.Obj("checksum" -> Json.Str("abc")))
                )
              ),
              Some(3L)
            )
          case _ => false
        }
      )
    },
    test("strictly rejects malformed and overflowing preflight fields") {
      val baseEntry = Json.Obj(
        "ref"           -> Json.Str("0"),
        "name"          -> Json.Str("file.txt"),
        "relative_path" -> Json.Null,
        "size"          -> Json.Num(1),
        "type"          -> Json.Str("text/plain"),
        "last_modified" -> Json.Null,
        "meta"          -> Json.Null
      )
      def preflight(entry: Json) = Json.Obj(
        "ref"     -> Json.Str("upload"),
        "entries" -> Json.Arr(entry),
        "cid"     -> Json.Null
      )
      val browserEntry = Json.Obj(
        "ref"           -> Json.Str("0"),
        "name"          -> Json.Str("file.txt"),
        "path"          -> Json.Str("document"),
        "relative_path" -> Json.Str("docs/file.txt"),
        "size"          -> Json.Num(1),
        "type"          -> Json.Str("text/plain"),
        "last_modified" -> Json.Null
      )

      assertTrue(
        PhoenixUploadProtocol.decodePreflight(preflight(baseEntry)).isRight,
        PhoenixUploadProtocol.decodeEventUploads(
          Json.Obj("upload" -> Json.Arr(browserEntry))
        ).exists(_.head.entries.head.relativePath.contains("docs/file.txt")),
        PhoenixUploadProtocol.decodePreflight(preflight(browserEntry)).isRight,
        PhoenixUploadProtocol.decodePreflight(
          preflight(browserEntry.add("path", Json.Num(1)))
        ).isLeft,
        PhoenixUploadProtocol.decodePreflight(preflight(baseEntry.add("unknown", Json.Null))).isLeft,
        PhoenixUploadProtocol.decodePreflight(preflight(baseEntry.add("size", Json.Num(1.5)))).isLeft,
        PhoenixUploadProtocol.decodePreflight(preflight(baseEntry.add("size", Json.Num(-1)))).isLeft,
        PhoenixUploadProtocol.decodePreflight(
          preflight(baseEntry.add("size", Json.Num(BigDecimal("9223372036854775808"))))
        ).isLeft,
        PhoenixUploadProtocol.decodePreflight(preflight(baseEntry.add("ref", Json.Num(0)))).isLeft,
        PhoenixUploadProtocol.decodePreflight(
          preflight(baseEntry.add("last_modified", Json.Str("1720000000000")))
        ).isLeft,
        PhoenixUploadProtocol.decodePreflight(
          Json.Obj("ref" -> Json.Str("upload"), "entries" -> Json.Obj.empty)
        ).isLeft
      )
    },
    test("strictly decodes progress in the inclusive 0 to 100 range") {
      def progress(value: Json, cid: Json = Json.Null) = Json.Obj(
        "ref"       -> Json.Str("upload"),
        "entry_ref" -> Json.Str("0"),
        "progress"  -> value,
        "cid"       -> cid,
        "event"     -> Json.Str("binding")
      )

      assertTrue(
        PhoenixProtocol.decode(envelope("lv:root", "progress", progress(Json.Num(100), Json.Num(4)))) ==
          Right(
            PhoenixInbound.UploadProgress(
              joinRef,
              ref,
              "lv:root",
              PhoenixUploadProgress("upload", "0", 100, Some(4L), Some("binding"))
            )
          ),
        PhoenixUploadProtocol.decodeProgress(progress(Json.Num(0))).isRight,
        PhoenixUploadProtocol.decodeProgress(progress(Json.Num(-1))).isLeft,
        PhoenixUploadProtocol.decodeProgress(progress(Json.Num(101))).isLeft,
        PhoenixUploadProtocol.decodeProgress(progress(Json.Num(1.5))).isLeft,
        PhoenixUploadProtocol.decodeProgress(progress(Json.Str("50"))).isLeft,
        PhoenixUploadProtocol.decodeProgress(progress(Json.Num(50)).add("event", Json.Num(1))).isLeft,
        PhoenixUploadProtocol.decodeProgress(progress(Json.Num(50), Json.Num(BigDecimal("1e30")))).isLeft,
        PhoenixUploadProtocol.decodeProgress(progress(Json.Num(50)).add("extra", Json.Null)).isLeft,
        PhoenixUploadProtocol.decodeProgress(
          Json.Obj(
            "ref"       -> Json.Str("upload"),
            "ref"       -> Json.Str("other"),
            "entry_ref" -> Json.Str("0"),
            "progress"  -> Json.Num(50)
          )
        ).isLeft
      )
    },
    test("round-trips the canonical upload binary frame without changing bytes") {
      val fixture = Chunk.fromArray(
        Array[Byte](0, 2, 2, 5, 5) ++
          "7a8blvu:0chunk".getBytes(StandardCharsets.UTF_8) ++
          Array[Byte](0, 1, -1, 42)
      )
      val expected = PhoenixUploadBinaryFrame(
        "7a",
        "8b",
        "lvu:0",
        "chunk",
        Chunk(0.toByte, 1.toByte, -1.toByte, 42.toByte)
      )

      assertTrue(
        PhoenixUploadProtocol.decodeBinary(fixture) == Right(expected),
        PhoenixUploadProtocol.encodeBinary(expected) == Right(fixture),
        PhoenixUploadProtocol
          .encodeBinary(
            PhoenixUploadBinaryFrame("连接", "réf", "lvu:条目", "chunk", Chunk.single(9.toByte))
          )
          .flatMap(PhoenixUploadProtocol.decodeBinary) == Right(
          PhoenixUploadBinaryFrame("连接", "réf", "lvu:条目", "chunk", Chunk.single(9.toByte))
        )
      )
    },
    test("rejects malformed binary frame kinds, metadata, UTF-8, and shapes") {
      val invalidUtf8 = Chunk.fromArray(
        Array[Byte](0, 1, 0, 5, 5, -1) ++ "lvu:0chunk".getBytes(StandardCharsets.UTF_8)
      )
      val oversized = PhoenixUploadBinaryFrame(
        "x" * 256,
        "1",
        "lvu:0",
        "chunk",
        Chunk.empty
      )

      assertTrue(
        PhoenixUploadProtocol.decodeBinary(Chunk(0.toByte, 0.toByte)).isLeft,
        PhoenixUploadProtocol.decodeBinary(Chunk[Byte](1, 0, 0, 0, 0)).isLeft,
        PhoenixUploadProtocol.decodeBinary(Chunk[Byte](0, 9, 0, 0, 0, 1)).isLeft,
        PhoenixUploadProtocol.decodeBinary(invalidUtf8).isLeft,
        PhoenixUploadProtocol
          .encodeBinary(PhoenixUploadBinaryFrame("1", "2", "lv:root", "chunk", Chunk.empty))
          .isLeft,
        PhoenixUploadProtocol
          .encodeBinary(PhoenixUploadBinaryFrame("1", "2", "lvu:0", "not-chunk", Chunk.empty))
          .isLeft,
        PhoenixUploadProtocol.encodeBinary(oversized).isLeft
      )
    },
    test("encodes canonical hosted and external preflight replies and acknowledgements") {
      val response = PhoenixUploadPreflightResponse(
        "upload-ref",
        PhoenixUploadClientConfig(8_000_000L, 2, 64_000, 10_000),
        Map(
          "1" -> PhoenixUploadEntryConfig.External(
            Json.Obj("uploader" -> Json.Str("S3"), "url" -> Json.Str("https://upload.test"))
          ),
          "0" -> PhoenixUploadEntryConfig.Hosted("opaque-token")
        ),
        Map("2" -> Vector(Json.Str("too_large")))
      )
      val canonicalResponse =
        """{"ref":"upload-ref","config":{"max_file_size":8000000,"max_entries":2,"chunk_size":64000,"chunk_timeout":10000},"entries":{"0":"opaque-token","1":{"uploader":"S3","url":"https://upload.test"}},"errors":{"2":["too_large"]}}"""
      val diff  = Json.Obj("0" -> Json.Str("rendered"))
      val reply = PhoenixUploadProtocol.preflightReply(joinRef, ref, "lv:root", response, None)
      val replyWithDiff =
        PhoenixUploadProtocol.preflightReply(joinRef, ref, "lv:root", response, Some(diff))

      assertTrue(
        PhoenixUploadProtocol.encodePreflight(response).toJson == canonicalResponse,
         reply.payload == Json.Obj(
           "status"   -> Json.Str("ok"),
           "response" -> PhoenixUploadProtocol.encodePreflight(response)
         ),
        replyWithDiff.payload == Json.Obj(
          "status" -> Json.Str("ok"),
          "response" -> PhoenixUploadProtocol.encodePreflight(response).add("diff", diff)
        ),
        PhoenixUploadProtocol.chunkAcknowledgement(joinRef, ref, "lvu:0").payload ==
          Json.Obj("status" -> Json.Str("ok"), "response" -> Json.Obj.empty),
        PhoenixUploadProtocol.uploadJoinErrorReply(
          joinRef,
          ref,
          "lvu:0",
          PhoenixUploadJoinError.InvalidToken
        ).payload == Json.Obj(
          "status"   -> Json.Str("error"),
          "response" -> Json.Obj("reason" -> Json.Str("invalid_token"))
        ),
        PhoenixUploadProtocol.chunkErrorReply(
          joinRef,
          ref,
          "lvu:0",
          PhoenixUploadChunkError.FileSizeLimitExceeded(12L)
        ).payload == Json.Obj(
          "status" -> Json.Str("error"),
          "response" -> Json.Obj(
            "reason" -> Json.Str("file_size_limit_exceeded"),
            "limit"  -> Json.Num(12)
          )
        ),
        PhoenixUploadProtocol.chunkErrorReply(
          joinRef,
          ref,
          "lvu:0",
          PhoenixUploadChunkError.QueueOverflow
        ).payload == Json.Obj(
          "status"   -> Json.Str("error"),
          "response" -> Json.Obj("reason" -> Json.Str("queue_overflow"))
        )
      )
    }
  )
