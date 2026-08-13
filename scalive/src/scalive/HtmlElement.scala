package scalive

import scala.concurrent.duration.FiniteDuration

import scalive.JSCommands.JSCommand
import scalive.Mod.Attr
import scalive.Mod.Content
import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder

/** An immutable element in Scalive's typed HTML tree.
  *
  * `tag` determines the opening and closing markup. `mods` contains both attributes and child
  * content; rendering projects those two kinds independently, so attributes are emitted in the
  * opening tag even when they occur after content in `mods`. The covariant `Msg` parameter is the
  * type that server event bindings in this subtree may produce.
  *
  * Ordinary string content and encoded attribute values are escaped when rendered. Escaping only
  * makes text safe for its HTML context: it does not sanitize URLs, CSS, JavaScript, or other
  * attribute semantics. [[scalive.rawHtml]] deliberately bypasses text escaping.
  *
  * @param tag
  *   tag metadata used to serialize this element
  * @param mods
  *   attributes and content in DSL order
  * @tparam Msg
  *   messages that bindings in this element may emit
  */
class HtmlElement[+Msg](val tag: HtmlTag, val mods: Vector[Mod[Msg]]):
  /** Returns the static template fragments for this tree.
    *
    * Slots occupied by bindings, boolean-presence attributes, components, nested LiveViews, flash,
    * or keyed content are omitted from the result. Consequently this is an advanced template
    * inspection API, not complete serialized HTML; use [[HtmlBuilder.build]] to render a standalone
    * tree. Included text and attribute fragments are already HTML-escaped unless their text was
    * explicitly marked raw.
    */
  def static: Seq[String] = StaticBuilder.build(this)

  /** Returns this element's attributes in source order, recursively flattening [[Mod.Attr.Group]]
    * values.
    *
    * Attributes of nested elements are not included.
    */
  def attrMods: Seq[Mod.Attr[Msg]] =
    mods.collect { case mod: Mod.Attr[Msg] => mod }.flatMap(_.flattened)

  /** Returns this element's direct content modifiers in source order.
    *
    * Descendant content is not traversed.
    */
  def contentMods: Seq[Mod.Content[Msg]] =
    mods.collect { case mod: Mod.Content[Msg] => mod }

  /** Returns a new element with `mod` inserted before the existing modifiers.
    *
    * This element and its `mods` vector are unchanged. Accepting a supertype of `Msg` allows the
    * returned tree to widen when a prepended binding can produce additional messages.
    */
  def prepended[Msg2 >: Msg](mod: Mod[Msg2]*): HtmlElement[Msg2] =
    HtmlElement(tag, mods.prependedAll(mod))

  /** Returns a new element with `mod` inserted after the existing modifiers.
    *
    * This element and its `mods` vector are unchanged. Accepting a supertype of `Msg` allows the
    * returned tree to widen when an appended binding can produce additional messages.
    */
  def appended[Msg2 >: Msg](mod: Mod[Msg2]*): HtmlElement[Msg2] =
    HtmlElement(tag, mods.appendedAll(mod))
end HtmlElement

/** A reusable HTML tag factory.
  *
  * Custom names are syntax-checked when this value is created: a name must start with a lowercase
  * ASCII letter and its remaining characters may contain letters, digits, underscores, colons, and
  * hyphens. This check prevents a name from breaking out of tag markup; it is not an HTML
  * allow-list and does not establish that a custom element is implemented by the browser.
  *
  * When `void` is true, rendering emits `<name/>` and no closing tag. Content is not rejected or
  * discarded, so callers defining custom void tags must still avoid supplying children.
  *
  * @param name
  *   validated tag name
  * @param void
  *   whether this tag is serialized without a closing tag
  * @throws IllegalArgumentException
  *   if `name` is not a valid tag name
  */
class HtmlTag(val name: String, val void: Boolean = false):
  require(Escaping.validTag(name), s"invalid HTML tag name '$name'")

  /** Creates an immutable element from this tag and the supplied modifiers.
    *
    * Each argument may be one modifier or an `IterableOnce` of modifiers. Collections are consumed
    * once and flattened one level, in argument and iteration order. This permits conditional and
    * grouped DSL values such as `Option[Mod[Msg]]` and `Vector[Mod[Msg]]` without changing the
    * resulting message type.
    */
  def apply[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] = HtmlElement(
    this,
    mods.toVector.flatMap {
      case m: Mod[Msg]                => Some(m)
      case ms: IterableOnce[Mod[Msg]] => ms
    }
  )

