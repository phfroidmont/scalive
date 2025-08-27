import scalive.*
import zio.json.JsonCodec

@main
def main =
  val initModel = MyModel(elems =
    List(
      Elem("a", 10),
      Elem("b", 15),
      Elem("c", 30)
    )
  )
  val s = Socket("", "", TestView(initModel))
  println("Init")
  println(s.renderHtml())
  s.syncClient
  s.syncClient

  println("Edit class attribue")
  s.lv.handleServerEvent(
    TestView.Event.UpdateModel(_.copy(cls = "text-lg"))
  )
  s.syncClient

  println("Edit first and last")
  s.lv.handleServerEvent(
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
  s.syncClient
  println(s.renderHtml())

  println("Add one")
  s.lv.handleServerEvent(
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
  s.syncClient
  println(s.renderHtml())

  println("Remove first")
  s.lv.handleServerEvent(
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
  s.syncClient
  println(s.renderHtml())

  println("Remove all")
  s.lv.handleServerEvent(
    TestView.Event.UpdateModel(
      _.copy(
        cls = "text-lg",
        bool = false,
        elems = List.empty
      )
    )
  )
  s.syncClient
  s.syncClient
  println(s.renderHtml())
end main
