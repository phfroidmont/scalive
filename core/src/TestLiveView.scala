package scalive
package playground

import scalive.*
import zio.*
import zio.stream.ZStream

import TestView.*
class TestView extends LiveView[Msg, Model]:

  def init = Model()

  def update(model: Model) =
    case Msg.UpdateModel(f) => f(model)

  def view(model: Dyn[Model]) =
    div(
      idAttr   := "42",
      cls      := model(_.cls),
      disabled := model(_.bool),
      ul(
        model(_.elems).splitByIndex((_, elem) =>
          li(
            "Nom: ",
            elem(_.name),
            " Age: ",
            elem(_.age.toString)
          )
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty

object TestView:

  enum Msg:
    case UpdateModel(f: Model => Model)

  final case class Model(
    cls: String = "text-xs",
    bool: Boolean = true,
    elems: List[Elem] = List.empty)
  final case class Elem(name: String, age: Int)
