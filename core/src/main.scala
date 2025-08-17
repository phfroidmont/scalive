import scalive.*

@main
def main =
  val s = Socket(TestView())
  println("Init")
  println(s.renderHtml)
  s.syncClient
  s.syncClient

  println("Edit first and last")
  s.receiveCommand(
    TestView.Cmd.UpdateTextCls("text-lg")
  )
  s.syncClient

  println("Edit first and last")
  s.receiveCommand(
    TestView.Cmd.UpdateElems(
      List(
        Elem("x", 10),
        Elem("b", 15),
        Elem("c", 99)
      )
    )
  )
  s.syncClient

  println("Add one")
  s.receiveCommand(
    TestView.Cmd.UpdateElems(
      List(
        Elem("x", 10),
        Elem("b", 15),
        Elem("c", 99),
        Elem("d", 35)
      )
    )
  )
  s.syncClient

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
