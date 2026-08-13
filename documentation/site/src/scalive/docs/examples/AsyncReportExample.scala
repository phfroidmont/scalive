package scalive.docs.examples

import zio.*

import scalive.*

// docs:start async-report-example
final class AsyncReportExample(instanceId: String)
    extends LiveView[AsyncReportExample.Msg, AsyncReportExample.Model]:
  import AsyncReportExample.*

  private val ReportTask = reportKey(instanceId)

  def mount(ctx: MountContext): LiveIO[Model] =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.RunSuccess => start(model, ctx, successfulReport)
    case Msg.RunFailure => start(model, ctx, failingReport)
    case Msg.Replace    => start(model, ctx, replacementReport)
    case Msg.Retry      => start(model, ctx, retryReport)
    case Msg.Cancel     =>
      if model.report.isLoading then
        ctx.async.cancel(ReportTask, Some("Cancelled by the user")).as(model)
      else ZIO.succeed(model)
    case Msg.Reset =>
      ctx.async.cancel(ReportTask, Some("Example reset")).as(Model())
    case Msg.ReportCompleted(LiveAsyncResult.Cancelled(_)) if model.report == AsyncValue.Empty =>
      ZIO.succeed(model)
    case Msg.ReportCompleted(result) =>
      ZIO.succeed(model.copy(report = model.report.updated(result)))

  def render(model: Model): HtmlElement[Msg] =
    div(
      cls := "docs-managed-work",
      div(
        cls := "docs-managed-work-controls",
        button(typ := "button", on.click(Msg.RunSuccess), "Run successful report"),
        button(typ := "button", on.click(Msg.RunFailure), "Run failing report"),
        button(typ := "button", on.click(Msg.Replace), "Replace current work"),
        button(typ := "button", on.click(Msg.Retry), "Retry report"),
        button(
          typ      := "button",
          disabled := !model.report.isLoading,
          on.click(Msg.Cancel),
          "Cancel report"
        )
      ),
      renderReportState(model.report)
    )

  private def start(model: Model, ctx: MessageContext, task: Task[Report]) =
    ctx.async
      .start(ReportTask)(task)(Msg.ReportCompleted(_))
      .as(model.copy(report = model.report.loading()))

  private def renderReportState(value: AsyncValue[Report]): HtmlElement[Msg] =
    value match
      case AsyncValue.Empty =>
        sectionTag(
          dataAttr("report-state") := "",
          aria.live                := "polite",
          "Empty"
        )
      case AsyncValue.Loading(previous) =>
        reportPanel("Loading", previous, "Generating report...")
      case AsyncValue.Ok(report) =>
        reportPanel("Succeeded", Some(report), "Report completed.")
      case AsyncValue.Failed(previous, _) =>
        reportPanel("Failed", previous, "The deterministic data source rejected the report.")
      case AsyncValue.Cancelled(previous, reason) =>
        reportPanel("Cancelled", previous, reason.getOrElse("Report generation was cancelled."))

  private def reportPanel(
    state: String,
    report: Option[Report],
    status: String
  ): HtmlElement[Msg] =
    sectionTag(
      dataAttr("report-state") := "",
      aria.live                := "polite",
      h2(dataAttr("report-status") := "", state),
      p(status),
      report.map { value =>
        articleTag(
          h3(dataAttr("report-title") := "", value.title),
          p(value.summary),
          p(s"${value.rows} rows")
        )
      }
    )
end AsyncReportExample

object AsyncReportExample:
  final case class Report(title: String, rows: Int, summary: String)
  final case class Model(report: AsyncValue[Report] = AsyncValue.empty)

  enum Msg:
    case RunSuccess
    case RunFailure
    case Replace
    case Retry
    case Cancel
    case Reset
    case ReportCompleted(result: LiveAsyncResult[Report])

  private[docs] def reportKey(instanceId: String): AsyncKey[Report] =
    AsyncKey[Report](s"async-report-$instanceId")

  private def successfulReport: Task[Report] =
    ZIO
      .sleep(2.seconds).as(
        Report("Quarterly activity", 128, "The deterministic success path completed normally.")
      )

  private def failingReport: Task[Report] =
    ZIO.sleep(1.second) *>
      ZIO.fail(new RuntimeException("The deterministic data source rejected the report."))

  private def replacementReport: Task[Report] =
    ZIO
      .sleep(600.millis).as(
        Report("Replacement report", 64, "The replacement suppressed the obsolete completion.")
      )

  private def retryReport: Task[Report] =
    ZIO
      .sleep(800.millis).as(
        Report("Retried report", 128, "The retry completed with deterministic data.")
      )
end AsyncReportExample
// docs:end async-report-example
