package forms

import zio.*
import zio.test.*

import scalive.testing.ConnectedRender

object GuestListFormSpec extends ZIOSpecDefault:
  def spec = suite("Per-record form definitions")(
    test("a definition built for one event renders its rows and checks its capacity") {
      ZIO.scoped {
        for
          view     <- ConnectedRender.join(GuestListLiveView(capacity = 2))
          presence <- view.selectAll("input[type=hidden]")
          kept = presence.head.attribute("name").get
          rows = Vector("ada" -> "Ada", "bob" -> "Bob", "cleo" -> "Cleo").flatMap((key, name) =>
                   Vector(
                     kept.replace("[ada]", s"[$key]") -> presence.head.value,
                     s"guestList[guests][$key][name]" -> name
                   )
                 )
          _ <- view.changeForm("#guest-list", rows)
          errors <- view.text("#guest-errors")
        yield assertTrue(presence.size == 1, errors == "At most 2 guests")
      }
    }
  )
end GuestListFormSpec
