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

  def mount(ctx: MountContext): Task[Model] =
    load

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Select(report) if model.contains(report) =>
      ZIO.succeed(model.select(report))
    case Msg.Select(_)      => ZIO.succeed(model)
    case Msg.ResetSelection => ZIO.succeed(model.resetSelection)
    case Msg.Refresh        => load

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val loaded = model.map {
      case Model.Loaded(reports, selected) => Some((reports, selected))
      case _                               => None
    }
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
      loaded.option { loaded =>
        val reports  = loaded.map(_._1)
        val selected = loaded.map(_._2)
        sectionTag(
          cls        := "docs-reports-console",
          aria.label := "Reports",
          navTag(
            cls        := "docs-reports-picker",
            aria.label := "Available reports",
            p(cls := "docs-reports-picker-label", "Available reports"),
            reports.splitBy(_.id) { (_, report) =>
              val isSelected = report.zip(selected).map { case (report, selected) =>
                report == selected
              }
              button(
                cls                   := "docs-report-option",
                typ                   := "button",
                dataAttr("report-id") := report.map(_.id.toString),
                dataAttr("selected")  := isSelected.map(_.toString),
                aria.pressed          := isSelected.map(_.toString),
                on.click(report.map(Msg.Select(_))),
                span(cls := "docs-report-option-title", report.map(_.title)),
                span(
                  cls := "docs-report-option-state",
                  isSelected.map(if _ then "Selected" else "View report")
                )
              )
            }
          ),
          articleTag(
            cls                     := "docs-report-detail",
            dataAttr("report-card") := selected.map(_.id.toString),
            p(cls := "docs-report-detail-label", "Selected report"),
            h4(
              cls                         := "docs-report-detail-title",
              dataAttr("report-selected") := "",
              selected.map(_.title)
            ),
            p(
              cls                        := "docs-report-detail-summary",
              dataAttr("report-summary") := "",
              selected.map(_.summary)
            ),
            footerTag(
              span("Report ID"),
              code(selected.map(report => s"#${report.id}"))
            )
          )
        )
      },
      model
        .map(_ == Model.Empty).when(
          div(
            cls                       := "docs-reports-status docs-reports-status-empty",
            dataAttr("reports-empty") := "",
            strong("No reports are available."),
            span("Refresh to ask the service again.")
          )
        ),
      model
        .map(_ == Model.Failed).when(
          div(
            cls                        := "docs-reports-status docs-reports-status-failed",
            dataAttr("reports-failed") := "",
            strong("Reports are temporarily unavailable."),
            span("Refresh to retry the service request.")
          )
        )
    )
  end view

  private def load: UIO[Model] =
    reports.recent
      .map(Model.from)
      .catchAll(_ => ZIO.succeed(Model.Failed))
end ReportsExample

object ReportsExample:
  val LabRoute = "/examples/service-injection/lab"

  val route =
    (live / "examples" / "service-injection" / "lab")
      .from((_, _, reports: Reports) => ReportsExample(reports))

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
