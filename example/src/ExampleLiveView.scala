import ExampleLiveView.Event
import monocle.syntax.all.*
import scalive.*
import zio.json.*

final case class ExampleModel(elems: List[NestedModel], cls: String = "text-xs")
final case class NestedModel(name: String, age: Int)

class ExampleLiveView(someParam: String) extends LiveView[Event]:

  val model = Var(
    ExampleModel(
      List(
        NestedModel("a", 10),
        NestedModel("b", 15),
        NestedModel("c", 20)
      )
    )
  )

  def handleEvent =
    case Event.Event(value) =>
      model.update(_.focus(_.elems.index(2).age).modify(_ + value))

  val el =
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
        phx.click := Event.Event(1),
        "Inc age"
      )
    )
end ExampleLiveView

object ExampleLiveView:
  enum Event derives JsonCodec:
    case Event(value: Int)
