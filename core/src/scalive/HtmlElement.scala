package scalive

import scalive.Mod.Attr
import scalive.Mod.Content
import scalive.codecs.BooleanAsAttrPresenceCodec
import scalive.codecs.Codec
import zio.json.*

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

class HtmlAttr[V](val name: String, val codec: Codec[V, String]):
  private inline def isBooleanAsAttrPresence = codec == BooleanAsAttrPresenceCodec

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

class HtmlAttrJsonValue(val name: String):

  def :=[V: JsonEncoder](value: V): Mod.Attr =
    Mod.Attr.Static(name, value.toJson, isJson = true)

  def :=[V: JsonEncoder](value: Dyn[V]): Mod.Attr =
    Mod.Attr.Dyn(name, value(_.toJson), isJson = true)

sealed trait Mod
sealed trait StaticMod  extends Mod
sealed trait DynamicMod extends Mod

object Mod:
  enum Attr extends Mod:
    case Static(name: String, value: String, isJson: Boolean = false) extends Attr with StaticMod
    case StaticValueAsPresence(name: String, value: Boolean)          extends Attr with StaticMod
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
      case Attr.Static(_, _, _)              => ()
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
      case Attr.Static(_, _, _)              => ()
      case Attr.StaticValueAsPresence(_, _)  => ()
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
end extension
