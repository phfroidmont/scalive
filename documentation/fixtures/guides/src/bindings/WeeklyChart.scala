package bindings

import zio.*

import scalive.*

enum ChartMsg:
  case Visit(day: Int)

// docs:start bindings-svg-chart
def weeklyChart(visits: Signal[Vector[Int]]): HtmlElement[ChartMsg] =
  svg(
    svg.viewBox := "0 0 70 40",
    cls         := "h-40 w-full text-indigo-600",
    aria.label  := "Visits per day",
    visits.map(_.zipWithIndex).splitBy(_._2) { (day, bar) =>
      svg.rect(
        svg.x      := (day * 10 + 1).toString,
        svg.y      := bar.map((count, _) => (40 - count).toString),
        svg.width  := "8",
        svg.height := bar.map((count, _) => count.toString),
        svg.fill   := "currentColor",
        on.click(ChartMsg.Visit(day))
      )
    }
  )
// docs:end bindings-svg-chart

final class WeeklyChartLiveView extends LiveView[ChartMsg, Vector[Int]]:
  def mount(ctx: MountContext) = ZIO.succeed(Vector(4, 12, 8, 20, 16, 2, 6))

  def handleMessage(model: Vector[Int], ctx: MessageContext) =
    case ChartMsg.Visit(day) => ZIO.succeed(model.updated(day, model(day) + 1))

  def view(model: Signal[Vector[Int]]): HtmlElement[ChartMsg] = weeklyChart(model)
