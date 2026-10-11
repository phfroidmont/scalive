package scalive

import scala.annotation.targetName
import scala.concurrent.duration.FiniteDuration

import scalive.JSCommands.JSCommand
import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder
import scalive.codecs.StringAsIsEncoder

/** An immutable element in Scalive's typed, protocol-neutral HTML algebra.
  *
  * @param tag
  *   The tag that names the element.
  * @param mods
  *   The element's attributes, bindings, and children, in the order they were given.
  */
final class HtmlElement[+Msg](val tag: HtmlTag, val mods: Vector[Mod[Msg]]):
  private[scalive] def attrMods: Vector[Mod.Attr[Msg]] =
    mods.collect { case attr: Mod.Attr[Msg] => attr }.flatMap(_.flattened)

  private[scalive] def contentMods: Vector[Mod.Content[Msg]] =
    mods.collect { case content: Mod.Content[Msg] => content }

  /** Returns a copy of the element with `values` before its modifiers, so any children among them
    * come first.
    */
  def prepended[Msg2 >: Msg](values: Mod[Msg2]*): HtmlElement[Msg2] =
    HtmlElement(tag, mods.prependedAll(values))

  /** Returns a copy of the element with `values` after its modifiers, so any children among them
    * come last.
    */
  def appended[Msg2 >: Msg](values: Mod[Msg2]*): HtmlElement[Msg2] =
    HtmlElement(tag, mods.appendedAll(values))

/** Direct construction of [[HtmlElement]] values from a tag and modifiers. */
object HtmlElement:
  /** Creates an element from `tag` and `mods`.
    *
    * Calling the tag, as in `div(...)`, is usually simpler and also accepts elements and
    * collections.
    */
  def apply[Msg](tag: HtmlTag, mods: Vector[Mod[Msg]]): HtmlElement[Msg] =
    new HtmlElement(tag, mods)

/** A reusable, validated HTML tag factory.
  *
  * @param name
  *   The tag name, such as `div` or `mwc-input`. An invalid name throws `IllegalArgumentException`.
  * @param void
  *   Whether the tag is a void element, such as `input`, rendered without a closing tag. Rendering
  *   a void element with children fails.
  */