/** A typed HTML attribute definition.
  *
  * Assigning a value runs `codec` immediately and stores the encoded string in a [[Mod.Attr]]. The
  * renderer subsequently HTML-escapes that string. Encoding is not sanitization: custom encoders
  * and attributes remain responsible for semantic constraints such as trusted URL schemes, CSS, or
  * JavaScript.
  *
  * Custom encoders must return a non-null string. The library's boolean-presence encoder is the
  * sole exception because [[HtmlAttr]] recognizes its `null` false result before rendering.
  *
  * Custom names are syntax-checked but are not restricted to attributes defined by HTML. A valid
  * name starts with an ASCII letter or colon and continues with letters, digits, hyphens, colons,
  * periods, or underscores.
  *
  * @param name
  *   validated attribute name
  * @param codec
  *   conversion from the Scala value to its string representation
  * @tparam V
  *   Scala value accepted by this attribute
  * @throws IllegalArgumentException
  *   if `name` is not a valid attribute name
  */
class HtmlAttr[V](val name: String, val codec: Encoder[V, String]):
  require(Escaping.validAttrName(name), s"invalid HTML attribute name '$name'")

  private inline def isBooleanAsAttrPresence = codec == BooleanAsAttrPresenceEncoder

  /** Encodes `value` as an attribute modifier.
    *
    * With the library's [[scalive.codecs.BooleanAsAttrPresenceEncoder]] singleton, `true` renders
    * the bare attribute name and `false` omits the attribute. Every other encoder produces a
    * quoted, escaped attribute value. The returned modifier emits no server message and can
    * therefore be used in an element of any message type.
    */
  def :=(value: V): Mod.Attr[Nothing] =
    if isBooleanAsAttrPresence then
      Mod.Attr.StaticValueAsPresence(
        name,
        codec.encode(value) != null
      )
    else Mod.Attr.Static(name, codec.encode(value))

/** A typed server-event or JavaScript-command attribute builder.
  *
  * Values such as `on.click`, `on.change`, and `dom.onMount` are instances of this class.
  * Server-message terminal methods create a generated identifier registered with the owning
  * LiveView runtime; command terminal methods serialize declarative browser operations instead. The
  * terminal method's `Msg` result is reflected in the enclosing [[HtmlElement]] type, preventing a
  * view from binding messages that its handler cannot accept.
  *
  * Rate-limit methods are immutable configuration: each returns a new builder and leaves this one
  * unchanged. Their companion attributes are grouped with whichever terminal binding is eventually
  * created.
  *
  * @param name
  *   validated protocol attribute name, normally a `phx-*` event attribute
  * @param companionAttrs
  *   advanced preconfigured attributes to emit beside the terminal binding
  * @throws IllegalArgumentException
  *   if `name` is not a valid attribute name
  */
