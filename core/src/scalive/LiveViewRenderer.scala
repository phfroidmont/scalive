package scalive

import scalive.LiveViewRenderer.buildDynamic
import scalive.LiveViewRenderer.buildStatic

import scala.annotation.nowarn
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer

class RenderedLiveView[Model] private[scalive] (
    val static: ArraySeq[String],
    val dynamic: ArraySeq[RenderedMod[Model]]
):
  def update(model: Model): Unit =
    dynamic.foreach(_.update(model))
  def wasUpdated: Boolean = dynamic.exists(_.wasUpdated)

sealed trait RenderedMod[Model]:
  def update(model: Model): Unit
  def wasUpdated: Boolean

object RenderedMod:

  class Dynamic[I, O](d: Dyn[I, O], init: I, startsUpdated: Boolean = false)
      extends RenderedMod[I]:
    private var value: O = d.run(init)
    private var updated: Boolean = startsUpdated
    def wasUpdated: Boolean = updated
    def currentValue: O = value
    def update(v: I): Unit =
      val newValue = d.run(v)
      if value == newValue then updated = false
      else
        value = newValue
        updated = true

  class When[Model](
      dynCond: Dyn[Model, Boolean],
      tag: HtmlTag[Model],
      init: Model
  ) extends RenderedMod[Model]:
    val cond = RenderedMod.Dynamic(dynCond, init)
    val nested = LiveViewRenderer.render(tag, init)
    def displayed: Boolean = cond.currentValue
    def wasUpdated: Boolean = cond.wasUpdated || nested.wasUpdated
    def update(model: Model): Unit =
      cond.update(model)
      nested.update(model)

  class Split[Model, Item](
      dynList: Dyn[Model, List[Item]],
      project: Dyn[Item, Item] => HtmlTag[Item],
      init: Model
  ) extends RenderedMod[Model]:
    private val tag = project(Dyn.id)
    val static: ArraySeq[String] = buildStatic(tag)
    val dynamic: ArrayBuffer[ArraySeq[RenderedMod[Item]]] =
      dynList.run(init).map(buildDynamic(tag, _)).to(ArrayBuffer)
    var removedIndexes: Seq[Int] = Seq.empty

    def wasUpdated: Boolean =
      removedIndexes.nonEmpty || dynamic.exists(_.exists(_.wasUpdated))

    def update(model: Model): Unit =
      val items = dynList.run(model)
      removedIndexes =
        if items.size < dynamic.size then items.size until dynamic.size
        else Seq.empty
      dynamic.takeInPlace(items.size)
      items.zipWithIndex.map((item, i) =>
        if i >= dynamic.size then
          dynamic.append(buildDynamic(tag, item, startsUpdated = true))
        else dynamic(i).foreach(_.update(item))
      )

object LiveViewRenderer:

  def render[Model](
      lv: LiveView[Model],
      model: Model
  ): RenderedLiveView[Model] =
    render(lv.view, model)

  def buildStatic[Model](tag: HtmlTag[Model]): ArraySeq[String] =
    buildNestedStatic(tag).flatten.to(ArraySeq)

  private def buildNestedStatic[Model](
      tag: HtmlTag[Model]
  ): Seq[Option[String]] =
    val static = ListBuffer.empty[Option[String]]
    var staticFragment = s"<${tag.name}>"
    for mod <- tag.mods.flatMap(buildStatic) do
      mod match
        case Some(s) =>
          staticFragment += s
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""
    staticFragment += s"</${tag.name}>"
    static.append(Some(staticFragment))
    static.toSeq

  def buildStatic[Model](mod: Mod[Model]): Seq[Option[String]] =
    mod match
      case Mod.Tag(tag)    => buildNestedStatic(tag)
      case Mod.Text(text)  => List(Some(text))
      case Mod.DynText(_)  => List(None)
      case Mod.When(_, _)  => List(None)
      case Mod.Split(_, _) => List(None)

  def buildDynamic[Model](
      tag: HtmlTag[Model],
      model: Model,
      startsUpdated: Boolean = false
  ): ArraySeq[RenderedMod[Model]] =
    tag.mods.flatMap(buildDynamic(_, model, startsUpdated)).to(ArraySeq)

  @nowarn("cat=unchecked")
  def buildDynamic[Model](
      mod: Mod[Model],
      model: Model,
      startsUpdated: Boolean
  ): Seq[RenderedMod[Model]] =
    mod match
      case Mod.Tag(tag)   => buildDynamic(tag, model, startsUpdated)
      case Mod.Text(text) => List.empty
      case Mod.DynText[Model](dynText) =>
        List(RenderedMod.Dynamic(dynText, model, startsUpdated))
      case Mod.When[Model](dynCond, tag) =>
        List(RenderedMod.When(dynCond, tag, model))
      case Mod.Split[Model, Any](dynList, project) =>
        List(RenderedMod.Split(dynList, project, model))

  def render[Model](
      tag: HtmlTag[Model],
      model: Model
  ): RenderedLiveView[Model] =
    new RenderedLiveView(buildStatic(tag), buildDynamic(tag, model))
