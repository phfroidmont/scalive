package scalive

import scalive.SocketMessage.LiveResponse
import scalive.SocketMessage.Payload
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
      channel
        .receiveAll {
          case Read(WebSocketFrame.Text(content)) =>
            for
              message <- ZIO
                           .fromEither(content.fromJson[SocketMessage])
                           .mapError(new IllegalArgumentException(_))
              reply <- handleMessage(message)
              _     <- channel.send(Read(WebSocketFrame.text(reply.toJson)))
            yield ()
          case _ => ZIO.unit
        }.tapErrorCause(ZIO.logErrorCause(_))
    }

  def handleMessage(message: SocketMessage): Task[SocketMessage] =
    val reply = message.payload match
      case Payload.Heartbeat => ZIO.succeed(Payload.Reply("ok", LiveResponse.Empty))
      case Payload.Join(url, session, static, sticky) =>
        // TODO very rough handling
        ZIO
          .fromEither(URL.decode(url)).map(url =>
            val req = Request(url = url)
            liveRoutes
              .collectFirst { route =>
                val pathParams = route.path.decode(req.path).getOrElse(???)
                val lv         = route.liveviewBuilder(pathParams, req)
                val s          = Socket(lv)
                Payload.Reply("ok", LiveResponse.InitDiff(s.diff))

              }.getOrElse(???)
          )
      case Payload.Reply(_, _) => ZIO.die(new IllegalArgumentException())

    reply.map(SocketMessage(message.joinRef, message.messageRef, message.topic, "phx_reply", _))

  val routes: Routes[Any, Response] =
    Routes.fromIterable(
      liveRoutes
        .map(route => route.toZioRoute(rootLayout))
        .prepended(
          Method.GET / "live" / "websocket" -> handler(socketApp.toResponse)
        )
    )
end LiveRouter

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
          case "heartbeat" => Right(Payload.Heartbeat)
          case "phx_join"  => payload.as[Payload.Join]
          case s           => Left(s"Unknown event type : $s")

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
        m.joinRef.map(ref => Json.Str(ref.toString)).getOrElse(Json.Null),
        Json.Str(m.messageRef.toString),
        Json.Str(m.topic),
        Json.Str(m.eventType),
        m.payload.match
          case Payload.Heartbeat => Json.Obj.empty
          case p: Payload.Join   => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.Reply  => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
      )
  )

  enum Payload:
    case Heartbeat
    case Join(
      url: String,
      // params: Map[String, String],
      session: String,
      static: Option[String],
      sticky: Boolean)
    case Reply(status: String, response: LiveResponse)
  object Payload:
    given JsonCodec[Payload.Join]    = JsonCodec.derived
    given JsonEncoder[Payload.Reply] = JsonEncoder.derived

  enum LiveResponse:
    case Empty
    case InitDiff(rendered: scalive.Diff)
  object LiveResponse:
    given JsonEncoder[LiveResponse] =
      JsonEncoder[Json].contramap {
        case Empty              => Json.Obj.empty
        case InitDiff(rendered) =>
          Json.Obj(
            "liveview_version" -> Json.Str("1.1.8"),
            "rendered"         -> rendered.toJsonAST.getOrElse(throw new IllegalArgumentException())
          )
      }
end SocketMessage
