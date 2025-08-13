package scalive

import scala.annotation.nowarn
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ListBuffer

class LiveView[Model] private (
    val static: ArraySeq[String],
    val dynamic: ArraySeq[LiveMod[Model]]
):
  def update(model: Model): Unit =
    dynamic.foreach(_.update(model))

  def wasUpdated: Boolean = dynamic.exists(_.wasUpdated)

  def fullDiff: Diff =
    DiffBuilder.build(static, dynamic, includeUnchanged = true)

  def diff: Diff =
    DiffBuilder.build(static = Seq.empty, dynamic)

object LiveView:

  def apply[Model](
      lv: View[Model],
      model: Model
  ): LiveView[Model] =
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
  ): ArraySeq[LiveMod[Model]] =
    tag.mods.flatMap(buildDynamic(_, model, startsUpdated)).to(ArraySeq)

  @nowarn("cat=unchecked")
  def buildDynamic[Model](
      mod: Mod[Model],
      model: Model,
      startsUpdated: Boolean
  ): Seq[LiveMod[Model]] =
    mod match
      case Mod.Tag(tag)   => buildDynamic(tag, model, startsUpdated)
      case Mod.Text(text) => List.empty
      case Mod.DynText[Model](dynText) =>
        List(LiveMod.Dynamic(dynText, model, startsUpdated))
      case Mod.When[Model](dynCond, tag) =>
        List(LiveMod.When(dynCond, tag, model))
      case Mod.Split[Model, Any](dynList, project) =>
        List(LiveMod.Split(dynList, project, model))

  def render[Model](
      tag: HtmlTag[Model],
      model: Model
  ): LiveView[Model] =
    new LiveView(buildStatic(tag), buildDynamic(tag, model))
