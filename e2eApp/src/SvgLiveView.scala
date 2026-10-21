import zio.*

import scalive.*

/** An inline SVG bar whose width follows the model, and bars added after the join. */
class SvgLiveView extends LiveView[SvgLiveView.Msg, Vector[Int]]:
  import SvgLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(Vector(10))

  def handleMessage(model: Vector[Int], ctx: MessageContext) =
    case Msg.Grow => ZIO.succeed(model.map(_ + 10))
    case Msg.Add  => ZIO.succeed(model :+ 5)

  override def view(model: Signal[Vector[Int]]) =
    div(
      button(on.click(Msg.Grow), "Grow"),
      button(on.click(Msg.Add), "Add"),
      svg(
        idAttr      := "chart",
        svg.viewBox := "0 0 100 100",
        model.map(_.zipWithIndex).splitBy(_._2) { (row, bar) =>
          svg.rect(
            idAttr     := s"bar-$row",
            svg.y      := (row * 10).toString,
            svg.height := "8",
            svg.width  := bar.map(_._1.toString)
          )
        }
      )
    )

object SvgLiveView:
  enum Msg:
    case Grow, Add
