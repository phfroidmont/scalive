package scalive

import scala.annotation.nowarn
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ListBuffer

class LiveView[Model] private (
    val static: ArraySeq[String],
    val dynamic: ArraySeq[LiveDyn[Model]]
):
  def update(model: Model): Unit =
    dynamic.foreach(_.update(model))

  def wasUpdated: Boolean = dynamic.exists(_.wasUpdated)

  def fullDiff: Diff =
    DiffBuilder.build(static, dynamic, includeUnchanged = true)

  def diff: Diff =
    DiffBuilder.build(static = Seq.empty, dynamic)

object LiveView:

  inline def apply[Model](
      lv: View[Model],
      model: Model
  ): LiveView[Model] =
    render(lv.root, model)

  def render[Model](
      tag: HtmlElement[Model],
      model: Model
  ): LiveView[Model] =
    new LiveView(buildStatic(tag), buildDynamic(tag, model).to(ArraySeq))

  def buildStatic[Model](el: HtmlElement[Model]): ArraySeq[String] =
    buildStaticFragments(el).flatten.to(ArraySeq)

  private def buildStaticFragments[Model](
      el: HtmlElement[Model]
  ): Seq[Option[String]] =
    val (attrs, children) = buildStaticFragmentsByType(el)
    val static = ListBuffer.empty[Option[String]]
    var staticFragment = s"<${el.tag.name}"
    for attr <- attrs do
      attr match
        case Some(s) =>
          staticFragment += s
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""
    staticFragment += s">"
    for child <- children do
      child match
        case Some(s) =>
          staticFragment += s
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""
    staticFragment += s"</${el.tag.name}>"
    static.append(Some(staticFragment))
    static.toSeq

  @nowarn("cat=unchecked")
  private def buildStaticFragmentsByType[Model](
      el: HtmlElement[Model]
  ): (attrs: Seq[Option[String]], children: Seq[Option[String]]) =
    val (attrs, children) = el.mods.partitionMap {
      case Mod.StaticAttr(attr, value) =>
        Left(List(Some(s""" ${attr.name}="$value"""")))
      case Mod.Tag(el)                 => Right(buildStaticFragments(el))
      case Mod.Text(text)              => Right(List(Some(text)))
      case Mod.DynText[Model](_)       => Right(List(None))
      case Mod.When[Model](_, _)       => Right(List(None))
      case Mod.Split[Model, Any](_, _) => Right(List(None))
    }
    (attrs.flatten, children.flatten)

  @nowarn("cat=unchecked")
  def buildDynamic[Model](
      el: HtmlElement[Model],
      model: Model,
      startsUpdated: Boolean = false
  ): Seq[LiveDyn[Model]] =
    val (attrs, children) = el.mods.partitionMap {
      case Mod.StaticAttr(_, _) => Left(List.empty)
      case Mod.Text(_)          => Right(List.empty)
      case Mod.Tag(el) =>
        Right(buildDynamic(el, model, startsUpdated))
      case Mod.DynText[Model](dynText) =>
        Right(List(LiveDyn.Value(dynText, model, startsUpdated)))
      case Mod.When[Model](dynCond, el) =>
        Right(List(LiveDyn.When(dynCond, el, model)))
      case Mod.Split[Model, Any](dynList, project) =>
        Right(List(LiveDyn.Split(dynList, project, model)))
    }
    attrs.flatten ++ children.flatten
