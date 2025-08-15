package scalive

import zio.json.*

@main
def main =
  val lv =
    LiveView(
      TestView,
      MyModel(
        List(
          NestedModel("a", 10),
          NestedModel("b", 15),
          NestedModel("c", 20)
        )
      )
    )
  println(lv.fullDiff.toJsonPretty)
  println(HtmlBuilder.build(lv))

  println("Edit first and last")
  lv.update(
    MyModel(
      List(
        NestedModel("x", 10),
        NestedModel("b", 15),
        NestedModel("c", 99)
      )
    )
  )
  println(lv.diff.toJsonPretty)
  println(HtmlBuilder.build(lv))

  println("Add one")
  lv.update(
    MyModel(
      List(
        NestedModel("x", 10),
        NestedModel("b", 15),
        NestedModel("c", 99),
        NestedModel("d", 35)
      )
    )
  )
  println(lv.diff.toJsonPretty)
  println(HtmlBuilder.build(lv))

  println("Remove first")
  lv.update(
    MyModel(
      List(
        NestedModel("b", 15),
        NestedModel("c", 99),
        NestedModel("d", 35)
      )
    )
  )
  println(lv.diff.toJsonPretty)
  println(HtmlBuilder.build(lv))

  println("Remove all")
  lv.update(
    MyModel(List.empty, "text-lg")
  )
  println(lv.diff.toJsonPretty)
  println(HtmlBuilder.build(lv))

final case class MyModel(elems: List[NestedModel], cls: String = "text-xs")
final case class NestedModel(name: String, age: Int)

object TestView extends View[MyModel]:
  val root: HtmlElement[MyModel] =
    div(
      idAttr := "42",
      cls := model(_.cls),
      ul(
        model.splitByIndex(_.elems)(elem =>
          li(
            "Nom: ",
            elem(_.name),
            " Age: ",
            elem(_.age.toString)
          )
        )
      )
    )
