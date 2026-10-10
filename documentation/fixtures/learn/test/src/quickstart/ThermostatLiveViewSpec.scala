// docs:start learn-thermostat-test
package quickstart

import zio.*
import zio.test.*

import scalive.testing.*

object ThermostatLiveViewSpec extends ZIOSpecDefault:
  private def fixedSensor(current: Ref[Double]) = new Thermostat:
    def temperature: Task[Double] = current.get

  private val brokenSensor = new Thermostat:
    def temperature: Task[Double] = ZIO.fail(RuntimeException("sensor offline"))

  def spec = suite("ThermostatLiveView")(
    test("shows the reading after connecting and refreshes on request") {
      ZIO.scoped {
        // docs:start testing-await-html
        for
          current <- Ref.make(20.0)
          view    <- ConnectedRender.join(ThermostatLiveView(fixedSensor(current)))
          _       <- view.awaitHtml("first reading", 5.seconds)(_.contains("20.0 °C"))
          _       <- current.set(21.5)
          _       <- view.clickButton("Refresh")
          _       <- view.awaitHtml("refreshed reading", 5.seconds)(_.contains("21.5 °C"))
        yield assertCompletes
        // docs:end testing-await-html
      }
    },
    test("tells the visitor when the sensor does not answer") {
      ZIO.scoped {
        for
          view  <- ConnectedRender.join(ThermostatLiveView(brokenSensor))
          _     <- view.awaitHtml("failed reading", 5.seconds)(_.contains("role=\"alert\""))
          alert <- view.text("[role=alert]")
        yield assertTrue(alert == "The sensor did not answer.")
      }
    }
  ) @@ TestAspect.withLiveClock
// docs:end learn-thermostat-test
