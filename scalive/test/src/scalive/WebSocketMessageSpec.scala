package scalive

import java.nio.charset.StandardCharsets

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Protocol

object WebSocketMessageSpec extends ZIOSpecDefault:

  private def decode(value: Json): Either[String, WebSocketMessage] =
    value.toJson.fromJson[WebSocketMessage]

  private def ascii(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))

  private def binaryFrame(
    joinRef: String,
    ref: String,
    topic: String,
    event: String,
    payload: Chunk[Byte] = Chunk.empty
  ): Chunk[Byte] =
    val joinRefBytes = ascii(joinRef)
    val refBytes     = ascii(ref)
    val topicBytes   = ascii(topic)
    val eventBytes   = ascii(event)

    Chunk(
      0.toByte,
      joinRefBytes.length.toByte,
      refBytes.length.toByte,
      topicBytes.length.toByte,
      eventBytes.length.toByte
    ) ++
      joinRefBytes ++
      refBytes ++
      topicBytes ++
      eventBytes ++
      payload

  override def spec = suite("WebSocketMessageSpec")(
    test("returns Left for invalid message ref") {
      val result = decode(
        Json.Arr(
          Chunk(
            Json.Null,
            Json.Str("not-an-int"),
            Json.Str("lv:test"),
            Json.Str(Protocol.EventHeartbeat),
            Json.Obj.empty
          )
        )
      )

      assertTrue(result.left.exists(_.contains("Invalid ref: not-an-int")))
    },
    test("returns Left for unknown event type") {
      val result = decode(
        Json.Arr(
          Chunk(
            Json.Null,
            Json.Str("1"),
            Json.Str("lv:test"),
            Json.Str("unknown"),
            Json.Obj.empty
          )
        )
      )

      assertTrue(result.left.exists(_.contains("Unknown event type: unknown")))
    },
    test("decodes phx_join with token-only payload as UploadJoin") {
      val result = decode(
        Json.Arr(
          Chunk(
            Json.Null,
            Json.Str("1"),
            Json.Str("lvu:entry"),
            Json.Str(Protocol.EventJoin),
            Json.Obj("token" -> Json.Str("join-token"))
          )
        )
      )

      assertTrue(result.exists(_.payload == Payload.UploadJoin("join-token")))
    },
    test("decodes phx_join with session payload as Join") {
      val payload = Json.Obj(
        "url"      -> Json.Str("http://localhost/live"),
        "redirect" -> Json.Null,
        "session"  -> Json.Str("session-token"),
        "static"   -> Json.Null,
        "params"   -> Json.Null,
        "flash"    -> Json.Str("flash-token"),
        "sticky"   -> Json.Bool(false)
      )

      val result = decode(
        Json.Arr(
          Chunk(
            Json.Null,
            Json.Str("1"),
            Json.Str("lv:test"),
            Json.Str(Protocol.EventJoin),
            payload
          )
        )
      )

      val flash = result.toOption.collect { case WebSocketMessage(_, _, _, _, join: Payload.Join) =>
        join.flash
      }.flatten

      assertTrue(result.exists(_.payload.isInstanceOf[Payload.Join]), flash.contains("flash-token"))
    },
    test("decodeBinaryPush returns Left for unsupported event") {
      val result = WebSocketMessage.decodeBinaryPush(
        binaryFrame(
          joinRef = "",
          ref = "1",
          topic = "lvu:entry",
          event = "not_chunk"
        )
      )

      assertTrue(result == Left("Unsupported binary event type: not_chunk"))
    },
    test("InitDiff encodes liveview version from protocol constants") {
      val payload = Payload.okReply(LiveResponse.InitDiff(Diff.Tag()))
      val version = payload
        .toJsonAST
        .toOption
        .collect { case obj: Json.Obj => obj }
        .flatMap(_.fields.collectFirst { case ("response", response: Json.Obj) => response })
        .flatMap(_.fields.collectFirst { case ("liveview_version", Json.Str(v)) => v })

      assertTrue(version.contains(Protocol.LiveViewVersion))
    }
  )
end WebSocketMessageSpec
