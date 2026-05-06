import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.URL
import zio.json.ast.Json

import scalive.*
import scalive.LiveIO.given

class ErrorLiveView extends LiveView[ErrorLiveView.Msg, ErrorLiveView.Model]:
  import ErrorLiveView.*

  override val queryCodec: LiveQueryCodec[QueryParams] = QueryParams.codec

  def mount(ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    if !ctx.connected && params.deadMountRaise then ZIO.fail(RuntimeException("boom"))
    else if ctx.connected && params.connectedMountRaise then ZIO.fail(RuntimeException("boom"))
    else model.copy(child = childBehavior(params, ctx))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.CrashMain => ZIO.fail(RuntimeException("boom"))

  def render(model: Model) =
    div(
      p(idAttr := "render-time", "main rendered at: ", model.renderTime),
      button(phx.onClick(Msg.CrashMain), "Crash main"),
      p(cls := "if-phx-error", "Error"),
      p(cls := "if-phx-client-error", "Client Error"),
      p(cls := "if-phx-server-error", "Server Error"),
      p(cls := "if-phx-disconnected", "Disconnected"),
      p(cls := "if-phx-loading", "Loading"),
      div(
        styleAttr := "border: 1px solid lightgray; padding: 4px; margin-top: 16px;",
        model.child.map(behavior =>
          liveView(
            "child",
            ChildLiveView(behavior),
            linkParentOnCrash = behavior.linkParentOnCrash
          )
        )
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
      )
    )
end ErrorLiveView

object ErrorLiveView:
  enum Msg:
    case CrashMain

  enum ChildMsg:
    case CrashChild

  final case class Model(
    child: Option[ChildBehavior] = None,
    renderTime: String = now)

  final case class ChildModel(
    connected: Boolean,
    renderTime: String = now)

  final case class ChildBehavior(
    remainingConnectedMountFailures: AtomicInteger = AtomicInteger(0),
    linkParentOnCrash: Boolean = false):
    def failNextConnectedMount: Boolean =
      remainingConnectedMountFailures.getAndUpdate(current =>
        if current > 0 then current - 1 else current
      ) > 0

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

  class ChildLiveView(behavior: ChildBehavior) extends LiveView[ChildMsg, ChildModel]:
    override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

    def mount(ctx: MountContext) =
      ChildModel(connected = ctx.connected)

    override def handleParams(model: ChildModel, params: Unit, _url: URL, ctx: ParamsContext) =
      if ctx.connected && behavior.failNextConnectedMount then ZIO.fail(RuntimeException("boom"))
      else model

    def handleMessage(model: ChildModel, ctx: MessageContext) =
      case ChildMsg.CrashChild => ZIO.fail(RuntimeException("boom"))

    def render(model: ChildModel) =
      div(
        if model.connected then "Child connected" else "Child rendered (dead)",
        p(idAttr := "child-render-time", "child rendered at: ", model.renderTime),
        button(phx.onClick(ChildMsg.CrashChild), "Crash child"),
        p(cls := "if-phx-error", "Error"),
        p(cls := "if-phx-client-error", "Client Error"),
        p(cls := "if-phx-server-error", "Server Error"),
        p(cls := "if-phx-disconnected", "Disconnected"),
        p(cls := "if-phx-loading", "Loading")
      )

  private def childBehavior(
    params: QueryParams,
    ctx: scalive.ParamsContext[Msg, Model]
  ): Option[ChildBehavior] =
    params.connectedChildMountRaise match
      case Some("link") =>
        val failOnce = if connectedRootMountCount(ctx) == 0 then 1 else 0
        Some(ChildBehavior(AtomicInteger(failOnce), linkParentOnCrash = true))
      case Some(value) =>
        Some(ChildBehavior(AtomicInteger(value.toIntOption.getOrElse(0))))
      case None if params.child => Some(ChildBehavior())
      case None                 => None

  private def connectedRootMountCount(ctx: scalive.ParamsContext[Msg, Model]): Int =
    ctx.connectParams
      .get("_mounts")
      .flatMap {
        case Json.Num(value) => Some(value.intValue)
        case Json.Str(value) => value.toIntOption
        case _               => None
      }.getOrElse(0)

  private def now: String =
    java.time.Instant.now().toString + ":" + java.lang.System.nanoTime().toString
end ErrorLiveView
