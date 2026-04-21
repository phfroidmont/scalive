package scalive

import scalive.JSCommands.JSCommand
import scalive.Mod.Attr
import scalive.Mod.Content
import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder

class HtmlElement(val tag: HtmlTag, val mods: Vector[Mod]):
  def static: Seq[String]     = StaticBuilder.build(this)
  def attrMods: Seq[Mod.Attr] =
    mods.collect { case mod: Mod.Attr => mod }
  def contentMods: Seq[Mod.Content] =
    mods.collect { case mod: Mod.Content => mod }

  def prepended(mod: Mod*): HtmlElement = HtmlElement(tag, mods.prependedAll(mod))
  def apended(mod: Mod*): HtmlElement   = HtmlElement(tag, mods.appendedAll(mod))

class HtmlTag(val name: String, val void: Boolean = false):
  def apply(mods: (Mod | IterableOnce[Mod])*): HtmlElement = HtmlElement(
    this,
    mods.toVector.flatMap {
      case m: Mod                => Some(m)
      case ms: IterableOnce[Mod] => ms
    }
  )

class HtmlAttr[V](val name: String, val codec: Encoder[V, String]):
  private inline def isBooleanAsAttrPresence = codec == BooleanAsAttrPresenceEncoder

  def :=(value: V): Mod.Attr =
    if isBooleanAsAttrPresence then
      Mod.Attr.StaticValueAsPresence(
        name,
        codec.encode(value) != null
      )
    else Mod.Attr.Static(name, codec.encode(value))

class HtmlAttrBinding(val name: String):
  def apply(cmd: JSCommand): Mod.Attr =
    Mod.Attr.JsBinding(name, cmd)

  def apply[Msg](msg: Msg): Mod.Attr =
    apply(_ => msg)

  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr =
    Mod.Attr.Binding(name, f)
  def withValue[Msg](f: String => Msg): Mod.Attr =
    apply(m => f(m("value")))

  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr =
    apply(m =>
      f(m("value") match
        case "on" | "yes" | "true"  => true
        case "off" | "no" | "false" => false)
    )

sealed trait Mod

object Mod:
  enum Attr extends Mod:
    case Static(name: String, value: String)                 extends Attr
    case StaticValueAsPresence(name: String, value: Boolean) extends Attr
    case Binding(name: String, f: Map[String, String] => ?)  extends Attr
    case JsBinding(name: String, command: JSCommand)         extends Attr

  enum Content extends Mod:
    case Text(text: String, raw: Boolean = false) extends Content
    case Tag(el: HtmlElement)                     extends Content
    case Component(cid: Int, el: HtmlElement)     extends Content
    case Keyed(
      entries: Vector[Content.Keyed.Entry],
      stream: Option[Diff.Stream] = None,
      allEntries: Option[Vector[Content.Keyed.Entry]] = None) extends Content

  object Content:
    object Keyed:
      final case class Entry(key: Any, element: HtmlElement)
