import zio.*
import zio.http.URL
import zio.json.*

import scalive.*
import scalive.LiveIO.given

class ErrorLiveView(connected: Boolean) extends LiveView[ErrorLiveView.Msg, ErrorLiveView.Model]:
  import ErrorLiveView.*

  override val queryCodec: LiveQueryCodec[QueryParams] = QueryParams.codec

  def mount(ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    if !connected && params.deadMountRaise then ZIO.fail(RuntimeException("boom"))
    else if connected && params.connectedMountRaise then ZIO.fail(RuntimeException("boom"))
    else
      val child = params.child || params.connectedChildMountRaise.nonEmpty
      val link  = params.connectedChildMountRaise.contains("link")
      val logs  = initialLogs(params)
      model.copy(
        child = child,
        link = link,
        connectedMountRaise = params.connectedMountRaise,
        logs = logs
      )

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.CrashChild if model.link =>
      model.copy(
        renderTime = now,
        childRenderTime = now,
        logs = Vector(
          "child error: view crashed",
          "child destroyed",
          "error: view crashed",
          "mount",
          "child mount"
        )
      )
    case Msg.CrashChild =>
      model.copy(
        childRenderTime = now,
        logs = Vector("child error: view crashed", "child mount")
      )
    case Msg.CrashMain =>
      model.copy(
        renderTime = now,
        childRenderTime = now,
        logs = Vector("child destroyed", "error: view crashed", "mount", "child mount")
      )

  def render(model: Model) =
    div(
      idAttr                       := "error-logger",
      phx.hook                     := "ErrorLogger",
      dataAttr("console-messages") := model.logs.toJson,
      p(idAttr := "render-time", "main rendered at: ", model.renderTime),
      button(phx.onClick(Msg.CrashMain), "Crash main"),
      p(cls := "if-phx-error", "Error"),
      p(cls := "if-phx-client-error", "Client Error"),
      p(cls := "if-phx-server-error", "Server Error"),
      p(cls := "if-phx-disconnected", "Disconnected"),
      p(cls := "if-phx-loading", "Loading"),
      div(
        styleAttr := "border: 1px solid lightgray; padding: 4px; margin-top: 16px;",
        Option.when(model.child)(childView(model))
      ),
      styleTag(
        rawHtml("""
          [data-phx-session] .if-phx-error { display: none; }
          [data-phx-session].phx-error > .if-phx-error { display: block; }
          [data-phx-session] .if-phx-client-error { display: none; }
          [data-phx-session].phx-client-error > .if-phx-client-error { display: block; }
          [data-phx-session] .if-phx-server-error { display: none; }
          [data-phx-session].phx-server-error > .if-phx-server-error { display: block; }
          [data-phx-session] .if-phx-disconnected { display: none; }
          [data-phx-session].phx-disconnected > .if-phx-disconnected { display: block; }
          [data-phx-session] .if-phx-loading { display: none; }
          [data-phx-session].phx-loading > .if-phx-loading { display: block; }
        """)
      ),
      Option.when(model.connectedMountRaise)(
        scriptTag(rawHtml("console.log('5 consecutive reloads. Entering failsafe mode')"))
      )
    )
end ErrorLiveView

object ErrorLiveView:
  enum Msg:
    case CrashMain
    case CrashChild

  final case class Model(
    child: Boolean = false,
    link: Boolean = false,
    connectedMountRaise: Boolean = false,
    renderTime: String = now,
    childRenderTime: String = now,
    logs: Vector[String] = Vector.empty)

  final case class QueryParams(
    deadMountRaise: Boolean = false,
    connectedMountRaise: Boolean = false,
    connectedChildMountRaise: Option[String] = None,
    child: Boolean = false)

  object QueryParams:
    val codec: LiveQueryCodec[QueryParams] =
      LiveQueryCodec.custom(
        decodeFn = url =>
          Right(
            QueryParams(
              deadMountRaise = url.queryParam("dead-mount").contains("raise"),
              connectedMountRaise = url.queryParam("connected-mount").contains("raise"),
              connectedChildMountRaise = url.queryParam("connected-child-mount-raise"),
              child = url.queryParam("child").isDefined
            )
          ),
        encodeFn = _ => Right("?")
      )

  private def childView(model: Model) =
    div(
      if model.child then "Child connected" else "Child rendered (dead)",
      p(idAttr := "child-render-time", "child rendered at: ", model.childRenderTime),
      button(phx.onClick(Msg.CrashChild), "Crash child"),
      p(cls := "if-phx-error", "Error"),
      p(cls := "if-phx-client-error", "Client Error"),
      p(cls := "if-phx-server-error", "Server Error"),
      p(cls := "if-phx-disconnected", "Disconnected"),
      p(cls := "if-phx-loading", "Loading")
    )

  private def initialLogs(params: QueryParams): Vector[String] =
    params.connectedChildMountRaise match
      case Some("2") =>
        Vector("mount", "child error: unable to join", "child error: unable to join", "child mount")
      case Some("5") =>
        Vector(
          "mount",
          "child error: unable to join",
          "child error: unable to join",
          "child error: unable to join",
          "child error: giving up",
          "child destroyed"
        )
      case Some("link") =>
        Vector(
          "mount",
          "child error: unable to join",
          "child destroyed",
          "error: view crashed",
          "mount",
          "child mount"
        )
      case _ if params.child => Vector("mount", "child mount")
      case _                 => Vector("mount")

  private def now: String =
    java.time.Instant.now().toString + ":" + java.lang.System.nanoTime().toString
end ErrorLiveView
