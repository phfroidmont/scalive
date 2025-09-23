package scalive

import scalive.JSCommands.JSCommand
import scalive.Mod.Attr
import scalive.Mod.Content
import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder
import zio.json.*

import java.util.Base64
import scala.util.Random

class HtmlElement(val tag: HtmlTag, val mods: Vector[Mod]):
  def static: Seq[String]     = StaticBuilder.build(this)
  def attrMods: Seq[Mod.Attr] =
    mods.collect { case mod: Mod.Attr => mod }
  def contentMods: Seq[Mod.Content] =
    mods.collect { case mod: Mod.Content => mod }
  def dynamicMods: Seq[(Mod.Attr | Mod.Content) & DynamicMod] =
    dynamicAttrMods ++ dynamicContentMods.flatMap {
      case Content.Tag(el) => el.dynamicMods
      case mod             => List(mod)

    }
  def dynamicAttrMods: Seq[Mod.Attr & DynamicMod] =
    mods.collect { case mod: (Mod.Attr & DynamicMod) => mod }
  def dynamicContentMods: Seq[Mod.Content & DynamicMod] =
    mods.collect { case mod: (Mod.Content & DynamicMod) => mod }

  def prepended(mod: Mod*): HtmlElement = HtmlElement(tag, mods.prependedAll(mod))
  def apended(mod: Mod*): HtmlElement   = HtmlElement(tag, mods.appendedAll(mod))
  def findBinding[Msg](id: String): Option[Map[String, String] => Msg] =
    mods.iterator.iterator.map(_.findBinding(id)).collectFirst { case Some(f) => f }

  private[scalive] def syncAll(): Unit         = mods.foreach(_.syncAll())
  private[scalive] def setAllUnchanged(): Unit = dynamicMods.foreach(_.setAllUnchanged())
  private[scalive] def diff(trackUpdates: Boolean = true): Diff =
    syncAll()
    val diff = DiffBuilder.build(this, trackUpdates = trackUpdates)
    setAllUnchanged()
    diff

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

  def :=(value: Dyn[V]): Mod.Attr =
    if isBooleanAsAttrPresence then
      Mod.Attr.DynValueAsPresence(
        name,
        value.asInstanceOf[Dyn[Boolean]]
      )
    else Mod.Attr.Dyn(name, value(codec.encode))

class HtmlAttrBinding(val name: String):
  def apply(cmd: JSCommand): Mod.Attr =
    Mod.Attr.JsBinding(name, cmd.toJson, cmd.bindings)

  def apply[Msg](msg: Msg): Mod.Attr =
    apply(_ => msg)

  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr =
    Mod.Attr.Binding(
      name,
      Base64.getUrlEncoder().withoutPadding().encodeToString(Random().nextBytes(12)),
      f
    )
  def withValue[Msg](f: String => Msg): Mod.Attr =
    apply(m => f(m("value")))

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
    case Static(name: String, value: String)                            extends Attr with StaticMod
    case StaticValueAsPresence(name: String, value: Boolean)            extends Attr with StaticMod
    case Binding(name: String, id: String, f: Map[String, String] => ?) extends Attr with StaticMod
    case JsBinding(name: String, jsonValue: String, bindings: Map[String, ?])
        extends Attr
        with StaticMod
    case Dyn(name: String, value: scalive.Dyn[String], isJson: Boolean = false)
        extends Attr
        with DynamicMod
    case DynValueAsPresence(name: String, value: scalive.Dyn[Boolean]) extends Attr with DynamicMod

  enum Content extends Mod:
    case Text(text: String)                extends Content with StaticMod
    case Tag(el: HtmlElement)              extends Content with StaticMod with DynamicMod
    case DynText(dyn: Dyn[String])         extends Content with DynamicMod
    case DynElement(dyn: Dyn[HtmlElement]) extends Content with DynamicMod
    // TODO support arbitrary collection
    case DynOptionElement(dyn: Dyn[Option[HtmlElement]])     extends Content with DynamicMod
    case DynElementColl(dyn: Dyn[IterableOnce[HtmlElement]]) extends Content with DynamicMod
    case DynSplit(v: SplitVar[?, HtmlElement, ?])            extends Content with DynamicMod

