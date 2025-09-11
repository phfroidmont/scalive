import ExampleLiveView.*
import monocle.syntax.all.*
import scalive.*
import zio.*
import zio.json.*
import zio.stream.ZStream

class ExampleLiveView(someParam: String) extends LiveView[Msg, Model]:

  def init = ZIO.succeed(
    Model(
      isVisible = true,
      counter = 0,
      elems = List(
        NestedModel("a", 10),
        NestedModel("b", 15),
        NestedModel("c", 20)
      )
    )
  )

  def update(model: Model) =
    case Msg.IncAge(value) =>
      ZIO.succeed(model.focus(_.elems.index(2).age).modify(_ + value))
    case Msg.ToggleCounter =>
      ZIO.succeed(model.focus(_.isVisible).modify(!_))
    case Msg.IncCounter =>
      ZIO.succeed(model.focus(_.counter).modify(_ + 1))
    case Msg.DecCounter =>
      ZIO.succeed(model.focus(_.counter).modify(_ - 1))

  def view(model: Dyn[Model]) =
    div(
      h1(
        cls := "text-2xl font-semibold tracking-tight text-gray-900",
        someParam
      ),
      cls    := "max-w-2xl mx-auto bg-white shadow rounded-2xl p-6 space-y-6",
      idAttr := "42",
      ul(
        cls := "divide-y divide-gray-200",
        model(_.elems).splitByIndex((_, elem) =>
          li(
            cls := "py-3 flex flex-wrap items-center justify-between gap-2",
            span(
              cls := "text-gray-700",
              "Nom: ",
              span(cls := "font-medium", elem(_.name))
            ),
            span(
              cls := "text-sm text-gray-500",
              "Age: ",
              span(cls := "font-semibold text-gray-700", elem(_.age.toString))
            )
          )
        )
      ),
      div(
        cls := "flex flex-wrap items-center gap-3",
        button(
          cls := "inline-flex items-center rounded-lg px-3 py-2 text-sm font-medium bg-gray-900 text-white shadow hover:opacity-90 focus:outline-none focus:ring-2 focus:ring-gray-900/30",
          phx.click := Msg.IncAge(1),
          "Inc age"
        ),
        span(cls := "grow"),
        button(
          cls := "inline-flex items-center rounded-lg px-3 py-2 text-sm font-medium ring-1 ring-inset ring-gray-300 text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-gray-400/30",
          phx.click := Msg.ToggleCounter,
          model(_.isVisible match
            case true  => "Hide counter"
            case false => "Show counter")
        )
      ),
      model.when(_.isVisible)(
        div(
          cls := "rounded-xl border border-gray-200 p-4 bg-gray-50",
          div(
            cls := "flex items-center justify-center gap-4",
            button(
              cls := "inline-flex items-center justify-center h-9 w-9 rounded-lg ring-1 ring-inset ring-gray-300 text-gray-700 hover:bg-white focus:outline-none focus:ring-2 focus:ring-gray-400/30",
              phx.click := Msg.DecCounter,
              "-"
            ),
            div(
              cls := "min-w-[4rem] text-center text-lg font-semibold text-gray-900",
              model(_.counter.toString)
            ),
            button(
              cls := "inline-flex items-center justify-center h-9 w-9 rounded-lg bg-gray-900 text-white shadow hover:opacity-90 focus:outline-none focus:ring-2 focus:ring-gray-900/30",
              phx.click := Msg.IncCounter,
              "+"
            )
          )
        )
      )
    )

  def subscriptions(model: Model) =
    ZStream.tick(1.second).map(_ => Msg.IncCounter).drop(1)

end ExampleLiveView

object ExampleLiveView:

  enum Msg derives JsonCodec:
    case IncAge(value: Int)
    case ToggleCounter
    case IncCounter
    case DecCounter

  final case class Model(
    isVisible: Boolean,
    counter: Int,
    elems: List[NestedModel])
  final case class NestedModel(name: String, age: Int)
