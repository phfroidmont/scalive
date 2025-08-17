import scalive.*

final case class MyModel(cls: String = "text-xs", bool: Boolean = true)
final case class Elem(name: String, age: Int)

class TestView extends LiveView[TestView.Cmd]:
  import TestView.Cmd.*

  private val textCls  = LiveState.Key[String]
  private val someBool = LiveState.Key[Boolean]
  private val elems    = LiveState.Key[List[Elem]]

  def mount(state: LiveState): LiveState =
    state
      .set(textCls, "text-xs")
      .set(someBool, true)
      .set(
        elems,
        List(
          Elem("a", 10),
          Elem("b", 15),
          Elem("c", 20)
        )
      )

  def handleCommand(cmd: TestView.Cmd, state: LiveState): LiveState = cmd match
    case UpdateElems(es)    => state.set(elems, es)
    case UpdateBool(b)      => state.set(someBool, b)
    case UpdateTextCls(cls) => state.set(textCls, cls)

  val render =
    div(
      idAttr    := "42",
      cls       := textCls,
      draggable := someBool,
      disabled  := someBool,
      ul(
        elems.splitByIndex(elem =>
          li(
            "Nom: ",
            elem(_.name),
            " Age: ",
            elem(_.age.toString)
          )
        )
      )
    )
end TestView

object TestView:
  enum Cmd:
    case UpdateElems(es: List[Elem])
    case UpdateBool(b: Boolean)
    case UpdateTextCls(cls: String)
