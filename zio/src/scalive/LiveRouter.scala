package scalive

import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.http.codec.PathCodec
import zio.http.template.Html
import zio.json.*
import zio.json.ast.Json

final case class LiveRoute[A, Cmd](
  path: PathCodec[A],
  liveviewBuilder: (A, Request) => LiveView[Cmd]):

  def toZioRoute(rootLayout: HtmlElement => HtmlElement): Route[Any, Nothing] =
    Method.GET / path -> handler { (params: A, req: Request) =>
      val s = Socket(liveviewBuilder(params, req))
      Response.html(Html.raw(s.renderHtml(rootLayout)))
    }

class LiveRouter(rootLayout: HtmlElement => HtmlElement, liveRoutes: List[LiveRoute[?, ?]]):

  private val socketApp: WebSocketApp[Any] =
    Handler.webSocket { channel =>
      channel.receiveAll {
        case Read(WebSocketFrame.Text(content)) =>
          for
            _ <-
              content.fromJson[SocketMessage].fold(m => ZIO.logError(m), m => ZIO.log(m.toString))
            _ <- channel.send(Read(WebSocketFrame.text("bar")))
          yield ()
        case _ => ZIO.unit
      }
    }

  val routes: Routes[Any, Response] =
    Routes.fromIterable(
      liveRoutes
        .map(route => route.toZioRoute(rootLayout))
        .prepended(
          Method.GET / "live" / "websocket" -> handler(socketApp.toResponse)
        )
    )

final case class SocketMessage(
  // Live session ID, auto increment defined by the client on join
  joinRef: Option[Int],
  // Message ID, global auto increment defined by the client on every message
  messageRef: Int,
  // LiveView instance id
  topic: String,
  eventType: String,
  payload: SocketMessage.Payload)
object SocketMessage:
  given JsonCodec[SocketMessage] = JsonCodec[Json].transformOrFail(
    {
      case Json.Arr(
            Chunk(joinRef, Json.Str(messageRef), Json.Str(topic), Json.Str(eventType), payload)
          ) =>
        val payloadParsed = eventType match
          case "phx_join" => payload.as[Payload.Join]
          case s          => Left(s"Unknown event type : $s")

        payloadParsed.map(
          SocketMessage(
            joinRef.asString.map(_.toInt),
            messageRef.toInt,
            topic,
            eventType,
            _
          )
        )
      case v => Left(s"Could not parse socket message ${v.toJson}")
    },
    m =>
      Json.Arr(
        m.joinRef.map(Json.Num(_)).getOrElse(Json.Null),
        Json.Num(m.messageRef),
        Json.Str(m.topic),
        Json.Str(m.eventType),
        m.payload.match
          case p: Payload.Join => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
      )
  )

  enum Payload:
    case Join(
      url: String,
      // params: Map[String, String],
      session: String,
      static: Option[String],
      sticky: Boolean)
  object Payload:
    given JsonCodec[Payload.Join] = JsonCodec.derived
end SocketMessage
