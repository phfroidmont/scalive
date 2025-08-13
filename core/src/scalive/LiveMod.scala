package scalive

import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ArrayBuffer

sealed trait LiveMod[Model]:
  def update(model: Model): Unit
  def wasUpdated: Boolean

object LiveMod:

  class Dynamic[I, O](d: Dyn[I, O], init: I, startsUpdated: Boolean = false)
      extends LiveMod[I]:
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
  ) extends LiveMod[Model]:
    val cond = LiveMod.Dynamic(dynCond, init)
    val nested = LiveView.render(tag, init)
    def displayed: Boolean = cond.currentValue
    def wasUpdated: Boolean = cond.wasUpdated || nested.wasUpdated
    def update(model: Model): Unit =
      cond.update(model)
      nested.update(model)

  class Split[Model, Item](
      dynList: Dyn[Model, List[Item]],
      project: Dyn[Item, Item] => HtmlTag[Item],
      init: Model
  ) extends LiveMod[Model]:
    private val tag = project(Dyn.id)
    val static: ArraySeq[String] = LiveView.buildStatic(tag)
    val dynamic: ArrayBuffer[ArraySeq[LiveMod[Item]]] =
      dynList.run(init).map(LiveView.buildDynamic(tag, _)).to(ArrayBuffer)
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
          dynamic.append(LiveView.buildDynamic(tag, item, startsUpdated = true))
        else dynamic(i).foreach(_.update(item))
      )
