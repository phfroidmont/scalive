package scalive

import scalive.JSCommands.JSCommand
import scalive.Mod.Attr
import scalive.Mod.Content
import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder

class HtmlElement(val tag: HtmlTag, val mods: Vector[Mod]):
  // Binding IDs are allocated per render tree so newly created bindings remain unique across diffs.
  private var nextBindingId: Long = 0L

  def static: Seq[String]     = StaticBuilder.build(this)
  def attrMods: Seq[Mod.Attr] =
    mods.collect { case mod: Mod.Attr => mod }
  def contentMods: Seq[Mod.Content] =
    mods.collect { case mod: Mod.Content => mod }

  def prepended(mod: Mod*): HtmlElement = HtmlElement(tag, mods.prependedAll(mod))
  def apended(mod: Mod*): HtmlElement   = HtmlElement(tag, mods.appendedAll(mod))
  def findBinding[Msg](id: String): Option[Map[String, String] => Msg] =
    mods.iterator.iterator.map(_.findBinding(id)).collectFirst { case Some(f) => f }

  private[scalive] def syncAll(): Unit                   = mods.foreach(_.syncAll())
  private[scalive] def setAllUnchanged(): Unit           = ()
  private[scalive] def allocatePendingBindingIds(): Unit =
    nextBindingId = BindingId.assignPending(this, nextBindingId)

  private[scalive] def diff(trackUpdates: Boolean = true): Diff =
    val _ = trackUpdates
    syncAll()
    allocatePendingBindingIds()
    TreeDiff.initial(this)

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
        value.asInstanceOf[Boolean]
      )
    else Mod.Attr.Static(name, codec.encode(value))

class HtmlAttrBinding(val name: String):
  def apply(cmd: JSCommand): Mod.Attr =
    Mod.Attr.JsBinding(name, cmd)

  def apply[Msg](msg: Msg): Mod.Attr =
    apply(_ => msg)

  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr =
    Mod.Attr.Binding(
      name,
      MutableValue(BindingId.pending()),
      f
    )
  def withValue[Msg](f: String => Msg): Mod.Attr =
    apply(m => f(m("value")))

  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr =
    apply(m =>
      f(m("value") match
        case "on" | "yes" | "true"  => true
        case "off" | "no" | "false" => false)
    )

trait BindingAdapter[F, Msg]:
  def createMessage(f: F): Map[String, String] => Msg
object BindingAdapter:
  given fromString[Msg]: BindingAdapter[String => Msg, Msg]           = f => m => f(m("value"))
  given fromMap[Msg]: BindingAdapter[Map[String, String] => Msg, Msg] = f => f

final case class BindingParams(params: Map[String, String]):
  def apply(key: String) = params.apply(key)

sealed trait Mod
sealed trait StaticMod  extends Mod
sealed trait DynamicMod extends Mod

object Mod:
  enum Attr extends Mod:
    case Static(name: String, value: String)                 extends Attr with StaticMod
    case StaticValueAsPresence(name: String, value: Boolean) extends Attr with StaticMod
    case Binding(name: String, id: MutableValue[String], f: Map[String, String] => ?)
        extends Attr
        with DynamicMod
    case JsBinding(name: String, command: JSCommand) extends Attr with DynamicMod

  enum Content extends Mod:
    case Text(text: String, raw: Boolean = false) extends Content with StaticMod
    case Tag(el: HtmlElement)                     extends Content with StaticMod with DynamicMod
    case Component(cid: Int, el: HtmlElement)     extends Content with DynamicMod
    case Keyed(
      entries: Vector[Content.Keyed.Entry],
      stream: Option[Diff.Stream] = None,
      allEntries: Option[Vector[Content.Keyed.Entry]] = None) extends Content with DynamicMod

  object Content:
    object Keyed:
      final case class Entry(key: Any, element: HtmlElement)

extension (mod: Mod)
  private[scalive] def setAllUnchanged(): Unit =
    mod match
      case Attr.Static(_, _)                     => ()
      case Attr.Binding(_, _, _)                 => ()
      case Attr.JsBinding(_, _)                  => ()
      case Attr.StaticValueAsPresence(_, _)      => ()
      case Content.Text(text, _)                 => ()
      case Content.Tag(el)                       => el.setAllUnchanged()
      case Content.Component(_, el)              => el.setAllUnchanged()
      case Content.Keyed(entries, _, allEntries) =>
        allEntries.getOrElse(entries).foreach(_.element.setAllUnchanged())

  private[scalive] def syncAll(): Unit =
    mod match
      case Attr.Static(_, _)                     => ()
      case Attr.StaticValueAsPresence(_, _)      => ()
      case Attr.Binding(_, _, _)                 => ()
      case Attr.JsBinding(_, _)                  => ()
      case Content.Text(text, _)                 => ()
      case Content.Tag(el)                       => el.syncAll()
      case Content.Component(_, el)              => el.syncAll()
      case Content.Keyed(entries, _, allEntries) =>
        allEntries.getOrElse(entries).foreach(_.element.syncAll())

  private[scalive] def findBinding[Msg](id: String): Option[Map[String, String] => Msg] =
    mod match
      case Attr.Static(_, _)                => None
      case Attr.StaticValueAsPresence(_, _) => None
      case Attr.Binding(_, eventId, f)      =>
        if id == eventId.currentValue then Some(f.asInstanceOf[Map[String, String] => Msg])
        else None
      case Attr.JsBinding(_, command) =>
        command.bindings.get(id).map(msg => _ => msg.asInstanceOf[Msg])
      case Content.Text(text, _)                 => None
      case Content.Tag(el)                       => el.findBinding(id)
      case Content.Component(_, el)              => el.findBinding(id)
      case Content.Keyed(entries, _, allEntries) =>
        allEntries
          .getOrElse(entries)
          .iterator
          .map(_.element.findBinding(id))
          .collectFirst { case Some(f) => f }
end extension
