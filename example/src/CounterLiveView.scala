import CounterLiveView.*
import monocle.syntax.all.*
import scalive.*
import zio.*
import zio.json.*
import zio.stream.ZStream

class CounterLiveView() extends LiveView[Msg, Model]:

  def init = ZIO.succeed(
    Model(
      isVisible = true,
      counter = 0
    )
  )

  def update(model: Model) =
    case Msg.ToggleCounter =>
      ZIO.succeed(model.focus(_.isVisible).modify(!_))
    case Msg.IncCounter =>
      ZIO.succeed(model.focus(_.counter).modify(_ + 1))
    case Msg.DecCounter =>
      ZIO.succeed(model.focus(_.counter).modify(_ - 1))

  def view(model: Dyn[Model]) =
    div(
      cls := "mx-auto card bg-base-100 max-w-2xl shadow-xl space-y-6",
      div(
        cls := "card-body",
        h1(
          cls := "card-title",
          "Counter with auto increment every second"
        ),
        div(
          cls := "flex flex-wrap items-center gap-3",
          button(
            cls       := "btn btn-default",
            phx.click := Msg.ToggleCounter,
            model(_.isVisible match
              case true  => "Hide counter"
              case false => "Show counter")
          )
        ),
        model.when(_.isVisible)(
          div(
            cls := "flex items-center justify-center gap-4",
            button(
              cls       := "btn btn-neutral",
              phx.click := Msg.DecCounter,
              "-"
            ),
            div(
              cls := "min-w-[4rem] text-center text-lg font-semibold",
              model(_.counter.toString)
            ),
            button(
              cls       := "btn btn-neutral",
              phx.click := Msg.IncCounter,
              "+"
            )
          )
        )
      )
    )

  def subscriptions(model: Model) =
    ZStream.tick(1.second).map(_ => Msg.IncCounter).drop(1)

end CounterLiveView

object CounterLiveView:

  enum Msg derives JsonCodec:
    case ToggleCounter
    case IncCounter
    case DecCounter

  final case class Model(
    isVisible: Boolean,
    counter: Int)
