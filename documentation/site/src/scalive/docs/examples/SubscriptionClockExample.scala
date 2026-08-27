package scalive.docs.examples

import java.time.Instant

import zio.*
import zio.stream.ZStream

import scalive.*

// docs:start subscription-clock-example
final class SubscriptionClockExample(instanceId: String)
    extends LiveView[SubscriptionClockExample.Msg, SubscriptionClockExample.Model]:
  import SubscriptionClockExample.*

  private val ClockSubscription = subscriptionKey(instanceId)

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Start =>
      if model.mode == Mode.Stopped then
        ctx.subscriptions
          .start(ClockSubscription, SubscriptionDelivery.Lossless)(ticks(1.second))
          .as(model.copy(mode = Mode.EverySecond))
      else ZIO.succeed(model)
    case Msg.Replace =>
      ctx.subscriptions
        .replace(ClockSubscription, SubscriptionDelivery.Lossless)(ticks(250.millis))
        .as(model.copy(mode = Mode.FourTimesPerSecond))
    case Msg.Cancel =>
      ctx.subscriptions.cancel(ClockSubscription).as(model.copy(mode = Mode.Stopped))
    case Msg.Reset =>
      ctx.subscriptions.cancel(ClockSubscription).as(Model())
    case Msg.Tick(at) =>
      ZIO.succeed(model.copy(lastTick = Some(at), tickCount = model.tickCount + 1))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      cls := "docs-managed-work",
      sectionTag(
        cls        := "docs-managed-work-state",
        aria.label := "Clock subscription state",
        p("Mode", strong(dataAttr("clock-mode") := "", model.map(_.mode.label))),
        p("Ticks received", strong(dataAttr("clock-count") := "", model.map(_.tickCount.toString))),
        p(
          "Latest tick",
          span(dataAttr("clock-tick") := "", model.map(_.lastTick.fold("Waiting")(_.toString)))
        )
      ),
      div(
        cls := "docs-managed-work-controls",
        button(
          typ      := "button",
          disabled := model.map(_.mode != Mode.Stopped),
          on.click(Msg.Start),
          "Start every second"
        ),
        button(typ := "button", on.click(Msg.Replace), "Replace with fast clock"),
        button(
          typ      := "button",
          disabled := model.map(_.mode == Mode.Stopped),
          on.click(Msg.Cancel),
          "Cancel clock"
        )
      )
    )
end SubscriptionClockExample

object SubscriptionClockExample:
  enum Mode(val label: String):
    case Stopped            extends Mode("Stopped")
    case EverySecond        extends Mode("Every second")
    case FourTimesPerSecond extends Mode("Four times per second")

  final case class Model(
    mode: Mode = Mode.Stopped,
    lastTick: Option[Instant] = None,
    tickCount: Int = 0)

  enum Msg:
    case Start
    case Replace
    case Cancel
    case Reset
    case Tick(at: Instant)

  private[docs] def subscriptionKey(instanceId: String): SubscriptionKey =
    SubscriptionKey(s"subscription-clock-$instanceId")

  private def ticks(every: Duration): ZStream[Any, Nothing, Msg] =
    ZStream.tick(every).mapZIO(_ => Clock.instant).map(Msg.Tick(_))
// docs:end subscription-clock-example
