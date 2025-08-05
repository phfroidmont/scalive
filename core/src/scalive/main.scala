package scalive

import zio.json.*

@main
def main =
  val r =
    LiveViewRenderer.render(
      TestLiveView,
      MyModel("Initial string", true, "nested init")
    )
  println(r.buildInitJson.toJsonPretty)
  r.update(MyModel("Updated string", true, "nested updated"))
  println(r.buildDiffJson.toJsonPretty)
  r.update(MyModel("Updated string", false, "nested updated"))
  println(r.buildDiffJson.toJsonPretty)
  r.update(MyModel("Updated string", true, "nested displayed again"))
  println(r.buildDiffJson.toJsonPretty)
  r.update(MyModel("Updated string", true, "nested updated"))
  println(r.buildDiffJson.toJsonPretty)

final case class MyModel(title: String, bool: Boolean, nestedTitle: String)

object TestLiveView extends LiveView[MyModel]:
  val view: HtmlTag[MyModel] =
    div(
      div("Static string 1"),
      model(_.title),
      div("Static string 2"),
      model.when(_.bool)(
        div("maybe rendered", model(_.nestedTitle))
      )
    )
