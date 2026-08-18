// docs:start quick-start-live-view
package quickstart

import zio.ZIO

import scalive.*

final class CounterLiveView extends LiveView[CounterLiveView.Msg, Int]:
  import CounterLiveView.Msg

  def mount(ctx: MountContext): LiveIO[Int] =
    ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg.Decrement => ZIO.succeed(model - 1)
    case Msg.Increment => ZIO.succeed(model + 1)

  override def view(model: Signal[Int]): HtmlElement[Msg] =
    mainTag(
      h1("Scalive counter"),
      button(typ          := "button", on.click(Msg.Decrement), "Decrease"),
      outputTag(aria.live := "polite", model.map(_.toString)),
      button(typ          := "button", on.click(Msg.Increment), "Increase")
    )

object CounterLiveView:
  enum Msg:
    case Decrement, Increment
// docs:end quick-start-live-view
