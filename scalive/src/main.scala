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
  val lv       = TestView()
  var model    = initModel
  var previous = lv.view(model)

  def renderUpdate(next: Model): Unit =
    val current = lv.view(next)
    println(TreeDiff.diff(previous, current).toJsonPretty)
    println(current.html)
    previous = current
    model = next

  println("Init")
  println(previous.html)
  println(TreeDiff.initial(previous).toJsonPretty)

  println("Edit class attribue")
  renderUpdate(model.copy(cls = "text-lg"))

  println("Edit first and last")
  renderUpdate(
    model.copy(elems =
      List(
        Elem("x", 10),
        Elem("b", 15),
        Elem("c", 99)
      )
    )
  )

  println("Add one")
  renderUpdate(
    model.copy(elems =
      List(
        Elem("x", 10),
        Elem("b", 15),
        Elem("c", 99),
        Elem("d", 35)
      )
    )
  )

  println("Remove first")
  renderUpdate(
    model.copy(elems =
      List(
        Elem("b", 15),
        Elem("c", 99),
        Elem("d", 35)
      )
    )
  )

  println("Remove all")
  renderUpdate(
    model.copy(
      cls = "text-lg",
      bool = false,
      elems = List.empty
    )
  )
end main
