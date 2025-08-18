package scalive

import scala.annotation.nowarn
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ListBuffer

type RenderedDyn = Option[String | Rendered | Comprehension]

final case class Rendered(
  // val root: Boolean,
  val fingerprint: Long,
  val static: Seq[String],
  val dynamic: Seq[Boolean => RenderedDyn])

final case class Comprehension(
  // val hasKey: Boolean,
  val fingerprint: Long,
  val static: Seq[String],
  val entries: Seq[Boolean => Seq[RenderedDyn]])

object Rendered:

  def render(el: HtmlElement, state: LiveState): Rendered =
    Rendered(Fingerprint.apply(el), el.static, buildDynamicRendered(el, state))

  def render[T](mod: Mod.DynAttr[T], state: LiveState): Boolean => RenderedDyn =
    trackUpdates => mod.value.render(state, trackUpdates).map(mod.attr.codec.encode)
  def render(mod: Mod.DynAttrValueAsPresence, state: LiveState): Boolean => RenderedDyn =
    trackUpdates =>
      mod.value.render(state, trackUpdates).map(if _ then s" ${mod.attr.name}" else "")
  def render(mod: Mod.DynText, state: LiveState): Boolean => RenderedDyn =
    trackUpdates => mod.dyn.render(state, trackUpdates)
  def render(mod: Mod.When, state: LiveState): Boolean => RenderedDyn =
    trackUpdates =>
      mod.dyn
        .render(state, trackUpdates)
        .collect { case true => render(mod.el, state) }
  def render(mod: Mod.Split[Any], state: LiveState): Boolean => RenderedDyn =
    trackUpdates =>
      mod.dynList
        .render(state, trackUpdates)
        .collect {
          case items if items.nonEmpty =>
            val el = mod.project(Dyn.apply)
            Comprehension(
              Fingerprint.apply(el),
              el.static,
              items.map(item =>
                val localDyn   = Dyn[Any]
                val localState = LiveState.empty.set(localDyn, item)
                val localElem  = mod.project(localDyn)
                trackElemUpdates =>
                  buildDynamicRendered(localElem, localState).map(_(trackElemUpdates))
              )
            )
        }

  def buildStatic(el: HtmlElement): ArraySeq[String] =
    buildStaticFragments(el).flatten.to(ArraySeq)

  private def buildStaticFragments(el: HtmlElement): Seq[Option[String]] =
    val (attrs, children) = buildStaticFragmentsByType(el)
    val static            = ListBuffer.empty[Option[String]]
    var staticFragment    = s"<${el.tag.name}"
    for attr <- attrs do
      attr match
        case Some(s) =>
          staticFragment += s
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""
    staticFragment += (if el.tag.void then "/>" else ">")
    for child <- children do
      child match
        case Some(s) =>
          staticFragment += s
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""
    staticFragment += (if el.tag.void then "" else s"</${el.tag.name}>")
    static.append(Some(staticFragment))
    static.toSeq

  @nowarn("cat=unchecked")
  private def buildStaticFragmentsByType(el: HtmlElement)
    : (attrs: Seq[Option[String]], children: Seq[Option[String]]) =
    val (attrs, children) = el.mods.partitionMap {
      case Mod.StaticAttr(attr, value) => Left(List(Some(s""" ${attr.name}="$value"""")))
      case Mod.StaticAttrValueAsPresence(attr, true)  => Left(List(Some(s" ${attr.name}")))
      case Mod.StaticAttrValueAsPresence(attr, false) => Left(List.empty)
      case Mod.DynAttr(attr, _)                       =>
        Left(List(Some(s""" ${attr.name}=""""), None, Some('"'.toString)))
      case Mod.DynAttrValueAsPresence(attr, _) =>
        Left(List(Some(""), None, Some("")))
      case Mod.Tag(el)          => Right(buildStaticFragments(el))
      case Mod.Text(text)       => Right(List(Some(text)))
      case Mod.DynText(_)       => Right(List(None))
      case Mod.When(_, _)       => Right(List(None))
      case Mod.Split[Any](_, _) => Right(List(None))
    }
    (attrs.flatten, children.flatten)

  @nowarn("cat=unchecked")
  def buildDynamicRendered(
    el: HtmlElement,
    state: LiveState
  ): Seq[Boolean => RenderedDyn] =
    val (attrs, children) = el.mods.partitionMap {
      case Mod.Text(_)                         => Right(List.empty)
      case Mod.StaticAttr(_, _)                => Left(List.empty)
      case Mod.StaticAttrValueAsPresence(_, _) => Left(List.empty)
      case mod: Mod.DynAttr[?]                 => Right(List(Rendered.render(mod, state)))
      case mod: Mod.DynAttrValueAsPresence     => Right(List(Rendered.render(mod, state)))
      case Mod.Tag(el)                         => Right(buildDynamicRendered(el, state))
      case mod: Mod.DynText                    => Right(List(Rendered.render(mod, state)))
      case mod: Mod.When                       => Right(List(Rendered.render(mod, state)))
      case mod: Mod.Split[Any]                 => Right(List(Rendered.render(mod, state)))
    }
    attrs.flatten ++ children.flatten
end Rendered
