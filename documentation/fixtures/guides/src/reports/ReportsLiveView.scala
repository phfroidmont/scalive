// docs:start services-liveview
package reports

import zio.*

import scalive.*

final class ReportsLiveView(reports: Reports)
    extends LiveView[ReportsLiveView.Msg, ReportsLiveView.Model]:
  import ReportsLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    load

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Refresh => load

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val loaded = model.map {
      case Model.Loaded(reports) => Some(reports)
      case Model.Failed(_)       => None
    }
    val failure = model.map {
      case Model.Failed(message) => Some(message)
      case Model.Loaded(_)       => None
    }
    mainTag(
      h1("Reports"),
      button(typ := "button", on.click(Msg.Refresh), "Refresh"),
      loaded.option { reports =>
        ul(reports.splitBy(_.id)((_, report) => li(report.map(_.title))))
      },
      failure.option(message => p(role := "alert", message))
    )

  private def load: UIO[Model] =
    reports.recent
      .map(Model.Loaded(_))
      .catchAll { error =>
        ZIO
          .logWarningCause("Could not load reports", Cause.fail(error))
          .as(Model.Failed("Reports are temporarily unavailable."))
      }
end ReportsLiveView

object ReportsLiveView:
  enum Msg:
    case Refresh

  enum Model:
    case Loaded(reports: Vector[Report])
    case Failed(message: String)
// docs:end services-liveview
