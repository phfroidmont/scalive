import scalive.*

final case class MyModel(
  cls: String = "text-xs",
  bool: Boolean = true,
  elems: List[Elem] = List.empty)
final case class Elem(name: String, age: Int)

class TestView(initialModel: MyModel) extends LiveView[TestView.Cmd]:
  import TestView.Cmd.*

  private val modelVar = Var[MyModel](initialModel)

  def handleCommand(cmd: TestView.Cmd): Unit =
    cmd match
      case UpdateModel(f) => modelVar.update(f)

  val el: HtmlElement =
    div(
      idAttr   := "42",
      cls      := modelVar(_.cls),
      disabled := modelVar(_.bool),
      ul(
        modelVar(_.elems).splitByIndex((_, elem) =>
          li(
            "Nom: ",
            elem(_.name),
            " Age: ",
            elem(_.age.toString)
          )
        )
      )
    )

object TestView:
  enum Cmd:
    case UpdateModel(f: MyModel => MyModel)
