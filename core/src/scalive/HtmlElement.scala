package scalive

import scalive.codecs.BooleanAsAttrPresenceCodec
import scalive.codecs.Codec

class HtmlElement(val tag: HtmlTag, val mods: Vector[Mod]):
  lazy val static = Rendered.buildStatic(this)

class HtmlTag(val name: String, val void: Boolean = false):
  def apply(mods: Mod*): HtmlElement = HtmlElement(this, mods.toVector)

class HtmlAttr[V](val name: String, val codec: Codec[V, String]):
  private inline def isBooleanAsAttrPresence = codec == BooleanAsAttrPresenceCodec

  def :=(value: V): Mod =
    if isBooleanAsAttrPresence then
      Mod.StaticAttrValueAsPresence(
        this.asInstanceOf[HtmlAttr[Boolean]],
        value.asInstanceOf[Boolean]
      )
    else Mod.StaticAttr(this, codec.encode(value))

  def :=(value: Dyn[V]): Mod =
    if isBooleanAsAttrPresence then
      Mod.DynAttrValueAsPresence(
        this.asInstanceOf[HtmlAttr[Boolean]],
        value.asInstanceOf[Dyn[Boolean]]
      )
    else Mod.DynAttr(this, value)

enum Mod:
  case Text(text: String)
  case StaticAttr(attr: HtmlAttr[?], value: String)
  case StaticAttrValueAsPresence(attr: HtmlAttr[Boolean], value: Boolean)
  case DynAttr[T](attr: HtmlAttr[T], value: Dyn[T])
  case DynAttrValueAsPresence(attr: HtmlAttr[Boolean], value: Dyn[Boolean])
  case Tag(el: HtmlElement)
  case DynText(dyn: Dyn[String])
  case When(dyn: Dyn[Boolean], el: HtmlElement)
  case Split[T](
    dynList: Dyn[List[T]],
    project: Dyn[T] => HtmlElement)

given [T]: Conversion[HtmlElement, Mod] = Mod.Tag(_)
given [T]: Conversion[String, Mod]      = Mod.Text(_)
given [T]: Conversion[Dyn[String], Mod] = Mod.DynText(_)

final case class Dyn[T](key: LiveState.Key, f: key.Type => T):
  def render(state: LiveState, trackUpdates: Boolean): Option[T] =
    val entry = state(key)
    if !trackUpdates | entry.changed then Some(f(entry.value))
    else None

  def map[T2](f2: T => T2): Dyn[T2] = Dyn(key, f.andThen(f2))

  inline def apply[T2](f2: T => T2): Dyn[T2] = map(f2)

  def when(f2: T => Boolean)(el: HtmlElement): Mod.When = Mod.When(map(f2), el)

  inline def whenNot(f2: T => Boolean)(el: HtmlElement): Mod.When =
    when(f2.andThen(!_))(el)

extension [T](dyn: Dyn[List[T]])
  def splitByIndex(project: Dyn[T] => HtmlElement): Mod.Split[T] =
    Mod.Split(dyn, project)

object Dyn:
  def dummy[T] = Dyn(LiveState.Key[T], identity)
