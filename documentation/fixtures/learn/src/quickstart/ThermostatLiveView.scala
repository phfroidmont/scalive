// docs:start learn-thermostat-live-view
package quickstart

import zio.*
import zio.stream.ZStream

import scalive.*

final class ThermostatLiveView(thermostat: Thermostat)
    extends LiveView[ThermostatLiveView.Msg, ThermostatLiveView.Model]:
  import ThermostatLiveView.*

  // docs:start learn-thermostat-mount
  def mount(ctx: MountContext): Task[Model] =
    ctx.connection match
      case Connection.Disconnected =>
        ZIO.succeed(Model(reading = AsyncValue.loading))
      case Connection.Connected(connected) =>
        for
          _ <- connected.async.start(Reading)(thermostat.temperature)(Msg.Measured(_))
          _ <- connected.subscriptions.start(RefreshTimer, SubscriptionDelivery.Latest)(
                 ZStream.fromSchedule(Schedule.spaced(RefreshEvery)).as(Msg.Refresh)
               )
        yield Model(reading = AsyncValue.loading)
  // docs:end learn-thermostat-mount

  // docs:start learn-thermostat-handle-message
  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Refresh =>
      ctx.async
        .start(Reading)(thermostat.temperature)(Msg.Measured(_))
        .as(model.copy(reading = model.reading.loading()))
    case Msg.Measured(result) =>
      ZIO.succeed(model.copy(reading = model.reading.updated(result)))
  // docs:end learn-thermostat-handle-message

  // docs:start learn-thermostat-view
  def view(model: Signal[Model]): HtmlElement[Msg] =
    val reading = model.map(_.reading)
    mainTag(
      h1("Thermostat"),
      p("Temperature: ", outputTag(reading.map(_.valueOption.fold("unknown")(celsius)))),
      reading.map(_.isLoading).when(p("Reading the sensor…")),
      reading.map(failed).when(p(role := "alert", "The sensor did not answer.")),
      button(on.click(Msg.Refresh), "Refresh")
    )
  // docs:end learn-thermostat-view
end ThermostatLiveView

object ThermostatLiveView:
  // docs:start learn-thermostat-model
  final case class Model(reading: AsyncValue[Double])

  enum Msg:
    case Refresh
    case Measured(result: LiveAsyncResult[Double])

  private val Reading      = AsyncKey[Double]("reading")
  private val RefreshTimer = SubscriptionKey("refresh-timer")
  private val RefreshEvery = 10.seconds
  // docs:end learn-thermostat-model

  private def celsius(value: Double): String = f"$value%.1f °C"

  private def failed(reading: AsyncValue[Double]): Boolean = reading match
    case AsyncValue.Failed(_, _) => true
    case _                       => false
// docs:end learn-thermostat-live-view
