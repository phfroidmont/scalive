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
      h1(someParam),
      h2(model(_.cls)),
      idAttr := "42",
      cls    := model(_.cls),
      ul(
        model(_.elems).splitByIndex((_, elem) =>
          li(
            "Nom: ",
            elem(_.name),
            " Age: ",
            elem(_.age.toString)
          )
        )
      ),
      button(
        phx.click := Msg.IncAge(1),
        "Inc age"
      ),
      br(),
      br(),
      br(),
      button(
        phx.click := Msg.ToggleCounter,
        model(_.isVisible match
          case true  => "Hide counter"
          case false => "Show counter")
      ),
      model.when(_.isVisible)(
        div(
          button(phx.click := Msg.DecCounter, "-"),
          div(model(_.counter.toString)),
          button(phx.click := Msg.IncCounter, "+")
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
    elems: List[NestedModel],
    cls: String = "text-xs")
  final case class NestedModel(name: String, age: Int)
