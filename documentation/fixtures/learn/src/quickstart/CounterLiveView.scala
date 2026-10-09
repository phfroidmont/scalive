// docs:start learn-counter
package quickstart

import zio.{Task, ZIO}

import scalive.*

final class CounterLiveView extends LiveView[CounterLiveView.Msg, CounterLiveView.Model]:
  import CounterLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(count = 0))

  // docs:start learn-counter-handle-message
  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Decrement => ZIO.succeed(model.copy(count = model.count - 1))
    case Msg.Increment => ZIO.succeed(model.copy(count = model.count + 1))
    case Msg.Reset     => ZIO.succeed(Model(count = 0))
  // docs:end learn-counter-handle-message

  // docs:start learn-counter-view
  def view(model: Signal[Model]): HtmlElement[Msg] =
    mainTag(
      h1("Scalive counter"),
      button(on.click(Msg.Decrement), "Decrease"),
      outputTag(aria.live := "polite", model.map(_.count.toString)),
      button(on.click(Msg.Increment), "Increase"),
      button(on.click(Msg.Reset), "Reset")
    )
  // docs:end learn-counter-view

object CounterLiveView:
  // docs:start learn-counter-model
  final case class Model(count: Int)

  enum Msg:
    case Decrement, Increment, Reset
  // docs:end learn-counter-model
// docs:end learn-counter
