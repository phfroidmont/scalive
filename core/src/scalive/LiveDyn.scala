package scalive

import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ArrayBuffer

sealed trait LiveDyn[Model]:
  def update(model: Model): Unit
  def wasUpdated: Boolean

object LiveDyn:

  class Value[I, O](d: Dyn[I, O], init: I, startsUpdated: Boolean = false) extends LiveDyn[I]:
    private var value: O         = d.run(init)
    private var updated: Boolean = startsUpdated
    def wasUpdated: Boolean      = updated
    def currentValue: O          = value
    def update(v: I): Unit       =
      val newValue = d.run(v)
      if value == newValue then updated = false
      else
        value = newValue
        updated = true

  class When[Model](
    dynCond: Dyn[Model, Boolean],
    el: HtmlElement[Model],
    init: Model)
      extends LiveDyn[Model]:
    val cond                       = LiveDyn.Value(dynCond, init)
    val nested                     = LiveView.render(el, init)
    def displayed: Boolean         = cond.currentValue
    def wasUpdated: Boolean        = cond.wasUpdated || nested.wasUpdated
    def update(model: Model): Unit =
      cond.update(model)
      nested.update(model)

  class Split[Model, Item](
    dynList: Dyn[Model, List[Item]],
    project: Dyn[Item, Item] => HtmlElement[Item],
    init: Model)
      extends LiveDyn[Model]:
    private val el                                    = project(Dyn.id)
    val static: ArraySeq[String]                      = LiveView.buildStatic(el)
    val dynamic: ArrayBuffer[ArraySeq[LiveDyn[Item]]] =
      dynList
        .run(init)
        .map(LiveView.buildDynamic(el, _).to(ArraySeq))
        .to(ArrayBuffer)
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
          dynamic.append(
            LiveView.buildDynamic(el, item, startsUpdated = true).to(ArraySeq)
          )
        else dynamic(i).foreach(_.update(item))
      )
  end Split
end LiveDyn
