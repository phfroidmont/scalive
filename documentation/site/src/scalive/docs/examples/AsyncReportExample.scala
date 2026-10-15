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
    // docs:start async-report-cancel
    case Msg.Cancel =>
      if model.report.isLoading then
        ctx.async.cancel(ReportTask, Some("Cancelled by the user")).as(model)
      else ZIO.succeed(model)
    // docs:end async-report-cancel
    case Msg.Reset =>
      ctx.async.cancel(ReportTask, Some("Example reset")).as(Model())
    case Msg.ReportCompleted(LiveAsyncResult.Cancelled(_)) if model.report == AsyncValue.Empty =>
      ZIO.succeed(model)
    // docs:start async-report-completed
    case Msg.ReportCompleted(result) =>
      ZIO.succeed(model.copy(report = model.report.updated(result)))
    // docs:end async-report-completed

  def view(model: Signal[Model]): HtmlElement[Msg] =
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
      sectionTag(
        dataAttr("report-state") := "",
        aria.live                := "polite",
        // docs:start async-report-status
        report.matchCases { is =>
          (
            is.Empty(_ => "Empty"),
            is.Loading(loading =>
              reportPanel("Loading", "Generating report...", loading.map(_.previous))
            ),
            is.Ok(ok =>
              reportPanel("Succeeded", "Report completed.", ok.map(ok => Some(ok.value)))
            ),
            is.Failed(failed =>
              reportPanel(
                "Failed",
                failed.map(failed => explain(failed.error)),
                failed.map(_.previous)
              )
            ),
            is.Died(died =>
              reportPanel("Failed", "Report generation stopped unexpectedly.", died.map(_.previous))
            ),
            is.Cancelled(cancelled =>
              reportPanel(
                "Cancelled",
                cancelled.map(_.reason.getOrElse("Report generation was cancelled.")),
                cancelled.map(_.previous)
              )
            )
          )
        }
        // docs:end async-report-status
      )
    )
  end view

  // docs:start async-report-start
  private def start(model: Model, ctx: MessageContext, task: IO[ReportError, Report]) =
    ctx.async
      .start(ReportTask)(task)(Msg.ReportCompleted(_))
      .as(model.copy(report = model.report.loading()))
  // docs:end async-report-start

  private def explain(error: ReportError): String = error match
    case ReportError.SourceRejected => "The deterministic data source rejected the report."

  private def reportPanel(
    state: String,
    status: Mod[Nothing],
    report: Signal[Option[Report]]
  ): Vector[Mod[Msg]] =
    Vector(
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
end AsyncReportExample

object AsyncReportExample:
  final case class Report(title: String, rows: Int, summary: String)
  // docs:start async-report-model
  enum ReportError:
    case SourceRejected

  final case class Model(report: AsyncValue[ReportError, Report] = AsyncValue.empty)
  // docs:end async-report-model

  enum Msg:
    case RunSuccess
    case RunFailure
    case Replace
    case Retry
    case Cancel
    case Reset
    case ReportCompleted(result: LiveAsyncResult[ReportError, Report])

  private[docs] def reportKey(instanceId: String): AsyncKey[Report] =
    AsyncKey[Report](s"async-report-$instanceId")

  private def successfulReport: UIO[Report] =
    ZIO
      .sleep(2.seconds).as(
        Report("Quarterly activity", 128, "The deterministic success path completed normally.")
      )

  private def failingReport: IO[ReportError, Report] =
    ZIO.sleep(1.second) *> ZIO.fail(ReportError.SourceRejected)

  private def replacementReport: UIO[Report] =
    ZIO
      .sleep(600.millis).as(
        Report("Replacement report", 64, "The replacement suppressed the obsolete completion.")
      )

  private def retryReport: UIO[Report] =
    ZIO
      .sleep(800.millis).as(
        Report("Retried report", 128, "The retry completed with deterministic data.")
      )
end AsyncReportExample
// docs:end async-report-example
