package scalive

import scalive.SocketMessage.LiveResponse
import scalive.SocketMessage.Payload
import scalive.SocketMessage.Payload.EventType
import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.http.codec.PathCodec
import zio.http.template.Html
import zio.json.*
import zio.json.ast.Json

import java.util.Base64
import scala.collection.mutable
import scala.util.Random

final case class LiveRoute[A, ClientEvt: JsonCodec, ServerEvt](
  path: PathCodec[A],
  liveviewBuilder: (A, Request) => LiveView[ClientEvt, ServerEvt]):
  val clientEventCodec = JsonCodec[ClientEvt]

  def toZioRoute(rootLayout: HtmlElement => HtmlElement): Route[Any, Nothing] =
    Method.GET / path -> handler { (params: A, req: Request) =>
      val lv         = liveviewBuilder(params, req)
      val id: String =
        s"phx-${Base64.getUrlEncoder().withoutPadding().encodeToString(Random().nextBytes(12))}"
      val token = Token.sign("secret", id, "")
      lv.el.syncAll()
      Response.html(
        Html.raw(
          HtmlBuilder.build(
            rootLayout(
              div(
                idAttr      := id,
                phx.main    := true,
                phx.session := token,
                lv.el
              )
            )
          )
        )
      )
    }

class LiveChannel():
  // TODO not thread safe
  private val sockets: mutable.Map[String, Socket[?, ?]] = mutable.Map.empty

  // TODO should check id isn't already present
  def join[ClientEvt: JsonCodec](id: String, token: String, lv: LiveView[ClientEvt, ?]): Diff =
    val socket = Socket(id, token, lv)
    sockets.addOne(id, socket)
    socket.diff

  // TODO handle missing id
  def event(id: String, value: String): Diff =
    val s = sockets(id)
    s.lv.handleClientEvent(
      value
        .fromJson(using s.clientEventCodec.decoder).getOrElse(throw new IllegalArgumentException())
    )
    s.diff

class LiveRouter(rootLayout: HtmlElement => HtmlElement, liveRoutes: List[LiveRoute[?, ?, ?]]):

  private val socketApp: WebSocketApp[Any] =
    val liveChannel = new LiveChannel()
    Handler.webSocket { channel =>
      channel
        .receiveAll {
          case Read(WebSocketFrame.Text(content)) =>
            for
              message <- ZIO
                           .fromEither(content.fromJson[SocketMessage])
                           .mapError(new IllegalArgumentException(_))
              reply <- handleMessage(message, liveChannel)
              _     <- channel.send(Read(WebSocketFrame.text(reply.toJson)))
            yield ()
          case _ => ZIO.unit
        }.tapErrorCause(ZIO.logErrorCause(_))
    }

  private def handleMessage(message: SocketMessage, liveChannel: LiveChannel): Task[SocketMessage] =
    val reply = message.payload match
      case Payload.Heartbeat => ZIO.succeed(Payload.Reply("ok", LiveResponse.Empty))
      case Payload.Join(url, session, static, sticky) =>
        ZIO
          .fromEither(URL.decode(url)).map(url =>
            val req = Request(url = url)
            liveRoutes
              .collectFirst { route =>
                val pathParams = route.path.decode(req.path).getOrElse(???)
                val lv         = route.liveviewBuilder(pathParams, req)
                val diff       =
                  liveChannel.join(message.topic, session, lv)(using route.clientEventCodec)
                Payload.Reply("ok", LiveResponse.InitDiff(diff))

              }.getOrElse(???)
          )
      case Payload.Event(_, event, _) =>
        val diff = liveChannel.event(message.topic, event)
        ZIO.succeed(Payload.Reply("ok", LiveResponse.Diff(diff)))
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
          case "event"     => payload.as[Payload.Event]
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
          case p: Payload.Event  => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
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
    case Event(`type`: Payload.EventType, event: String, value: Map[String, String])
  object Payload:
    given JsonCodec[Payload.Join]    = JsonCodec.derived
    given JsonEncoder[Payload.Reply] = JsonEncoder.derived
    given JsonCodec[Payload.Event]   = JsonCodec.derived

    enum EventType:
      case Click
    object EventType:
      given JsonCodec[EventType] = JsonCodec[String].transformOrFail(
        {
          case "click" => Right(Click)
          case s       => Left(s"Unsupported event type: $s")
        },
        { case Click =>
          "click"
        }
      )

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
end SocketMessage
