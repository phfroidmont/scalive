package routing

import java.time.LocalDate
import java.util.UUID

import zio.*
import zio.schema.Schema
import zio.schema.annotation.caseName
import zio.schema.derived

import scalive.*

// docs:start routing-page-state
enum CalendarMode derives Schema:
  @caseName("week") case Week
  @caseName("month") case Month

final case class CalendarView(
  date: Option[LocalDate] = None,
  mode: CalendarMode = CalendarMode.Week,
  people: List[UUID] = Nil)
    derives Schema

object CalendarRoutes:
  val calendar = (live / "calendar").query[CalendarView]
// docs:end routing-page-state

// docs:start routing-page-state-return
final case class EntryEditor(start: Option[LocalDate] = None, back: CalendarView = CalendarView())
    derives Schema

object EditorRoutes:
  val newEntry = (live / "calendar" / "new").query[EntryEditor]

  def afterSaving(editor: EntryEditor, saved: LocalDate): LiveLocation =
    CalendarRoutes.calendar.location(editor.back.copy(date = Some(saved)))
// docs:end routing-page-state-return

final class CalendarLiveView extends LiveView.Routed.Eventless[CalendarView, CalendarView]:
  def mount(view: CalendarView, ctx: MountContext) = ZIO.succeed(view)

  override def handleParams(
    model: CalendarView,
    view: CalendarView,
    url: zio.http.URL,
    ctx: ParamsContext
  ) = ZIO.succeed(view)

  def view(model: Signal[CalendarView]): HtmlElement[Nothing] =
    p(model.map(view => s"${view.mode} ${view.date.fold("today")(_.toString)}"))

object PageState:
  val application = Live.router(CalendarRoutes.calendar -> CalendarLiveView())
