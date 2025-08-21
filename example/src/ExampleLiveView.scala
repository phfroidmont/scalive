import scalive.*

final case class ExampleModel(elems: List[NestedModel], cls: String = "text-xs")
final case class NestedModel(name: String, age: Int)

class ExampleLiveView(someParam: String) extends LiveView[Nothing]:

  val model = Var(
    ExampleModel(
      List(
        NestedModel("a", 10),
        NestedModel("b", 15),
        NestedModel("c", 20)
      )
    )
  )

  def handleCommand(cmd: Nothing): Unit = ()

  val el =
    div(
      h1(someParam),
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
      )
    )
end ExampleLiveView
