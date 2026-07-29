package scalive.examples.processing

import zio.*

import scalive.*

final class AsyncReportLiveView
    extends LiveView[AsyncReportLiveView.Msg, AsyncReportLiveView.Model]:
  import AsyncReportLiveView.*

  def mount(ctx: MountContext) =
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
    case Msg.ReportCompleted(result) =>
      ZIO.succeed(model.copy(report = model.report.updated(result)))

  def render(model: Model) =
    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Async work"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Deterministic report generator"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "One typed AsyncKey replaces stale work, while AsyncValue renders progress, failures, cancellation, and results."
        )
      ),
      div(
        cls := "mb-6 flex flex-wrap gap-3",
        button(
          typ := "button",
          cls := "btn btn-primary",
          phx.onClick(Msg.RunSuccess),
          "Run successful report"
        ),
        button(
          typ := "button",
          cls := "btn btn-outline btn-error",
          phx.onClick(Msg.RunFailure),
          "Run failing report"
        ),
        button(
          typ := "button",
          cls := "btn btn-outline",
          phx.onClick(Msg.Replace),
          "Replace current work"
        ),
        button(
          typ := "button",
          cls := "btn btn-ghost",
          phx.onClick(Msg.Retry),
          "Retry"
        ),
        button(
          typ      := "button",
          cls      := "btn btn-ghost",
          disabled := !model.report.isLoading,
          phx.onClick(Msg.Cancel),
          "Cancel"
        )
      ),
      renderReportState(model.report)
    )

  private def start(model: Model, ctx: MessageContext, task: Task[Report]) =
    ctx.async
      .start(ReportTask)(task)(Msg.ReportCompleted(_))
      .as(model.copy(report = model.report.loading()))

  private def renderReportState(value: AsyncValue[Report]) =
    value match
      case AsyncValue.Empty =>
        div(
          cls := "rounded-box border border-dashed border-base-300 p-10 text-center text-base-content/60",
          "Choose a deterministic task to begin."
        )
      case AsyncValue.Loading(previous) =>
        div(
          cls := "space-y-4",
          div(
            cls := "alert",
            span(cls := "loading loading-spinner loading-sm"),
            span("Generating report...")
          ),
          previous.map(reportCard)
        )
      case AsyncValue.Ok(report)              => reportCard(report)
      case AsyncValue.Failed(previous, cause) =>
        div(
          cls := "space-y-4",
          div(
            cls := "alert alert-error",
            div(
              h2(cls := "font-semibold", "Report failed"),
              p(Option(cause.getMessage).getOrElse("The report could not be generated."))
            )
          ),
          previous.map(reportCard)
        )
      case AsyncValue.Cancelled(previous, reason) =>
        div(
          cls := "space-y-4",
          div(
            cls := "alert alert-warning",
            span(reason.getOrElse("Report generation was cancelled."))
          ),
          previous.map(reportCard)
        )

  private def reportCard(report: Report) =
    articleTag(
      cls := "rounded-box border border-base-300 bg-base-100 p-6 shadow-sm",
      div(
        cls := "flex flex-wrap items-start justify-between gap-3",
        div(
          h2(cls := "text-xl font-semibold", report.title),
          p(cls  := "mt-2 leading-7 text-base-content/70", report.summary)
        ),
        div(
          cls := "stat w-auto p-0 text-right",
          div(cls := "stat-title", "Rows"),
          div(cls := "stat-value text-3xl", report.rows.toString)
        )
      )
    )
end AsyncReportLiveView

object AsyncReportLiveView:
  final case class Report(title: String, rows: Int, summary: String)

  final case class Model(report: AsyncValue[Report] = AsyncValue.empty)

  enum Msg:
    case RunSuccess
    case RunFailure
    case Replace
    case Retry
    case Cancel
    case ReportCompleted(result: LiveAsyncResult[Report])

  private val ReportTask = AsyncKey[Report]("example-report")

  private def successfulReport: Task[Report] =
    ZIO
      .sleep(2.seconds).as(
        Report("Quarterly activity", 128, "The deterministic success path completed normally.")
      )

  private def failingReport: Task[Report] =
    ZIO.sleep(1.second) *>
      ZIO.fail(new RuntimeException("The deterministic demo data source rejected the report."))

  private def replacementReport: Task[Report] =
    ZIO
      .sleep(600.millis).as(
        Report(
          "Replacement report",
          64,
          "Starting this task reused the key and replaced stale work."
        )
      )

  private def retryReport: Task[Report] =
    ZIO
      .sleep(800.millis).as(
        Report("Retried report", 128, "The retry completed with the same deterministic data.")
      )
end AsyncReportLiveView
