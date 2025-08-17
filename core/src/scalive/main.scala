package scalive

@main
def main =
  val s = Socket(
    TestView,
    LiveState.empty.set(
      TestView.model,
      MyModel(
        List(
          NestedModel("a", 10),
          NestedModel("b", 15),
          NestedModel("c", 20)
        )
      )
    )
  )
  println("Init")
  println(s.renderHtml)
  s.syncClient
  s.syncClient

  println("Edit first and last")
  s.updateState(
    _.set(
      TestView.model,
      MyModel(
        List(
          NestedModel("x", 10),
          NestedModel("b", 15),
          NestedModel("c", 99)
        )
      )
    )
  )
  s.syncClient
  // val lv =
  //   LiveView(
  //     TestView,
  //     LiveState.empty.set(
  //       TestView.model,
  //       MyModel(
  //         List(
  //           NestedModel("a", 10),
  //           NestedModel("b", 15),
  //           NestedModel("c", 20)
  //         )
  //       )
  //     )
  //   )
  // println(lv.fullDiff.toJsonPretty)
  // println(HtmlBuilder.build(lv))
  //
  // println("Edit first and last")
  // lv.update(
  //   MyModel(
  //     List(
  //       NestedModel("x", 10),
  //       NestedModel("b", 15),
  //       NestedModel("c", 99)
  //     )
  //   )
  // )
  // println(lv.diff.toJsonPretty)
  // println(HtmlBuilder.build(lv))
  //
  // println("Add one")
  // lv.update(
  //   MyModel(
  //     List(
  //       NestedModel("x", 10),
  //       NestedModel("b", 15),
  //       NestedModel("c", 99),
  //       NestedModel("d", 35)
  //     )
  //   )
  // )
  // println(lv.diff.toJsonPretty)
  // println(HtmlBuilder.build(lv))
  //
  // println("Remove first")
  // lv.update(
  //   MyModel(
  //     List(
  //       NestedModel("b", 15),
  //       NestedModel("c", 99),
  //       NestedModel("d", 35)
  //     )
  //   )
  // )
  // println(lv.diff.toJsonPretty)
  // println(HtmlBuilder.build(lv))
  //
  // println("Remove all")
  // lv.update(
  //   MyModel(List.empty, "text-lg", bool = false)
  // )
  // println(lv.diff.toJsonPretty)
  // println(HtmlBuilder.build(lv))
end main

final case class MyModel(elems: List[NestedModel], cls: String = "text-xs", bool: Boolean = true)
final case class NestedModel(name: String, age: Int)

object TestView extends LiveView:
  val model  = LiveState.Key[MyModel]
  val render =
    div(
      idAttr    := "42",
      cls       := model(_.cls),
      draggable := model(_.bool),
      disabled  := model(_.bool),
      ul(
        model(_.elems).splitByIndex(elem =>
          li(
            "Nom: ",
            elem(_.name),
            " Age: ",
            elem(_.age.toString)
          )
        )
      )
    )
