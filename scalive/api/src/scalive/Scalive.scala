import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder
import scalive.codecs.IntAsStringEncoder
import scalive.codecs.StringAsIsEncoder
import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.components.Components
import scalive.defs.tags.HtmlTags

/** Ergonomic API imported with `scalive.*`. */
package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys with Components:
  export _root_.scalive.streams.api.*
  export _root_.scalive.upload.api.*

  lazy val defer = htmlAttr("defer", BooleanAsAttrPresenceEncoder)

  /** Makes this element and its flat-tree descendants inert while assigned `true`.
    *
    * Inert content cannot receive user input or focus and is excluded from the accessibility tree.
    * The attribute does not provide a visual indication, so visually distinguish inactive content.
    *
    * See:
    * [[https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Global_attributes/inert inert @ MDN]]
    */
  lazy val inert: HtmlAttr[Boolean] = htmlAttr("inert", BooleanAsAttrPresenceEncoder)

  /** Inserts `html` without escaping it.
    *
    * Never pass text that a visitor or another untrusted source can influence: it can inject
    * scripts into the page. Text passed as a plain string is escaped instead.
    */
  def rawHtml(html: String): Mod[Nothing] = Mod.Content.Text(html, raw = true)

  /** Inserts `html` without escaping it.
    *
    * Never pass text that a visitor or another untrusted source can influence: it can inject
    * scripts into the page. Text passed as a plain string is escaped instead.
    */
  def rawHtml(html: Signal[String]): Mod[Nothing] = Mod.Content.SignalText(html, raw = true)

  /** Names one instance of a stateful component, to render it with `render(props)`.
    *
    * `id` identifies the instance among components of the same class; keep it stable so the
    * instance keeps its model. Declare instances once, as in
    * `private val ScalaVote = component(VoteComponent, "scala-vote")`.
    */
  def component[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: String
  ): LiveComponentInstance[Props, Msg, Model] = LiveComponentInstance(value, id)

  /** Names one instance of a stateful component, to render it with `render(props)`.
    *
    * This form names a component that emits outputs; render it with `render(props, onOutput)`.
    */
  def component[Props, Msg, Model, Output](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String
  ): LiveComponentOutputInstance[Props, Msg, Model, Output] =
    LiveComponentOutputInstance(value, id)

  def liveComponent[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: String,
    props: Props
  ): Mod[Nothing] = Mod.Content.Component(ComponentSpec.Plain(value, id, props))

  def liveComponent[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: String,
    props: Signal[Props]
  ): Mod[Nothing] = Mod.Content.Component(ComponentSpec.PlainSignal(value, id, props))

  def liveComponent[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: Signal[String],
    props: Signal[Props]
  ): Mod[Nothing] = Mod.Content.Component(ComponentSpec.Dynamic(value, id, props))

  def liveComponent[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: Signal[String],
    props: Props
  ): Mod[Nothing] = liveComponent(value, id, id.map(_ => props))

  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String,
    props: Props,
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.Output(value, id, props, onOutput))

  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String,
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.OutputSignal(value, id, props, onOutput))

  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: Signal[String],
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.OutputDynamic(value, id, props, onOutput))

  def liveView[Msg, Model](
    id: String,
    value: => LiveView[Msg, Model],
    sticky: Boolean = false,
    linkParentOnCrash: Boolean = false
  ): Mod[Nothing] =
    Mod.Content.NestedView(NestedViewSpec.Static(id, () => value, sticky, linkParentOnCrash))

  def liveView[A, Msg, Model](
    id: String,
    value: Signal[A],
    sticky: Boolean,
    linkParentOnCrash: A => Boolean
  )(
    factory: A => LiveView[Msg, Model]
  ): Mod[Nothing] =
    Mod.Content.NestedView(
      NestedViewSpec.Dynamic(id, value, factory, sticky, linkParentOnCrash)
    )

  def liveView[A, Msg, Model](
    id: String,
    value: Signal[A]
  )(
    factory: A => LiveView[Msg, Model]
  ): Mod[Nothing] = liveView(id, value, sticky = false, _ => false)(factory)

  private lazy val portalTemplateTag = HtmlTag("template")
  private lazy val phxPortal         = dataAttr("phx-portal")

  def portal[Msg](
    id: String,
    target: DomSelector,
    container: String = "div",
    wrapperClass: Option[String] = None
  )(
    mods: Mod.Input[Msg]*
  ): HtmlElement[Msg] =
    require(
      Escaping.validTag(container),
      s"portal container must be a valid HTML tag, got '$container'"
    )
    val content = Mod.flatten(mods)
    val wrapper = Vector.newBuilder[Mod[Msg]]
    wrapper += (idAttr := s"_lv_portal_wrap_$id")
    wrapperClass.foreach(value => wrapper += (cls := value))
    wrapper ++= content

    portalTemplateTag(
      idAttr    := id,
      phxPortal := target.requiredValue,
      HtmlTag(container)(wrapper.result())
    )

  /** Renders flash messages set with `ctx.flash`. */
  object flash:
    /** Renders the message for `kind` with `project`, and nothing while there is none. */
    def apply(kind: FlashKind)(project: String => HtmlElement[Nothing]): Mod[Nothing] =
      Mod.Content.Flash(kind, project)

    /** Clears flash when the element is clicked, without a message to the LiveView.
      *
      * This form clears every message.
      */
    lazy val clearOnClick: Mod.Attr[Nothing] = phx.click := "lv:clear-flash"

    /** Clears flash when the element is clicked, without a message to the LiveView.
      *
      * This form clears only the message for `kind`.
      */
    def clearOnClick(kind: FlashKind): Vector[Mod.Attr[Nothing]] =
      Vector(clearOnClick, phx.value("key") := kind.value)

  /** Renders the document `<title>` in a root layout, so the LiveView's `pageTitle` can change it.
    *
    * Pass the `pageTitle` the root layout receives. `default` applies while the page has no title,
    * and `prefix` and `suffix` surround every title.
    */
  def liveTitle(
    pageTitle: Option[String],
    default: String,
    prefix: String = "",
    suffix: String = ""
  ): HtmlElement[Nothing] =
    val title = pageTitle.filter(_.trim.nonEmpty).getOrElse(default)
    titleTag(
      dataAttr("prefix")  := prefix,
      dataAttr("default") := default,
      dataAttr("suffix")  := suffix,
      s"$prefix$title$suffix"
    )

  /** Renders the document `<title>` in a root layout, so the LiveView's `pageTitle` can change it.
    *
    * This form follows a signal of the title.
    */
  def liveTitle(
    pageTitle: Signal[Option[String]],
    default: String,
    prefix: String,
    suffix: String
  ): HtmlElement[Nothing] =
    titleTag(
      dataAttr("prefix")  := prefix,
      dataAttr("default") := default,
      dataAttr("suffix")  := suffix,
      pageTitle.map(value => s"$prefix${value.filter(_.trim.nonEmpty).getOrElse(default)}$suffix")
    )

  /** Renders the document `<title>` in a root layout, so the LiveView's `pageTitle` can change it.
    *
    * This form follows a signal of the title.
    */
  def liveTitle(pageTitle: Signal[Option[String]], default: String): HtmlElement[Nothing] =
    liveTitle(pageTitle, default, "", "")

  object navigation:
    private lazy val guardMessage = dataAttr("scalive-navigation-guard")

    /** Guards browser-initiated navigation while `dirty` is true.
      *
      * Add the returned modifier to the element which owns the dirty state and install the
      * `NavigationGuardAssets` script in the root layout. The browser runtime uses `message` for
      * live links, live patch links, and LiveView history traversal. Browsers use their own generic
      * message for cross-document history traversal, reload, and tab-close confirmation.
      *
      * The guard reflects the latest value rendered by the server. Server-issued navigation and
      * navigation encoded in a [[JSCommands.JSCommand]] bypass the configured confirmation, but a
      * resulting document unload may still request the browser's generic confirmation.
      */
    def guardWhen(dirty: Signal[Boolean], message: String): Mod.Attr[Nothing] =
      require(!message.isBlank, "navigation guard message must not be blank")
      guardMessage.optional(dirty.map(Option.when(_)(message)))

  /** Links that navigate or patch without reloading the page.
    *
    * They render an ordinary `href`, so they also work before JavaScript connects and when opened
    * in a new tab. Build destinations with a route's `location`. See [patch or
    * navigate](https://scalive.dev/guides/routes-and-navigation#choose-patch-or-navigate).
    */
  object link:
    private def liveDestination(value: String): String =
      NavigationDestination.live(value).fold(throw _, identity)

    /** Renders a link that mounts the LiveView at `to` and adds a browser history entry. */
    def pushNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      pushNavigateUnsafe(to.href, mods*)

    /** Renders a link that mounts the LiveView at `to` and adds a browser history entry.
      *
      * This form follows a signal of the destination.
      */
    def pushNavigate[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      pushNavigateUnsafe(to.map(_.href), mods*)

    /** Like `pushNavigate`, for a raw URL that the route types do not check. */
    def pushNavigateUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := liveDestination(to), phx.link := "redirect", phx.linkState := "push", mods)

    /** Like `pushNavigate`, for a raw URL that the route types do not check.
      *
      * This form follows a signal of the destination.
      */
    def pushNavigateUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to.map(liveDestination), phx.link := "redirect", phx.linkState := "push", mods)

    /** Renders a link that mounts the LiveView at `to`, replacing the current history entry. */
    def replaceNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.href, mods*)

    /** Renders a link that mounts the LiveView at `to`, replacing the current history entry.
      *
      * This form follows a signal of the destination.
      */
    def replaceNavigate[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.map(_.href), mods*)

    /** Like `replaceNavigate`, for a raw URL that the route types do not check. */
    def replaceNavigateUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := liveDestination(to), phx.link := "redirect", phx.linkState := "replace", mods)

    /** Like `replaceNavigate`, for a raw URL that the route types do not check.
      *
      * This form follows a signal of the destination.
      */
    def replaceNavigateUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to.map(liveDestination), phx.link := "redirect", phx.linkState := "replace", mods)

    /** Renders a link that patches this LiveView's URL to `to` and adds a history entry. */
    def pushPatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.href, mods*)

    /** Renders a link that patches this LiveView's URL to `to` and adds a history entry.
      *
      * This form follows a signal of the destination.
      */
    def pushPatch[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.map(_.href), mods*)

    /** Like `pushPatch`, for a raw URL that the route types do not check. */
    def pushPatchUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := liveDestination(to), phx.link := "patch", phx.linkState := "push", mods)

    /** Like `pushPatch`, for a raw URL that the route types do not check.
      *
      * This form follows a signal of the destination.
      */
    def pushPatchUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to.map(liveDestination), phx.link := "patch", phx.linkState := "push", mods)

    /** Renders a link that patches this LiveView's URL to `to`, replacing the current entry. */
    def replacePatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.href, mods*)

    /** Renders a link that patches this LiveView's URL to `to`, replacing the current entry.
      *
      * This form follows a signal of the destination.
      */
    def replacePatch[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.map(_.href), mods*)

    /** Like `replacePatch`, for a raw URL that the route types do not check. */
    def replacePatchUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := liveDestination(to), phx.link := "patch", phx.linkState := "replace", mods)

    /** Like `replacePatch`, for a raw URL that the route types do not check.
      *
      * This form follows a signal of the destination.
      */
    def replacePatchUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to.map(liveDestination), phx.link := "patch", phx.linkState := "replace", mods)
  end link

  /** Raw Phoenix LiveView attributes and client behavior switches.
    *
    * Event attributes in this namespace accept rendered values. Use [[on]] when a browser
    * interaction should produce a typed view message.
    */
  object phx:
    private def attr(name: String)     = htmlAttr(s"phx-$name", StringAsIsEncoder)
    private def boolAttr(name: String) = htmlAttr(s"phx-$name", BooleanAsAttrPresenceEncoder)
    private def intAttr(name: String)  = htmlAttr(s"phx-$name", IntAsStringEncoder)
    private def data(name: String)     = dataAttr(s"phx-$name")

    private[scalive] lazy val link      = data("link")
    private[scalive] lazy val linkState = data("link-state")

    lazy val click         = attr("click")
    lazy val clickAway     = attr("click-away")
    lazy val blur          = attr("blur")
    lazy val focus         = attr("focus")
    lazy val windowBlur    = attr("window-blur")
    lazy val windowFocus   = attr("window-focus")
    lazy val keyDown       = attr("keydown")
    lazy val keyUp         = attr("keyup")
    lazy val windowKeyDown = attr("window-keydown")
    lazy val windowKeyUp   = attr("window-keyup")
    lazy val change        = attr("change")
    lazy val submit        = attr("submit")
    lazy val autoRecover   = attr("auto-recover")
    lazy val feedbackFor   = attr("feedback-for")
    lazy val triggerAction = boolAttr("trigger-action")
    lazy val noUnusedField = boolAttr("no-unused-field")
    lazy val patchFocused  = boolAttr("patch-focused")
    lazy val disableWith   = attr("disable-with")
    lazy val connected     = attr("connected")
    lazy val disconnected  = attr("disconnected")
    lazy val mounted       = attr("mounted")
    lazy val remove        = attr("remove")
    lazy val hook          = attr("hook")
    lazy val update        = HtmlAttr[PhxUpdate]("phx-update", Encoder(_.value))
    lazy val dropTarget    = HtmlAttr[UploadRef]("phx-drop-target", Encoder(_.value))
    lazy val debounce      = HtmlAttr["blur" | Int](
      "phx-debounce",
      Encoder {
        case _: "blur"  => "blur"
        case value: Int => value.toString
      }
    )
    lazy val throttle    = intAttr("throttle")
    lazy val trackStatic = boolAttr("track-static")

    def target[Msg](ref: ComponentRef[Msg]): Mod.Attr[Msg] =
      Mod.Attr.ComponentTarget(ref)

    def target(selector: DomSelector): Mod.Attr[Nothing] = attr("target") := selector.requiredValue

    def value(key: String) = attr(s"value-$key")
  end phx

  /** Typed Phoenix LiveView event bindings.
    *
    * Members render `phx-*` attributes and dispatch typed messages or JavaScript commands. They are
    * not native inline HTML event attributes such as `onclick` or `onchange`. Give a binding a
    * message, a signal of a message, or a `JS` command, as in `button(on.click(Msg.Save), "Save")`.
    * See [event bindings](https://scalive.dev/guides/html-dsl-and-event-bindings#choose-a-binding).
    */
  object on:
    private def binding(name: String)    = HtmlAttrBinding(s"phx-$name")
    private def keyBinding(name: String) = KeyHtmlAttrBinding(s"phx-$name")

    /** Binds a click on the element or inside it (`phx-click`). */
    lazy val click = binding("click")

    /** Binds a click that starts outside the element while it is visible (`phx-click-away`). */
    lazy val clickAway = binding("click-away")

    /** Binds the element losing focus (`phx-blur`). */
    lazy val blur = binding("blur")

    /** Binds the element gaining focus (`phx-focus`). */
    lazy val focus = binding("focus")

    /** Binds the browser window losing focus (`phx-window-blur`). */
    lazy val windowBlur = binding("window-blur")

    /** Binds the browser window gaining focus (`phx-window-focus`). */
    lazy val windowFocus = binding("window-focus")

    /** Binds a key press while the element has focus (`phx-keydown`). Filter keys with `key`. */
    lazy val keyDown = keyBinding("keydown")

    /** Binds a key release while the element has focus (`phx-keyup`). Filter keys with `key`. */
    lazy val keyUp = keyBinding("keyup")

    /** Binds a key press anywhere in the window (`phx-window-keydown`). Filter keys with `key`. */
    lazy val windowKeyDown = keyBinding("window-keydown")

    /** Binds a key release anywhere in the window (`phx-window-keyup`). Filter keys with `key`. */
    lazy val windowKeyUp = keyBinding("window-keyup")

    /** Binds the element's first child reaching the top of the viewport while the visitor scrolls
      * up (`phx-viewport-top`). The element needs a stable `id`.
      */
    lazy val viewportTop = binding("viewport-top")

    /** Binds the element's last child reaching the bottom of the viewport while the visitor scrolls
      * down (`phx-viewport-bottom`), as for infinite scrolling. The element needs a stable `id`.
      */
    lazy val viewportBottom = binding("viewport-bottom")

    /** Creates a `phx-change` LiveView binding.
      *
      * Phoenix drives this binding from browser `input` and `change` events; it is not the native
      * `onchange` attribute. For typed forms, prefer `FormDefinition.onChange`.
      */
    lazy val change = binding("change")

    /** Binds a form submission (`phx-submit`). For typed forms, prefer `FormDefinition.onSubmit`.
      */
    lazy val submit                          = binding("submit")
    private[scalive] lazy val recover        = binding("auto-recover")
    private[scalive] lazy val uploadProgress = binding("progress")
  end on

  /** Bindings and helpers that follow the LiveView's connection to the server. */
  object connection:
    /** Runs a `JS` command each time the LiveView joins, including after a reconnect. */
    lazy val onConnect = HtmlAttrBinding("phx-connected")

    /** Runs a `JS` command shortly after the LiveView loses its connection. */
    lazy val onDisconnect = HtmlAttrBinding("phx-disconnected")

    /** Shows the element only while the LiveView is connected. */
    lazy val visibleWhenConnected: Vector[Mod[Nothing]] = Vector(
      hidden := true,
      onConnect(JS.removeAttribute("hidden")),
      onDisconnect(JS.setAttribute("hidden" -> ""))
    )

    /** Shows the element only while the LiveView is not connected, including before it first
      * connects.
      */
    lazy val visibleWhenDisconnected: Vector[Mod[Nothing]] = Vector(
      hidden := false,
      onConnect(JS.setAttribute("hidden" -> "")),
      onDisconnect(JS.removeAttribute("hidden"))
    )

  object dom:
    lazy val onMount  = HtmlAttrBinding("phx-mounted")
    lazy val onRemove = HtmlAttrBinding("phx-remove")

    def hook(name: String, id: DomRef): Vector[Mod.Attr[Nothing]] =
      require(name.nonEmpty, "hook name must not be empty")
      Vector(id.attr, phx.hook := name)

    def ignoreUpdates(id: DomRef): Vector[Mod.Attr[Nothing]] =
      Vector(id.attr, phx.update := PhxUpdate.Ignore)

  object submission:
    lazy val disable: Mod.Attr[Nothing] =
      htmlAttr("phx-disable-with", BooleanAsAttrPresenceEncoder) := true
    def replaceTextWith(text: String): Mod.Attr[Nothing] = phx.disableWith := text

  extension [R](upload: LiveUpload[R])
    def dropTarget: Mod.Attr[Nothing]                                 = phx.dropTarget := upload.ref
    def onProgress[Msg](message: Msg): Mod.Attr[Msg]                  = on.uploadProgress(message)
    def onProgress[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] = on.uploadProgress(f)

  extension [R](upload: Signal[LiveUpload[R]])
    def dropTarget: Mod.Attr[Nothing]                = phx.dropTarget := upload.map(_.ref)
    def onProgress[Msg](message: Msg): Mod.Attr[Msg] = on.uploadProgress(message)
    def onProgress[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] = on.uploadProgress(f)

  implicit def stringToMod(value: String): Mod[Nothing]               = Mod.Content.Text(value)
  implicit def signalStringToMod(value: Signal[String]): Mod[Nothing] =
    Mod.Content.SignalText(value)
  implicit def htmlElementToMod[Msg](value: HtmlElement[Msg]): Mod[Msg] = Mod.Content.Tag(value)
end scalive
