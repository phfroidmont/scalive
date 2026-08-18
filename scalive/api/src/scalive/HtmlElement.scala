package scalive

import scala.annotation.targetName
import scala.concurrent.duration.FiniteDuration

import scalive.JSCommands.JSCommand
import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder

/** An immutable element in Scalive's typed, protocol-neutral HTML algebra. */
final class HtmlElement[+Msg](val tag: HtmlTag, val mods: Vector[Mod[Msg]]):
  def attrMods: Vector[Mod.Attr[Msg]] =
    mods.collect { case attr: Mod.Attr[Msg] => attr }.flatMap(_.flattened)

  def contentMods: Vector[Mod.Content[Msg]] =
    mods.collect { case content: Mod.Content[Msg] => content }

  def prepended[Msg2 >: Msg](values: Mod[Msg2]*): HtmlElement[Msg2] =
    HtmlElement(tag, mods.prependedAll(values))

  def appended[Msg2 >: Msg](values: Mod[Msg2]*): HtmlElement[Msg2] =
    HtmlElement(tag, mods.appendedAll(values))

object HtmlElement:
  def apply[Msg](tag: HtmlTag, mods: Vector[Mod[Msg]]): HtmlElement[Msg] =
    new HtmlElement(tag, mods)

/** A reusable, validated HTML tag factory. */
class HtmlTag(val name: String, val void: Boolean = false):
  require(Escaping.validTag(name), s"invalid HTML tag name '$name'")

  def apply[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    HtmlElement(
      this,
      mods.toVector.flatMap {
        case mod: Mod[Msg]                  => Some(mod)
        case values: IterableOnce[Mod[Msg]] => values
      }
    )

/** A typed, validated HTML attribute definition. */
class HtmlAttr[V](val name: String, val codec: Encoder[V, String]):
  require(Escaping.validAttrName(name), s"invalid HTML attribute name '$name'")

  private inline def presence = codec == BooleanAsAttrPresenceEncoder

  def :=(value: V): Mod.Attr[Nothing] =
    if presence then Mod.Attr.StaticValueAsPresence(name, codec.encode(value) != null)
    else Mod.Attr.Static(name, codec.encode(value))

  def :=(value: Signal[V]): Mod.Attr[Nothing] =
    if presence then Mod.Attr.SignalValueAsPresence(name, value.map(codec.encode(_) != null))
    else Mod.Attr.SignalValue(name, value.map(codec.encode))

  def optional(value: Signal[Option[V]]): Mod.Attr[Nothing] =
    Mod.Attr.SignalOptionalValue(name, value.map(_.map(codec.encode)))

/** A protocol-neutral component dispatch selected by a rendered event binding. */
sealed trait ComponentDispatch:
  type Message
  def message: Message

object ComponentDispatch:
  final case class Instance[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model],
    message: Msg)
      extends ComponentDispatch:
    type Message = Msg

  final case class OutputInstance[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output],
    message: Msg)
      extends ComponentDispatch:
    type Message = Msg

  final case class Definition[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    message: Msg)
      extends ComponentDispatch:
    type Message = Msg

/** Structured browser input supplied to a typed binding operation. */
private[scalive] enum BindingPayload:
  case Params(values: Map[String, String])
  case Form(data: FormData, meta: FormEvent.Meta = FormEvent.Meta.empty)

  def params: Map[String, String] = this match
    case Params(values)   => values
    case Form(data, meta) =>
      val values = data.asMap ++ meta.params
      meta.submitter match
        case Some(FormSubmitter(name, value)) if !values.contains(name) =>
          values.updated(name, value)
        case _ => values

  def formData: FormData = this match
    case Params(values) => FormData.fromMap(values)
    case Form(data, _)  => data

  def formEvent[A](codec: FormCodec[A], submitted: Boolean): FormEvent[A] = this match
    case Params(values) =>
      FormEvent.decode(FormData.fromMap(values), codec, submitted, FormEvent.Meta.empty)
    case Form(data, meta) => FormEvent.decode(data, codec, submitted, meta)

