package scalive.protocol.phoenix

import zio.json.ast.Json

private[scalive] object PhoenixOutput:
  def heartbeatReply(request: PhoenixEnvelope): PhoenixEnvelope = heartbeat(request)

  def heartbeat(request: PhoenixEnvelope): PhoenixEnvelope =
    PhoenixEnvelope(request.joinRef, request.ref, request.topic, "phx_reply", ok(Json.Obj.empty))

  def heartbeat(joinRef: PhoenixRef, ref: PhoenixRef): PhoenixEnvelope =
    PhoenixEnvelope(joinRef, ref, "phoenix", "phx_reply", ok(Json.Obj.empty))

  def join(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    rendered: Json.Obj
  ): PhoenixEnvelope = PhoenixEnvelope(
    joinRef,
    ref,
    topic,
    "phx_reply",
    ok(
      Json.Obj(
        "rendered"         -> rendered,
        "liveview_version" -> Json.Str(PhoenixProtocol.LiveViewVersion)
      )
    )
  )

  def joinReply(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    rendered: Json.Obj
  ): PhoenixEnvelope = join(joinRef, ref, topic, rendered)

  def event(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    diff: Json.Obj
  ): PhoenixEnvelope =
    PhoenixEnvelope(joinRef, ref, topic, "phx_reply", ok(Json.Obj("diff" -> diff)))

  def eventReply(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    diff: Json.Obj
  ): PhoenixEnvelope = event(joinRef, ref, topic, diff)

  def error(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    response: Json = Json.Obj.empty
  ): PhoenixEnvelope = PhoenixEnvelope(
    joinRef,
    ref,
    topic,
    "phx_reply",
    Json.Obj("status" -> Json.Str("error"), "response" -> response)
  )

  def errorReply(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    response: Json = Json.Obj.empty
  ): PhoenixEnvelope = error(joinRef, ref, topic, response)

  def diff(joinRef: PhoenixRef, topic: String, value: Json.Obj): PhoenixEnvelope =
    PhoenixEnvelope(joinRef, PhoenixRef.Null, topic, "diff", value)

  def serverDiff(joinRef: PhoenixRef, topic: String, value: Json.Obj): PhoenixEnvelope =
    diff(joinRef, topic, value)

  def leave(joinRef: PhoenixRef, ref: PhoenixRef, topic: String): PhoenixEnvelope =
    PhoenixEnvelope(joinRef, ref, topic, "phx_reply", ok(Json.Obj.empty))

  def close(joinRef: PhoenixRef, topic: String): PhoenixEnvelope =
    PhoenixEnvelope(joinRef, PhoenixRef.Null, topic, "phx_close", Json.Obj.empty)

  def channelError(joinRef: PhoenixRef, topic: String): PhoenixEnvelope =
    PhoenixEnvelope(joinRef, PhoenixRef.Null, topic, "phx_error", Json.Obj.empty)

  def livePatch(
    joinRef: PhoenixRef,
    topic: String,
    to: String,
    kind: String
  ): PhoenixEnvelope =
    val payload = Json.Obj(
      "to"   -> Json.Str(to),
      "kind" -> Json.Str(kind)
    )
    PhoenixEnvelope(joinRef, PhoenixRef.Null, topic, "live_patch", payload)

  def liveRedirect(
    joinRef: PhoenixRef,
    topic: String,
    to: String,
    kind: String,
    flash: Option[String] = None
  ): PhoenixEnvelope =
    PhoenixEnvelope(
      joinRef,
      PhoenixRef.Null,
      topic,
      "live_redirect",
      withFlash(Json.Obj("to" -> Json.Str(to), "kind" -> Json.Str(kind)), flash)
    )

  def redirect(
    joinRef: PhoenixRef,
    topic: String,
    to: String,
    flash: Option[String] = None
  ): PhoenixEnvelope =
    PhoenixEnvelope(
      joinRef,
      PhoenixRef.Null,
      topic,
      "redirect",
      withFlash(Json.Obj("to" -> Json.Str(to)), flash)
    )

  def eventLiveRedirect(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    to: String,
    kind: String,
    flash: Option[String] = None,
    diff: Option[Json.Obj] = None
  ): PhoenixEnvelope =
    navigationReply(
      joinRef,
      ref,
      topic,
      "ok",
      "live_redirect",
      withFlash(Json.Obj("to" -> Json.Str(to), "kind" -> Json.Str(kind)), flash),
      diff
    )

  def eventRedirect(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    to: String,
    flash: Option[String] = None,
    diff: Option[Json.Obj] = None
  ): PhoenixEnvelope =
    navigationReply(
      joinRef,
      ref,
      topic,
      "ok",
      "redirect",
      withFlash(Json.Obj("to" -> Json.Str(to)), flash),
      diff
    )

  def joinErrorLiveRedirect(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    to: String,
    kind: String,
    flash: Option[String] = None
  ): PhoenixEnvelope =
    navigationReply(
      joinRef,
      ref,
      topic,
      "error",
      "live_redirect",
      withFlash(Json.Obj("to" -> Json.Str(to), "kind" -> Json.Str(kind)), flash),
      None
    )

  def joinErrorRedirect(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    to: String,
    flash: Option[String] = None
  ): PhoenixEnvelope =
    navigationReply(
      joinRef,
      ref,
      topic,
      "error",
      "redirect",
      withFlash(Json.Obj("to" -> Json.Str(to)), flash),
      None
    )

  private def withFlash(payload: Json.Obj, flash: Option[String]): Json.Obj =
    flash.fold(payload)(value => payload.add("flash", Json.Str(value)))

  private def navigationReply(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    status: String,
    navigation: String,
    payload: Json.Obj,
    diff: Option[Json.Obj]
  ): PhoenixEnvelope =
    val navigationResponse = Json.Obj(navigation -> payload)
    val response = diff.fold(navigationResponse)(value => navigationResponse.add("diff", value))
    PhoenixEnvelope(
      joinRef,
      ref,
      topic,
      "phx_reply",
      Json.Obj("status" -> Json.Str(status), "response" -> response)
    )

  private def ok(response: Json.Obj): Json.Obj =
    Json.Obj("status" -> Json.Str("ok"), "response" -> response)
end PhoenixOutput
