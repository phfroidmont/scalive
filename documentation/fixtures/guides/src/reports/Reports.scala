// docs:start services-service
package reports

import zio.*

final case class Report(id: Long, title: String)

trait Reports:
  def recent: Task[Vector[Report]]

object Reports:
  val inMemory: ULayer[Reports] = ZLayer.succeed(
    new Reports:
      def recent: Task[Vector[Report]] = ZIO.succeed(
        Vector(Report(1L, "Daily sales"), Report(2L, "Open incidents"))
      )
  )
// docs:end services-service
