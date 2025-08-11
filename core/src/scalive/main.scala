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
  println(r.buildInitJson.toJsonPretty)
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
  println(r.buildDiffJson.toJsonPretty)
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
  println(r.buildDiffJson.toJsonPretty)
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
  println(r.buildDiffJson.toJsonPretty)
  println("Remove all")
  r.update(
    MyModel(List.empty)
  )
  println(r.buildDiffJson.toJsonPretty)

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
