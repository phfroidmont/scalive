package scalive.protocol.phoenix

import zio.json.ast.Json
import zio.test.*

import scalive.*

object PhoenixProtocolSpec extends ZIOSpecDefault:
  override def spec = suite("PhoenixProtocolSpec")(
    test("strictly round-trips the five-tuple and nullable refs") {
      val envelope = PhoenixEnvelope(
        PhoenixRef.Null,
        PhoenixRef.Value("17"),
        "phoenix",
        "heartbeat",
        Json.Obj.empty
      )
      assertTrue(
        PhoenixEnvelope.decode(PhoenixEnvelope.encode(envelope)) == Right(envelope),
        PhoenixEnvelope.fromJson(Json.Arr(Json.Null)) ==
          Left("Phoenix envelope must contain exactly five items"),
        PhoenixEnvelope.fromJson(
          Json.Arr(Json.Num(1), Json.Null, Json.Str("x"), Json.Str("y"), Json.Obj.empty)
        ).isLeft
      )
    },
    test("decodes heartbeat, join, and event without losing payload fields") {
      val heartbeat = Json.Arr(
        Json.Null,
        Json.Str("1"),
        Json.Str("phoenix"),
        Json.Str("heartbeat"),
        Json.Obj.empty
      )
      val join = Json.Arr(
        Json.Str("2"),
        Json.Str("2"),
        Json.Str("lv:root"),
        Json.Str("phx_join"),
        Json.Obj(
          "url"     -> Json.Str("https://example.test/path"),
          "session" -> Json.Str("session-token"),
          "static"  -> Json.Str("static-token"),
          "params"  -> Json.Obj("_mounts" -> Json.Num(0)),
          "sticky"  -> Json.Bool(true)
        )
      )
      val event = Json.Arr(
        Json.Str("2"),
        Json.Str("3"),
        Json.Str("lv:root"),
        Json.Str("event"),
        Json.Obj(
          "type"  -> Json.Str("click"),
          "event" -> Json.Str("save"),
          "value" -> Json.Obj("id" -> Json.Str("42")),
          "cid"   -> Json.Num(7)
        )
      )
      assertTrue(
        PhoenixProtocol.decode(heartbeat) ==
          Right(PhoenixInbound.Heartbeat(PhoenixRef.Null, PhoenixRef.Value("1"))),
        PhoenixProtocol.decode(join).exists {
          case PhoenixInbound.Join(_, _, _, payload) =>
            payload.url.contains("https://example.test/path") && payload.redirect.isEmpty &&
              payload.flash.isEmpty && payload.session == "session-token" &&
              payload.static.contains("static-token") &&
              payload.params("_mounts") == Json.Num(0) && payload.sticky
          case _ => false
        },
        PhoenixProtocol.decode(event).exists {
          case PhoenixInbound.Event(_, _, _, payload) =>
            payload.eventType == "click" && payload.event == "save" && payload.cid.contains(7L) &&
              payload.rootClickParams == Right(BindingPayload.Params(Map("id" -> "42")))
          case _ => false
        }
      )
    },
    test("accepts the pinned LiveView root and redirect join shapes") {
      def frame(payload: Json.Obj) = Json.Arr(
        Json.Str("1"),
        Json.Str("1"),
        Json.Str("lv:root"),
        Json.Str("phx_join"),
        payload
      )

      val rootClientPayload = Json.Obj(
        "url"     -> Json.Str("https://example.test/root"),
        "params"  -> Json.Obj("_mounts" -> Json.Num(0)),
        "session" -> Json.Str("session-token"),
        "static"  -> Json.Null,
        "flash"   -> Json.Null,
        "sticky"  -> Json.Bool(false)
      )
      val redirectPayload = Json.Obj(
        "redirect" -> Json.Str("https://example.test/redirected"),
        "url"      -> Json.Null,
        "params"   -> Json.Obj("_mounts" -> Json.Num(1)),
        "session"  -> Json.Str("session-token"),
        "static"   -> Json.Str("static-token"),
        "flash"    -> Json.Str("flash-token"),
        "sticky"   -> Json.Bool(true)
      )

      assertTrue(
        PhoenixProtocol.decode(frame(rootClientPayload)).exists {
          case PhoenixInbound.Join(_, _, _, payload) =>
            payload.url.contains("https://example.test/root") && payload.redirect.isEmpty &&
              payload.flash.isEmpty && payload.static.isEmpty
          case _ => false
        },
        PhoenixProtocol.decode(frame(redirectPayload)).exists {
          case PhoenixInbound.Join(_, _, _, payload) =>
            payload.url.isEmpty &&
              payload.redirect.contains("https://example.test/redirected") &&
              payload.flash.contains("flash-token")
          case _ => false
        },
        PhoenixProtocol.decode(frame(rootClientPayload.add("unknown", Json.Null))).isLeft,
        PhoenixProtocol.decode(
          frame(rootClientPayload.add("url", Json.Null))
        ).left.exists(_.contains("url' or 'redirect"))
      )
    },
    test("rejects nested and form-shaped click values explicitly") {
      val nested = RootEvent("click", "save", Json.Obj("user" -> Json.Obj.empty), None)
      val form   = RootEvent("click", "save", Json.Str("user%5Bname%5D=x"), None)
      assertTrue(
        nested.rootClickParams.left.exists(_.contains("nested")),
        form.rootClickParams.left.exists(_.contains("form-encoded"))
      )
    },
    test("builds exact reply and uncorrelated diff envelopes") {
      val joinRef = PhoenixRef.Value("4")
      val ref     = PhoenixRef.Value("5")
      val rendered = Json.Obj("s" -> Json.Arr(Json.Str("<main></main>")))
      val join = PhoenixOutput.join(joinRef, ref, "lv:root", rendered)
      val event = PhoenixOutput.event(joinRef, ref, "lv:root", Json.Obj.empty)
      val pushed = PhoenixOutput.diff(joinRef, "lv:root", Json.Obj.empty)
      assertTrue(
        join.ref == ref,
        join.payload == Json.Obj(
          "status" -> Json.Str("ok"),
          "response" -> Json.Obj(
            "rendered"         -> rendered,
            "liveview_version" -> Json.Str("1.1.28")
          )
        ),
        event.ref == ref,
        pushed.ref == PhoenixRef.Null,
        pushed.event == "diff"
      )
    }
  )
