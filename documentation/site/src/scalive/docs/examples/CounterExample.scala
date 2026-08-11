package scalive.docs.examples

import zio.ZIO

import scalive.*

// docs:start counter-example
final private[docs] class CounterExample extends LiveView[CounterExample.Msg, CounterExample.Model]:
  import CounterExample.*

  def mount(ctx: MountContext): LiveIO[Model] =
    ZIO.succeed(Model(count = 0))

  def handleMessage(model: Model, ctx: MessageContext) =
    case message @ Msg.Decrement =>
      ZIO.succeed(model.copy(count = model.count - 1, lastMessage = Some(message)))
    case message @ Msg.Increment =>
      ZIO.succeed(model.copy(count = model.count + 1, lastMessage = Some(message)))
    case message @ Msg.Reset =>
      ZIO.succeed(model.copy(count = 0, lastMessage = Some(message)))

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
          cls := "docs-counter-secondary",
          on.click(Msg.Decrement),
          "Decrease"
        ),
        button(
          typ := "button",
          cls := "docs-counter-secondary",
          on.click(Msg.Reset),
          "Reset"
        ),
        button(
          typ := "button",
          cls := "docs-counter-increment",
          on.click(Msg.Increment),
          "Increase"
        )
      ),
      model.lastMessage match
        case None =>
          p(cls := "docs-counter-flow docs-counter-flow-pending", "Waiting for a browser event")
        case Some(message) =>
          p(
            cls := "docs-counter-flow",
            span("browser event"),
            span(cls := "docs-counter-flow-arrow", aria.hidden := true, "\u2192"),
            code(s"Msg.$message"),
            span(cls := "docs-counter-flow-arrow", aria.hidden := true, "\u2192"),
            span(s"server state: ${model.count}"),
            span(cls := "docs-counter-flow-arrow", aria.hidden := true, "\u2192"),
            span("HTML diff")
          )
    )
end CounterExample

private[docs] object CounterExample:
  enum Msg:
    case Decrement, Increment, Reset

  final case class Model(count: Int, lastMessage: Option[Msg] = None)
// docs:end counter-example