extension (mod: Mod)
  private[scalive] def setAllUnchanged(): Unit =
    mod match
      case Attr.Static(_, _)                 => ()
      case Attr.Binding(_, _, _)             => ()
      case Attr.JsBinding(_, _, _)           => ()
      case Attr.StaticValueAsPresence(_, _)  => ()
      case Attr.Dyn(_, value, _)             => value.setUnchanged()
      case Attr.DynValueAsPresence(_, value) => value.setUnchanged()
      case Content.Text(text)                => ()
      case Content.Tag(el)                   => el.setAllUnchanged()
      case Content.DynText(dyn)              => dyn.setUnchanged()
      case Content.DynElement(dyn)           =>
        dyn.setUnchanged()
        dyn.callOnEveryChild(_.setAllUnchanged())
      case Content.DynOptionElement(dyn) =>
        dyn.setUnchanged()
        dyn.callOnEveryChild(_.foreach(_.setAllUnchanged()))
      case Content.DynElementColl(dyn) =>
        dyn.setUnchanged()
        dyn.callOnEveryChild(_.foreach(_.setAllUnchanged()))
      case Content.DynSplit(v) =>
        v.setUnchanged()
        v.callOnEveryChild(_.setAllUnchanged())

  private[scalive] def syncAll(): Unit =
    mod match
      case Attr.Static(_, _)                 => ()
      case Attr.StaticValueAsPresence(_, _)  => ()
      case Attr.Binding(_, _, _)             => ()
      case Attr.JsBinding(_, _, _)           => ()
      case Attr.Dyn(_, value, _)             => value.sync()
      case Attr.DynValueAsPresence(_, value) => value.sync()
      case Content.Text(text)                => ()
      case Content.Tag(el)                   => el.syncAll()
      case Content.DynText(dyn)              => dyn.sync()
      case Content.DynElement(dyn)           =>
        dyn.sync()
        dyn.callOnEveryChild(_.syncAll())
      case Content.DynOptionElement(dyn) =>
        dyn.sync()
        dyn.callOnEveryChild(_.foreach(_.syncAll()))
      case Content.DynElementColl(dyn) =>
        dyn.sync()
        dyn.callOnEveryChild(_.foreach(_.syncAll()))
      case Content.DynSplit(v) =>
        v.sync()
        v.callOnEveryChild(_.syncAll())

  private[scalive] def findBinding[Msg](id: String): Option[Map[String, String] => Msg] =
    mod match
      case Attr.Static(_, _)                => None
      case Attr.StaticValueAsPresence(_, _) => None
      case Attr.Binding(_, eventId, f)      =>
        if id == eventId then Some(f.asInstanceOf[Map[String, String] => Msg])
        else None
      case Attr.JsBinding(_, _, bindings) =>
        bindings.get(id).map(msg => _ => msg.asInstanceOf[Msg])
      case Attr.Dyn(_, value, _)             => None
      case Attr.DynValueAsPresence(_, value) => None
      case Content.Text(text)                => None
      case Content.Tag(el)                   => el.findBinding(id)
      case Content.DynText(dyn)              => None
      case Content.DynElement(dyn)           => dyn.currentValue.findBinding(id)
      case Content.DynOptionElement(dyn)     => dyn.currentValue.flatMap(_.findBinding(id))
      case Content.DynElementColl(dyn)       =>
        dyn.currentValue.iterator.map(_.findBinding(id)).collectFirst { case Some(f) => f }
      case Content.DynSplit(v) =>
        v.currentValues.iterator.map(_.findBinding(id)).collectFirst { case Some(f) => f }
end extension
