package scalive.docs.examples

import zio.*
import zio.stream.ZStream

import scalive.*

// docs:start component-subscriptions-example
object SubscriptionTickerComponent
    extends LiveComponent[
      SubscriptionTickerComponent.Props,
      SubscriptionTickerComponent.Msg,
      SubscriptionTickerComponent.Model
    ]:
  final case class Props(id: String, title: String, resetEpoch: Int)

  enum Mode(val label: String):
    case Stopped            extends Mode("Stopped")
    case EverySecond        extends Mode("Every second")
    case FourTimesPerSecond extends Mode("Four times per second")

  final case class Model(
    mode: Mode = Mode.EverySecond,
    ticks: Int = 0,
    resetEpoch: Int = 0)

  enum Msg:
    case Start
    case Replace
    case Cancel
    case Tick

  // Both component instances deliberately reuse this key. The runtime namespace
  // is the exact component instance, so their registrations do not collide.
  private val LocalTicks = SubscriptionKey("component-local-ticks")

  def mount(props: Props, ctx: MountContext): Task[Model] =
    ctx.connection match
      case Connection.Disconnected         => ZIO.succeed(Model(resetEpoch = props.resetEpoch))
      case Connection.Connected(connected) =>
        connected.subscriptions
          .start(LocalTicks, SubscriptionDelivery.Lossless)(ticks(1.second))
          .as(Model(resetEpoch = props.resetEpoch))

  override def update(props: Props, model: Model, ctx: UpdateContext): Task[Model] =
    if props.resetEpoch == model.resetEpoch then ZIO.succeed(model)
    else
      ctx.connection match
        case Connection.Disconnected         => ZIO.succeed(Model(resetEpoch = props.resetEpoch))
        case Connection.Connected(connected) =>
          connected.subscriptions
            .replace(LocalTicks, SubscriptionDelivery.Lossless)(ticks(1.second))
            .as(Model(resetEpoch = props.resetEpoch))

  def handleMessage(props: Props, model: Model, ctx: MessageContext) =
    case Msg.Start =>
      if model.mode == Mode.Stopped then
        ctx.subscriptions
          .start(LocalTicks, SubscriptionDelivery.Lossless)(ticks(1.second))
          .as(model.copy(mode = Mode.EverySecond))
      else ZIO.succeed(model)
    case Msg.Replace =>
      ctx.subscriptions
        .replace(LocalTicks, SubscriptionDelivery.Lossless)(ticks(250.millis))
        .as(model.copy(mode = Mode.FourTimesPerSecond))
    case Msg.Cancel =>
      ctx.subscriptions.cancel(LocalTicks).as(model.copy(mode = Mode.Stopped))
    case Msg.Tick =>
      ZIO.succeed(model.copy(ticks = model.ticks + 1))

  def view(props: Signal[Props], model: Signal[Model], self: ComponentRef[Msg]) =
    articleTag(
      cls                                := "docs-vote-card",
      dataAttr("subscription-component") := props.map(_.id),
      headerTag(
        div(
          p(cls := "docs-vote-kicker", "Component-owned subscription"),
          h4(props.map(_.title))
        ),
        code(dataAttr("component-id") := props.map(_.id), props.map(_.id))
      ),
      div(
        cls := "docs-vote-count-row",
        div(
          cls := "docs-vote-metric",
          span("Ticks"),
          strong(dataAttr("component-ticks") := "", model.map(_.ticks.toString))
        ),
        p(dataAttr("component-mode") := "", model.map(_.mode.label))
      ),
      div(
        cls := "docs-vote-actions",
        button(
          typ      := "button",
          disabled := model.map(_.mode != Mode.Stopped),
          phx.target(self),
          on.click.to(self)(Msg.Start),
          "Start local ticks"
        ),
        button(
          typ := "button",
          phx.target(self),
          on.click.to(self)(Msg.Replace),
          "Replace local ticks"
        ),
        button(
          typ      := "button",
          disabled := model.map(_.mode == Mode.Stopped),
          phx.target(self),
          on.click.to(self)(Msg.Cancel),
          "Cancel local ticks"
        )
      )
    )

  private def ticks(every: Duration): ZStream[Any, Nothing, Msg] =
    ZStream.repeatZIO(ZIO.sleep(every).as(Msg.Tick))
end SubscriptionTickerComponent

final class ComponentSubscriptionsExample
    extends LiveView[ComponentSubscriptionsExample.Msg, ComponentSubscriptionsExample.Model]:
  import ComponentSubscriptionsExample.*

  def mount(ctx: MountContext): Task[Model] = ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ToggleFirst => ZIO.succeed(model.copy(firstVisible = !model.firstVisible))
    case Msg.Reset       =>
      ZIO.succeed(model.copy(firstVisible = true, resetEpoch = model.resetEpoch + 1))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      cls := "docs-voting-components",
      sectionTag(
        cls        := "docs-vote-parent",
        aria.label := "Parent component visibility",
        div(
          p(cls := "docs-vote-kicker", "Parent-owned visibility"),
          p(
            dataAttr("first-visibility") := "",
            model.map(value =>
              if value.firstVisible then "First ticker is rendered."
              else "First ticker is removed."
            )
          )
        ),
        button(
          typ := "button",
          on.click(Msg.ToggleFirst),
          model.map(value =>
            if value.firstVisible then "Remove first ticker" else "Reinsert first ticker"
          )
        )
      ),
      div(
        cls := "docs-vote-grid",
        model
          .map(_.firstVisible).when(
            div(
              FirstTicker.render(
                model.map(value =>
                  SubscriptionTickerComponent
                    .Props("first-ticker", "First ticker", value.resetEpoch)
                )
              )
            )
          ),
        SecondTicker.render(
          model.map(value =>
            SubscriptionTickerComponent.Props("second-ticker", "Second ticker", value.resetEpoch)
          )
        )
      )
    )
end ComponentSubscriptionsExample

object ComponentSubscriptionsExample:
  final case class Model(firstVisible: Boolean = true, resetEpoch: Int = 0)

  enum Msg:
    case ToggleFirst
    case Reset

  private val FirstTicker  = component(SubscriptionTickerComponent, "first-ticker")
  private val SecondTicker = component(SubscriptionTickerComponent, "second-ticker")
// docs:end component-subscriptions-example
