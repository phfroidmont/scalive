package scalive

import zio.json.*
import zio.json.ast.Json

/** The empty [[JSCommands.JSCommand]] from which client commands are composed.
  *
  * Each extension appends one operation and returns a new command. Chained operations execute in
  * call order. Client-only operations do not wait for an earlier server operation to be
  * acknowledged before starting.
  */
val JS: JSCommands.JSCommand[Nothing] = JSCommands.empty

/** Types and extension methods for composable Phoenix LiveView client commands. */
object JSCommands:
  /** An immutable sequence of browser commands which may bind server messages of type `Msg`.
    *
    * The type is covariant so a command with no server message, including [[scalive.JS]], composes
    * with commands for any LiveView message type. Use a command with typed HTML event bindings such
    * as `on.click(command)`, or execute client-only commands from the server with
    * `ctx.client.exec`. Class, attribute, and visibility mutations use the LiveView client's
    * patch-aware operations, so their resulting DOM state is retained across server patches.
    * Builders do not generally validate class names, attribute names, display values, or durations;
    * invalid values retain the browser/client's behavior.
    *
    * A command containing [[push]] must be rendered as an HTML event binding. Rendering gives each
    * push a stable binding ID and registers its typed message. Encoding such a command directly, or
    * sending it through `ctx.client.exec`, has no HTML binding scope and leaves the push
    * unresolved; the encoded placeholder is not a usable server event binding.
    *
    * @tparam Msg
    *   the least upper bound of messages pushed by this command
    */
  opaque type JSCommand[+Msg] = List[Op[Msg]]

  final private case class Op[+Msg](
    renderJson: Option[String] => Json,
    binding: Option[Binding[Msg]])

  final private case class Binding[+Msg](msg: Msg)

  private[scalive] def empty: JSCommand[Nothing] = List.empty

  /** JSON encoding support for [[JSCommand]].
    *
    * Operations are emitted in composition order. Defaults represented by omitted command options
    * are supplied by the LiveView client. A [[push]] event remains unresolved under this standalone
    * encoder; normal HTML rendering uses its binding-aware encoder instead.
    */
  object JSCommand:
    /** Encodes a command for the Phoenix LiveView client.
      *
      * Do not use standalone encoding to execute a command containing [[push]]; attach that command
      * to a typed HTML event binding so its message can be registered.
      */
    given [Msg]: JsonEncoder[JSCommand[Msg]] =
      JsonEncoder[Json].contramap(ops => Json.Arr(ops.map(_.renderJson(None)).reverse*))

  private def encodeOp[A: JsonEncoder](kind: String, args: A): Json =
    (kind, args).toJsonAST.toOption.getOrElse(Json.Arr(Json.Str(kind), Json.Obj.empty))

  private def classNames(names: String): Seq[String] = names.split("\\s+").toSeq
  private def transitionClasses(names: String | (String, String, String))
    : Option[Seq[Seq[String]]] =
    names match
      case ""                          => None
      case names: String               => Some(Seq(classNames(names), Seq.empty, Seq.empty))
      case t: (String, String, String) => Some(t.toList.map(classNames))

  extension [Msg](ops: JSCommand[Msg])
    private[scalive] def map[Msg2](f: Msg => Msg2): JSCommand[Msg2] =
      ops.map(op => op.copy(binding = op.binding.map(binding => Binding(f(binding.msg)))))

    private def addOp[A: JsonEncoder](kind: String, args: A): JSCommand[Msg] =
      Op(_ => encodeOp(kind, args), None) :: ops

    private def resolved(scope: String): Vector[(Json, Option[(String, Msg)])] =
      ops.reverse.zipWithIndex.map { case (op, pushIndex) =>
        val resolvedId =
          if op.binding.isDefined then Some(s"$scope:js:$pushIndex") else None
        val json    = op.renderJson(resolvedId)
        val binding =
          op.binding.map(binding => resolvedId.get -> binding.msg)
        (json, binding)
      }.toVector

    private[scalive] def renderJson(scope: String): String =
      Json.Arr(resolved(scope).map(_._1)*).toJson

    private[scalive] def bindings(scope: String): Vector[(String, Msg)] =
      resolved(scope).flatMap(_._2)

    /** Appends a command which adds whitespace-separated class `names` to the selected elements.
      *
      * `to` defaults to the element executing the command. `transition` may be a class string or an
      * `(transition, start, end)` tuple of class strings; `""` requests no transition. `time` is
      * the transition duration in milliseconds and defaults to 200. `blocking` defaults to `true`,
      * so LiveView defers concurrent UI work for the transition; use `false` to let it run
      * asynchronously.
      */
    def addClass = ClassOp("add_class", ops)

    /** Appends a command which toggles whitespace-separated class `names` on the selected elements.
      *
      * `to` defaults to the element executing the command. `transition` may be a class string or an
      * `(transition, start, end)` tuple of class strings; `""` requests no transition. `time` is
      * the transition duration in milliseconds and defaults to 200. `blocking` defaults to `true`,
      * so LiveView defers concurrent UI work for the transition; use `false` to let it run
      * asynchronously.
      */
    def toggleClass = ClassOp("toggle_class", ops)

    /** Appends a command which removes whitespace-separated class `names` from selected elements.
      *
      * `to` defaults to the element executing the command. `transition` may be a class string or an
      * `(transition, start, end)` tuple of class strings; `""` requests no transition. `time` is
      * the transition duration in milliseconds and defaults to 200. `blocking` defaults to `true`,
      * so LiveView defers concurrent UI work for the transition; use `false` to let it run
      * asynchronously.
      */
    def removeClass = ClassOp("remove_class", ops)

    /** Appends a command which dispatches a DOM event to the selected elements.
      *
      * The LiveView client dispatches `"click"` as a `MouseEvent` and other names as a
      * `CustomEvent`. `detail` is included in the event detail along with a client-supplied
      * `dispatcher` reference. Events bubble by default. With `blocking = true`, the detail also
      * contains `done`; the listener must eventually call it to release the blocked UI.
      *
      * @param event
      *   the DOM event name
      * @param to
      *   the event targets, defaulting to the element executing this command
      * @param detail
      *   string-valued custom-event detail, defaulting to no application detail
      * @param bubbles
      *   whether the event bubbles, defaulting to `true`
      * @param blocking
      *   whether to block LiveView UI work until `event.detail.done()` is called; defaults to
      *   `false`
      */
    def dispatch(
      event: String,
      to: DomSelector = DomSelector.current,
      detail: Map[String, String] = Map.empty,
      bubbles: Boolean = true,
      blocking: Boolean = false
    ) =
      ops.addOp(
        "dispatch",
        Args.Dispatch(
          event,
          to.jsonValue,
          Option.when(detail.nonEmpty)(detail),
          Option.when(!bubbles)(bubbles),
          Option.when(blocking)(blocking)
        )
      )

    /** Appends a command which executes the encoded JS command stored in an HTML attribute.
      *
      * The attribute is read from each selected element and executed with that element as its
      * command source. A missing or empty attribute causes the LiveView client to report an error.
      *
      * @param attr
      *   the attribute name containing an encoded command
      * @param to
      *   the element from which to read the attribute, defaulting to the current command source
      */
    def exec(attr: String, to: DomSelector = DomSelector.current) =
      ops.addOp(
        "exec",
        Args.Attr(
          attr,
          to.jsonValue
        )
      )

    /** Appends a command which focuses the selected elements.
      *
      * `to` defaults to the element executing the command.
      */
    def focus(to: DomSelector = DomSelector.current) =
      ops.addOp(
        "focus",
        Args.To(to.jsonValue)
      )

    /** Appends a command which focuses the first focusable descendant of each selected element.
      *
      * The client prefers an interactive descendant and falls back to the first focusable one. `to`
      * defaults to the element executing the command.
      */
    def focusFirst(to: DomSelector = DomSelector.current) =
      ops.addOp(
        "focus_first",
        Args.To(to.jsonValue)
      )

    /** Appends a command which hides selected elements that are currently visible.
      *
      * `transition` accepts a class string or an `(transition, start, end)` tuple; `""` requests no
      * transition. `time` is measured in milliseconds and defaults to 200. `blocking` defaults to
      * `true`; `false` allows the timed transition to proceed without blocking other LiveView UI
      * work. `to` defaults to the element executing the command.
      */
    def hide(
      to: DomSelector = DomSelector.current,
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true
    ) =
      ops.addOp(
        "hide",
        Args.Hide(
          to.jsonValue,
          transitionClasses(transition),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking)
        )
      )

    /** Appends a command which preserves selected client-side attributes across future DOM patches.
      *
      * Names may include the LiveView client's `*` wildcard syntax. Calling this command again
      * replaces the ignored set for each target. It affects future patches only, so it cannot
      * restore an attribute already changed during disconnected render. Although `attrs` defaults
      * to an empty sequence, the current client expects an actual list; pass a non-empty sequence
      * rather than relying on that default. `to` defaults to the element executing the command.
      */
    def ignoreAttributes(
      attrs: Seq[String] = Seq.empty,
      to: DomSelector = DomSelector.current
    ) =
      ops.addOp(
        "ignore_attrs",
        Args.IgnoreAttributes(
          Option.when(attrs.nonEmpty)(attrs),
          to.jsonValue
        )
      )

    /** Appends a live navigation to a typed location and pushes a browser history entry.
      *
      * This changes LiveViews rather than patching the current one. Requiring [[LiveLocation]]
      * keeps normal navigation tied to route-derived, encoded destinations; use
      * [[pushNavigateUnsafe]] only when no typed location can represent the destination.
      */
    def pushNavigate(to: LiveLocation) =
      pushNavigateUnsafe(to.href)

    /** Builds a signal-backed command for a navigation destination. */
    def pushNavigate(to: Signal[LiveLocation]): Signal[JSCommand[Msg]] =
      to.map(location => ops.pushNavigate(location))

    /** Appends a live navigation to the raw `href` and pushes a browser history entry.
      *
      * The string is passed through without route typing, validation, or normalization. "Unsafe"
      * denotes that explicit escape from typed outbound navigation; it does not apply a different
      * client navigation mechanism.
      */
    def pushNavigateUnsafe(href: String) =
      ops.addOp("navigate", Args.Href(liveDestination(href), None))

    /** Builds a signal-backed command for a raw navigation destination. */
    def pushNavigateUnsafe(href: Signal[String]): Signal[JSCommand[Msg]] =
      href.map(value => ops.pushNavigateUnsafe(value))

    /** Appends a live navigation to a typed location and replaces the current history entry.
      *
      * This changes LiveViews rather than patching the current one. Use [[replaceNavigateUnsafe]]
      * only for a destination which cannot be represented by a [[LiveLocation]].
      */
    def replaceNavigate(to: LiveLocation) =
      replaceNavigateUnsafe(to.href)

    /** Builds a signal-backed command for replacement navigation. */
    def replaceNavigate(to: Signal[LiveLocation]): Signal[JSCommand[Msg]] =
      to.map(location => ops.replaceNavigate(location))

    /** Appends a live navigation to raw `href` and replaces the current browser history entry.
      *
      * The string is passed through without route typing, validation, or normalization.
      */
    def replaceNavigateUnsafe(href: String) =
      ops.addOp("navigate", Args.Href(liveDestination(href), Some(true)))

    /** Builds a signal-backed command for raw replacement navigation. */
    def replaceNavigateUnsafe(href: Signal[String]): Signal[JSCommand[Msg]] =
      href.map(value => ops.replaceNavigateUnsafe(value))

    /** Appends a live patch to a typed location and pushes a browser history entry.
      *
      * A patch updates the current LiveView without replacing it. Use [[pushPatchUnsafe]] for raw
      * destinations such as query-only references which cannot be represented by a full typed
      * location.
      */
    def pushPatch(to: LiveLocation) =
      pushPatchUnsafe(to.href)

    /** Builds a signal-backed command for a patch destination. */
    def pushPatch(to: Signal[LiveLocation]): Signal[JSCommand[Msg]] =
      to.map(location => ops.pushPatch(location))

    /** Appends a live patch to raw `href` and pushes a browser history entry.
      *
      * The string is passed through without route typing, validation, or normalization. This is the
      * explicit escape hatch for destinations such as `"?page=2"`.
      */
    def pushPatchUnsafe(href: String) =
      ops.addOp("patch", Args.Href(liveDestination(href), None))

    /** Builds a signal-backed command for a raw patch destination. */
    def pushPatchUnsafe(href: Signal[String]): Signal[JSCommand[Msg]] =
      href.map(value => ops.pushPatchUnsafe(value))

    /** Appends a live patch to a typed location and replaces the current browser history entry. */
    def replacePatch(to: LiveLocation) =
      replacePatchUnsafe(to.href)

    /** Builds a signal-backed command for replacement patching. */
    def replacePatch(to: Signal[LiveLocation]): Signal[JSCommand[Msg]] =
      to.map(location => ops.replacePatch(location))

    /** Appends a live patch to raw `href` and replaces the current browser history entry.
      *
      * The string is passed through without route typing, validation, or normalization.
      */
    def replacePatchUnsafe(href: String) =
      ops.addOp("patch", Args.Href(liveDestination(href), Some(true)))

    /** Builds a signal-backed command for raw replacement patching. */
    def replacePatchUnsafe(href: Signal[String]): Signal[JSCommand[Msg]] =
      href.map(value => ops.replacePatchUnsafe(value))

    private def liveDestination(value: String): String =
      NavigationDestination.live(value).fold(throw _, identity)

    /** Appends a command which focuses the most recently pushed focus target, if one exists. */
    def popFocus() =
      ops.addOp("pop_focus", Json.Obj.empty)

    /** Appends a typed event push to the server.
      *
      * Unlike Phoenix's string event API, `event` is the Scala message delivered by the rendered
      * event binding. HTML rendering replaces it with a stable binding ID and registers the message
      * under that ID. This widens the command's message type as needed. Multiple pushes in one
      * command are registered independently and retain composition order.
      *
      * `target = DomSelector.current` omits an explicit target, allowing the source element's
      * `phx-target` and normal LiveView ownership rules to apply. An explicit selector overrides
      * that target. The source receives the normal loading state; an explicit `loading` selector
      * additionally applies the event's loading class and locks matching elements until the server
      * acknowledges the push. `pageLoading` defaults to `false`; the option is encoded, but the
      * currently supported Phoenix client does not observe its camel-case name, so setting it does
      * not currently trigger page-loading lifecycle events.
      *
      * A push must be attached to a typed HTML event binding such as `on.click(JS.push(message))`.
      * Standalone JSON encoding and `ctx.client.exec` cannot allocate its binding ID and therefore
      * leave an unresolved placeholder.
      *
      * @param event
      *   the Scala message to deliver to the LiveView
      * @param target
      *   an explicit server-event target, or [[DomSelector.current]] to use normal source targeting
      * @param loading
      *   additional elements which receive loading state, or [[DomSelector.current]] for only the
      *   source's normal loading state
      * @param pageLoading
      *   the page-loading option, currently not observed by the supported client; defaults to
      *   `false`
      */
    def push[Msg2 >: Msg](
      event: Msg2,
      target: DomSelector = DomSelector.current,
      loading: DomSelector = DomSelector.current,
      pageLoading: Boolean = false
    ): JSCommand[Msg2] =
      val binding = Binding(event)
      Op(
        maybeBindingId =>
          encodeOp(
            "push",
            Args.Push(
              maybeBindingId.getOrElse("$scalive-unresolved-binding"),
              target.jsonValue,
              loading.jsonValue,
              Option.when(pageLoading)(pageLoading)
            )
          ),
        Some(binding)
      )
        :: ops

    /** Appends a command which pushes a focus target onto the client's focus stack.
      *
      * Pair this with [[popFocus]] to restore focus later. `to` defaults to the element executing
      * the command.
      */
    def pushFocus(to: DomSelector = DomSelector.current) =
      ops.addOp("push_focus", Args.To(to.jsonValue))

    /** Appends a command which removes `attr` from the selected elements.
      *
      * The client keeps this attribute mutation across subsequent DOM patches. `to` defaults to the
      * element executing the command.
      */
    def removeAttribute(attr: String, to: DomSelector = DomSelector.current) =
      ops.addOp(
        "remove_attr",
        Args.Attr(
          attr,
          to.jsonValue
        )
      )

    /** Appends a command which sets an HTML attribute on the selected elements.
      *
      * `arg` is the attribute name and value. The client keeps this attribute mutation across
      * subsequent DOM patches. This changes an attribute, not a same-named DOM property. `to`
      * defaults to the element executing the command.
      */
    def setAttribute(arg: (String, String), to: DomSelector = DomSelector.current) =
      ops.addOp("set_attr", Args.SetAttribute(attr = arg, to = to.jsonValue))

    /** Appends a command which shows selected elements that are currently hidden.
      *
      * `transition` accepts a class string or an `(transition, start, end)` tuple; `""` requests no
      * transition. `time` is measured in milliseconds and defaults to 200. `blocking` defaults to
      * `true`; `false` allows the timed transition to proceed without blocking other LiveView UI
      * work. The default `display` request uses the client's normal display value (`block`, with
      * table-row and table-cell handling); another value is sent explicitly. `to` defaults to the
      * element executing the command.
      */
    def show(
      to: DomSelector = DomSelector.current,
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true,
      display: String = "block"
    ) =
      ops.addOp(
        "show",
        Args.Show(
          to.jsonValue,
          transitionClasses(transition),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking),
          Option.when(display != "block")(display)
        )
      )

    /** Appends a command which toggles the visibility of selected elements.
      *
      * `in` and `out` each accept a class string or an `(transition, start, end)` tuple; `""`
      * requests no classes for that direction. `time` is measured in milliseconds and defaults to
      * 200. `blocking` defaults to `true`; `false` lets a timed transition run without blocking
      * other LiveView UI work. The default `display` request uses the client's normal display value
      * (`block`, with table-row and table-cell handling); another value is sent explicitly. `to`
      * defaults to the element executing the command.
      */
    def toggle(
      to: DomSelector = DomSelector.current,
      in: String | (String, String, String) = "",
      out: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true,
      display: String = "block"
    ) =
      ops.addOp(
        "toggle",
        Args.Toggle(
          to.jsonValue,
          ins = transitionClasses(in),
          outs = transitionClasses(out),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking),
          Option.when(display != "block")(display)
        )
      )

    /** Appends a command which toggles an attribute on selected elements.
      *
      * With the default empty `altValue`, the client sets `name` to `value` when absent and removes
      * it when present. With a non-empty `altValue`, it toggles between `value` and `altValue`.
      * `to` defaults to the element executing the command.
      */
    def toggleAttribute(
      name: String,
      value: String,
      altValue: String = "",
      to: DomSelector = DomSelector.current
    ) =
      ops.addOp(
        "toggle_attr",
        Args.ToggleAttribute(
          attr = Seq(name, value).appendedAll(Option.when(altValue.nonEmpty)(altValue)),
          to.jsonValue
        )
      )

    /** Appends a temporary class transition to the selected elements.
      *
      * A string supplies transition classes with empty start and end phases. A tuple supplies the
      * `(transition, start, end)` class strings. Unlike the optional transitions on visibility and
      * class commands, this command always sends a transition shape; callers should provide at
      * least one non-empty class rather than relying on the `""` default. `time` is measured in
      * milliseconds and defaults to 200. `blocking` defaults to `true`; `false` lets the transition
      * run without blocking other LiveView UI work. `to` defaults to the element executing the
      * command.
      */
    def transition(
      transition: String | (String, String, String) = "",
      to: DomSelector = DomSelector.current,
      time: Int = 200,
      blocking: Boolean = true
    ) =
      ops.addOp(
        "transition",
        Args.Transition(
          transition match
            case names: String               => Seq(classNames(names), Seq.empty, Seq.empty)
            case t: (String, String, String) => t.toList.map(classNames),
          to.jsonValue,
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking)
        )
      )

  end extension

  final private[scalive] class ClassOp[Msg](kind: String, ops: JSCommand[Msg]):
    def apply(
      names: String,
      to: DomSelector = DomSelector.current,
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true
    ): JSCommand[Msg] =
      ops.addOp(
        kind,
        Args.ClassChange(
          classNames(names),
          to.jsonValue,
          transitionClasses(transition),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking)
        )
      )

  private object Args:
    final case class ClassChange(
      names: Seq[String],
      to: Option[String],
      transition: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class Dispatch(
      event: String,
      to: Option[String],
      detail: Option[Map[String, String]],
      bubbles: Option[Boolean],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class Attr(attr: String, to: Option[String]) derives JsonEncoder
    final case class To(to: Option[String]) derives JsonEncoder
    final case class IgnoreAttributes(attrs: Option[Seq[String]], to: Option[String])
        derives JsonEncoder
    final case class Hide(
      to: Option[String],
      transition: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class SetAttribute(
      attr: (String, String),
      to: Option[String])
        derives JsonEncoder
    final case class Href(href: String, replace: Option[Boolean]) derives JsonEncoder
    final case class Show(
      to: Option[String],
      transition: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean],
      display: Option[String])
        derives JsonEncoder
    final case class Toggle(
      to: Option[String],
      ins: Option[Seq[Seq[String]]],
      outs: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean],
      display: Option[String])
        derives JsonEncoder
    final case class ToggleAttribute(attr: Seq[String], to: Option[String]) derives JsonEncoder
    final case class Transition(
      transition: Seq[Seq[String]],
      to: Option[String],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class Push(
      event: String,
      target: Option[String],
      loading: Option[String],
      pageLoading: Option[Boolean])
        derives JsonEncoder
  end Args

end JSCommands
