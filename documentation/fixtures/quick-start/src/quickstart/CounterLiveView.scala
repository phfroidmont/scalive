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

  def render(model: Int): HtmlElement[Msg] =
    mainTag(
      h1("Scalive counter"),
      button(typ          := "button", on.click(Msg.Decrement), "Decrease"),
      outputTag(aria.live := "polite", model.toString),
      button(typ          := "button", on.click(Msg.Increment), "Increase")
    )

object CounterLiveView:
  enum Msg:
    case Decrement, Increment
