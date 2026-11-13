import zio.ZIO

import scalive.*

/** Messages pushed by `JS` commands that the client runs outside a DOM event, on mount and connect.
  */
class LifecyclePushLiveView
    extends LiveView[LifecyclePushLiveView.Msg, LifecyclePushLiveView.Model]:
  import LifecyclePushLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model(mounted = 0, connected = 0, panelShown = false, panelMounted = 0))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Mounted      => ZIO.succeed(model.copy(mounted = model.mounted + 1))
    case Msg.Connected    => ZIO.succeed(model.copy(connected = model.connected + 1))
    case Msg.ShowPanel    => ZIO.succeed(model.copy(panelShown = true))
    case Msg.PanelMounted => ZIO.succeed(model.copy(panelMounted = model.panelMounted + 1))

  override def view(model: Signal[Model]) =
    div(
      div(idAttr := "on-mount", dom.onMount(JS.push(Msg.Mounted)), model.map(_.mounted.toString)),
      div(
        idAttr := "on-connect",
        connection.onConnect(JS.push(Msg.Connected)),
        model.map(_.connected.toString)
      ),
      button(idAttr := "show-panel", on.click(Msg.ShowPanel), "Show panel"),
      model.map(_.panelShown).when(div(idAttr := "panel", dom.onMount(JS.push(Msg.PanelMounted)))),
      div(idAttr := "panel-mounted", model.map(_.panelMounted.toString))
    )

object LifecyclePushLiveView:
  final case class Model(mounted: Int, connected: Int, panelShown: Boolean, panelMounted: Int)

  enum Msg:
    case Mounted
    case Connected
    case ShowPanel
    case PanelMounted
