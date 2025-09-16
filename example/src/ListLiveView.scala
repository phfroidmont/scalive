import ListLiveView.*
import monocle.syntax.all.*
import scalive.*
import zio.*
import zio.json.*
import zio.stream.ZStream

class ListLiveView(someParam: String) extends LiveView[Msg, Model]:

  def init = ZIO.succeed(
    Model(
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
          phx.click := JS.toggleClass("bg-red-500 border-5"),
          "Toggle color"
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty

end ListLiveView

object ListLiveView:

  enum Msg derives JsonCodec:
    case IncAge(value: Int)

  final case class Model(
    elems: List[NestedModel])
  final case class NestedModel(name: String, age: Int)
