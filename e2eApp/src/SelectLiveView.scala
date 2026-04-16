import zio.stream.ZStream

import scalive.*

import SelectLiveView.*

class SelectLiveView extends LiveView[Msg, Model]:

  def init = Model(selected = "2", validated = false)

  def update(model: Model) =
    case Msg.ChangeSelected(value) => model.copy(selected = value, validated = true)

  def view(model: Dyn[Model]) =
    div(
      styleAttr := "padding: 20px; max-width: 500px; font-family: sans-serif;",
      styleTag(
        "* { font-size: unset; }",
        ".has-error { border: 5px solid red; }",
        "select { border: 1px solid black; }"
      ),
      div(
        h1("Select Playgroud"),
        p(
          "This page contains multiple select inputs to test various behaviors. " +
            "Sadly, we cannot test all of them automatically, as there is no way to assert the state of an open select's native UI."
        ),
        form(
          phx.onChange(params => Msg.ChangeSelected(params.getOrElse("select_form[select3]", "2"))),
          h2("Select 3"),
          p(
            "Error classes are correctly applied to the third select. " +
              "It should have a red border for all values from 1 to 5. The border should disappear when selecting 6 or higher."
          ),
          select(
            idAttr   := "select_form_select3",
            nameAttr := "select_form[select3]",
            cls      := model(m => if m.hasError then "has-error" else ""),
            option(value := "1", selected  := model(_.selected == "1"), "1"),
            option(value := "2", selected  := model(_.selected == "2"), "2"),
            option(value := "3", selected  := model(_.selected == "3"), "3"),
            option(value := "4", selected  := model(_.selected == "4"), "4"),
            option(value := "5", selected  := model(_.selected == "5"), "5"),
            option(value := "6", selected  := model(_.selected == "6"), "6"),
            option(value := "7", selected  := model(_.selected == "7"), "7"),
            option(value := "8", selected  := model(_.selected == "8"), "8"),
            option(value := "9", selected  := model(_.selected == "9"), "9"),
            option(value := "10", selected := model(_.selected == "10"), "10")
          )
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end SelectLiveView

object SelectLiveView:

  enum Msg:
    case ChangeSelected(value: String)

  final case class Model(selected: String, validated: Boolean):
    def hasError: Boolean = validated && selected.toIntOption.exists(_ <= 5)