/** Builder for typed server events and declarative browser commands. */
class HtmlAttrBinding(
  val name: String,
  protected val companionAttrs: Vector[Mod.Attr[Nothing]] = Vector.empty):
  require(Escaping.validAttrName(name), s"invalid HTML attribute name '$name'")

  protected def recreate(attrs: Vector[Mod.Attr[Nothing]]): HtmlAttrBinding =
    HtmlAttrBinding(name, attrs)

  protected def append(attr: Mod.Attr[Nothing]): HtmlAttrBinding = recreate(companionAttrs :+ attr)

  private def configured[Msg](binding: Mod.Attr[Msg]): Mod.Attr[Msg] =
    if companionAttrs.isEmpty then binding else Mod.Attr.Group(binding +: companionAttrs)

  protected def durationAttr(name: String, duration: FiniteDuration): Mod.Attr[Nothing] =
    require(duration.length >= 0, s"$name duration must not be negative")
    Mod.Attr.Static(name, duration.toMillis.toString)

  def debounce(duration: FiniteDuration): HtmlAttrBinding =
    append(durationAttr("phx-debounce", duration))

  def debounceOnBlur: HtmlAttrBinding = append(Mod.Attr.Static("phx-debounce", "blur"))

  def throttle(duration: FiniteDuration): HtmlAttrBinding =
    append(durationAttr("phx-throttle", duration))

  def to[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(Mod.Attr.RoutedBinding(name, _ => ComponentDispatch.Instance(instance, message)))

  def to[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(
      Mod.Attr.RoutedBinding(name, _ => ComponentDispatch.OutputInstance(instance, message))
    )

  def to[Msg](ref: ComponentRef[Msg])(message: Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.ComponentBinding(name, ref, _ => message))

  def toComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(Mod.Attr.RoutedBinding(name, _ => ComponentDispatch.Definition(component, message)))

  def apply[Msg](command: JSCommand[Msg]): Mod.Attr[Msg] =
    configured(Mod.Attr.JsBinding(name, command))

  @targetName("applySignalCommand")
  def apply[Msg](command: Signal[JSCommand[Msg]]): Mod.Attr[Msg] =
    configured(Mod.Attr.SignalJsBinding(name, command))

  def apply[Msg](message: Msg): Mod.Attr[Msg] = apply(_ => message)

  def apply[Msg](message: Signal[Msg]): Mod.Attr[Msg] =
    configured(Mod.Attr.SignalBinding(name, message, (value, _) => value))

  def apply[A, Msg](
    current: Signal[A]
  )(
    f: (A, Map[String, String]) => Msg
  ): Mod.Attr[Msg] =
    configured(Mod.Attr.SignalBinding(name, current, (value, payload) => f(value, payload.params)))

  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.Binding(name, payload => f(payload.params)))

  def form[Msg](f: FormData => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.FormBinding(name, f))

  def form[A, Msg](codec: FormCodec[A])(f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.FormEventBinding(name, codec, f))

  def withValueOption[Msg](f: Option[String] => Msg): Mod.Attr[Msg] =
    apply(values => f(values.get("value")))

  def withValue[Msg](f: String => Msg): Mod.Attr[Msg] =
    withValueOption(value => f(value.getOrElse("")))

  def withBoolValueOption[Msg](f: Option[Boolean] => Msg): Mod.Attr[Msg] =
    withValueOption(value =>
      f(value.flatMap {
        case "on" | "yes" | "true"  => Some(true)
        case "off" | "no" | "false" => Some(false)
        case _                      => None
      })
    )

  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg] =
    withBoolValueOption(value => f(value.getOrElse(false)))
end HtmlAttrBinding

final class KeyHtmlAttrBinding(
  name: String,
  override protected val companionAttrs: Vector[Mod.Attr[Nothing]] = Vector.empty)
    extends HtmlAttrBinding(name, companionAttrs):
  override protected def recreate(attrs: Vector[Mod.Attr[Nothing]]): KeyHtmlAttrBinding =
    KeyHtmlAttrBinding(name, attrs)

  override protected def append(attr: Mod.Attr[Nothing]): KeyHtmlAttrBinding =
    recreate(companionAttrs :+ attr)

  override def debounce(duration: FiniteDuration): KeyHtmlAttrBinding =
    append(durationAttr("phx-debounce", duration))

  override def debounceOnBlur: KeyHtmlAttrBinding =
    append(Mod.Attr.Static("phx-debounce", "blur"))

  override def throttle(duration: FiniteDuration): KeyHtmlAttrBinding =
    append(durationAttr("phx-throttle", duration))

  def key(value: Key): KeyHtmlAttrBinding = append(Mod.Attr.Static("phx-key", value.value))

/** A typed declarative HTML modifier. */
sealed trait Mod[+Msg]

