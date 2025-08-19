package scalive

import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.http.codec.PathCodec
import zio.http.template.Html
import zio.json.JsonCodec

final case class LiveRoute[A, Cmd](
  path: PathCodec[A],
  liveviewBuilder: (A, Request) => LiveView[Cmd]):

  def toZioRoute(rootLayout: HtmlElement => HtmlElement): Route[Any, Nothing] =
    Method.GET / path -> handler { (params: A, req: Request) =>
      val s = Socket(liveviewBuilder(params, req))
      Response.html(Html.raw(s.renderHtml(rootLayout)))
    }

// 1 Request to live route
// 2 Create live view with stateless token containing user id if connected, http params, live view id
// 3 Response with HTML and token
// 4 Websocket connection with token
// 5 Recreate exact same liveview as before using token data
class LiveRouter(rootLayout: HtmlElement => HtmlElement, liveRoutes: List[LiveRoute[?, ?]]):

  private val socketApp: WebSocketApp[Any] =
    Handler.webSocket { channel =>
      channel.receiveAll {
        case Read(WebSocketFrame.Text(content)) =>
          // content.fromJson[SocketMessage]
          channel.send(Read(WebSocketFrame.text("bar")))
        case _ => ZIO.unit
      }
    }

  val routes: Routes[Any, Response] =
    Routes.fromIterable(
      liveRoutes
        .map(route => route.toZioRoute(rootLayout)).prepended(
          Method.GET / "live" / "ws" -> handler(socketApp.toResponse)
        )
    )

final case class SocketMessage(
  // Live session ID, auto increment defined by the client on join
  joinRef: Option[Int],
  // Message ID, global auto increment defined by the client on every message
  messageRef: Int,
  // LiveView instance id
  topic: String,
  payload: String)
    derives JsonCodec
