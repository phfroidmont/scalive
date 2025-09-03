package scalive
package playground

import scalive.*
import zio.json.*

extension (lv: LiveView[?])
  def renderHtml: String =
    HtmlBuilder.build(lv.el)

@main
def main =
  val initModel = MyModel(elems =
    List(
      Elem("a", 10),
      Elem("b", 15),
      Elem("c", 30)
    )
  )
  val lv = TestView(initModel)
  println("Init")
  println(lv.renderHtml)
  println(lv.diff().toJsonPretty)

  println("Edit class attribue")
  lv.handleEvent(
    TestView.Event.UpdateModel(_.copy(cls = "text-lg"))
  )
  println(lv.diff().toJsonPretty)

  println("Edit first and last")
  lv.handleEvent(
    TestView.Event.UpdateModel(
      _.copy(elems =
        List(
          Elem("x", 10),
          Elem("b", 15),
          Elem("c", 99)
        )
      )
    )
  )
  println(lv.diff().toJsonPretty)
  println(lv.diff().toJsonPretty)

  println("Add one")
  lv.handleEvent(
    TestView.Event.UpdateModel(
      _.copy(elems =
        List(
          Elem("x", 10),
          Elem("b", 15),
          Elem("c", 99),
          Elem("d", 35)
        )
      )
    )
  )
  println(lv.diff().toJsonPretty)
  println(lv.renderHtml)

  println("Remove first")
  lv.handleEvent(
    TestView.Event.UpdateModel(
      _.copy(elems =
        List(
          Elem("b", 15),
          Elem("c", 99),
          Elem("d", 35)
        )
      )
    )
  )
  println(lv.diff().toJsonPretty)
  println(lv.renderHtml)

  println("Remove all")
  lv.handleEvent(
    TestView.Event.UpdateModel(
      _.copy(
        cls = "text-lg",
        bool = false,
        elems = List.empty
      )
    )
  )
  println(lv.diff().toJsonPretty)
  println(lv.diff().toJsonPretty)
  println(lv.renderHtml)
end main