class HtmlAttrBinding(
  val name: String,
  protected val companionAttrs: Vector[Mod.Attr[Nothing]] = Vector.empty):
  require(Escaping.validAttrName(name), s"invalid HTML attribute name '$name'")

  /** Recreates this builder with a new immutable companion-attribute sequence.
    *
    * Subclasses override this factory to preserve their more specific fluent return type.
    */
  protected def recreate(attrs: Vector[Mod.Attr[Nothing]]): HtmlAttrBinding =
    new HtmlAttrBinding(name, attrs)

  /** Returns a recreated builder with `attr` appended to its companion attributes. */
  protected def append(attr: Mod.Attr[Nothing]): HtmlAttrBinding =
    recreate(companionAttrs :+ attr)

  private def configured[Msg](binding: Mod.Attr[Msg]): Mod.Attr[Msg] =
    if companionAttrs.isEmpty then binding
    else Mod.Attr.Group(binding +: companionAttrs)

  /** Debounces the eventual binding by `duration` in the browser.
    *
    * The duration must be non-negative and is serialized as whole milliseconds with
    * `FiniteDuration.toMillis`; positive sub-millisecond values therefore become `0`.
    *
    * @return
    *   a new configured builder
    * @throws IllegalArgumentException
    *   if `duration` is negative
    */
  def debounce(duration: FiniteDuration): HtmlAttrBinding =
    append(durationAttr("phx-debounce", duration))

  /** Defers the eventual binding until the element loses focus.
    *
    * This emits Phoenix's `phx-debounce="blur"` configuration and returns a new builder.
    */
  def debounceOnBlur: HtmlAttrBinding =
    append(Mod.Attr.Static("phx-debounce", "blur"))

  /** Throttles the eventual binding to at most once per `duration` in the browser.
    *
    * The duration must be non-negative and is serialized as whole milliseconds with
    * `FiniteDuration.toMillis`; positive sub-millisecond values therefore become `0`.
    *
    * @return
    *   a new configured builder
    * @throws IllegalArgumentException
    *   if `duration` is negative
    */
  def throttle(duration: FiniteDuration): HtmlAttrBinding =
    append(durationAttr("phx-throttle", duration))

  /** Builds one non-negative millisecond companion attribute for a rate limit. */
  protected def durationAttr(name: String, duration: FiniteDuration): Mod.Attr[Nothing] =
    require(duration.length >= 0, s"$name duration must not be negative")
    Mod.Attr.Static(name, duration.toMillis.toString)

  /** Routes `message` to one stable component instance.
    *
    * The target is the instance's component class and logical `id`; unlike the [[ComponentRef]]
    * overload, this overload does not emit `phx-target` and does not depend on a current numeric
    * component ID. The event payload is ignored. Because the message is routed to the component
    * rather than the root LiveView, the returned modifier has message type `Nothing`.
    */
  def to[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(
      Mod.Attr.RoutedBinding(
        name,
        _ =>
          ComponentInstanceMessage(
            ComponentIdentity(instance.component.getClass, instance.id),
            message
          )
      )
    )

  /** Routes `message` to an output-producing component instance. */
  def to[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(
      Mod.Attr.RoutedBinding(
        name,
        _ =>
          ComponentInstanceMessage(
            ComponentIdentity(instance.component.getClass, instance.id),
            message
          )
      )
    )

  /** Routes `message` to the component represented by `ref`.
    *
    * This emits both the event binding and `phx-target` with the component's current numeric ID. A
    * typed [[ComponentRef]] is normally the `self` value supplied to `LiveComponent.render`, so a
    * component cannot accidentally bind another component's message type. The event payload is
    * ignored.
    */
  def to[Msg](ref: ComponentRef[Msg])(message: Msg): Mod.Attr[Msg] =
    configured(
      Mod.Attr.Group(
        Vector(
          Mod.Attr.Binding(name, _ => message),
          Mod.Attr.Static("phx-target", ref.toString)
        )
      )
    )

  /** Routes `message` to a component selected by a separate `phx-target` attribute.
    *
    * The component value fixes the accepted message type and records the runtime component class.
    * Put a `phx.target(ComponentRef)` or `phx.target(DomSelector)` modifier on the event element to
    * select the receiving component according to Phoenix targeting rules. This method does not add
    * a target itself. Because the message is routed to a component, the returned modifier has
    * message type `Nothing`.
    */
  def toComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model]
  )(
    message: Msg
  ): Mod.Attr[Nothing] =
    configured(
      Mod.Attr.RoutedBinding(name, _ => ComponentTargetMessage(component.getClass, message))
    )

  /** Binds a declarative browser command sequence.
    *
    * The command is serialized as JSON in the attribute. Any `JS.push(message)` operations in the
    * sequence register their typed messages with the server; purely client-side commands preserve
    * the `Nothing` message type of `JS`.
    */
  def apply[Msg](cmd: JSCommand[Msg]): Mod.Attr[Msg] =
    configured(Mod.Attr.JsBinding(name, cmd))

  /** Binds a constant server message, ignoring the browser event payload. */
  def apply[Msg](msg: Msg): Mod.Attr[Msg] =
    apply(_ => msg)

  /** Binds a message computed from the browser event's string parameters.
    *
    * Form fields are exposed here as a last-value-per-name map, augmented with available event
    * metadata and submitter parameters. Use [[form]] when repeated field values, field order, typed
    * decoding, or structured form metadata matter.
    */
  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.Binding(name, f))

  /** Binds a message computed from structured form data.
    *
    * [[FormData]] preserves the ordered raw name/value pairs, including repeated names, and also
    * provides convenience lookup methods. A non-form parameter payload is converted to `FormData`
    * without repeated values.
    */
  def form[Msg](f: FormData => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.FormBinding(name, f))

  /** Decodes each form payload with `codec` before computing a message.
    *
    * The resulting [[FormEvent]] retains raw data and metadata such as the changed target,
    * submitter, recovery state, component ID, and uploads. Decoding success or validation failure
    * is represented by `event.value: Either[FormErrors, A]`; validation failures remain values. A
    * binding whose name is exactly `phx-submit` marks the event and its [[FormState]] as submitted.
    */
  def form[A, Msg](codec: FormCodec[A])(f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    configured(Mod.Attr.FormEventBinding(name, codec, f))

  /** Binds a message from the optional `value` event parameter.
    *
    * `None` represents a missing key; a present empty string remains `Some("")`.
    */
  def withValueOption[Msg](f: Option[String] => Msg): Mod.Attr[Msg] =
    apply(m => f(m.get("value")))

  /** Binds a message from the `value` event parameter, defaulting a missing value to `""`. */
  def withValue[Msg](f: String => Msg): Mod.Attr[Msg] =
    withValueOption(value => f(value.getOrElse("")))

  /** Binds a message from an optionally decoded boolean `value` parameter.
    *
    * The exact lowercase strings `on`, `yes`, and `true` decode to `Some(true)`; `off`, `no`, and
    * `false` decode to `Some(false)`. Missing values and all other spellings decode to `None`;
    * values are not trimmed or case-normalized.
    */
  def withBoolValueOption[Msg](f: Option[Boolean] => Msg): Mod.Attr[Msg] =
    withValueOption(value =>
      f(value.flatMap {
        case "on" | "yes" | "true"  => Some(true)
        case "off" | "no" | "false" => Some(false)
        case _                      => None
      })
    )

  /** Binds a message from a decoded boolean `value`, defaulting missing or unrecognized values to
    * `false`.
    */
  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg] =
    withBoolValueOption(value => f(value.getOrElse(false)))
