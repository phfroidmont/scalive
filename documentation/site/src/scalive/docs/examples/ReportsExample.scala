package scalive.docs.examples

import zio.*

import scalive.*

// docs:start reports-service
final case class Report(id: Long, title: String, summary: String)

trait Reports:
  def recent: Task[Vector[Report]]

object Reports:
  val fixtures = Vector(
    Report(1L, "Daily sales", "Revenue increased 8% over yesterday."),
    Report(2L, "Open incidents", "3 incidents need an owner.")
  )

  val inMemory: ULayer[Reports] = ZLayer.succeed(
    new Reports:
      def recent: Task[Vector[Report]] = ZIO.succeed(fixtures)
  )
// docs:end reports-service

private[docs] val reportsFixtureService: Reports = new Reports:
  def recent: Task[Vector[Report]] = ZIO.succeed(Reports.fixtures)

// docs:start reports-liveview
final class ReportsExample(reports: Reports)
    extends LiveView[ReportsExample.Msg, ReportsExample.Model]:
  import ReportsExample.*

  def mount(ctx: MountContext): LiveIO[Model] =
    load

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Select(report) if model.contains(report) =>
      ZIO.succeed(model.select(report))
    case Msg.Select(_)      => ZIO.succeed(model)
    case Msg.ResetSelection => ZIO.succeed(model.resetSelection)
    case Msg.Refresh        => load

  def render(model: Model): HtmlElement[Msg] =
    div(
      cls := "docs-reports-example",
      div(
        cls                          := "docs-reports-toolbar",
        dataAttr("example-controls") := "",
        div(
          cls := "docs-reports-toolbar-copy",
          strong("Reports workspace"),
          span("Service-backed data with connection-local selection.")
        ),
        div(
          cls := "docs-reports-toolbar-actions",
          button(
            cls := "docs-reports-refresh",
            typ := "button",
            on.click(Msg.Refresh),
            "Refresh reports"
          ),
          a(
            cls  := "docs-reports-lab-link",
            href := "/examples/service-injection/lab",
            "Open layer-backed route"
          )
        )
      ),
      model match
        case Model.Loaded(reports, selected) =>
          sectionTag(
            cls        := "docs-reports-console",
            aria.label := "Reports",
            navTag(
              cls        := "docs-reports-picker",
              aria.label := "Available reports",
              p(cls := "docs-reports-picker-label", "Available reports"),
              reports.map { report =>
                button(
                  cls                   := "docs-report-option",
                  typ                   := "button",
                  dataAttr("report-id") := report.id.toString,
                  dataAttr("selected")  := (report == selected).toString,
                  aria.pressed          := (report == selected).toString,
                  on.click(Msg.Select(report)),
                  span(cls := "docs-report-option-title", report.title),
                  span(
                    cls := "docs-report-option-state",
                    if report == selected then "Selected" else "View report"
                  )
                )
              }
            ),
            articleTag(
              cls                     := "docs-report-detail",
              dataAttr("report-card") := selected.id.toString,
              p(cls := "docs-report-detail-label", "Selected report"),
              h4(
                cls                         := "docs-report-detail-title",
                dataAttr("report-selected") := "",
                selected.title
              ),
              p(
                cls                        := "docs-report-detail-summary",
                dataAttr("report-summary") := "",
                selected.summary
              ),
              footerTag(
                span("Report ID"),
                code(s"#${selected.id}")
              )
            )
          )
        case Model.Empty =>
          div(
            cls                       := "docs-reports-status docs-reports-status-empty",
            dataAttr("reports-empty") := "",
            strong("No reports are available."),
            span("Refresh to ask the service again.")
          )
        case Model.Failed =>
          div(
            cls                        := "docs-reports-status docs-reports-status-failed",
            dataAttr("reports-failed") := "",
            strong("Reports are temporarily unavailable."),
            span("Refresh to retry the service request.")
          )
    )

  private def load: UIO[Model] =
    reports.recent
      .map(Model.from)
      .catchAll(_ => ZIO.succeed(Model.Failed))
end ReportsExample

object ReportsExample:
  val LabRoute = "/examples/service-injection/lab"

  val layer: URLayer[Reports, ReportsExample] =
    ZLayer.fromFunction(ReportsExample.apply)

  val route =
    (live / "examples" / "service-injection" / "lab") -> layer

  enum Model:
    case Loaded(reports: Vector[Report], selected: Report)
    case Empty
    case Failed

    def contains(report: Report): Boolean = this match
      case Loaded(reports, _) => reports.contains(report)
      case _                  => false

    def select(report: Report): Model = this match
      case Loaded(reports, _) => Loaded(reports, report)
      case other              => other

    def resetSelection: Model = this match
      case Loaded(reports, _) => Loaded(reports, reports.head)
      case other              => other

  object Model:
    def from(reports: Vector[Report]): Model =
      reports.headOption.fold[Model](Empty)(Loaded(reports, _))

  enum Msg:
    case Select(report: Report)
    case ResetSelection
    case Refresh
end ReportsExample
// docs:end reports-liveview

private[docs] object ReportsExamplePreview:
  def apply(): ReportsExample =
    new ReportsExample(reportsFixtureService)
