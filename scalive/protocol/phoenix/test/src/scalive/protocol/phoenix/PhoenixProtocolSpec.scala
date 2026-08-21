package scalive.protocol.phoenix

import zio.json.ast.Json
import zio.test.*

import scalive.*

object PhoenixProtocolSpec extends ZIOSpecDefault:
  private def eventFrame(payload: Json): Json = Json.Arr(
    Json.Str("1"),
    Json.Str("2"),
    Json.Str("lv:root"),
    Json.Str("event"),
    payload
  )

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
        PhoenixProtocol.decode(frame(rootClientPayload.add("url", Json.Null))).exists {
          case PhoenixInbound.Join(_, _, _, payload) =>
            payload.url.isEmpty && payload.redirect.isEmpty
          case _ => false
        }
      )
    },
    test("decodes the two-phase component destruction protocol") {
      def frame(event: String, payload: Json) = Json.Arr(
        Json.Str("1"),
        Json.Str("2"),
        Json.Str("lv:root"),
        Json.Str(event),
        payload
      )

      val cids = Json.Obj("cids" -> Json.Arr(Json.Num(1), Json.Num(2)))

      assertTrue(
        PhoenixProtocol.decode(frame("cids_will_destroy", cids)).isRight,
        PhoenixProtocol.decode(frame("cids_destroyed", cids)).isRight,
        PhoenixProtocol
          .decode(frame("cids_will_destroy", Json.Obj("cids" -> Json.Arr(Json.Str("1")))))
          .isLeft
      )
    },
    test("decodes ordered form data and semantic metadata") {
      val event = RootEvent(
        "form",
        "save",
        Json.Str("tag=first&tag=second&save=Publish"),
        None,
        meta = Some(
          Json.Obj(
            "_target" -> Json.Arr(Json.Str("user"), Json.Str("name")),
            "submitter" -> Json.Obj(
              "name"  -> Json.Str("save"),
              "value" -> Json.Str("Publish")
            ),
            "_recovery" -> Json.Bool(true),
            "details"   -> Json.Obj("count" -> Json.Num(2))
          )
        )
      )

      assertTrue(
        event.toBindingPayload.exists {
          case BindingPayload.Form(data, meta) =>
            data.raw == Vector("tag" -> "first", "tag" -> "second", "save" -> "Publish") &&
              meta.target.contains(FormPath("user", "name")) &&
              meta.submitter.contains(FormSubmitter("save", "Publish")) && meta.recovery &&
              meta.metadata("details") == "{\"count\":2}"
          case _ => false
        }
      )
    },
    test("decodes target undefined and submitter field-name metadata") {
      val event = RootEvent(
        "form",
        "save",
        Json.Str("save=Draft"),
        None,
        meta = Some(
          Json.Obj(
            "_target"    -> Json.Arr(Json.Str("undefined")),
            "_submitter" -> Json.Str("save"),
            "recovery"   -> Json.Str("true")
          )
        )
      )

      assertTrue(
        event.toBindingPayload.exists {
          case BindingPayload.Form(_, meta) =>
            meta.target.isEmpty && meta.submitter.contains(FormSubmitter("save", "Draft")) &&
              meta.recovery
          case _ => false
        }
      )
    },
    test("rejects malformed form encoding and non-string form values") {
      val malformed = RootEvent("form", "save", Json.Str("name=%ZZ"), None)
      val nonString = RootEvent("form", "save", Json.Obj("name" -> Json.Str("value")), None)
      assertTrue(malformed.toBindingPayload.isLeft, nonString.toBindingPayload.isLeft)
    },
    test("stringifies primitive and nested non-form parameters") {
      val event = RootEvent(
        "click",
        "save",
        Json.Obj(
          "string" -> Json.Str("value"),
          "number" -> Json.Num(42),
          "flag"   -> Json.Bool(true),
          "empty"  -> Json.Null,
          "nested" -> Json.Obj("id" -> Json.Num(7)),
          "array"  -> Json.Arr(Json.Str("a"), Json.Num(2))
        ),
        None
      )

      assertTrue(
        event.toBindingPayload == Right(
          BindingPayload.Params(
            Map(
              "string" -> "value",
              "number" -> "42",
              "flag"   -> "true",
              "empty"  -> "",
              "nested" -> "{\"id\":7}",
              "array"  -> "[\"a\",2]"
            )
          )
        ),
        RootEvent("click", "save", Json.Str("value"), None).toBindingPayload.isLeft
      )
    },
    test("strictly decodes root event optional fields") {
      val valid = Json.Obj(
        "type"    -> Json.Str("click"),
        "event"   -> Json.Str("save"),
        "value"   -> Json.Obj.empty,
        "uploads" -> Json.Obj("avatar" -> Json.Arr()),
        "cid"     -> Json.Num(3),
        "meta"    -> Json.Obj("key" -> Json.Str("value"))
      )

      assertTrue(
        PhoenixProtocol.decode(eventFrame(valid)).exists {
          case PhoenixInbound.Event(_, _, _, payload) =>
            payload.cid.contains(3) && payload.uploads.nonEmpty && payload.meta.nonEmpty
          case _ => false
        },
        PhoenixProtocol.decode(eventFrame(valid.add("unknown", Json.Null))).isLeft,
        PhoenixProtocol.decode(eventFrame(valid.add("meta", Json.Arr()))).isLeft,
        PhoenixProtocol.decode(eventFrame(valid.add("uploads", Json.Str("bad")))).isLeft,
        PhoenixProtocol.decode(eventFrame(valid.add("cid", Json.Str("3")))).isLeft
      )
    },
    test("strictly decodes live_patch and phx_leave") {
      def frame(event: String, payload: Json, topic: String = "lv:root") = Json.Arr(
        Json.Str("1"),
        Json.Str("2"),
        Json.Str(topic),
        Json.Str(event),
        payload
      )

      assertTrue(
        PhoenixProtocol.decode(
          frame("live_patch", Json.Obj("url" -> Json.Str("/next")))
        ) == Right(
          PhoenixInbound.LivePatch(
            PhoenixRef.Value("1"),
            PhoenixRef.Value("2"),
            "lv:root",
            "/next"
          )
        ),
        PhoenixProtocol.decode(frame("live_patch", Json.Obj.empty)).isLeft,
        PhoenixProtocol.decode(
          frame("live_patch", Json.Obj("url" -> Json.Str("/next"), "extra" -> Json.Null))
        ).isLeft,
        PhoenixProtocol.decode(frame("live_patch", Json.Obj("url" -> Json.Num(1)))).isLeft,
        PhoenixProtocol.decode(frame("phx_leave", Json.Obj.empty)) == Right(
          PhoenixInbound.Leave(PhoenixRef.Value("1"), PhoenixRef.Value("2"), "lv:root")
        ),
        PhoenixProtocol.decode(frame("phx_leave", Json.Obj("reason" -> Json.Str("bye")))).isLeft,
        PhoenixProtocol.decode(frame("phx_leave", Json.Obj.empty, topic = "room:root")).isLeft
      )
    },
    test("builds exact reply and uncorrelated diff envelopes") {
      val joinRef = PhoenixRef.Value("4")
      val ref     = PhoenixRef.Value("5")
      val rendered = Json.Obj("s" -> Json.Arr(Json.Str("<main></main>")))
      val join = PhoenixOutput.join(joinRef, ref, "lv:root", rendered)
      val event = PhoenixOutput.event(joinRef, ref, "lv:root", Json.Obj.empty)
      val intercept = PhoenixOutput.eventReply(
        joinRef,
        ref,
        "lv:root",
        Json.Obj("0" -> Json.Str("updated")),
        Json.Obj("result" -> Json.Str("accepted"))
      )
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
        intercept.payload == Json.Obj(
          "status" -> Json.Str("ok"),
          "response" -> Json.Obj(
            "diff" -> Json.Obj(
              "0" -> Json.Str("updated"),
              "r" -> Json.Obj("result" -> Json.Str("accepted"))
            )
          )
        ),
        pushed.ref == PhoenixRef.Null,
        pushed.event == "diff"
      )
    },
    test("builds exact uncorrelated non-patch navigation envelopes") {
      val joinRef = PhoenixRef.Value("4")

      assertTrue(
        PhoenixOutput.liveRedirect(joinRef, "lv:root", "/next", "push") == PhoenixEnvelope(
          joinRef,
          PhoenixRef.Null,
          "lv:root",
          "live_redirect",
          Json.Obj("to" -> Json.Str("/next"), "kind" -> Json.Str("push"))
        ),
        PhoenixOutput.redirect(joinRef, "lv:root", "/login", Some("flash-token")) ==
          PhoenixEnvelope(
            joinRef,
            PhoenixRef.Null,
            "lv:root",
            "redirect",
            Json.Obj("to" -> Json.Str("/login"), "flash" -> Json.Str("flash-token"))
          )
      )
    },
    test("builds the root channel close event used during replacement") {
      val joinRef = PhoenixRef.Value("4")
      assertTrue(
        PhoenixOutput.close(joinRef, "lv:root") == PhoenixEnvelope(
          joinRef,
          PhoenixRef.Null,
          "lv:root",
          "phx_close",
          Json.Obj.empty
        ),
        PhoenixOutput.channelError(joinRef, "lv:root") == PhoenixEnvelope(
          joinRef,
          PhoenixRef.Null,
          "lv:root",
          "phx_error",
          Json.Obj.empty
        )
      )
    },
    test("builds exact correlated non-patch navigation replies") {
      val joinRef = PhoenixRef.Value("4")
      val ref     = PhoenixRef.Value("5")
      val diff    = Json.Obj("0" -> Json.Str("updated"))

      assertTrue(
        PhoenixOutput.eventLiveRedirect(
          joinRef,
          ref,
          "lv:root",
          "/next",
          "replace",
          Some("flash-token"),
          Some(diff)
        ).payload == Json.Obj(
          "status" -> Json.Str("ok"),
          "response" -> Json.Obj(
            "live_redirect" -> Json.Obj(
              "to"    -> Json.Str("/next"),
              "kind"  -> Json.Str("replace"),
              "flash" -> Json.Str("flash-token")
            ),
            "diff" -> diff
          )
        ),
        PhoenixOutput.eventRedirect(joinRef, ref, "lv:root", "/login").payload == Json.Obj(
          "status" -> Json.Str("ok"),
          "response" -> Json.Obj(
            "redirect" -> Json.Obj("to" -> Json.Str("/login"))
          )
        )
      )
    },
    test("builds exact join error navigation replies") {
      val joinRef = PhoenixRef.Value("4")
      val ref     = PhoenixRef.Value("4")

      assertTrue(
        PhoenixOutput.joinErrorLiveRedirect(
          joinRef,
          ref,
          "lv:root",
          "/next",
          "push"
        ).payload == Json.Obj(
          "status" -> Json.Str("error"),
          "response" -> Json.Obj(
            "live_redirect" -> Json.Obj(
              "to"   -> Json.Str("/next"),
              "kind" -> Json.Str("push")
            )
          )
        ),
        PhoenixOutput.joinErrorRedirect(
          joinRef,
          ref,
          "lv:root",
          "/login",
          Some("flash-token")
        ).payload == Json.Obj(
          "status" -> Json.Str("error"),
          "response" -> Json.Obj(
            "redirect" -> Json.Obj(
              "to"    -> Json.Str("/login"),
              "flash" -> Json.Str("flash-token")
            )
          )
        )
      )
    }
  )
