package scalive

import zio.Chunk
import zio.http.QueryParams
import zio.json.*
import zio.json.ast.Json

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

final case class WebSocketMessage(
  // Live session ID, auto increment defined by the client on join
  joinRef: Option[Int],
  // Message ID, global auto increment defined by the client on every message
  messageRef: Option[Int],
  // LiveView instance id
  topic: String,
  eventType: String,
  payload: WebSocketMessage.Payload):
  val meta    = WebSocketMessage.Meta(joinRef, messageRef, topic, eventType)
  def okReply =
    WebSocketMessage(
      joinRef,
      messageRef,
      topic,
      "phx_reply",
      Payload.Reply("ok", LiveResponse.Empty)
    )
object WebSocketMessage:

  final case class Meta(
    joinRef: Option[Int],
    messageRef: Option[Int],
    topic: String,
    eventType: String)

  given JsonCodec[WebSocketMessage] = JsonCodec[Json].transformOrFail(
    {
      case Json.Arr(
            Chunk(joinRef, Json.Str(messageRef), Json.Str(topic), Json.Str(eventType), payload)
          ) =>
        val payloadParsed = eventType match
          case "heartbeat" => Right(Payload.Heartbeat)
          case "phx_join"  => payload.as[Payload.Join]
          case "phx_leave" => Right(Payload.Leave)
          case "phx_close" => Right(Payload.Close)
          case "event"     => payload.as[Payload.Event]
          case s           => Left(s"Unknown event type : $s")

        payloadParsed.map(
          WebSocketMessage(
            joinRef.asString.map(_.toInt),
            Some(messageRef.toInt),
            topic,
            eventType,
            _
          )
        )
      case v => Left(s"Could not parse socket message ${v.toJson}")
    },
    m =>
      Json.Arr(
        m.joinRef.map(ref => Json.Str(ref.toString)).getOrElse(Json.Null),
        m.messageRef.map(ref => Json.Str(ref.toString)).getOrElse(Json.Null),
        Json.Str(m.topic),
        Json.Str(m.eventType),
        m.payload match
          case Payload.Heartbeat => Json.Obj.empty
          case p: Payload.Join   => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case Payload.Leave     => Json.Obj.empty
          case Payload.Close     => Json.Obj.empty
          case p: Payload.Reply  => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.Event  => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.Diff   => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
      )
  )

  enum Payload:
    case Heartbeat
    case Join(
      url: Option[String],
      redirect: Option[String],
      // params: Map[String, String],
      session: String,
      static: Option[String],
      sticky: Boolean)
    case Leave
    case Close
    case Reply(status: String, response: LiveResponse)
    case Diff(diff: scalive.Diff)
    case Event(`type`: String, event: String, value: Json)

  object Payload:
    given JsonCodec[Payload.Join]    = JsonCodec.derived
    given JsonEncoder[Payload.Reply] = JsonEncoder.derived
    given JsonCodec[Payload.Event]   = JsonCodec.derived
    given JsonEncoder[Payload.Diff]  = JsonEncoder[scalive.Diff].contramap(_.diff)

    extension (p: Payload.Event)
      def params: Map[String, String] =
        p.`type` match
          case "form" =>
            QueryParams
              .decode(
                p.value.asString.getOrElse(throw new IllegalArgumentException())
              ).map.view.mapValues(_.head).toMap

          case _ => p.value.as[Map[String, String]].getOrElse(throw new IllegalArgumentException())

    def okReply(response: LiveResponse) =
      Payload.Reply("ok", response)

  enum LiveResponse:
    case Empty
    case InitDiff(rendered: scalive.Diff)
    case Diff(diff: scalive.Diff)
  object LiveResponse:
    given JsonEncoder[LiveResponse] =
      JsonEncoder[Json].contramap {
        case Empty              => Json.Obj.empty
        case InitDiff(rendered) =>
          Json.Obj(
            "liveview_version" -> Json.Str("1.1.8"),
            "rendered"         -> rendered.toJsonAST.getOrElse(throw new IllegalArgumentException())
          )
        case Diff(diff) =>
          Json.Obj(
            "diff" -> diff.toJsonAST.getOrElse(throw new IllegalArgumentException())
          )
      }
end WebSocketMessage
