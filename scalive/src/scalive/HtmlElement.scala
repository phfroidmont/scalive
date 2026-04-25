package scalive

import scalive.JSCommands.JSCommand
import scalive.Mod.Attr
import scalive.Mod.Content
import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder

class HtmlElement[+Msg](val tag: HtmlTag, val mods: Vector[Mod[Msg]]):
  def static: Seq[String]          = StaticBuilder.build(this)
  def attrMods: Seq[Mod.Attr[Msg]] =
    mods.collect { case mod: Mod.Attr[Msg] => mod }
  def contentMods: Seq[Mod.Content[Msg]] =
    mods.collect { case mod: Mod.Content[Msg] => mod }

  def prepended[Msg2 >: Msg](mod: Mod[Msg2]*): HtmlElement[Msg2] =
    HtmlElement(tag, mods.prependedAll(mod))
  def appended[Msg2 >: Msg](mod: Mod[Msg2]*): HtmlElement[Msg2] =
    HtmlElement(tag, mods.appendedAll(mod))

class HtmlTag(val name: String, val void: Boolean = false):
  def apply[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] = HtmlElement(
    this,
    mods.toVector.flatMap {
      case m: Mod[Msg]                => Some(m)
      case ms: IterableOnce[Mod[Msg]] => ms
    }
  )

class HtmlAttr[V](val name: String, val codec: Encoder[V, String]):
  private inline def isBooleanAsAttrPresence = codec == BooleanAsAttrPresenceEncoder

  def :=(value: V): Mod.Attr[Nothing] =
    if isBooleanAsAttrPresence then
      Mod.Attr.StaticValueAsPresence(
        name,
        codec.encode(value) != null
      )
    else Mod.Attr.Static(name, codec.encode(value))

class HtmlAttrBinding(val name: String):
  def apply[Msg](cmd: JSCommand[Msg]): Mod.Attr[Msg] =
    Mod.Attr.JsBinding(name, cmd)

  def apply[Msg](msg: Msg): Mod.Attr[Msg] =
    apply(_ => msg)

  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] =
    Mod.Attr.Binding(name, f)

  def form[Msg](f: FormData => Msg): Mod.Attr[Msg] =
    Mod.Attr.FormBinding(name, f)

  def form[A, Msg](codec: FormCodec[A])(f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    Mod.Attr.FormEventBinding(name, codec, f)

  def withValue[Msg](f: String => Msg): Mod.Attr[Msg] =
    apply(m => f(m("value")))

  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg] =
    apply(m =>
      f(m("value") match
        case "on" | "yes" | "true"  => true
        case "off" | "no" | "false" => false)
    )

sealed trait Mod[+Msg]

object Mod:
  enum Attr[+Msg] extends Mod[Msg]:
    case Static(name: String, value: String)                       extends Attr[Nothing]
    case StaticValueAsPresence(name: String, value: Boolean)       extends Attr[Nothing]
    case Binding[Msg](name: String, f: Map[String, String] => Msg) extends Attr[Msg]
    case FormBinding[Msg](name: String, f: FormData => Msg)        extends Attr[Msg]
    case FormEventBinding[A, Msg](name: String, codec: FormCodec[A], f: FormEvent[A] => Msg)
        extends Attr[Msg]
    case JsBinding[Msg](name: String, command: JSCommand[Msg]) extends Attr[Msg]

  enum Content[+Msg] extends Mod[Msg]:
    case Text(text: String, raw: Boolean = false)       extends Content[Nothing]
    case Tag[Msg](el: HtmlElement[Msg])                 extends Content[Msg]
    case Component[Msg](cid: Int, el: HtmlElement[Msg]) extends Content[Msg]
    case Keyed(
      entries: Vector[Content.Keyed.Entry[Msg]],
      stream: Option[Diff.Stream] = None,
      allEntries: Option[Vector[Content.Keyed.Entry[Msg]]] = None) extends Content[Msg]

  object Content:
    object Keyed:
      final case class Entry[+Msg](key: Any, element: HtmlElement[Msg])