object Mod:
  enum Attr[+Msg] extends Mod[Msg]:
    case Static(name: String, value: String)                              extends Attr[Nothing]
    case StaticValueAsPresence(name: String, value: Boolean)              extends Attr[Nothing]
    case SignalValue(name: String, value: Signal[String])                 extends Attr[Nothing]
    case SignalOptionalValue(name: String, value: Signal[Option[String]]) extends Attr[Nothing]
    case SignalValueAsPresence(name: String, value: Signal[Boolean])      extends Attr[Nothing]
    case Binding[Msg](name: String, operation: BindingPayload => Msg)     extends Attr[Msg]
    case SignalBinding[A, Msg](
      name: String,
      signal: Signal[A],
      operation: (A, BindingPayload) => Msg)                        extends Attr[Msg]
    case FormBinding[Msg](name: String, operation: FormData => Msg) extends Attr[Msg]
    case FormEventBinding[A, Msg](name: String, codec: FormCodec[A], operation: FormEvent[A] => Msg)
        extends Attr[Msg]
    case JsBinding[Msg](name: String, command: JSCommand[Msg])               extends Attr[Msg]
    case SignalJsBinding[Msg](name: String, command: Signal[JSCommand[Msg]]) extends Attr[Msg]
    case RoutedBinding(name: String, operation: BindingPayload => ComponentDispatch)
        extends Attr[Nothing]
    case ComponentBinding[Msg](
      name: String,
      target: ComponentRef[Msg],
      operation: BindingPayload => Msg)                  extends Attr[Msg]
    case ComponentTarget[Msg](target: ComponentRef[Msg]) extends Attr[Msg]
    case Group[Msg](attrs: Vector[Attr[Msg]])            extends Attr[Msg]

    def flattened: Vector[Attr[Msg]] = this match
      case Group(attrs) => attrs.flatMap(_.flattened)
      case attr         => Vector(attr)

  enum Content[+Msg] extends Mod[Msg]:
    case Text(text: String, raw: Boolean = false)                extends Content[Nothing]
    case SignalText(value: Signal[String], raw: Boolean = false) extends Content[Nothing]
    case SignalChoice[A, Msg](value: Signal[A], branches: Vector[(A, HtmlElement[Msg])])
        extends Content[Msg]
    case SignalModChoice[A, Msg](value: Signal[A], branches: Vector[(A, Mod[Msg])])
        extends Content[Msg]
    case SignalOption[A, Msg](value: Signal[Option[A]], project: Signal[A] => HtmlElement[Msg])
        extends Content[Msg]
    case Keyed[Key, Msg](entries: Vector[Content.Keyed.Entry[Key, Msg]]) extends Content[Msg]
    case SignalKeyed[A, Key, Msg](
      values: Signal[Iterable[A]],
      key: A => Key,
      project: (Key, Signal[A]) => HtmlElement[Msg]) extends Content[Msg]
    case SignalKeyedByIndex[A, Msg](
      values: Signal[Iterable[A]],
      project: (Int, Signal[A]) => HtmlElement[Msg]) extends Content[Msg]
    case Stream[A, Msg](value: streams.LiveStream[A], project: (String, A) => HtmlElement[Msg])
        extends Content[Msg]
    case SignalStream[A, Msg](
      value: Signal[streams.LiveStream[A]],
      project: (String, Signal[A]) => HtmlElement[Msg])                  extends Content[Msg]
    case Tag[Msg](element: HtmlElement[Msg])                             extends Content[Msg]
    case Component[Msg](spec: ComponentSpec[Msg])                        extends Content[Msg]
    case NestedView(spec: NestedViewSpec)                                extends Content[Nothing]
    case Flash(kind: FlashKind, project: String => HtmlElement[Nothing]) extends Content[Nothing]

  object Content:
    object Keyed:
      final case class Entry[+Key, +Msg](key: Key, element: HtmlElement[Msg])
end Mod

/** A declarative nested LiveView requirement. */
sealed trait NestedViewSpec

object NestedViewSpec:
  final case class Static[Msg, Model](
    id: String,
    factory: () => LiveView[Msg, Model],
    sticky: Boolean,
    linkParentOnCrash: Boolean)
      extends NestedViewSpec

  final case class Dynamic[A, Msg, Model](
    id: String,
    value: Signal[A],
    factory: A => LiveView[Msg, Model],
    sticky: Boolean,
    linkParentOnCrash: A => Boolean)
      extends NestedViewSpec