class HtmlTag(val name: String, val void: Boolean = false):
  require(Escaping.validTag(name), s"invalid HTML tag name '$name'")

  /** Creates an element of this tag from `mods`, in document order.
    *
    * Pass attributes, bindings, strings, child elements, and `Option`s or collections of them, as
    * in `ul(cls := "items", items.map(item => li(item.name)))`.
    */
  def apply[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    HtmlElement(this, Mod.flatten(mods))

/** A typed, validated HTML attribute definition.
  *
  * @param name
  *   The attribute name, as rendered. An invalid name throws `IllegalArgumentException`.
  * @param codec
  *   The encoder that turns an assigned value into the attribute's text.
  */
class HtmlAttr[V](val name: String, val codec: Encoder[V, String]):
  require(Escaping.validAttrName(name), s"invalid HTML attribute name '$name'")

  private inline def presence = codec == BooleanAsAttrPresenceEncoder

  /** Sets the attribute to `value`.
    *
    * This form sets a constant value. A boolean presence attribute, such as `disabled`, is rendered
    * bare when `true` and omitted when `false`. Setting one attribute twice on an element fails
    * rendering, except for a [[CompositeHtmlAttr]] such as `cls`.
    */
  def :=(value: V): Mod.Attr[Nothing] =
    if presence then Mod.Attr.StaticValueAsPresence(name, codec.encode(value) != null)
    else Mod.Attr.Static(name, codec.encode(value))

  /** Sets the attribute to `value`.
    *
    * This form follows a signal, as in `disabled := model.map(_.lines.isEmpty)`, and updates the
    * attribute when the value changes.
    */
  def :=(value: Signal[V]): Mod.Attr[Nothing] =
    if presence then Mod.Attr.SignalValueAsPresence(name, value.map(codec.encode(_) != null))
    else Mod.Attr.SignalValue(name, value.map(codec.encode))

  /** Sets the attribute to the value the signal holds, and omits it while the signal holds `None`.
    */
  def optional(value: Signal[Option[V]]): Mod.Attr[Nothing] =
    Mod.Attr.SignalOptionalValue(name, value.map(_.map(codec.encode)))

/** A space-separated token attribute whose repeated modifiers contribute to one rendered value.
  *
  * Static values, signals, optional signals, and attribute modifiers selected by `chooseMod` are
  * split on HTML ASCII whitespace. Empty tokens are discarded, exact duplicate tokens retain their
  * first position, and the attribute is omitted when no tokens remain. Ordinary [[HtmlAttr]]
  * definitions remain scalar rather than composing.
  */
final class CompositeHtmlAttr(name: String) extends HtmlAttr[String](name, StringAsIsEncoder):
  /** Adds the whitespace-separated tokens of `value` to the attribute.
    *
    * This form adds constant tokens, as in `cls := "card featured"`.
    */
  override def :=(value: String): Mod.Attr[Nothing] =
    Mod.Attr.CompositeStatic(name, value)

  /** Adds the whitespace-separated tokens of `value` to the attribute.
    *
    * This form adds the tokens that the signal currently holds.
    */
  override def :=(value: Signal[String]): Mod.Attr[Nothing] =
    Mod.Attr.CompositeSignalValue(name, value)

  /** Adds the tokens that the signal holds, and none while it holds `None`. */
  override def optional(value: Signal[Option[String]]): Mod.Attr[Nothing] =
    Mod.Attr.CompositeSignalOptionalValue(name, value)

/** Constructors for custom [[CompositeHtmlAttr]] definitions. */
object CompositeHtmlAttr:
  /** Creates a reusable composite definition for a space-separated token attribute. */
  def apply(name: String): CompositeHtmlAttr = new CompositeHtmlAttr(name)

/** A protocol-neutral component dispatch selected by a rendered event binding. */
sealed private[scalive] trait ComponentDispatch:
  type Message
  def message: Message

private[scalive] object ComponentDispatch:
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
  case Form(data: FormData, meta: RawFormEvent.Meta = RawFormEvent.Meta.empty)

  def params: Map[String, String] = this match
    case Params(values)   => values
    case Form(data, meta) =>
      val values = data.asMap ++ meta.params
      meta.rawSubmitter match
        case Some(RawFormSubmitter(name, value)) if !values.contains(name) =>
          values.updated(name, value)
        case _ => values

  def formData: FormData = this match
    case Params(values) => FormData.fromMap(values)
    case Form(data, _)  => data

  def formEvent[A](codec: FormCodec[A], kind: FormEventKind): RawFormEvent[A] = this match
    case Params(values) =>
      RawFormEvent.decode(FormData.fromMap(values), codec, kind, RawFormEvent.Meta.empty)
    case Form(data, meta) => RawFormEvent.decode(data, codec, kind, meta)

  def typedFormEvent[Event](
    decode: (FormData, FormEventKind, RawFormEvent.Meta) => Event,
    kind: FormEventKind
  ): Event = this match
    case Params(values)   => decode(FormData.fromMap(values), kind, RawFormEvent.Meta.empty)
    case Form(data, meta) =>
      val effectiveKind =
        if meta.recovery && kind == FormEventKind.Changed then FormEventKind.Recovered else kind
      decode(data, effectiveKind, meta)
end BindingPayload

/** Builder for typed server events and declarative browser commands.
  *
  * Obtain bindings from `on` or the relevant feature helper; `dom` and `connection` provide
  * [[JSCommandBinding]]s instead. Configure rate limits first, then complete the binding with a
  * message, as in `on.keyUp.debounce(300.millis).withValue(Msg.Search(_))`. See [event
  * bindings](https://scalive.dev/guides/html-dsl-and-event-bindings#choose-a-binding).
  *
  * @param name
  *   The `phx-*` attribute that the binding renders, such as `phx-click`.
  */
class HtmlAttrBinding private[scalive] (
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

  /** Sends the latest event once no new event has arrived for `duration`.
    *
    * A pending event is sent immediately when the element loses focus. The limit applies to every
    * binding on the element. For a form's change events, put `phx.debounce` on the control instead.
    */
  def debounce(duration: FiniteDuration): HtmlAttrBinding =
    append(durationAttr("phx-debounce", duration))

  /** Waits until the element loses focus, then sends the latest event. */
  def debounceOnBlur: HtmlAttrBinding = append(Mod.Attr.Static("phx-debounce", "blur"))

  /** Sends the first event immediately, then ignores further events until `duration` has passed.
    *
    * The limit applies to every binding on the element.
    */
  def throttle(duration: FiniteDuration): HtmlAttrBinding =
    append(durationAttr("phx-throttle", duration))

  /** Sends `message` to a stateful component instead of the LiveView.
    *
    * This form addresses the instance by its component class and stable ID, as returned by
    * `component`.
    */
  def to[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(Mod.Attr.RoutedBinding(name, _ => ComponentDispatch.Instance(instance, message)))

  /** Sends `message` to a stateful component instead of the LiveView.
    *
    * This form addresses an instance of a component that emits outputs.
    */
  def to[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(
      Mod.Attr.RoutedBinding(name, _ => ComponentDispatch.OutputInstance(instance, message))
    )

  /** Sends `message` to a stateful component instead of the LiveView.
    *
    * Inside a component's `view`, pass its `self`, as in `on.click.to(self)(Msg.Vote)`, together
    * with `phx.target(self)`.
    */
  def to[Msg](ref: ComponentRef[Msg])(message: Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.ComponentBinding(name, ref, _ => message))

  /** Sends `message` to the component of class `component` that the browser targets.
    *
    * This fixes only the message type. Add `phx.target` to choose the instance.
    */
  def toComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(Mod.Attr.RoutedBinding(name, _ => ComponentDispatch.Definition(component, message)))

  /** Completes the binding with what happens when the event fires.
    *
    * This form runs a client-side `JS` command, which may push a typed message itself.
    */
  def apply[Msg](command: JSCommand[Msg]): Mod.Attr[Msg] =
    configured(Mod.Attr.JsBinding(name, command))

  /** Completes the binding with what happens when the event fires.
    *
    * This form runs the `JS` command that the signal currently holds.
    */
  @targetName("applySignalCommand")
  def apply[Msg](command: Signal[JSCommand[Msg]]): Mod.Attr[Msg] =
    configured(Mod.Attr.SignalJsBinding(name, command))

  /** Completes the binding with what happens when the event fires.
    *
    * This form sends a constant message, as in `on.click(Msg.Save)`.
    */
  def apply[Msg](message: Msg): Mod.Attr[Msg] = apply(_ => message)

  /** Completes the binding with what happens when the event fires.
    *
    * This form sends the message that the signal currently holds, as in
    * `on.click(line.map(line => Msg.Remove(line.product)))` for one row of a list.
    */
  def apply[Msg](message: Signal[Msg]): Mod.Attr[Msg] =
    configured(Mod.Attr.SignalBinding(name, message, (value, _) => value))

  /** Completes the binding with what happens when the event fires.
    *
    * This form builds the message from the signal's current value and the event's payload. Payload
    * values come from the browser; validate them before use.
    */
  def apply[A, Msg](
    current: Signal[A]
  )(
    f: (A, Map[String, String]) => Msg
  ): Mod.Attr[Msg] =
    configured(Mod.Attr.SignalBinding(name, current, (value, payload) => f(value, payload.params)))

  /** Completes the binding with what happens when the event fires.
    *
    * This form builds the message from the event's payload, such as the `key` of a key event.
    * Payload values come from the browser; validate them before use.
    */
  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.Binding(name, payload => f(payload.params)))

  /** Builds the message from the form fields the event carries.
    *
    * This form passes the raw, untrusted field values. Prefer `FormDefinition.onChange` and
    * `onSubmit`, which decode them into a typed form.
    */
  def form[Msg](f: FormData => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.FormBinding(name, f))

  /** Builds the message from the form fields the event carries.
    *
    * This form decodes the fields with `codec`.
    */
  def form[A, Msg](codec: FormCodec[A])(f: RawFormEvent[A] => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.FormEventBinding(name, codec, f))

  /** Builds the message from the form fields the event carries.
    *
    * This form decodes the fields into `definition`'s typed event, like `definition.onChange`.
    */
  def form[Owner, Domain, Msg](
    definition: FormDefinition[Owner, Domain]
  )(
    f: definition.Event => Msg
  ): Mod.Attr[Msg] =
    configured(
      Mod.Attr.TypedFormEventBinding(
        name,
        (data, kind, meta) => definition.event(data, kind, meta),
        f
      )
    )

  private[scalive] def formWith[Event, Msg](
    decode: (FormData, FormEventKind, RawFormEvent.Meta) => Event
  )(
    f: Event => Msg
  ): Mod.Attr[Msg] =
    configured(Mod.Attr.TypedFormEventBinding(name, decode, f))

  /** Builds the message from the event's `value`, or `None` when the payload has none.
    *
    * An unchecked checkbox or radio button sends no `value`.
    */
  def withValueOption[Msg](f: Option[String] => Msg): Mod.Attr[Msg] =
    apply(values => f(values.get("value")))

  /** Builds the message from the event's `value`, such as a control's current text.
    *
    * Supplies an empty string when the payload has no `value`; use [[withValueOption]] when that
    * case matters.
    */
  def withValue[Msg](f: String => Msg): Mod.Attr[Msg] =
    withValueOption(value => f(value.getOrElse("")))

  /** Builds the message from the event's `value` read as a boolean, or `None` when it is missing.
    *
    * `on`, `yes`, and `true` read as `true`; `off`, `no`, and `false` as `false`; anything else as
    * `None`.
    */
  def withBoolValueOption[Msg](f: Option[Boolean] => Msg): Mod.Attr[Msg] =
    withValueOption(value =>
      f(value.flatMap {
        case "on" | "yes" | "true"  => Some(true)
        case "off" | "no" | "false" => Some(false)
        case _                      => None
      })
    )

  /** Builds the message from the event's `value` read as a boolean, as for a checkbox.
    *
    * `on`, `yes`, and `true` read as `true`; anything else, including a missing value, as `false`.
    */
  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg] =
    withBoolValueOption(value => f(value.getOrElse(false)))
end HtmlAttrBinding

/** A key event binding, which can also filter the keys it reacts to with [[key]]. */
final class KeyHtmlAttrBinding private[scalive] (
  name: String,
  override protected val companionAttrs: Vector[Mod.Attr[Nothing]] = Vector.empty)
    extends HtmlAttrBinding(name, companionAttrs):
  override protected def recreate(attrs: Vector[Mod.Attr[Nothing]]): KeyHtmlAttrBinding =
    KeyHtmlAttrBinding(name, attrs)

  override protected def append(attr: Mod.Attr[Nothing]): KeyHtmlAttrBinding =
    recreate(companionAttrs :+ attr)

  /** Like [[HtmlAttrBinding.debounce]], keeping the key filter available. */
  override def debounce(duration: FiniteDuration): KeyHtmlAttrBinding =
    append(durationAttr("phx-debounce", duration))

  /** Like [[HtmlAttrBinding.debounceOnBlur]], keeping the key filter available. */
  override def debounceOnBlur: KeyHtmlAttrBinding =
    append(Mod.Attr.Static("phx-debounce", "blur"))

  /** Like [[HtmlAttrBinding.throttle]], keeping the key filter available.
    *
    * On a key-down binding, a press of a different key is sent immediately.
    */
  override def throttle(duration: FiniteDuration): KeyHtmlAttrBinding =
    append(durationAttr("phx-throttle", duration))

  /** Reacts only to `value`, as in `on.windowKeyDown.key(Key.Escape)(Msg.Close)`.
    *
    * The filter applies to every key binding on the element.
    */
  def key(value: Key): KeyHtmlAttrBinding = append(Mod.Attr.Static("phx-key", value.value))
end KeyHtmlAttrBinding

/** Builder for a client lifecycle attribute, such as `phx-mounted`, which runs a `JS` command.
  *
  * The Phoenix client runs only `JS` commands for these attributes, so the builder accepts nothing
  * else. To send a message, push it from the command, as in `dom.onMount(JS.push(Msg.Shown))`.
  * Obtain one from `dom` or `connection`.
  *
  * @param name
  *   The `phx-*` attribute that the binding renders, such as `phx-mounted`.
  */
final class JSCommandBinding private[scalive] (val name: String):
  /** Runs `command` when the lifecycle point occurs.
    *
    * This form runs a constant command.
    */
  def apply[Msg](command: JSCommand[Msg]): Mod.Attr[Msg] = Mod.Attr.JsBinding(name, command)

  /** Runs `command` when the lifecycle point occurs.
    *
    * This form runs the command that the signal currently holds.
    */
  @targetName("applySignalCommand")
  def apply[Msg](command: Signal[JSCommand[Msg]]): Mod.Attr[Msg] =
    Mod.Attr.SignalJsBinding(name, command)

/** A typed declarative HTML modifier. */
sealed trait Mod[+Msg]

/** The kinds of [[Mod]], and helpers for passing modifiers through reusable functions. */
object Mod:
  /** One modifier or element, or a collection of them, accepted by HTML and component factories.
    *
    * An `Option` or `Vector` of elements is a valid child, as in
    * `ul(items.map(item => li(item.name)))`.
    */
  type Input[+Msg] = Mod[Msg] | HtmlElement[Msg] | IterableOnce[Mod[Msg] | HtmlElement[Msg]]

  /** Normalizes modifier inputs while preserving their encounter order. */
  def flatten[Msg](inputs: IterableOnce[Input[Msg]]): Vector[Mod[Msg]] =
    inputs.iterator.flatMap {
      case mod: Mod[Msg]             => Iterator.single(mod)
      case element: HtmlElement[Msg] => Iterator.single(Content.Tag(element))
      case values: IterableOnce[Mod[Msg] | HtmlElement[Msg]] =>
        values.iterator.map {
          case mod: Mod[Msg]             => mod
          case element: HtmlElement[Msg] => Content.Tag(element)
        }
    }.toVector

  /** An attribute or event binding modifier, as returned by `:=` and the binding builders. */
  enum Attr[+Msg] extends Mod[Msg]:
    private[scalive] case Static(name: String, value: String)                 extends Attr[Nothing]
    private[scalive] case StaticValueAsPresence(name: String, value: Boolean) extends Attr[Nothing]
    private[scalive] case SignalValue(name: String, value: Signal[String])    extends Attr[Nothing]
    private[scalive] case SignalOptionalValue(name: String, value: Signal[Option[String]])
        extends Attr[Nothing]
    private[scalive] case SignalValueAsPresence(name: String, value: Signal[Boolean])
        extends Attr[Nothing]
    private[scalive] case CompositeStatic(name: String, value: String) extends Attr[Nothing]
    private[scalive] case CompositeSignalValue(name: String, value: Signal[String])
        extends Attr[Nothing]
    private[scalive] case CompositeSignalOptionalValue(
      name: String,
      value: Signal[Option[String]]) extends Attr[Nothing]
    private[scalive] case Binding[Msg](name: String, operation: BindingPayload => Msg)
        extends Attr[Msg]
    private[scalive] case SignalBinding[A, Msg](
      name: String,
      signal: Signal[A],
      operation: (A, BindingPayload) => Msg) extends Attr[Msg]
    private[scalive] case FormBinding[Msg](name: String, operation: FormData => Msg)
        extends Attr[Msg]
    private[scalive] case FormEventBinding[A, Msg](
      name: String,
      codec: FormCodec[A],
      operation: RawFormEvent[A] => Msg) extends Attr[Msg]
    private[scalive] case TypedFormEventBinding[Event, Msg](
      name: String,
      decode: (FormData, FormEventKind, RawFormEvent.Meta) => Event,
      operation: Event => Msg)                                                  extends Attr[Msg]
    private[scalive] case JsBinding[Msg](name: String, command: JSCommand[Msg]) extends Attr[Msg]
    private[scalive] case SignalJsBinding[Msg](name: String, command: Signal[JSCommand[Msg]])
        extends Attr[Msg]
    private[scalive] case RoutedBinding(
      name: String,
      operation: BindingPayload => ComponentDispatch) extends Attr[Nothing]
    private[scalive] case ComponentBinding[Msg](
      name: String,
      target: ComponentRef[Msg],
      operation: BindingPayload => Msg)                                   extends Attr[Msg]
    private[scalive] case ComponentTarget[Msg](target: ComponentRef[Msg]) extends Attr[Msg]
    private[scalive] case Group[Msg](attrs: Vector[Attr[Msg]])            extends Attr[Msg]

    private[scalive] def flattened: Vector[Attr[Msg]] = this match
      case Group(attrs) => attrs.flatMap(_.flattened)
      case attr         => Vector(attr)
  end Attr

  /** A content modifier: text, a child element, or rendered collection, component, or LiveView. */
  enum Content[+Msg] extends Mod[Msg]:
    private[scalive] case Text(text: String, raw: Boolean = false) extends Content[Nothing]
    private[scalive] case SignalText(value: Signal[String], raw: Boolean = false)
        extends Content[Nothing]
    private[scalive] case SignalChoice[A, Msg](
      value: Signal[A],
      branches: Vector[(A, HtmlElement[Msg])]) extends Content[Msg]
    private[scalive] case SignalModChoice[A, Msg](
      value: Signal[A],
      branches: Vector[(A, Mod[Msg])]) extends Content[Msg]
    private[scalive] case SignalOption[A, Msg](
      value: Signal[Option[A]],
      project: Signal[A] => HtmlElement[Msg]) extends Content[Msg]
    private[scalive] case Keyed[Key, Msg](entries: Vector[Content.Keyed.Entry[Key, Msg]])
        extends Content[Msg]
    private[scalive] case SignalKeyed[A, Key, Msg](
      values: Signal[Iterable[A]],
      key: A => Key,
      project: (Key, Signal[A]) => HtmlElement[Msg]) extends Content[Msg]
    private[scalive] case SignalKeyedByIndex[A, Msg](
      values: Signal[Iterable[A]],
      project: (Int, Signal[A]) => HtmlElement[Msg]) extends Content[Msg]
    private[scalive] case Stream[A, Msg](
      value: streams.LiveStream[A],
      project: (String, A) => HtmlElement[Msg]) extends Content[Msg]
    private[scalive] case SignalStream[A, Msg](
      value: Signal[streams.LiveStream[A]],
      project: (String, Signal[A]) => HtmlElement[Msg])            extends Content[Msg]
    private[scalive] case Tag[Msg](element: HtmlElement[Msg])      extends Content[Msg]
    private[scalive] case Component[Msg](spec: ComponentSpec[Msg]) extends Content[Msg]
    private[scalive] case NestedView(spec: NestedViewSpec)         extends Content[Nothing]
    private[scalive] case Flash(kind: FlashKind, project: String => HtmlElement[Nothing])
        extends Content[Nothing]
  end Content

  object Content:
    private[scalive] object Keyed:
      final case class Entry[+Key, +Msg](key: Key, element: HtmlElement[Msg])
end Mod

/** A declarative nested LiveView requirement. */
sealed private[scalive] trait NestedViewSpec

private[scalive] object NestedViewSpec:
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