end HtmlAttrBinding

/** An event binding builder with a browser keyboard-event filter.
  *
  * Instances back `on.keyDown`, `on.keyUp`, and their window variants. [[key]] and inherited
  * rate-limit methods all return new `KeyHtmlAttrBinding` values, so they can be chained in any
  * order before a terminal message, payload function, form handler, component target, or
  * `JSCommand` is supplied.
  *
  * @param name
  *   validated keyboard-event attribute name
  * @param companionAttrs
  *   advanced preconfigured attributes to emit beside the terminal binding
  */
final class KeyHtmlAttrBinding(
  name: String,
  override protected val companionAttrs: Vector[Mod.Attr[Nothing]] = Vector.empty)
    extends HtmlAttrBinding(name, companionAttrs):

  override protected def recreate(attrs: Vector[Mod.Attr[Nothing]]): KeyHtmlAttrBinding =
    new KeyHtmlAttrBinding(name, attrs)

  override protected def append(attr: Mod.Attr[Nothing]): KeyHtmlAttrBinding =
    recreate(companionAttrs :+ attr)

  /** Configures inherited debounce behavior while preserving the key-specific fluent type. */
  override def debounce(duration: FiniteDuration): KeyHtmlAttrBinding =
    append(durationAttr("phx-debounce", duration))

  /** Configures inherited blur debounce behavior while preserving the key-specific fluent type. */
  override def debounceOnBlur: KeyHtmlAttrBinding =
    append(Mod.Attr.Static("phx-debounce", "blur"))

  /** Configures inherited throttle behavior while preserving the key-specific fluent type. */
  override def throttle(duration: FiniteDuration): KeyHtmlAttrBinding =
    append(durationAttr("phx-throttle", duration))

  /** Filters the eventual binding to browser keyboard events whose `KeyboardEvent.key` is `key`.
    *
    * This emits the `phx-key` companion attribute and returns a new builder.
    */
  def key(key: Key): KeyHtmlAttrBinding =
    append(Mod.Attr.Static("phx-key", key.value))

/** A modifier accepted by an [[HtmlTag]].
  *
  * A modifier is either an attribute or content, and its covariant `Msg` parameter records messages
  * that may be emitted by bindings in that modifier. Application code normally obtains modifiers
  * from typed attributes, `on.*` bindings, elements, strings, component and flash helpers, or keyed
  * collection extensions. The concrete cases are public for advanced tree inspection and framework
  * integrations; constructing them directly bypasses the validation and invariants of those DSL
  * entry points.
  */
sealed trait Mod[+Msg]

