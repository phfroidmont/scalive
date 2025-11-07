package scalive
package playground

import zio.json.*

import scalive.*

extension (el: HtmlElement) def html: String = HtmlBuilder.build(el)

import TestView.*
@main
def main =
  val initModel = Model(elems =
    List(
      Elem("a", 10),
      Elem("b", 15),
      Elem("c", 30)
    )
  )
  val modelVar = Var(initModel)
  val lv       = TestView()
  val el       = lv.view(modelVar)
  println("Init")
  println(el.html)
  println(el.diff().toJsonPretty)

  println("Edit class attribue")
  modelVar.update(_.copy(cls = "text-lg"))
  println(el.diff().toJsonPretty)

  println("Edit first and last")
  modelVar.update(
    _.copy(elems =
      List(
        Elem("x", 10),
        Elem("b", 15),
        Elem("c", 99)
      )
    )
  )
  println(el.diff().toJsonPretty)
  println(el.diff().toJsonPretty)

  println("Add one")
  modelVar.update(
    _.copy(elems =
      List(
        Elem("x", 10),
        Elem("b", 15),
        Elem("c", 99),
        Elem("d", 35)
      )
    )
  )
  println(el.diff().toJsonPretty)
  println(el.html)

  println("Remove first")
  modelVar.update(
    _.copy(elems =
      List(
        Elem("b", 15),
        Elem("c", 99),
        Elem("d", 35)
      )
    )
  )
  println(el.diff().toJsonPretty)
  println(el.html)

  println("Remove all")
  modelVar.update(
    _.copy(
      cls = "text-lg",
      bool = false,
      elems = List.empty
    )
  )
  println(el.diff().toJsonPretty)
  println(el.diff().toJsonPretty)
  println(el.html)
end main
