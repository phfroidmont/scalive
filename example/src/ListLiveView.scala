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
      cls := "mx-auto card bg-base-100 max-w-2xl shadow-xl space-y-6",
      div(
        cls := "card-body",
        h1(cls := "card-title", someParam),
        ul(
          cls := "divide-y divide-base-200",
          model(_.elems).splitByIndex((_, elem) =>
            li(
              cls := "py-3 flex flex-wrap items-center justify-between gap-2",
              span(
                cls := "text-base-content",
                "Nom: ",
                span(cls := "font-semibold", elem(_.name))
              ),
              span(
                cls := "text-sm opacity-70",
                "Age: ",
                span(cls := "font-bold", elem(_.age.toString))
              )
            )
          )
        ),
        div(
          cls := "card-actions",
          button(
            cls       := "btn btn-default",
            phx.click := Msg.IncAge(1),
            "Inc age"
          ),
          span(cls := "grow"),
          button(
            cls       := "btn btn-neutral",
            phx.click := JS.toggleClass("btn-neutral btn-accent"),
            "Toggle color"
          )
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
