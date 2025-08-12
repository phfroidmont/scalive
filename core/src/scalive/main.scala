package scalive

import zio.json.*

@main
def main =
  val r =
    LiveViewRenderer.render(
      TestLiveView,
      MyModel(
        List(
          NestedModel("a", 10),
          NestedModel("b", 15),
          NestedModel("c", 20)
        )
      )
    )
  println(DiffEngine.buildInitJson(r).toJsonPretty)
  println("Edit first and last")
  r.update(
    MyModel(
      List(
        NestedModel("x", 10),
        NestedModel("b", 15),
        NestedModel("c", 99)
      )
    )
  )
  println(DiffEngine.buildDiffJson(r).toJsonPretty)
  println("Add one")
  r.update(
    MyModel(
      List(
        NestedModel("x", 10),
        NestedModel("b", 15),
        NestedModel("c", 99),
        NestedModel("d", 35)
      )
    )
  )
  println(DiffEngine.buildDiffJson(r).toJsonPretty)
  println("Remove first")
  r.update(
    MyModel(
      List(
        NestedModel("b", 15),
        NestedModel("c", 99),
        NestedModel("d", 35)
      )
    )
  )
  println(DiffEngine.buildDiffJson(r).toJsonPretty)
  println("Remove all")
  r.update(
    MyModel(List.empty)
  )
  println(DiffEngine.buildDiffJson(r).toJsonPretty)

final case class MyModel(elems: List[NestedModel])
final case class NestedModel(name: String, age: Int)

object TestLiveView extends LiveView[MyModel]:
  val view: HtmlTag[MyModel] =
    div(
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
