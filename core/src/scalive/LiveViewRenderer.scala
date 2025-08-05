package scalive

import scala.collection.mutable.ListBuffer
import scala.collection.immutable.ArraySeq
import zio.json.ast.Json

class RenderedLiveView[Model] private[scalive] (
    val static: ArraySeq[String],
    val dynamic: ArraySeq[RenderedMod[Model]]
):
  def update(model: Model): Unit =
    dynamic.foreach(_.update(model))
  def wasUpdated: Boolean = dynamic.exists(_.wasUpdated)
  def buildInitJson: Json = JsonAstBuilder.buildInit(static, dynamic)
  def buildDiffJson: Json = JsonAstBuilder.buildDiff(dynamic)

sealed trait RenderedMod[Model]:
  def update(model: Model): Unit
  def wasUpdated: Boolean

object RenderedMod:

  class Dynamic[I, O](d: Dyn[I, O], init: I) extends RenderedMod[I]:
    private var value: O = d.run(init)
    private var updated: Boolean = false
    def wasUpdated: Boolean = updated
    def currentValue: O = value
    def update(v: I): Unit =
      val newValue = d.run(v)
      if value == newValue then updated = false
      else
        value = newValue
        updated = true

  class When[Model](
      val dynCond: Dynamic[Model, Boolean],
      val nested: RenderedLiveView[Model]
  ) extends RenderedMod[Model]:
    def displayed: Boolean = dynCond.currentValue
    def wasUpdated: Boolean = dynCond.wasUpdated || nested.wasUpdated
    def update(model: Model): Unit =
      dynCond.update(model)
      nested.update(model)

object LiveViewRenderer:

  def render[Model](
      lv: LiveView[Model],
      model: Model
  ): RenderedLiveView[Model] =
    render(lv.view, model)

  private def render[Model](
      tag: HtmlTag[Model],
      model: Model
  ): RenderedLiveView[Model] =
    val static = ListBuffer.empty[String]
    val dynamic = ListBuffer.empty[RenderedMod[Model]]

    var staticFragment = ""
    for elem <- renderTag(tag, model) do
      elem match
        case s: String =>
          staticFragment += s
        case d: RenderedMod[Model] =>
          static.append(staticFragment)
          staticFragment = ""
          dynamic.append(d)
    if staticFragment.nonEmpty then static.append(staticFragment)
    new RenderedLiveView(static.to(ArraySeq), dynamic.to(ArraySeq))

  private def renderTag[Model](
      tag: HtmlTag[Model],
      model: Model
  ): List[String | RenderedMod[Model]] =
    (s"<${tag.name}>"
      :: tag.mods.flatMap(renderMod(_, model))) :+
      (s"</${tag.name}>")

  private def renderMod[Model](
      mod: Mod[Model],
      model: Model
  ): List[String | RenderedMod[Model]] =
    mod match
      case Mod.Tag(tag)   => renderTag(tag, model)
      case Mod.Text(text) => List(text)
      case Mod.DynText[Model](dynText) =>
        List(RenderedMod.Dynamic(dynText, model))
      case Mod.When[Model](dynCond, tag) =>
        List(
          RenderedMod.When(
            RenderedMod.Dynamic(dynCond, model),
            LiveViewRenderer.render(tag, model)
          )
        )
