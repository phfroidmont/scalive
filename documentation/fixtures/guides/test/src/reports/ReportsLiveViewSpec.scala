// docs:start services-test
package reports

import zio.*
import zio.test.*

import scalive.testing.*

object ReportsLiveViewSpec extends ZIOSpecDefault:
  private val oneReport = new Reports:
    def recent: Task[Vector[Report]] = ZIO.succeed(Vector(Report(42L, "Fixture report")))

  private val unavailable = new Reports:
    def recent: Task[Vector[Report]] = ZIO.fail(RuntimeException("database is down"))

  def spec = suite("ReportsLiveView")(
    test("lists the reports returned by the service") {
      ZIO.scoped {
        for
          view   <- ConnectedRender.join(ReportsLiveView(oneReport))
          report <- view.text("li")
        yield assertTrue(report == "Fixture report")
      }
    },
    test("shows a safe message when the service fails") {
      ZIO.scoped {
        for
          view    <- ConnectedRender.join(ReportsLiveView(unavailable))
          message <- view.text("[role=alert]")
        yield assertTrue(message == "Reports are temporarily unavailable.")
      }
    }
  )
// docs:end services-test
