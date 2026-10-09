// docs:start learn-thermostat-service
package quickstart

import zio.*

trait Thermostat:
  def temperature: Task[Double]

object Thermostat:
  /** Stands in for a real sensor, which takes a moment to answer. */
  val simulated: ULayer[Thermostat] = ZLayer.succeed(
    new Thermostat:
      def temperature: Task[Double] =
        Random.nextDoubleBetween(19.0, 23.0).delay(500.millis)
  )
// docs:end learn-thermostat-service
