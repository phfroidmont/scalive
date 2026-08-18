import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder
import scalive.codecs.IntAsStringEncoder
import scalive.codecs.StringAsIsEncoder
import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.components.Components
import scalive.defs.tags.HtmlTags

/** Ergonomic HTML, LiveView, component, navigation, event, stream, and upload API imported with
  * `scalive.*`.
  */
package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys with Components:

  export _root_.scalive.streams.api.*
  export _root_.scalive.upload.api.*

  /** The HTML boolean `defer` attribute, primarily for external scripts.
    *
    * `defer := true` emits the presence attribute; `false` omits it.
    */
  lazy val defer = htmlAttr("defer", codecs.BooleanAsAttrPresenceEncoder)

  /** Inserts trusted HTML without escaping it.
    *
    * Normal `String` content is HTML-escaped. This method deliberately bypasses that protection, so
    * untrusted or insufficiently sanitized input can execute scripts, inject attributes, or alter
    * the surrounding DOM. Use it only for HTML from a trusted source or a context-appropriate
    * sanitizer.
    *
    * @param html
    *   trusted markup to emit verbatim
    */
  def rawHtml(html: String): Mod[Nothing] = Mod.Content.Text(html, raw = true)

  /** Inserts trusted HTML supplied by a signal-backed value without escaping it. */
  def rawHtml(html: Signal[String]): Mod[Nothing] = Mod.Content.SignalText(html, raw = true)

  /** Renders the LiveView-aware document title expected by the browser client.
    *
    * `default` is used when `pageTitle` is absent, empty, or whitespace-only. The prefix and suffix
    * are applied to the rendered text and stored with the default in `data-*` metadata so connected
    * title updates can apply the same policy. This belongs in the root layout's `head`.
    *
    * @param pageTitle
    *   current title supplied by the root LiveView
    * @param default
    *   fallback title when no meaningful page title is available
    * @param prefix
    *   text prepended to either title
    * @param suffix
    *   text appended to either title
    */
  def liveTitle(
    pageTitle: Option[String],
    default: String,
    prefix: String = "",
    suffix: String = ""
  ): HtmlElement[Nothing] =
    val title = normalizePageTitle(pageTitle).getOrElse(default)
    titleTag(
      dataAttr("prefix")  := prefix,
      dataAttr("default") := default,
      dataAttr("suffix")  := suffix,
      s"$prefix$title$suffix"
    )

  /** Renders a signal-backed LiveView-aware document title. */
  def liveTitle(
    pageTitle: Signal[Option[String]],
    default: String,
    prefix: String,
    suffix: String
  ): HtmlElement[Nothing] =
    val title = pageTitle.map(normalizePageTitle(_).getOrElse(default))
    titleTag(
      dataAttr("prefix")  := prefix,
      dataAttr("default") := default,
      dataAttr("suffix")  := suffix,
      title.map(value => s"$prefix$value$suffix")
    )

  /** Renders a signal-backed title without a prefix or suffix. */
  def liveTitle(
    pageTitle: Signal[Option[String]],
    default: String
  ): HtmlElement[Nothing] =
    liveTitle(pageTitle, default, "", "")

  private[scalive] def normalizePageTitle(pageTitle: Option[String]): Option[String] =
    pageTitle.filter(_.trim.nonEmpty)

  /** Creates a typed handle for one stateful LiveComponent identity.
    *
    * A component is identified within its owning LiveView socket by its runtime component class and
    * `id`. Reuse this handle to render it, route typed events with `on.*.to`, and send typed prop
    * updates. The component mounts when that identity first appears, retains its model across
    * parent renders, and receives changed props through its update lifecycle. Rendering the same
    * identity twice in one tree is an error; keep IDs stable and unique per component class.
    */
  def component[Props, Msg, Model](
    liveComponent: LiveComponent[Props, Msg, Model],
    id: String
  ): LiveComponentInstance[Props, Msg, Model] =
    LiveComponentInstance(liveComponent, id)

  /** Creates a typed handle for an output-producing LiveComponent identity. */
  def component[Props, Msg, Model, Output](
    liveComponent: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String
  ): LiveComponentOutputInstance[Props, Msg, Model, Output] =
    LiveComponentOutputInstance(liveComponent, id)

  /** Renders a stateful LiveComponent with a string identity and typed props.
    *
    * Identity is the component's runtime class plus `id`, scoped to the owning LiveView socket. A
    * stable identity preserves the mounted model across parent renders; changed props invoke the
    * component update lifecycle. Removing the component ends that instance, while rendering the
    * same identity twice in one tree fails the render. Use [[component]] when the same identity
    * must also be referenced by event routing or `sendUpdate`.
    */
  def liveComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: String,
    props: Props
  ): Mod[Nothing] =
    Mod.Content.LiveComponent(LiveComponentSpec(component, id, props, None))

  /** Renders a stateful LiveComponent with props sampled from the committed parent graph. */
  def liveComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: String,
    props: Signal[Props]
  ): Mod[Nothing] =
    Mod.Content.SignalLiveComponent(LiveComponentSignalSpec(component, id, props, None))

  /** Renders a LiveComponent whose explicit logical ID and props are signal-backed values. */
  def liveComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: Signal[String],
    props: Signal[Props]
  ): Mod[Nothing] =
    Mod.Content.DynamicLiveComponent(LiveComponentDynamicSpec(component, id, props, None))

  /** Renders a LiveComponent whose logical ID is a signal and props are static. */
  def liveComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: Signal[String],
    props: Props
  ): Mod[Nothing] =
    liveComponent(component, id, id.map(_ => props))

  /** Renders an output-producing LiveComponent and maps its outputs into enclosing messages. */
  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    component: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String,
    props: Props,
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.LiveComponent(
      LiveComponentSpec(component, id, props, Some(value => onOutput(value.asInstanceOf[Output])))
    )

  /** Renders an output component whose logical ID and props are signal-backed values. */
  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    component: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: Signal[String],
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.DynamicLiveComponent(
      LiveComponentDynamicSpec(
        component,
        id,
        props,
        Some(value => onOutput(value.asInstanceOf[Output]))
      )
    )

  /** Renders an output-producing LiveComponent with signal props. */
  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    component: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String,
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.SignalLiveComponent(
      LiveComponentSignalSpec(
        component,
        id,
        props,
        Some(value => onOutput(value.asInstanceOf[Output]))
      )
    )

  /** Embeds an independently mounted LiveView inside the current LiveView.
    *
    * The nested LiveView has its own model, socket lifecycle, messages, flash, and component state.
    * `id` is its stable identity and determines its topic; nested LiveViews in the same parent
    * render must have distinct IDs. The by-name value is evaluated when a disconnected or connected
    * child lifecycle is mounted, so it should construct or return the intended child definition
    * without relying on parent render-time side effects.
    *
    * By default the parent owns the child: leaving the parent removes its child socket. A sticky
    * child survives parent teardown for LiveView navigation. `linkParentOnCrash` additionally
    * propagates a joined child's crash to the parent; otherwise the child can fail independently.
    *
    * @param id
    *   stable, sibling-unique nested LiveView identity
    * @param liveView
    *   child LiveView evaluated when its lifecycle starts
    * @param sticky
    *   whether the child survives parent teardown during live navigation
    * @param linkParentOnCrash
    *   whether a child crash should also fail the parent socket
    */
  def liveView[Msg: LiveMessageTag, Model](
    id: String,
    liveView: => LiveView[Msg, Model],
    sticky: Boolean = false,
    linkParentOnCrash: Boolean = false
  ): Mod[Nothing] =
    Mod.Content.LiveView(
      NestedLiveViewSpec(
        id,
        () => liveView,
        summon[LiveMessageTag[Msg]].classTag,
        sticky,
        linkParentOnCrash
      )
    )

  /** Embeds a nested LiveView constructed from one committed parent signal value. */
  def liveView[A, Msg: LiveMessageTag, Model](
    id: String,
    value: Signal[A],
    sticky: Boolean,
    linkParentOnCrash: A => Boolean
  )(
    build: A => LiveView[Msg, Model]
  ): Mod[Nothing] =
    Mod.Content.SignalLiveView(
      SignalNestedLiveViewSpec(
        id,
        value,
        build,
        summon[LiveMessageTag[Msg]].classTag,
        sticky,
        linkParentOnCrash
      )
    )

  /** Embeds a non-sticky nested LiveView from one committed parent signal value. */
  def liveView[A, Msg: LiveMessageTag, Model](
    id: String,
    value: Signal[A]
  )(
    build: A => LiveView[Msg, Model]
  ): Mod[Nothing] =
    liveView(id, value, sticky = false, linkParentOnCrash = (_: A) => false)(build)

  /** Helpers for rendering and clearing socket-scoped flash messages. */
  object flash:
    /** Renders `f` with the current message for `kind`, or no content when that kind is absent.
      *
      * The `FlashKind` is typed to prevent accidental use of raw protocol keys. Flash rendered by a
      * nested LiveView is scoped to that child socket.
      */
    def apply(kind: FlashKind)(f: String => HtmlElement[Nothing]): Mod[Nothing] =
      Mod.Content.Flash(kind.value, f)

    /** Low-level click binding that asks the owning socket to clear all flash messages. */
    lazy val clearOnClick: Mod.Attr[Nothing] = phx.click := "lv:clear-flash"

    /** Click modifiers that ask the owning socket to clear only `kind`.
      *
      * Both modifiers must remain on the same clickable element so the `key` value accompanies the
      * `lv:clear-flash` protocol event.
      */
    def clearOnClick(kind: FlashKind): Vector[Mod.Attr[Nothing]] =
      Vector(clearOnClick, phx.value("key") := kind.value)

  /** Renders a stateful LiveComponent whose integer ID is normalized with `toString`.
    *
    * Its identity and lifecycle contract are otherwise the same as the string overload of
    * [[liveComponent]].
    */
  def liveComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: Int,
    props: Props
  ): Mod[Nothing] =
    liveComponent(component, id.toString, props)

  private lazy val portalTemplateTag = HtmlTag("template")
  private lazy val phxPortal         = dataAttr("phx-portal")

  /** Renders content that the browser moves from a source template into another DOM location.
    *
    * The template uses `id` as its stable portal identity and contains one generated wrapper named
    * `_lv_portal_wrap_$id`. `target` must be an explicit, non-empty [[DomSelector.css]] selector;
    * [[DomSelector.current]] is invalid because a portal has no meaningful current-element target.
    * Selector syntax and target existence are browser concerns and are not validated here.
    *
    * The wrapper, rather than each child, is moved, preserving LiveView's logical ownership of
    * event bindings, hooks, components, and nested LiveViews inside it. Keep `id` stable and
    * unique. The wrapper `container` must be a valid lowercase HTML tag name.
    *
    * @throws IllegalArgumentException
    *   if `container` is not a valid tag or `target` is not explicit
    */
  def portal[Msg](
    id: String,
    target: DomSelector,
    container: String = "div",
    wrapperClass: Option[String] = None
  )(
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] =
    require(
      Escaping.validTag(container),
      s"portal container must be a valid HTML tag, got '$container'"
    )
    val contentMods = mods.toVector.flatMap {
      case mod: Mod[Msg]                => Some(mod)
      case mods: IterableOnce[Mod[Msg]] => mods
    }
    val wrapperMods = Vector.newBuilder[Mod[Msg]]
    wrapperMods += (idAttr := s"_lv_portal_wrap_$id")
    wrapperClass.foreach(value => wrapperMods += (cls := value))
    wrapperMods ++= contentMods

    portalTemplateTag(
      idAttr    := id,
      phxPortal := target.requiredValue,
      HtmlTag(container)(wrapperMods.result())
    )

  /** Typed LiveView navigation links and explicit raw-destination escape hatches.
    *
    * Methods accepting [[LiveLocation]] preserve route encoding and parameter type safety. Methods
    * ending in `Unsafe` accept a raw `String`; callers are responsible for constructing a valid,
    * correctly encoded and trusted destination. Raw destinations are still escaped as HTML
    * attributes, but their scheme, origin, path, and query are not validated; do not pass unchecked
    * user input.
    */
  object link:
    /** Live-navigates to `to` and pushes a browser-history entry. */
    def pushNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      pushNavigateUnsafe(to.href, mods*)

    /** Live-navigates to a signal-backed location. */
    def pushNavigate[Msg](
      to: Signal[LiveLocation],
      mods: Mod[Msg]*
    ): HtmlElement[Msg] =
      pushNavigateUnsafe(to.map(_.href), mods*)

    /** Live-navigates to a raw destination and pushes a browser-history entry.
      *
      * This bypasses typed route construction and URI validation/encoding.
      */
    def pushNavigateUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "redirect", phx.linkState := "push", mods)

    /** Live-navigates to a raw signal-backed destination. */
    def pushNavigateUnsafe[Msg](
      path: Signal[String],
      mods: Mod[Msg]*
    ): HtmlElement[Msg] =
      a(href := path, phx.link := "redirect", phx.linkState := "push", mods)

    /** Live-navigates to `to` and replaces the current browser-history entry. */
    def replaceNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.href, mods*)

    /** Live-navigates with history replacement to a signal-backed location. */
    def replaceNavigate[Msg](
      to: Signal[LiveLocation],
      mods: Mod[Msg]*
    ): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.map(_.href), mods*)

    /** Live-navigates to a raw destination and replaces the current browser-history entry.
      *
      * This bypasses typed route construction and URI validation/encoding.
      */
    def replaceNavigateUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "redirect", phx.linkState := "replace", mods)

    /** Replaces navigation with a raw signal-backed destination. */
    def replaceNavigateUnsafe[Msg](
      path: Signal[String],
      mods: Mod[Msg]*
    ): HtmlElement[Msg] =
      a(href := path, phx.link := "redirect", phx.linkState := "replace", mods)

    /** Patches the current LiveView to `to` and pushes a browser-history entry. */
    def pushPatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.href, mods*)

    /** Live-patches to a signal-backed location. */
    def pushPatch[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.map(_.href), mods*)

    /** Patches the current LiveView to a raw destination and pushes a browser-history entry.
      *
      * This bypasses typed route construction and URI validation/encoding.
      */
    def pushPatchUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "patch", phx.linkState := "push", mods)

    /** Live-patches to a raw signal-backed destination. */
    def pushPatchUnsafe[Msg](path: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "patch", phx.linkState := "push", mods)

    /** Patches the current LiveView to `to` and replaces the current browser-history entry. */
    def replacePatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.href, mods*)

    /** Live-patches with history replacement to a signal-backed location. */
    def replacePatch[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.map(_.href), mods*)

    /** Patches the current LiveView to a raw destination and replaces browser history.
      *
      * This bypasses typed route construction and URI validation/encoding.
      */
    def replacePatchUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "patch", phx.linkState := "replace", mods)

    /** Replaces the current patch destination from a signal-backed value. */
    def replacePatchUnsafe[Msg](path: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "patch", phx.linkState := "replace", mods)
  end link

  /** Low-level Phoenix LiveView protocol attributes.
    *
    * These members expose wire-level names and values for interoperability and advanced cases. Raw
    * event attributes do not provide Scala message typing or register a typed binding; prefer
    * [[on]] for application events, [[connection]], [[dom]], and [[submission]] for common
    * lifecycle behavior, and `LiveStream.renderIn` for stream containers.
    */
  object phx:
    private def phxAttr(suffix: String): HtmlAttr[String] =
      new HtmlAttr(s"phx-$suffix", StringAsIsEncoder)
    private def phxAttrBool(suffix: String): HtmlAttr[Boolean] =
      new HtmlAttr(s"phx-$suffix", BooleanAsAttrPresenceEncoder)
    private def phxAttrInt(suffix: String): HtmlAttr[Int] =
      new HtmlAttr(s"phx-$suffix", IntAsStringEncoder)
    private def dataPhxAttr(suffix: String): HtmlAttr[String] =
      dataAttr(s"phx-$suffix")

    private[scalive] lazy val session   = dataPhxAttr("session")
    private[scalive] lazy val static    = dataPhxAttr("static")
    private[scalive] lazy val main      = htmlAttr("data-phx-main", BooleanAsAttrPresenceEncoder)
    private[scalive] lazy val parentId  = dataPhxAttr("parent-id")
    private[scalive] lazy val sticky    = htmlAttr("data-phx-sticky", BooleanAsAttrPresenceEncoder)
    private[scalive] lazy val link      = dataPhxAttr("link")
    private[scalive] lazy val linkState = dataPhxAttr("link-state")
    private[scalive] lazy val component = dataPhxAttr("component")

    /** Raw `phx-click` event or command value; prefer [[on.click]] for typed bindings. */
    lazy val click = phxAttr("click")

    /** Raw `phx-click-away` event or command value; prefer [[on.clickAway]]. */
    lazy val clickAway = phxAttr("click-away")

    /** Raw `phx-blur` event value; prefer [[on.blur]]. */
    lazy val blur = phxAttr("blur")

    /** Raw `phx-focus` event value; prefer [[on.focus]]. */
    lazy val focus = phxAttr("focus")

    /** Raw `phx-window-blur` event value; prefer [[on.windowBlur]]. */
    lazy val windowBlur = phxAttr("window-blur")

    /** Raw `phx-window-focus` event value; prefer [[on.windowFocus]]. */
    lazy val windowFocus = phxAttr("window-focus")

    /** Raw `phx-keydown` event value; prefer [[on.keyDown]]. */
    lazy val keyDown = phxAttr("keydown")

    /** Raw `phx-keyup` event value; prefer [[on.keyUp]]. */
    lazy val keyUp = phxAttr("keyup")

    /** Raw `phx-window-keydown` event value; prefer [[on.windowKeyDown]]. */
    lazy val windowKeyDown = phxAttr("window-keydown")

    /** Raw `phx-window-keyup` event value; prefer [[on.windowKeyUp]]. */
    lazy val windowKeyUp = phxAttr("window-keyup")

    /** Raw `phx-key` filter value; typed key bindings expose `.key(Key)`. */
    lazy val key = phxAttr("key")

    /** Raw `phx-viewport-top` event value; prefer [[on.viewportTop]]. */
    lazy val viewportTop = phxAttr("viewport-top")

    /** Raw `phx-viewport-bottom` event value; prefer [[on.viewportBottom]]. */
    lazy val viewportBottom = phxAttr("viewport-bottom")

    /** Raw upload `phx-progress` event value; prefer `LiveUpload.onProgress`. */
    lazy val progress = phxAttr("progress")

    /** Raw `phx-change` event value; prefer [[on.change]]. */
    lazy val change = phxAttr("change")

    /** Raw `phx-submit` event value; prefer [[on.submit]]. */
    lazy val submit = phxAttr("submit")

    /** Raw `phx-auto-recover` event value used when a form reconnects. */
    lazy val autoRecover = phxAttr("auto-recover")

    /** Raw `phx-feedback-for` field association used by the client. */
    lazy val feedbackFor = phxAttr("feedback-for")

    /** Boolean `phx-trigger-action` flag for submitting a form through the browser. */
    lazy val triggerAction = phxAttrBool("trigger-action")

    /** Raw replacement text for an element while its form is submitted. */
    lazy val disableWith = phxAttr("disable-with")

    /** Raw command run when the LiveView connects; prefer [[connection.onConnect]]. */
    lazy val connected = phxAttr("connected")

    /** Raw command run when the LiveView disconnects; prefer [[connection.onDisconnect]]. */
    lazy val disconnected = phxAttr("disconnected")

    /** Raw command run when an element is mounted; prefer [[dom.onMount]]. */
    lazy val mounted = phxAttr("mounted")

    /** Raw command run before an element is removed; prefer [[dom.onRemove]]. */
    lazy val remove = phxAttr("remove")

    /** Typed `phx-update` DOM patching mode. Prefer stream rendering helpers for `Stream`. */
    lazy val update = new HtmlAttr[PhxUpdate]("phx-update", Encoder(_.value))

    /** Raw client hook name. Prefer [[dom.hook]] so the required DOM ID is attached with it. */
    lazy val hook = phxAttr("hook")

    /** Typed upload reference accepted as a file-drop target. Prefer `LiveUpload.dropTarget`. */
    lazy val dropTarget = new HtmlAttr[UploadRef]("phx-drop-target", Encoder(_.value))

    /** Targets a low-level event at the mounted LiveComponent represented by `ref`.
      *
      * Prefer `on.*.to(ref)` when routing a typed Scala message.
      */
    def target[Msg](ref: ComponentRef[Msg]): Mod.Attr[Nothing] =
      phxAttr("target") := ref.toString

    /** Targets a low-level event at an explicit CSS selector.
      *
      * [[DomSelector.current]] is rejected because `phx-target` requires an explicit target.
      * Selector syntax and matching are resolved by the browser.
      *
      * @throws IllegalArgumentException
      *   if `selector` is [[DomSelector.current]]
      */
    def target(selector: DomSelector): Mod.Attr[Nothing] =
      phxAttr("target") := selector.requiredValue

    // Rate limiting
    /** Raw `phx-debounce` policy in milliseconds, or `"blur"` to emit on blur. */
    lazy val debounce = new HtmlAttr["blur" | Int](
      s"phx-debounce",
      Encoder {
        case _: "blur"  => "blur"
        case value: Int => value.toString
      }
    )

    /** Raw `phx-throttle` interval in milliseconds. Prefer typed binding `.throttle(duration)`. */
    lazy val throttle = phxAttrInt("throttle")

    /** Creates a raw `phx-value-$key` attribute included in event metadata.
      *
      * `key` forms part of an HTML attribute name and is validated when the attribute is created.
      */
    def value(key: String) = phxAttr(s"value-$key")

    /** Boolean marker for static assets tracked across live navigation. */
    lazy val trackStatic = htmlAttr("phx-track-static", BooleanAsAttrPresenceEncoder)

  end phx

  /** Typed server-event and JavaScript-command bindings.
    *
    * A binding can emit a `Msg`, decode raw event metadata, decode form data, execute a typed
    * [[JSCommands.JSCommand]], or route a message to a LiveComponent. Its `Msg` type must be
    * accepted by the enclosing [[HtmlElement]]. Key bindings additionally support `.key(Key)`, and
    * event bindings expose duration-based debounce and throttle policies. The browser always fires
    * a blur binding immediately, even if a rate-limit attribute is present.
    */
  object on:
    private def binding(suffix: String): HtmlAttrBinding =
      new HtmlAttrBinding(s"phx-$suffix")
    private def keyBinding(suffix: String): KeyHtmlAttrBinding =
      new KeyHtmlAttrBinding(s"phx-$suffix")

    /** Typed click binding for the element. */
    lazy val click = binding("click")

    /** Typed click-away binding fired for clicks outside the element. */
    lazy val clickAway = binding("click-away")

    /** Typed blur binding for the element. */
    lazy val blur = binding("blur")

    /** Typed focus binding for the element. */
    lazy val focus = binding("focus")

    /** Typed binding for window blur. */
    lazy val windowBlur = binding("window-blur")

    /** Typed binding for window focus. */
    lazy val windowFocus = binding("window-focus")

    /** Typed keydown binding, optionally filtered with `.key(Key)`. */
    lazy val keyDown = keyBinding("keydown")

    /** Typed keyup binding, optionally filtered with `.key(Key)`. */
    lazy val keyUp = keyBinding("keyup")

    /** Typed window keydown binding, optionally filtered with `.key(Key)`. */
    lazy val windowKeyDown = keyBinding("window-keydown")

    /** Typed window keyup binding, optionally filtered with `.key(Key)`. */
    lazy val windowKeyUp = keyBinding("window-keyup")

    /** Typed binding fired when the element enters the viewport at the top boundary. */
    lazy val viewportTop = binding("viewport-top")

    /** Typed binding fired when the element enters the viewport at the bottom boundary. */
    lazy val viewportBottom = binding("viewport-bottom")

    /** Typed form/input change binding; use `.form` for structured form decoding. */
    lazy val change = binding("change")

    /** Typed form submit binding; use `.form` for structured form decoding. */
    lazy val submit = binding("submit")

    private[scalive] lazy val recover        = binding("auto-recover")
    private[scalive] lazy val uploadProgress = binding("progress")
  end on

  /** Client-command bindings and ready-made visibility modifiers for LiveView connection state.
    *
    * Connection lifecycle bindings execute only inside a LiveView container.
    */
  object connection:
    /** JavaScript-command binding run when the LiveView connects. */
    lazy val onConnect = new HtmlAttrBinding("phx-connected")

    /** JavaScript-command binding run when the LiveView disconnects. */
    lazy val onDisconnect = new HtmlAttrBinding("phx-disconnected")

    /** Modifiers that hide the element until connected and hide it again on disconnect. */
    lazy val visibleWhenConnected: Vector[Mod[Nothing]] = Vector(
      hidden := true,
      onConnect(JS.removeAttribute("hidden")),
      onDisconnect(JS.setAttribute("hidden" -> ""))
    )

    /** Modifiers that show the element only while the LiveView is disconnected. */
    lazy val visibleWhenDisconnected: Vector[Mod[Nothing]] = Vector(
      hidden := false,
      onConnect(JS.setAttribute("hidden" -> "")),
      onDisconnect(JS.removeAttribute("hidden"))
    )

  /** Element mount/removal bindings and helpers for DOM modes that require stable IDs. */
  object dom:
    /** JavaScript-command binding run when the element is mounted. */
    lazy val onMount = new HtmlAttrBinding("phx-mounted")

    /** JavaScript-command binding run before the element is removed.
      *
      * Removal commands run only for the removed element, not recursively for its children.
      */
    lazy val onRemove = new HtmlAttrBinding("phx-remove")

    /** Attaches a client hook together with its required stable DOM ID.
      *
      * Prefer this over raw `phx.hook` so the hook cannot be rendered without an ID.
      *
      * @throws IllegalArgumentException
      *   if `name` is empty
      */
    def hook(name: String, id: DomRef): Vector[Mod.Attr[Nothing]] =
      require(name.nonEmpty, "hook name must not be empty")
      Vector(id.attr, phx.hook := name)

    /** Marks an element with a stable DOM ID whose client-managed DOM ignores server patches.
      *
      * The client owns the element's children and ordinary attributes. Server changes to `data-*`
      * attributes are still merged, providing a channel for updates to client code.
      */
    def ignoreUpdates(id: DomRef): Vector[Mod.Attr[Nothing]] =
      Vector(id.attr, phx.update := PhxUpdate.Ignore)

  /** Declarative element behavior while a form submission is in flight. */
  object submission:
    /** Disables the submitting element without replacing its text. */
    lazy val disable: Mod.Attr[Nothing] =
      htmlAttr("phx-disable-with", BooleanAsAttrPresenceEncoder) := true

    /** Disables the submitting element and temporarily replaces its text with `text`. */
    def replaceTextWith(text: String): Mod.Attr[Nothing] =
      phx.disableWith := text

  extension [R](upload: LiveUpload[R])
    /** Marks an element as a file-drop target for this upload's typed protocol reference. */
    def dropTarget: Mod.Attr[Nothing] = phx.dropTarget := upload.ref

    /** Creates a binding that emits `message` for upload-progress events.
      *
      * Attach it to the corresponding [[liveFileInput]]. The receiver makes the call type-directed
      * but is not encoded into the binding and does not authorize or correlate incoming events. The
      * handler should obtain a fresh [[LiveUpload]] snapshot from the lifecycle context before
      * rendering updated progress or errors.
      */
    def onProgress[Msg](message: Msg): Mod.Attr[Msg] =
      on.uploadProgress(message)

    /** Creates a binding that builds a message from raw upload-progress event metadata.
      *
      * Attach it to the corresponding [[liveFileInput]]. The receiver makes the call type-directed
      * but is not encoded into the binding. Map values such as `ref`, `entry_ref`, `progress`, and
      * optional `error` originate in the browser protocol and must be treated as untrusted. The
      * handler should obtain a fresh [[LiveUpload]] snapshot from the lifecycle context before
      * rendering updated progress or errors.
      */
    def onProgress[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] =
      on.uploadProgress(f)

  extension [R](upload: Signal[LiveUpload[R]])
    /** Marks an element as a drop target for the committed upload snapshot. */
    def dropTarget: Mod.Attr[Nothing] = phx.dropTarget := upload.map(_.ref)

    /** Creates a progress binding associated with this signal-backed upload value. */
    def onProgress[Msg](message: Msg): Mod.Attr[Msg] =
      on.uploadProgress(message)

    /** Creates a raw progress binding associated with this signal-backed upload value. */
    def onProgress[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] =
      on.uploadProgress(f)

  /** Converts ordinary string content to an escaped HTML text modifier.
    *
    * Use [[rawHtml]] only when deliberately inserting trusted markup without escaping.
    */
  implicit def stringToMod(v: String): Mod[Nothing] = Mod.Content.Text(v)

  /** Converts a read-only string signal to escaped dynamic text. */
  implicit def signalStringToMod(v: Signal[String]): Mod[Nothing] = Mod.Content.SignalText(v)

  /** Converts an [[HtmlElement]] to a child-content modifier while preserving its message type. */
  implicit def htmlElementToMod[Msg](el: HtmlElement[Msg]): Mod[Msg] = Mod.Content.Tag(el)
end scalive
