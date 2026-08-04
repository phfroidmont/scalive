package scalive.examples.processing

import java.time.Instant

import zio.*
import zio.stream.ZStream

import scalive.*

final class ClockLiveView extends LiveView[ClockLiveView.Msg, ClockLiveView.Model]:
  import ClockLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Start =>
      if model.mode == Mode.Stopped then
        ctx.subscriptions
          .start(ClockSubscription)(ticks(1.second))
          .as(model.copy(mode = Mode.EverySecond))
      else ZIO.succeed(model)
    case Msg.Replace =>
      ctx.subscriptions
        .replace(ClockSubscription)(ticks(250.millis))
        .as(model.copy(mode = Mode.FourTimesPerSecond))
    case Msg.Cancel =>
      ctx.subscriptions.cancel(ClockSubscription).as(model.copy(mode = Mode.Stopped))
    case Msg.Tick(at) =>
      ZIO.succeed(model.copy(lastTick = Some(at), tickCount = model.tickCount + 1))

  def render(model: Model) =
    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Subscriptions"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Managed clock stream"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "One typed SubscriptionKey starts, replaces, and cancels an environment-free ZStream. The LiveView never forks a fiber itself."
        )
      ),
      div(
        cls := "grid gap-6 md:grid-cols-[minmax(0,1fr)_auto]",
        sectionTag(
          cls := "rounded-box border border-base-300 bg-base-100 p-7 shadow-sm",
          p(
            cls := "text-sm font-semibold uppercase tracking-[0.14em] text-base-content/50",
            "Latest tick"
          ),
          p(
            cls := "mt-3 break-all font-mono text-2xl font-semibold",
            model.lastTick.fold("Waiting for a stream...")(_.toString)
          ),
          div(
            cls := "mt-5 flex flex-wrap gap-2 text-sm",
            span(cls := "badge badge-neutral badge-outline", model.mode.label),
            span(cls := "badge badge-ghost", s"${model.tickCount} ticks received")
          )
        ),
        div(
          cls := "flex flex-col gap-3 md:w-60",
          button(
            typ      := "button",
            cls      := "btn btn-primary",
            disabled := (model.mode != Mode.Stopped),
            on.click(Msg.Start),
            "Start: every second"
          ),
          button(
            typ := "button",
            cls := "btn btn-outline",
            on.click(Msg.Replace),
            "Replace: 4 times/second"
          ),
          button(
            typ      := "button",
            cls      := "btn btn-ghost",
            disabled := (model.mode == Mode.Stopped),
            on.click(Msg.Cancel),
            "Cancel stream"
          )
        )
      )
    )
end ClockLiveView

object ClockLiveView:
  private val ClockSubscription = SubscriptionKey("example-clock")

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
    case Tick(at: Instant)

  private def ticks(every: Duration): ZStream[Any, Nothing, Msg] =
    ZStream.tick(every).mapZIO(_ => Clock.instant).map(Msg.Tick(_))
