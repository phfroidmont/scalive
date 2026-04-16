import zio.stream.ZStream

import scalive.*

class StreamLiveView extends LiveView[StreamLiveView.Msg, Unit]:
  def init = ()

  def update(model: Unit) = _ => model

  def view(model: Dyn[Unit]) =
    div(
      h1("Stream placeholder"),
      p("This route exists to match upstream e2e route topology.")
    )

  def subscriptions(model: Unit) = ZStream.empty

object StreamLiveView:
  enum Msg:
    case Noop
