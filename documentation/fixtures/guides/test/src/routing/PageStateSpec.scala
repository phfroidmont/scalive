package routing

import java.time.{Duration, LocalDate}
import java.util.UUID

import zio.*
import zio.http.{Request, Status, URL}
import zio.test.{Live as _, live as _, *}

import scalive.*
import scalive.testing.*

object PageStateSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(throw _, identity)

  private val person = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val date   = LocalDate.of(2026, 10, 21)

  private def get(path: String) =
    for
      routes <- ZioHttp.routes(PageState.application, config)
      page   <- DisconnectedRender.run(routes, Request.get(URL.decode(path).toOption.get))
    yield page

  def spec = suite("Page state in the query")(
    test("defaults stay out of the URL and a nested record carries the way back") {
      val editor = EntryEditor(back = CalendarView(mode = CalendarMode.Month, people = List(person)))

      assertTrue(
        CalendarRoutes.calendar.location(CalendarView()).href == "/calendar",
        CalendarRoutes.calendar.location(CalendarView(mode = CalendarMode.Month)).href ==
          "/calendar?mode=month",
        EditorRoutes.newEntry.location(editor).href ==
          s"/calendar/new?back.mode=month&back.people=$person",
        EditorRoutes.afterSaving(editor, date).href ==
          s"/calendar?date=$date&mode=month&people=$person"
      )
    },
    test("a stale or hand-edited value falls back instead of failing the page") {
      for page <- get(s"/calendar?date=2026-13-01&mode=year&people=$person&people=garbage")
      yield assertTrue(page.response.status == Status.Ok, page.text.contains("Week today"))
    }
  )
end PageStateSpec
