package scalive.docs.examples

import zio.ZIO

import scalive.*

// docs:start counter-example
final private[docs] class CounterExample extends LiveView[CounterExample.Msg, CounterExample.Model]:
  import CounterExample.*

  def mount(ctx: MountContext): LiveIO[Model] =
    ZIO.succeed(Model(count = 0))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Decrement => ZIO.succeed(model.copy(count = model.count - 1))
    case Msg.Increment => ZIO.succeed(model.copy(count = model.count + 1))
    case Msg.Reset     => ZIO.succeed(model.copy(count = 0))

  def render(model: Model): HtmlElement[Msg] =
    div(
      cls := "docs-counter",
      p(
        cls         := "docs-counter-status",
        role        := "status",
        aria.live   := "polite",
        aria.atomic := true,
        span("Count"),
        strong(cls := "docs-counter-value", model.count.toString)
      ),
      fieldSet(
        dataAttr("example-controls") := "",
        legend(cls := "docs-visually-hidden", "Counter controls"),
        button(
          typ := "button",
          on.click(Msg.Decrement),
          "Decrease"
        ),
        button(
          typ := "button",
          on.click(Msg.Reset),
          "Reset"
        ),
        button(
          typ := "button",
          cls := "docs-counter-increment",
          on.click(Msg.Increment),
          "Increase"
        )
      )
    )
end CounterExample

private[docs] object CounterExample:
  enum Msg:
    case Decrement, Increment, Reset

  final case class Model(count: Int)
// docs:end counter-example
