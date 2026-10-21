import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder
import scalive.codecs.IntAsStringEncoder
import scalive.codecs.StringAsIsEncoder
import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.components.Components
import scalive.defs.tags.HtmlTags

/** Scalive's application API: typed HTML, event bindings, and LiveView helpers, imported with
  * `scalive.*`.
  */
package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys with Components:
  export _root_.scalive.streams.api.*
  export _root_.scalive.upload.api.*

  /** The `defer` attribute of a `<script>` with a `src`, which runs the script once the document
    * has been parsed while assigned `true`.
    */
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
  def rawHtml(html: String): Mod.Content[Nothing] = Mod.Content.Text(html, raw = true)

  /** Inserts `html` without escaping it.
    *
    * Never pass text that a visitor or another untrusted source can influence: it can inject
    * scripts into the page. Text passed as a plain string is escaped instead.
    */
  def rawHtml(html: Signal[String]): Mod.Content[Nothing] = Mod.Content.SignalText(html, raw = true)

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

  /** Names one instance of a stateful component, to render it with `render(props)`.
    *
    * This form takes an ID that follows a signal: a new ID renders a different instance, with its
    * own model. The result can only be rendered; build an instance with the current ID to target it
    * or send it props.
    */
  def component[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: Signal[String]
  ): LiveComponentInstance.Dynamic[Props, Msg, Model] = LiveComponentInstance.Dynamic(value, id)

  /** Names one instance of a stateful component, to render it with `render(props)`.
    *
    * This form names a component that emits outputs, with an ID that follows a signal; render it
    * with `render(props, onOutput)`.
    */
  def component[Props, Msg, Model, Output](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: Signal[String]
  ): LiveComponentOutputInstance.Dynamic[Props, Msg, Model, Output] =
    LiveComponentOutputInstance.Dynamic(value, id)

  /** Embeds a nested LiveView with its own lifecycle, identified by `id`.
    *
    * This form builds the child from `value` when its lifecycle starts. Keep `id` stable and unique
    * in the page. Set `sticky` to keep the child running when live navigation leaves the parent,
    * and `linkParentOnCrash` to make a crash of the child crash the parent too. See [nested
    * LiveViews](https://scalive.dev/guides/nested-liveviews#render-a-stable-child).
    */
  def liveView[Msg, Model](
    id: String,
    value: => LiveView[Msg, Model],
    sticky: Boolean = false,
    linkParentOnCrash: Boolean = false
  ): Mod.Content[Nothing] =
    Mod.Content.NestedView(NestedViewSpec.Static(id, () => value, sticky, linkParentOnCrash))

  /** Embeds a nested LiveView with its own lifecycle, identified by `id`.
    *
    * This form builds the child with `factory` from the signal's current value when the child's
    * lifecycle starts, and decides `linkParentOnCrash` from that value too.
    */
  def liveView[A, Msg, Model](
    id: String,
    value: Signal[A],
    sticky: Boolean,
    linkParentOnCrash: A => Boolean
  )(
    factory: A => LiveView[Msg, Model]
  ): Mod.Content[Nothing] =
    Mod.Content.NestedView(
      NestedViewSpec.Dynamic(id, value, factory, sticky, linkParentOnCrash)
    )

  /** Embeds a nested LiveView with its own lifecycle, identified by `id`.
    *
    * This form builds the child with `factory` from the signal's current value when the child's
    * lifecycle starts. The child is not sticky and does not crash the parent.
    */
  def liveView[A, Msg, Model](
    id: String,
    value: Signal[A]
  )(
    factory: A => LiveView[Msg, Model]
  ): Mod.Content[Nothing] = liveView(id, value, sticky = false, _ => false)(factory)

  private lazy val portalTemplateTag = HtmlTag("template")
  private lazy val phxPortal         = dataAttr("phx-portal")

  /** Renders `mods` in a wrapper that the browser moves into the element matching `target`.
    *
    * The content stays owned by this LiveView, with its events, hooks, and components, so a dialog
    * can appear inside a root-level container. Keep `id` stable and unique. `container` is the
    * wrapper's tag and `wrapperClass` its class. [[DomSelector.current]] throws
    * `IllegalArgumentException`; whether the target exists is not checked. See
    * [portals](https://scalive.dev/guides/html-dsl-and-event-bindings#move-content-and-wrap-focus).
    */
  def portal[Msg](
    id: DomRef,
    target: DomSelector,
    container: HtmlTag = div,
    wrapperClass: Option[String] = None
  )(
    mods: Mod.Input[Msg]*
  ): HtmlElement[Msg] =
    val content = Mod.flatten(mods)
    val wrapper = Vector.newBuilder[Mod[Msg]]
    wrapper += (idAttr := s"_lv_portal_wrap_${id.value}")
    wrapperClass.foreach(value => wrapper += (cls := value))
    wrapper ++= content

    portalTemplateTag(
      id.attr,
      phxPortal := target.requiredValue,
      container(wrapper.result())
    )

  /** Renders flash messages set with `ctx.flash`. */
  object flash:
    /** Renders the message for `kind` with `project`, and nothing while there is none.
      *
      * A stored message that does not decode as `A` renders nothing.
      */
    def apply[A](kind: FlashKind[A])(project: A => HtmlElement[Nothing]): Mod.Content[Nothing] =
      Mod.Content.Flash(kind.key, (encoded, _) => kind.decodeValue(encoded).map(project))

    /** Renders the message for `kind` with `project`, and nothing while there is none.
      *
      * This form also passes `put`, a number that changes each time `kind` is put while its message
      * is shown, even with the same message. Put it in the element's `id`, as in `s"saved-$put"`,
      * so that the browser replaces the element and its hooks mount again, for example to restart a
      * toast's timer.
      */
    def apply[A](
      kind: FlashKind[A]
    )(
      project: (A, Int) => HtmlElement[Nothing]
    ): Mod.Content[Nothing] =
      Mod.Content.Flash(
        kind.key,
        (encoded, put) => kind.decodeValue(encoded).map(project(_, put))
      )

    /** Clears flash when the element is clicked, without a message to the LiveView.
      *
      * This form clears every message.
      */
    lazy val clearOnClick: Mod.Attr[Nothing] = phx.click := "lv:clear-flash"

    /** Clears flash when the element is clicked, without a message to the LiveView.
      *
      * This form clears only the message for `kind`.
      */
    def clearOnClick(kind: FlashKind[?]): Vector[Mod.Attr[Nothing]] =
      Vector(clearOnClick, phx.value("key") := kind.value)
  end flash

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

  /** Helpers that ask the visitor to confirm before leaving a page with unsaved changes. See
    * [navigation guards](https://scalive.dev/guides/navigation-guards#render-a-guard).
    */
  object navigation:
    private lazy val guardMessage = dataAttr("scalive-navigation-guard")

    /** Guards browser-initiated navigation while `dirty` is true.
      *
      * Add the returned modifier to the element which owns the dirty state; pages served by
      * `ZioHttp.routes` include the browser runtime. The runtime uses `message` for live links,
      * live patch links, and LiveView history traversal. Browsers use their own generic message for
      * cross-document history traversal, reload, and tab-close confirmation.
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
    def pushNavigate[Msg](to: LiveLocation, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      pushNavigateUnsafe(to.href, mods*)

    /** Renders a link that mounts the LiveView at `to` and adds a browser history entry.
      *
      * This form follows a signal of the destination.
      */
    def pushNavigate[Msg](to: Signal[LiveLocation], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      pushNavigateUnsafe(to.map(_.href), mods*)

    /** Like `pushNavigate`, for a raw URL that the route types do not check. */
    def pushNavigateUnsafe[Msg](to: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      a(
        href          := liveDestination(to),
        phx.link      := "redirect",
        phx.linkState := "push",
        Mod.flatten(mods)
      )

    /** Like `pushNavigate`, for a raw URL that the route types do not check.
      *
      * This form follows a signal of the destination.
      */
    def pushNavigateUnsafe[Msg](to: Signal[String], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      a(
        href          := to.map(liveDestination),
        phx.link      := "redirect",
        phx.linkState := "push",
        Mod.flatten(mods)
      )

    /** Renders a link that mounts the LiveView at `to`, replacing the current history entry. */
    def replaceNavigate[Msg](to: LiveLocation, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.href, mods*)

    /** Renders a link that mounts the LiveView at `to`, replacing the current history entry.
      *
      * This form follows a signal of the destination.
      */
    def replaceNavigate[Msg](to: Signal[LiveLocation], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.map(_.href), mods*)

    /** Like `replaceNavigate`, for a raw URL that the route types do not check. */
    def replaceNavigateUnsafe[Msg](to: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      a(
        href          := liveDestination(to),
        phx.link      := "redirect",
        phx.linkState := "replace",
        Mod.flatten(mods)
      )

    /** Like `replaceNavigate`, for a raw URL that the route types do not check.
      *
      * This form follows a signal of the destination.
      */
    def replaceNavigateUnsafe[Msg](to: Signal[String], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      a(
        href          := to.map(liveDestination),
        phx.link      := "redirect",
        phx.linkState := "replace",
        Mod.flatten(mods)
      )

    /** Renders a link that patches this LiveView's URL to `to` and adds a history entry.
      *
      * `to` must be a location of this LiveView's route; a patch to another route stops the
      * LiveView. Use `pushNavigate` for other routes.
      */
    def pushPatch[Msg](to: LiveLocation, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.href, mods*)

    /** Renders a link that patches this LiveView's URL to `to` and adds a history entry.
      *
      * This form follows a signal of the destination.
      */
    def pushPatch[Msg](to: Signal[LiveLocation], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.map(_.href), mods*)

    /** Like `pushPatch`, for a raw URL that the route types do not check. */
    def pushPatchUnsafe[Msg](to: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      a(
        href          := liveDestination(to),
        phx.link      := "patch",
        phx.linkState := "push",
        Mod.flatten(mods)
      )

    /** Like `pushPatch`, for a raw URL that the route types do not check.
      *
      * This form follows a signal of the destination.
      */
    def pushPatchUnsafe[Msg](to: Signal[String], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      a(
        href          := to.map(liveDestination),
        phx.link      := "patch",
        phx.linkState := "push",
        Mod.flatten(mods)
      )

    /** Renders a link that patches this LiveView's URL to `to`, replacing the current entry. */
    def replacePatch[Msg](to: LiveLocation, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.href, mods*)

    /** Renders a link that patches this LiveView's URL to `to`, replacing the current entry.
      *
      * This form follows a signal of the destination.
      */
    def replacePatch[Msg](to: Signal[LiveLocation], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.map(_.href), mods*)

    /** Like `replacePatch`, for a raw URL that the route types do not check. */
    def replacePatchUnsafe[Msg](to: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      a(
        href          := liveDestination(to),
        phx.link      := "patch",
        phx.linkState := "replace",
        Mod.flatten(mods)
      )

    /** Like `replacePatch`, for a raw URL that the route types do not check.
      *
      * This form follows a signal of the destination.
      */
    def replacePatchUnsafe[Msg](to: Signal[String], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      a(
        href          := to.map(liveDestination),
        phx.link      := "patch",
        phx.linkState := "replace",
        Mod.flatten(mods)
      )
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

    /** The raw `phx-click` attribute, naming the event sent by a click on the element or inside it.
      * Prefer [[on.click]], which sends a typed message.
      */
    lazy val click = attr("click")

    /** The raw `phx-click-away` attribute, naming the event sent by a click that starts outside the
      * visible element. Prefer [[on.clickAway]], which sends a typed message.
      */
    lazy val clickAway = attr("click-away")

    /** The raw `phx-blur` attribute, naming the event sent when the element loses focus. Prefer
      * [[on.blur]], which sends a typed message.
      */
    lazy val blur = attr("blur")

    /** The raw `phx-focus` attribute, naming the event sent when the element gains focus. Prefer
      * [[on.focus]], which sends a typed message.
      */
    lazy val focus = attr("focus")

    /** The raw `phx-window-blur` attribute, naming the event sent when the browser window loses
      * focus. Prefer [[on.windowBlur]], which sends a typed message.
      */
    lazy val windowBlur = attr("window-blur")

    /** The raw `phx-window-focus` attribute, naming the event sent when the browser window gains
      * focus. Prefer [[on.windowFocus]], which sends a typed message.
      */
    lazy val windowFocus = attr("window-focus")

    /** The raw `phx-keydown` attribute, naming the event sent by a key press while the element has
      * focus. Prefer [[on.keyDown]], which sends a typed message.
      */
    lazy val keyDown = attr("keydown")

    /** The raw `phx-keyup` attribute, naming the event sent by a key release while the element has
      * focus. Prefer [[on.keyUp]], which sends a typed message.
      */
    lazy val keyUp = attr("keyup")

    /** The raw `phx-window-keydown` attribute, naming the event sent by a key press anywhere in the
      * window. Prefer [[on.windowKeyDown]], which sends a typed message.
      */
    lazy val windowKeyDown = attr("window-keydown")

    /** The raw `phx-window-keyup` attribute, naming the event sent by a key release anywhere in the
      * window. Prefer [[on.windowKeyUp]], which sends a typed message.
      */
    lazy val windowKeyUp = attr("window-keyup")

    /** The raw `phx-change` attribute, naming the event sent when a form or control changes. Prefer
      * `FormDefinition.onChange` for a typed form, or [[on.change]].
      */
    lazy val change = attr("change")

    /** The raw `phx-submit` attribute, naming the event sent when a form is submitted. Prefer
      * `FormDefinition.onSubmit` for a typed form, or [[on.submit]].
      */
    lazy val submit = attr("submit")

    /** The `phx-auto-recover` attribute of a form, naming the event that restores its values after
      * a reconnect, or `"ignore"` to skip recovery.
      *
      * `FormDefinition.onChange` already renders this attribute to recover a typed form.
      */
    lazy val autoRecover = attr("auto-recover")

    /** The `phx-feedback-for` attribute, naming the field that an error container reports on.
      *
      * The bundled Phoenix LiveView client does not act on it. Scalive's form error helpers render
      * it with the field's name.
      */
    lazy val feedbackFor = attr("feedback-for")

    /** The `phx-trigger-action` switch, which hands a form to the browser for an ordinary HTTP
      * submission once it renders as `true`.
      *
      * The form needs an ordinary `action` and `method`, and the HTTP handler must validate the
      * request again. See [validate, then submit over
      * HTTP](https://scalive.dev/guides/http-forms-and-redirects#validate-live-then-trigger-http).
      */
    lazy val triggerAction = boolAttr("trigger-action")

    /** The `phx-no-unused-field` switch, which stops the client from marking unused fields in a
      * form or control, so every received field counts as used for error feedback.
      */
    lazy val noUnusedField = boolAttr("no-unused-field")

    /** The `phx-patch-focused` switch, which lets patches update an editable control while it has
      * focus. Without it, a focused control keeps its browser-managed state during a patch.
      */
    lazy val patchFocused = boolAttr("patch-focused")

    /** The raw `phx-disable-with` attribute: while its event awaits the server's reply, the element
      * is disabled and shows this text instead of its content. Prefer
      * [[submission.replaceTextWith]] or [[submission.disable]].
      */
    lazy val disableWith = attr("disable-with")

    /** The raw `phx-connected` attribute: an encoded `JS` command run each time the LiveView
      * connects. Prefer [[connection.onConnect]], which takes a `JS` command.
      */
    lazy val connected = attr("connected")

    /** The raw `phx-disconnected` attribute: an encoded `JS` command run when the LiveView loses
      * its connection. Prefer [[connection.onDisconnect]], which takes a `JS` command.
      */
    lazy val disconnected = attr("disconnected")

    /** The raw `phx-mounted` attribute: an encoded `JS` command run when the element appears on the
      * page. Prefer [[dom.onMount]], which takes a `JS` command.
      */
    lazy val mounted = attr("mounted")

    /** The raw `phx-remove` attribute: an encoded `JS` command run when a patch removes the
      * element. Prefer [[dom.onRemove]], which takes a `JS` command.
      */
    lazy val remove = attr("remove")

    /** The raw `phx-hook` attribute, naming the client hook that manages the element. Prefer
      * [[dom.hook]], which also sets the `id` a hook requires.
      */
    lazy val hook = attr("hook")

    /** The `phx-update` attribute, which chooses how patches treat the element's children.
      *
      * Prefer [[dom.ignoreUpdates]] for [[PhxUpdate.Ignore]] and a stream's `renderIn` for
      * [[PhxUpdate.Stream]]; both also set the `id` these modes require.
      */
    lazy val update = HtmlAttr[PhxUpdate]("phx-update", Encoder(_.value))

    /** The raw `phx-drop-target` attribute, naming the upload that receives files dropped on the
      * element. Prefer [[LiveUploadDef.dropTarget]].
      */
    lazy val dropTarget = HtmlAttr[UploadRef]("phx-drop-target", Encoder(_.value))

    /** The `phx-debounce` attribute: how many milliseconds to wait for events to stop before
      * sending the latest one, or `"blur"` to wait until the element loses focus.
      *
      * Put it on a form control to limit that control's change events. Elsewhere, prefer
      * [[HtmlAttrBinding.debounce]] on the binding.
      */
    lazy val debounce = HtmlAttr["blur" | Int](
      "phx-debounce",
      Encoder {
        case _: "blur"  => "blur"
        case value: Int => value.toString
      }
    )

    /** The `phx-throttle` attribute: how many milliseconds to ignore further events after sending
      * one. Prefer [[HtmlAttrBinding.throttle]] on the binding.
      */
    lazy val throttle = intAttr("throttle")

    /** The `phx-track-static` switch for a `<script>` or stylesheet `<link>` whose URL the browser
      * reports when it connects, so [[ConnectedMetadata.staticChanged]] can reveal a new
      * deployment. Prefer `StaticAssets.trackedScript` and `trackedStylesheet`.
      */
    lazy val trackStatic = boolAttr("track-static")

    /** Chooses the LiveView or component that receives the element's events (`phx-target`): the
      * owners of the elements matching `selector`.
      *
      * Scalive already sends a binding's events to the LiveView or component that renders it, and
      * only that owner can resolve the binding. Use this escape hatch for Phoenix parity.
      * [[DomSelector.current]] throws `IllegalArgumentException`.
      */
    def target(selector: DomSelector): Mod.Attr[Nothing] = attr("target") := selector.requiredValue

    /** Returns the `phx-value-<key>` attribute, whose value the element's events carry under `key`.
      *
      * Read it from the payload of a binding's function form. A message that carries the value
      * itself, such as a signal of a message, is usually simpler.
      */
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
    lazy val onConnect = JSCommandBinding("phx-connected")

    /** Runs a `JS` command shortly after the LiveView loses its connection. */
    lazy val onDisconnect = JSCommandBinding("phx-disconnected")

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

  /** Client-side behavior for one element: hooks, ignored updates, and commands that run when the
    * element is added or removed.
    */
  object dom:
    /** Runs a `JS` command when the element appears on the page (`phx-mounted`).
      *
      * It runs once per element, when the LiveView connects or when a patch adds the element. Use
      * `JS.push` inside the command to send a message.
      */
    lazy val onMount = JSCommandBinding("phx-mounted")

    /** Runs a `JS` command when a patch removes the element (`phx-remove`), as for an exit
      * transition.
      */
    lazy val onRemove = JSCommandBinding("phx-remove")

    /** Attaches the client hook registered as `name` to the element, with the `id` a hook requires.
      *
      * Register the hook under the same name in the `LiveSocket` options, and derive `id` from the
      * LiveView or component instance so it stays unique. An empty `name` throws
      * `IllegalArgumentException`. See [hook
      * identity](https://scalive.dev/guides/browser-integration#give-every-hook-stable-identity).
      */
    def hook(name: String, id: DomRef): Vector[Mod.Attr[Nothing]] =
      require(name.nonEmpty, "hook name must not be empty")
      Vector(id.attr, phx.hook := name)

    /** Keeps patches from changing the element's content, for content that JavaScript manages.
      *
      * Renders `phx-update="ignore"` with the `id` that mode requires. The element's `data-*`
      * attributes still update.
      */
    def ignoreUpdates(id: DomRef): Vector[Mod.Attr[Nothing]] =
      Vector(id.attr, phx.update := PhxUpdate.Ignore)
  end dom

  /** Feedback on a button or other control while its event awaits the server's reply. */
  object submission:
    /** Disables the element, keeping its content, while its event awaits the server's reply
      * (`phx-disable-with`).
      */
    lazy val disable: Mod.Attr[Nothing] =
      htmlAttr("phx-disable-with", BooleanAsAttrPresenceEncoder) := true

    /** Disables the element and shows `text` instead of its content while its event awaits the
      * server's reply (`phx-disable-with`), as in `submission.replaceTextWith("Saving...")`.
      */
    def replaceTextWith(text: String): Mod.Attr[Nothing] = phx.disableWith := text

  extension [R](upload: Signal[LiveUpload[R]])
    /** Sends a message each time the browser reports transfer progress for this upload
      * (`phx-progress`).
      *
      * Rendering already follows progress; use it only to react to it. This form sends a constant
      * message.
      */
    def onProgress[Msg](message: Msg): UploadProgress[Msg] =
      UploadProgress(upload.map(_.ref), on.uploadProgress(message))

    /** Sends a message each time the browser reports transfer progress for this upload
      * (`phx-progress`).
      *
      * Rendering already follows progress; use it only to react to it. This form builds the message
      * from the raw `ref`, `entry_ref`, `progress`, and optional `error` values, which the browser
      * controls.
      */
    def onProgress[Msg](f: Map[String, String] => Msg): UploadProgress[Msg] =
      UploadProgress(upload.map(_.ref), on.uploadProgress(f))

  /** Converts a string to text content, escaped when rendered, so a string can be a child. */
  implicit def stringToMod(value: String): Mod.Content[Nothing] = Mod.Content.Text(value)

  /** Converts a signal of a string to text content that follows it, escaped when rendered. */
  implicit def signalStringToMod(value: Signal[String]): Mod.Content[Nothing] =
    Mod.Content.SignalText(value)

  /** Converts an element to child content where a [[Mod]] is expected, as in `appended`. */
  implicit def htmlElementToMod[Msg](value: HtmlElement[Msg]): Mod.Content[Msg] =
    Mod.Content.Tag(value)
end scalive
