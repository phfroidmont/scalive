package scalive.docs.examples

import zio.*

import scalive.*

// docs:start async-report-example
final class AsyncReportExample(instanceId: String)
    extends LiveView[AsyncReportExample.Msg, AsyncReportExample.Model]:
  import AsyncReportExample.*

  private val ReportTask = reportKey(instanceId)

  def mount(ctx: MountContext): Task[Model] =
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

  override def view(model: Signal[Model]): HtmlElement[Msg] =
    val report = model.map(_.report)
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
          disabled := report.map(!_.isLoading),
          on.click(Msg.Cancel),
          "Cancel report"
        )
      ),
      report
        .map(_ == AsyncValue.Empty).choose(
          sectionTag(
            dataAttr("report-state") := "",
            aria.live                := "polite",
            "Empty"
          ),
          reportPanel(
            report.map(reportState),
            report.map(reportValue),
            report.map(reportStatus)
          )
        )
    )
  end view

  private def start(model: Model, ctx: MessageContext, task: Task[Report]) =
    ctx.async
      .start(ReportTask)(task)(Msg.ReportCompleted(_))
      .as(model.copy(report = model.report.loading()))

  private def reportPanel(
    state: Signal[String],
    report: Signal[Option[Report]],
    status: Signal[String]
  ): HtmlElement[Msg] =
    sectionTag(
      dataAttr("report-state") := "",
      aria.live                := "polite",
      h2(dataAttr("report-status") := "", state),
      p(status),
      report.option { value =>
        articleTag(
          h3(dataAttr("report-title") := "", value.map(_.title)),
          p(value.map(_.summary)),
          p(value.map(value => s"${value.rows} rows"))
        )
      }
    )

  private def reportState(value: AsyncValue[Report]): String = value match
    case AsyncValue.Empty           => "Empty"
    case AsyncValue.Loading(_)      => "Loading"
    case AsyncValue.Ok(_)           => "Succeeded"
    case AsyncValue.Failed(_, _)    => "Failed"
    case AsyncValue.Cancelled(_, _) => "Cancelled"

  private def reportValue(value: AsyncValue[Report]): Option[Report] = value match
    case AsyncValue.Empty                  => None
    case AsyncValue.Loading(previous)      => previous
    case AsyncValue.Ok(report)             => Some(report)
    case AsyncValue.Failed(previous, _)    => previous
    case AsyncValue.Cancelled(previous, _) => previous

  private def reportStatus(value: AsyncValue[Report]): String = value match
    case AsyncValue.Empty                => "Empty"
    case AsyncValue.Loading(_)           => "Generating report..."
    case AsyncValue.Ok(_)                => "Report completed."
    case AsyncValue.Failed(_, _)         => "The deterministic data source rejected the report."
    case AsyncValue.Cancelled(_, reason) =>
      reason.getOrElse("Report generation was cancelled.")
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