/** Namespaces the concrete attribute and content nodes of a [[Mod]] tree. */
object Mod:
  /** Attribute modifiers understood by the renderer and binding registry.
    *
    * Direct case construction does not validate attribute names. Prefer [[HtmlAttr]] for values and
    * [[HtmlAttrBinding]] for events.
    */
  enum Attr[+Msg] extends Mod[Msg]:
    /** A named string value. The renderer quotes and HTML-escapes `value`. */
    case Static(name: String, value: String) extends Attr[Nothing]

    /** A boolean-presence attribute: `true` emits the bare name and `false` emits nothing. */
    case StaticValueAsPresence(name: String, value: Boolean) extends Attr[Nothing]

    /** A server binding whose generated attribute ID invokes `f` with string event parameters. */
    case Binding[Msg](name: String, f: Map[String, String] => Msg) extends Attr[Msg]

    /** A server binding that retains the browser form payload as [[FormData]]. */
    case FormBinding[Msg](name: String, f: FormData => Msg) extends Attr[Msg]

    /** A server binding that decodes form data and metadata into a typed [[FormEvent]]. */
    case FormEventBinding[A, Msg](name: String, codec: FormCodec[A], f: FormEvent[A] => Msg)
        extends Attr[Msg]

    /** A JSON-encoded browser command sequence, including any typed `JS.push` bindings. */
    case JsBinding[Msg](name: String, command: JSCommand[Msg]) extends Attr[Msg]

    /** Framework routing for a message delivered to a component rather than the root LiveView. */
    case RoutedBinding(name: String, f: BindingPayload => ComponentRoutedMessage)
        extends Attr[Nothing]

    /** An ordered, wrapper-free group of attributes that must stay with one DSL modifier. */
    case Group[Msg](attrs: Vector[Attr[Msg]]) extends Attr[Msg]

    /** Recursively removes `Group` wrappers while preserving leaf attribute order. */
    def flattened: Vector[Attr[Msg]] =
      this match
        case Group(attrs) => attrs.flatMap(_.flattened)
        case attr         => Vector(attr)
  end Attr

  /** Content modifiers understood by lifecycle resolution and rendering.
    *
    * `Text`, `Tag`, and keyed collections describe ordinary tree content. Component, nested
    * LiveView, and flash cases are lifecycle placeholders normally created and resolved by their
    * package-level helpers rather than constructed as a rendering protocol by applications.
    */
  enum Content[+Msg] extends Mod[Msg]:
    /** A text node. `raw = false` HTML-escapes `text`; `raw = true` emits it verbatim without
      * sanitization.
      */
    case Text(text: String, raw: Boolean = false) extends Content[Nothing]

    /** A nested HTML element. */
    case Tag[Msg](el: HtmlElement[Msg]) extends Content[Msg]

    /** A component subtree already associated with its runtime component ID. Framework-owned. */
    case Component[Msg](cid: Int, el: HtmlElement[Msg]) extends Content[Msg]

    /** An unresolved stateful LiveComponent request produced by component rendering helpers. */
    case LiveComponent[Msg](spec: LiveComponentSpec[?, ?, ?, ?]) extends Content[Msg]

    /** An unresolved nested LiveView request produced by [[scalive.liveView]]. */
    case LiveView(spec: NestedLiveViewSpec[?, ?]) extends Content[Nothing]

    /** Deferred flash content resolved against the owning socket's message for `kind`. */
    case Flash(kind: String, f: String => HtmlElement[Nothing]) extends Content[Nothing]

    /** A keyed, wrapper-free sequence used for collection and stream diffing.
      *
      * Stable, unique entry keys let the diff preserve identity across inserts, removals, moves,
      * and updates. `stream` carries an optional browser stream patch. For stream renders,
      * `allEntries` may carry the full snapshot for lifecycle resolution and binding registration,
      * including rows not present in the current patch; only `entries` is emitted and diffed.
      * Prefer `splitBy`, `splitByIndex`, or the `LiveStream.stream` extension to constructing this
      * protocol case directly.
      */
    case Keyed(
      entries: Vector[Content.Keyed.Entry[Msg]],
      stream: Option[Diff.Stream] = None,
      allEntries: Option[Vector[Content.Keyed.Entry[Msg]]] = None) extends Content[Msg]
  end Content

  object Content:
    object Keyed:
      /** One keyed collection element.
        *
        * `key` supplies diff identity and is not rendered into the DOM. Keys should be stable and
        * unique among sibling entries. Stream helpers separately establish required DOM IDs on
        * their elements.
        */
      final case class Entry[+Msg](key: Any, element: HtmlElement[Msg])
end Mod
