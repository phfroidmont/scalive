package bindings

import zio.*
import zio.test.*

import scalive.testing.ConnectedRender

object InvoiceFiltersSpec extends ZIOSpecDefault:
  def spec = suite("Small forms")(
    test("a filter form and a single select decode into typed messages") {
      ZIO.scoped {
        for
          view <- ConnectedRender.join(InvoiceListLiveView())
          _    <- view.changeForm(
                 "#invoice-filters",
                 Vector(
                   "status"        -> "paid",
                   "practitioners" -> "Ada",
                   "practitioners" -> "Bob",
                   "days"          -> "not a number"
                 )
               )
          _     <- view.changeForm("#page-size", Vector("size" -> "50"))
          shown <- view.text("#shown")
        yield assertTrue(shown == "(InvoiceFilters(Some(Paid),Set(Ada, Bob),30),50)")
      }
    }
  )
end InvoiceFiltersSpec
