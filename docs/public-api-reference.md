# Scalive Public API Reference

This document describes the intended public API exposed by Scalive to application authors.

Most application code starts with:

```scala
import scalive.*
```

The package object exports the generated HTML tag and attribute definitions, stream APIs, upload APIs, helpers for LiveViews and components, and Phoenix LiveView-style `phx-*` bindings.

## Public Boundary

The app-author API lives in `scalive.*` and the explicitly public subpackages used from it, such as `scalive.codecs` for custom attribute encoders.

Runtime, websocket protocol, diff rendering, socket orchestration, and disabled runtime implementation types are internal implementation details. They are kept package-private in code and are not supported as application APIs.

Test helpers live in the separate `scalive-testing` artifact under `scalive.testing.*`, so HTML parsing dependencies do not become application runtime dependencies.

## Testing API

Import the first Scalive-native testing helpers with:

```scala
import scalive.testing.*
```

### `DisconnectedRender.run`

`DisconnectedRender.run` executes finalized ZIO HTTP routes directly without starting a server:

```scala
object DisconnectedRender:
  def run[R](
    routes: zio.http.Routes[R, Nothing],
    request: zio.http.Request
  ): zio.ZIO[R, Throwable, RenderedPage]
```

This is a disconnected render through the production route lifecycle. It includes route decoding, mount aspects, `mount`, `handleParams`, layouts, components, nested LiveViews, session metadata, and CSRF response handling. It does not connect a LiveSocket, dispatch events, or run connected subscriptions and async work.

The complete `Request` is accepted so tests can supply typed locations, query parameters, headers, and authentication cookies. The route environment remains in the returned effect and can be provided with normal ZIO layers.

### `RenderedPage`

```scala
final class RenderedPage:
  val response: zio.http.Response
  val html: String

  def text: String
  def forms: Vector[RenderedForm]
  def form(query: FormQuery = FormQuery()): Either[FormQueryError, RenderedForm]
```

`response` preserves the status and headers and contains a replayable body. `html` exposes the complete rendered response when a low-level assertion is necessary. Prefer `text`, `forms`, and `form` for deterministic semantic assertions because LiveView IDs, signed session values, and CSRF values are intentionally opaque and may change between renders.

`form` requires exactly one match. `FormQuery` can match an action attribute, an effective HTTP method, or both:

```scala
final case class FormQuery(
  action: Option[String] = None,
  method: Option[zio.http.Method] = None
)

enum FormQueryError:
  case NotFound(query: FormQuery)
  case MultipleMatches(query: FormQuery, count: Int)
```

### `RenderedForm` and `RenderedField`

```scala
final class RenderedForm:
  def id: Option[String]
  def action: Option[String]
  def method: zio.http.Method
  def fields: Vector[RenderedField]
  def names: Vector[String]
  def values(name: String): Vector[String]
  def values(path: FormPath): Vector[String]
  def hasChangeBinding: Boolean
  def hasSubmitBinding: Boolean
  def triggersAction: Boolean

final class RenderedField:
  def tagName: String
  def id: Option[String]
  def name: String
  def value: String
  def inputType: Option[String]
  def required: Boolean
```

Named controls and repeated values retain document order. `POST` is reported explicitly; an absent or non-HTTP form method has the browser-effective `GET` method. Binding accessors report whether the rendered form uses Live change or submit handling, or requests an ordinary action through `phx-trigger-action`; they do not expose internal binding IDs or handlers.

## Core LiveView API

### `LiveView[Msg, Model]`

`LiveView` is the root application abstraction. A LiveView owns a typed model and receives typed messages.

```scala
trait LiveView[Msg, Model]:
  type MountContext = scalive.MountContext[Msg, Model]
  type MessageContext = scalive.MessageContext[Msg, Model]
  type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

  def hooks: LiveHooks[Msg, Model] = LiveHooks.empty

  def mount(ctx: MountContext): LiveIO[Model]
  def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model]
  def render(model: Model): HtmlElement[Msg]
```

Lifecycle methods:

- `mount` creates the initial model for disconnected and connected lifecycle phases.
- `handleMessage` handles typed messages produced by HTML bindings, JS push commands, async tasks, and subscriptions.
- `render` returns the current HTML tree.
- `hooks` installs static lifecycle hooks, including raw client-event interception through `LiveHooks.rawEvent`.
- Runtime subscriptions are started explicitly from phase contexts with `ctx.subscriptions.start`.

### `LiveView.Eventless[Model]`

Use `LiveView.Eventless` when a view has no server messages. It fixes the message type to `Nothing` and supplies the no-op `handleMessage`, so application code only needs to define `mount` and `render`. The `Nothing` message type also prevents server event bindings from appearing in the rendered HTML.

```scala
trait LiveView.Eventless[Model] extends LiveView[Nothing, Model]
```

Routes, route factories, and nested `liveView` content accept eventless views directly, including values widened to `LiveView[Nothing, Model]`. These APIs use `LiveMessageTag[Msg]`: its companion supplies the exact `Nothing` tag for eventless views and derives all other tags from `ClassTag[Msg]`, preserving runtime binding validation for message-bearing views.

### `LiveView.Routed[Msg, Model, Params]`

`LiveView.Routed` is a `LiveView` whose route declares typed URL params. Plain `LiveView`s do not run the params lifecycle.

```scala
trait LiveView.Routed[Msg, Model, Params] extends LiveView[Msg, Model]:
  type ParamsContext = scalive.ParamsContext[Msg, Model]

  def handleParams(
    model: Model,
    params: Params,
    url: zio.http.URL,
    ctx: ParamsContext
  ): LiveIO[Model]

  def handleParamsDecodeError(
    model: Model,
    error: LiveParamsCodec.DecodeError,
    url: zio.http.URL,
    ctx: ParamsContext
  ): LiveIO[Model]
```

Params lifecycle methods:

- `handleParams` runs when route path and query params decode successfully.
- `handleParamsDecodeError` runs when route params cannot decode the current URL.
- Params are decoded by the route declaration, not by the LiveView itself.

### `LiveView.Routed.Eventless[Model, Params]`

Use `LiveView.Routed.Eventless` for a routed view with typed params but no server messages. It combines `LiveView.Eventless[Model]` with `LiveView.Routed[Nothing, Model, Params]`.

```scala
trait LiveView.Routed.Eventless[Model, Params]
    extends LiveView.Eventless[Model],
      LiveView.Routed[Nothing, Model, Params]
```

### `LiveComponent[Props, Msg, Model]`

`LiveComponent` is a stateful component abstraction. A component receives typed props, owns a typed model, and receives typed component messages.

```scala
trait LiveComponent[Props, Msg, Model]:
  type MountContext = scalive.ComponentMountContext[Props, Msg, Model]
  type UpdateContext = scalive.ComponentUpdateContext[Props, Msg, Model]
  type MessageContext = scalive.ComponentMessageContext[Props, Msg, Model]
  type AfterRenderContext = scalive.ComponentAfterRenderContext[Props, Msg, Model]

  def hooks: ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.empty

  def mount(props: Props, ctx: MountContext): LiveIO[Model]
  def update(props: Props, model: Model, ctx: UpdateContext): LiveIO[Model]
  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => LiveIO[Model]
  def render(props: Props, model: Model, self: ComponentRef[Msg]): HtmlElement[Msg]
```

### `LiveComponent.Eventless[Props, Model]`

Use `LiveComponent.Eventless` when a component receives props and owns state but has no component messages. It fixes the message type and `ComponentRef` type to `Nothing` and supplies the no-op `handleMessage`.

```scala
trait LiveComponent.Eventless[Props, Model]
    extends LiveComponent[Props, Nothing, Model]
```

### `LiveIO[A]`

`LiveIO` is the effect type used by lifecycle callbacks and context facades.

```scala
type LiveIO[+A] = zio.Task[A]

object LiveIO:
  def succeed[A](value: A): LiveIO[A]
  def fail[A](error: Throwable): LiveIO[A]

  given [A]: Conversion[A, LiveIO[A]]
```

Plain model returns are opt-in. Import the conversion where you want that style:

```scala
import scalive.LiveIO.given

def mount(ctx: MountContext): LiveIO[Model] =
  Model.empty
```

`Task` values conform directly because `LiveIO` is a transparent type alias.

## Phase Context API

Lifecycle callbacks receive explicit phase contexts. Contexts expose domain facades directly and do not require application code to provide or request ZIO environment services.

### Context Availability

```scala
trait LifecycleContext:
  def connected: Boolean
  def staticChanged: Boolean
  def connectParams: Map[String, Json]

trait MountContext[Msg, Model] extends LifecycleContext:
  def nav: MountNavigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def title: Title
  def hooks: RootHooks[Msg, Model]

trait MessageContext[Msg, Model] extends LifecycleContext:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def title: Title
  def components: ComponentUpdates
  def hooks: RootHooks[Msg, Model]

trait ParamsContext[Msg, Model] extends LifecycleContext:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def title: Title
  def components: ComponentUpdates
  def hooks: RootHooks[Msg, Model]

trait AfterRenderContext[Msg, Model] extends LifecycleContext:
  def client: Client
  def hooks: RootHooks[Msg, Model]
```

```scala
trait ComponentMountContext[Props, Msg, Model] extends LifecycleContext:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def client: Client
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentUpdateContext[Props, Msg, Model] extends LifecycleContext:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def client: Client
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentMessageContext[Props, Msg, Model] extends LifecycleContext:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def client: Client
  def components: ComponentUpdates
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentAfterRenderContext[Props, Msg, Model] extends LifecycleContext:
  def hooks: ComponentHooks[Props, Msg, Model]
```

### Navigation

```scala
trait MountNavigation:
  def pushNavigate(to: LiveLocation): LiveIO[Unit]
  def pushNavigateUnsafe(to: String): LiveIO[Unit]

  def replaceNavigate(to: LiveLocation): LiveIO[Unit]
  def replaceNavigateUnsafe(to: String): LiveIO[Unit]

  def redirect(to: LiveLocation): LiveIO[Unit]
  def redirectUnsafe(to: String): LiveIO[Unit]

trait Navigation extends MountNavigation:
  def pushPatch(to: LiveLocation): LiveIO[Unit]
  def pushPatchUnsafe(to: String): LiveIO[Unit]

  def replacePatch(to: LiveLocation): LiveIO[Unit]
  def replacePatchUnsafe(to: String): LiveIO[Unit]
```

The methods without an `Unsafe` suffix require a full location derived from a Live route declaration. Use the explicit unsafe methods for external or dead routes and raw query-only patches such as `ctx.nav.pushPatchUnsafe("?page=2")`.

### Flash

Runtime resources and client payload contracts use explicit typed identifiers. Each companion provides `apply(String)` and each value exposes `.value: String`; there are no implicit string conversions.

```scala
opaque type FlashKind = String
opaque type AsyncKey[A] = String
opaque type SubscriptionKey = String
opaque type ClientEvent[A] = String
opaque type UploadKey = String
```

```scala
trait Flash:
  def put(kind: FlashKind, message: String): LiveIO[Unit]
  def clear(kind: FlashKind): LiveIO[Unit]
  def clearAll: LiveIO[Unit]
  def get(kind: FlashKind): LiveIO[Option[String]]
  def snapshot: LiveIO[Map[FlashKind, String]]
```

### Uploads

```scala
trait Uploads:
  def allow(key: UploadKey, options: LiveUploadOptions): LiveIO[LiveUpload]
  def disallow(key: UploadKey): LiveIO[Unit]
  def get(key: UploadKey): LiveIO[Option[LiveUpload]]
  def cancel(key: UploadKey, entryRef: String): LiveIO[Unit]
  def consumeCompleted(key: UploadKey): LiveIO[List[LiveUploadedEntry]]
  def consume(entryRef: String): LiveIO[Option[LiveUploadedEntry]]
  def drop(entryRef: String): LiveIO[Unit]
```

### Streams

```scala
trait Streams:
  def init[A](definition: LiveStreamDef[A], items: Iterable[A], at: StreamAt = StreamAt.Last, reset: Boolean = false, limit: Option[StreamLimit] = None): LiveIO[LiveStream[A]]
  def insert[A](definition: LiveStreamDef[A], item: A, at: StreamAt = StreamAt.Last, limit: Option[StreamLimit] = None, updateOnly: Boolean = false): LiveIO[LiveStream[A]]
  def delete[A](definition: LiveStreamDef[A], item: A): LiveIO[LiveStream[A]]
  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): LiveIO[LiveStream[A]]
```

### Async And Subscriptions

```scala
trait Async[Msg]:
  def start[A](key: AsyncKey[A])(task: zio.Task[A])(toMsg: LiveAsyncResult[A] => Msg): LiveIO[Unit]
  def cancel[A](key: AsyncKey[A], reason: Option[String] = None): LiveIO[Unit]

trait Subscriptions[Msg]:
  def start(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit]
  def replace(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit]
  def cancel(key: SubscriptionKey): LiveIO[Unit]
```

`start` converts every task outcome into a typed message. Async hooks run before
that message reaches `handleMessage` and may halt delivery. Explicit cancellation
produces `LiveAsyncResult.Cancelled`; socket shutdown, task replacement, and
component removal interrupt obsolete work without producing application messages.

### Client, Title, And Components

```scala
trait Client:
  def push[A: JsonEncoder](event: ClientEvent[A], payload: A): LiveIO[Unit]
  def exec[Msg](js: JSCommands.JSCommand[Msg]): LiveIO[Unit]

trait Title:
  def set(value: String): LiveIO[Unit]

trait ComponentUpdates:
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](id: String, props: LiveComponent.PropsOf[C]): LiveIO[Unit]
```

`ClientEvent[A]` guarantees that Scala push sites use the declared payload type and have a matching JSON encoder. JavaScript still subscribes by string and interprets the encoded payload dynamically.

### Hook Results

```scala
enum LiveHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model)

enum LiveEventHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[zio.json.ast.Json] = None)
```

Constructors:

```scala
LiveHookResult.cont(model)
LiveHookResult.halt(model)
LiveEventHookResult.cont(model)
LiveEventHookResult.halt(model)
LiveEventHookResult.haltReply(model, value)
```

### Static Hooks

```scala
LiveHooks.empty
LiveHooks.empty.rawEvent(id)(hook)
LiveHooks.empty.event(id)(hook)
LiveHooks.empty.params(id)(hook)
LiveHooks.empty.info(id)(hook)
LiveHooks.empty.async(id)(hook)
LiveHooks.empty.afterRender(id)(hook)
```

### Dynamic Hooks

```scala
ctx.hooks.rawEvent.attach(id)(hook)
ctx.hooks.rawEvent.detach(id)
ctx.hooks.event.attach(id)(hook)
ctx.hooks.event.detach(id)
ctx.hooks.params.attach(id)(hook)
ctx.hooks.params.detach(id)
ctx.hooks.info.attach(id)(hook)
ctx.hooks.info.detach(id)
ctx.hooks.async.attach(id)(hook)
ctx.hooks.async.detach(id)
ctx.hooks.afterRender.attach(id)(hook)
ctx.hooks.afterRender.detach(id)
```

## Async API

### `AsyncValue[A]`

`AsyncValue` models field-level async state.

```scala
enum AsyncValue[+A]:
  case Empty
  case Loading(previous: Option[A])
  case Ok(value: A)
  case Failed(previous: Option[A], cause: Throwable)
  case Cancelled(previous: Option[A], reason: Option[String])
```

Constructors and helpers:

```scala
AsyncValue.empty[A]
AsyncValue.loading[A]
AsyncValue.ok(value)
AsyncValue.currentValue(value)
AsyncValue.currentlyLoading(value)
AsyncValue.currentlyOk(value)
AsyncValue.markLoading(current, reset = false)
AsyncValue.applyResult(current, result)
```

Extension methods:

```scala
value.valueOption
value.isLoading
value.isOk
value.loading(reset = false)
value.updated(result)
```

### `LiveAsyncResult[A]`

```scala
enum LiveAsyncResult[+A]:
  case Succeeded(value: A)
  case Failed(cause: Throwable)
  case Cancelled(reason: Option[String])
```

## HTML Rendering API

### `HtmlElement[Msg]`

```scala
class HtmlElement[+Msg](val tag: HtmlTag, val mods: Vector[Mod[Msg]]):
  def static: Seq[String]
  def attrMods: Seq[Mod.Attr[Msg]]
  def contentMods: Seq[Mod.Content[Msg]]
  def prepended[Msg2 >: Msg](mod: Mod[Msg2]*): HtmlElement[Msg2]
  def appended[Msg2 >: Msg](mod: Mod[Msg2]*): HtmlElement[Msg2]
```

### `HtmlTag`

```scala
class HtmlTag(val name: String, val void: Boolean = false):
  def apply[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg]
```

Generated HTML tags are available through `import scalive.*`. Custom tags can be created with:

```scala
htmlTag(name, void = false)
HtmlTag(name, void = false)
```

### `HtmlAttr[V]`

```scala
class HtmlAttr[V](val name: String, val codec: Encoder[V, String]):
  def :=(value: V): Mod.Attr[Nothing]
```

Generated HTML attributes are available through `import scalive.*`. Custom attributes can be created with:

```scala
htmlAttr(name, codec)
dataAttr(name)
```

Namespaced attributes are available under `aria` and `xlink`.

### `HtmlAttrBinding`

`HtmlAttrBinding` is used for event-style attributes such as `phx.onClick`.

```scala
class HtmlAttrBinding(val name: String):
  def toComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model]
  )(message: Msg): Mod.Attr[Nothing]
  def apply[Msg](cmd: JSCommand[Msg]): Mod.Attr[Msg]
  def apply[Msg](msg: Msg): Mod.Attr[Msg]
  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg]
  def form[Msg](f: FormData => Msg): Mod.Attr[Msg]
  def form[A, Msg](codec: FormCodec[A])(f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def withValueOption[Msg](f: Option[String] => Msg): Mod.Attr[Msg]
  def withValue[Msg](f: String => Msg): Mod.Attr[Msg]
  def withBoolValueOption[Msg](f: Option[Boolean] => Msg): Mod.Attr[Msg]
  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg]
```

`withValue` is non-throwing and passes `""` when the client payload has no
`value`. `withBoolValue` is non-throwing and passes `false` for missing or
unrecognized values. Use the `Option` variants when application code must
distinguish missing or invalid values.

`toComponent(component)(message)` routes the binding's typed message to component instances selected
by a separate `phx.target`. The component value determines both the accepted message type and the
runtime component class:

```scala
button(
  phx.onClick.toComponent(CounterComponent)(CounterComponent.Msg.Increment),
  phx.target("#counter")
)
```

Keeping `phx.target` separate preserves Phoenix targeting semantics, including `ComponentRef`, CSS
selectors, and selectors that match multiple component instances. Events rendered inside a component
normally use the component message directly with `phx.target(self)`.

### `Mod[Msg]`

`Mod` is the common type for attributes and content.

```scala
sealed trait Mod[+Msg]
```

Attribute cases:

```scala
Mod.Attr.Static(name, value)
Mod.Attr.StaticValueAsPresence(name, value)
Mod.Attr.Binding(name, f)
Mod.Attr.FormBinding(name, f)
Mod.Attr.FormEventBinding(name, codec, f)
Mod.Attr.JsBinding(name, command)
Mod.Attr.RoutedBinding(name, f)
```

Content cases:

```scala
Mod.Content.Text(text, raw = false)
Mod.Content.Tag(el)
Mod.Content.Component(cid, el)
Mod.Content.LiveComponent(spec)
Mod.Content.LiveView(spec)
Mod.Content.Flash(kind, f)
Mod.Content.Keyed(entries, stream = None, allEntries = None)
```

### Package-level helpers

```scala
rawHtml(html): Mod[Nothing]
liveComponent(component, id: String, props): Mod[Nothing]
liveComponent(component, id: Int, props): Mod[Nothing]
liveView(id, liveView, sticky = false, linkParentOnCrash = false): Mod[Nothing]
flash(kind: FlashKind)(f): Mod[Nothing]
portal(id, target, container = "div", wrapperClass = None)(mods*): HtmlElement[Msg]
```

Implicit conversions:

```scala
String => Mod[Nothing]
HtmlElement[Msg] => Mod[Msg]
```

### Collection rendering extensions

```scala
items.splitBy(key)(project): Mod[Msg]
items.splitByIndex(project): Mod[Msg]
stream.stream(project): Mod[Msg]
```

`splitBy` and `splitByIndex` render keyed comprehensions. `LiveStream.stream` renders stream-backed keyed content.

## Phoenix Binding API

The `phx` object exposes typed attributes and event bindings.

### Event bindings

```scala
phx.onClick
phx.onClickAway
phx.onBlur
phx.onFocus
phx.onWindowBlur
phx.onKeydown
phx.onKeyup
phx.onWindowKeydown
phx.onWindowKeyup
phx.onViewportTop
phx.onViewportBottom
phx.onProgress
```

### Form bindings

```scala
phx.onChange
phx.onSubmit
phx.onChangeForm(f)
phx.onChangeForm(codec)(f)
phx.onSubmitForm(f)
phx.onSubmitForm(codec)(f)
phx.autoRecover
phx.triggerAction
```

### Lifecycle and JS bindings

```scala
phx.onConnected
phx.onDisconnected
phx.onMounted
phx.onRemove
phx.onUpdate
```

### Attributes

```scala
phx.key
phx.dropTarget
phx.disableWith
phx.hook
phx.clearFlash
phx.target(ref)
phx.target(selector)
phx.debounce
phx.throttle
phx.value(key)
phx.trackStatic
```

## Link API

The `link` object renders LiveView-aware anchors. Its default methods require full route-derived locations.

```scala
object link:
  def navigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg]
  def patch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg]
  def patchReplace[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg]

  def navigateUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def patchUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def patchReplaceUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg]
```

Unsafe links are the explicit escape hatch for destinations that cannot be derived from a Live route. Query-only patches remain unsafe and explicit, for example `link.patchUnsafe("?page=2", "Next")`.

## JS Command API

`JS` is the empty JS command builder.

```scala
val JS: JSCommands.JSCommand[Nothing]
```

`JSCommand[Msg]` is an opaque command list with a JSON encoder.

```scala
opaque type JSCommand[+Msg] = List[Op[Msg]]
```

Command builder methods:

```scala
JS.addClass(names, to = "", transition = "", time = 200, blocking = true)
JS.toggleClass(names, to = "", transition = "", time = 200, blocking = true)
JS.removeClass(names, to = "", transition = "", time = 200, blocking = true)
JS.dispatch(event, to = "", detail = Map.empty, bubbles = true, blocking = false)
JS.exec(attr, to = "")
JS.focus(to = "")
JS.focusFirst(to = "")
JS.hide(to = "", transition = "", time = 200, blocking = true)
JS.ignoreAttributes(attrs = Seq.empty, to = "")
JS.popFocus()
JS.push(event, target = "", loading = "", pageLoading = false)
JS.pushFocus(to = "")
JS.removeAttribute(attr, to = "")
JS.setAttribute((name, value), to = "")
JS.show(to = "", transition = "", time = 200, blocking = true, display = "block")
JS.toggle(to = "", in = "", out = "", time = 200, blocking = true, display = "block")
JS.toggleAttribute(name, value, altValue = "", to = "")
JS.transition(transition = "", to = "", time = 200, blocking = true)
```

Navigation command signatures:

```scala
extension [Msg](ops: JSCommand[Msg])
  def navigate(to: LiveLocation, replace: Boolean = false): JSCommand[Msg]
  def navigateUnsafe(href: String, replace: Boolean = false): JSCommand[Msg]
  def patch(to: LiveLocation, replace: Boolean = false): JSCommand[Msg]
  def patchUnsafe(href: String, replace: Boolean = false): JSCommand[Msg]
```

`transition` arguments accept either a space-separated class string or a tuple of three class strings.

## Components API

### Built-in component helpers

```scala
focusWrap(id, mods*)(content*)
liveFileInput(upload, mods*)
uploadErrors(upload)
uploadErrors(upload, entry)
uploadErrors(entry)
```

## Routing API

### `LiveLocation`

`LiveLocation` is an immutable relative URL produced by an encodable route builder. Its constructor is not public, so path and query values always come from the same codecs used for inbound route matching.

```scala
final class LiveLocation private[scalive] (...):
  def href: String
  def withFragment(fragment: String): LiveLocation
  def withFragmentEither(
    fragment: String
  ): Either[LiveLocation.EncodeError, LiveLocation]

object LiveLocation:
  enum EncodeError:
    case Path(details: String)
    case Query(cause: Throwable)
    case Fragment(details: String)

    def message: String

  final class EncodingException(val error: EncodeError)
      extends IllegalArgumentException(error.message)
```

`href` exposes the encoded relative URL for diagnostics and APIs outside Scalive's navigation helpers. `withFragment` and `withFragmentEither` accept already percent-encoded URI-fragment syntax. They validate but do not encode decoded text; the caller must encode spaces, for example by passing `"profile%20details"` instead of `"profile details"`. `withFragment` is the direct fragment API; `withFragmentEither` preserves a checked `EncodeError.Fragment`.

Direct `location`, `withFragment`, and no-argument `Unit` variants use `LiveLocation.EncodingException` only for path, query, fragment, or domain invariant violations reported as `EncodeError`. Use the corresponding `Either` methods for deliberately partial codecs. A `LiveLocation` does not prove that a patch targets the current view or that navigation remains in the same live session.

### `Live`

`Live` is the entry point for route and router construction.

```scala
object Live:
  val router: LiveRouter[Any]
  def route[A](path: PathCodec[A]): LiveRouteSeed[A]
  def session(name: String): LiveSessionSeed
```

The package-level `live` value is equivalent to an empty route seed.

```scala
val live: LiveRouteSeed[Unit]
```

### Route seeds and builders

`LiveRouteSeed[A]` starts a route from a typed `PathCodec[A]`.

```scala
seed / pathCodec
seed.location(value)
seed.locationEither(value)
seed.location                 // when the path value is Unit
seed.locationEither           // when the path value is Unit
seed.withMountAspect(aspect)
seed.withLayout(layout)
seed.withRootLayout(rootLayout)
seed.params
seed.params(codec)
seed.paramsDecodeOnly(decoder)
seed.query[QueryParams]
seed.query[QueryParam]("name")
seed.queryOptional[QueryParam]("name")
seed.query(codec)
seed(view)
seed -> view
seed(viewLayer)
seed -> viewLayer
seed((params, request, context) => view)
seed((params, request) => view)
seed(request => view)
seed((params, request, c1, c2) => view)
```

`LiveRouteBuilder[R, A, Need, Ctx]` is produced after modifiers are applied.

```scala
builder.withMountAspect(aspect)
builder.withLayout(layout)
builder.withRootLayout(rootLayout)
builder.location(value)
builder.locationEither(value)
builder.location                 // when the path value is Unit
builder.locationEither           // when the path value is Unit
builder.params
builder.params(codec)
builder.paramsDecodeOnly(decoder)
builder.query[QueryParams]
builder.query[QueryParam]("name")
builder.queryOptional[QueryParam]("name")
builder.query(codec)
builder(view)
builder -> view
builder(viewLayer)
builder -> viewLayer
builder((params, request, context) => view)
builder((params, request, c1, c2) => view)
```

`params` and `query` produce a `LiveRouteParamsBuilder` whose `apply` methods accept a `LiveView.Routed[Msg, Model, Params]`.

All direct and factory route forms accept `LiveView.Eventless`, `LiveView.Routed.Eventless`, and widened `Nothing`-message values without an explicit tag. Ordinary message-bearing views still require an implicit `ClassTag[Msg]`.

```scala
paramsBuilder.mapParams(decode)(encode)
paramsBuilder.mapParamsDecodeOnly(decode)
paramsBuilder.location(params)
paramsBuilder.locationEither(params)
paramsBuilder.location                 // when Params is Unit
paramsBuilder.locationEither           // when Params is Unit
paramsBuilder.withMountAspect(aspect)
paramsBuilder.withLayout(layout)
paramsBuilder.withRootLayout(rootLayout)
paramsBuilder(view)
paramsBuilder -> view
paramsBuilder(viewLayer)
paramsBuilder -> viewLayer
paramsBuilder((params, request, context) => view)
```

Route seeds and builders also accept an infallible `ZLayer` that constructs the
LiveView. `ZLayer.fromFunction` infers all constructor dependencies and propagates
their intersection as the route environment:

```scala
final class DashboardLiveView(reports: Reports, metrics: Metrics)
    extends LiveView[DashboardLiveView.Msg, DashboardLiveView.Model]

object DashboardLiveView:
  val layer = ZLayer.fromFunction(DashboardLiveView.apply)

val route = (live / "dashboard") -> DashboardLiveView.layer
```

Scalive builds a fresh layer output for disconnected and connected mount. Provide
the shared input service layers once at the application boundary; do not put a
prebuilt LiveView instance in the application environment.

Use `query[A]` for schema-derived query objects and named helpers for single query params:

```scala
(live / "search").query[SearchQuery] -> SearchLiveView()

(live / "stream").queryOptional[String]("empty_item") -> StreamLiveView()
```

For path-plus-query routes, use `query[A]` or a named query helper and bidirectional `mapParams` to map the tuple-shaped codec value into an application type. Keep the builder as a named route reference and construct full locations from it:

```scala
object Routes:
  final case class UserLocation(id: Int, tab: Option[String])

  val user =
    (live / "users" / PathCodec.int("id"))
      .queryOptional[String]("tab")
      .mapParams { case (id, tab) => UserLocation(id, tab) }(
        location => location.id -> location.tab
      )

val settings = Routes.user.location(UserLocation(42, Some("settings")))

link.navigate(settings, "Settings")
ctx.nav.pushNavigate(settings)
JS.patch(settings)
```

`locationEither` returns `Either[LiveLocation.EncodeError, LiveLocation]`. Encodable builders expose both location methods using the final parameter type after `mapParams`. `paramsDecodeOnly` and `mapParamsDecodeOnly` return builders that can still mount a `LiveView.Routed` but do not expose location construction; this is enforced by the builder's `LiveRouteParamsCapability` type.

### Live sessions

```scala
Live.session(name)(route, routes*)
Live.session(name).withMountAspect(aspect)
Live.session(name).withLayout(layout)
Live.session(name).withRootLayout(rootLayout)
```

`LiveSessionBuilder` supports additional named modifier composition and then applies to one or more routes.

### Router

```scala
Live.router.withLayout(layout)
Live.router.withRootLayout(rootLayout)
Live.router.withSocketPath(path)
Live.router.withTokenConfig(config)
Live.router.withSecurity(security)
Live.router(route, routes*)
```

The resulting value is a `zio.http.Routes` value.

Use one `LiveSecurity` value for the Live router and sibling ordinary HTTP handlers that validate protected forms or redirect with flash:

```scala
val security = LiveSecurity(
  TokenConfig.default,
  secureCookies = true
)

val liveRoutes = Live.router.withSecurity(security)(loginRoute)
val httpRoutes = AuthHttpRoutes(security)
```

`withTokenConfig` remains the direct configuration path when no ordinary handler needs to share security capabilities.

Supporting route types:

```scala
trait LiveRouteFragment[-R, -Need]
final class LiveRoute[R, A, -Need, Ctx, Msg, Model] private[scalive] (...)
```

Initial lifecycle outcome:

```scala
enum LiveRoute.InitialLifecycleOutcome[+Model]:
  case Render(model: Model)
  case Redirect(url: zio.http.URL)
```

### Layouts

```scala
final case class LiveLayoutContext[+A, +Ctx](
  params: A,
  request: zio.http.Request,
  currentUrl: zio.http.URL,
  context: Ctx
)
```

```scala
trait LiveLayout[-A, -Ctx]:
  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]): HtmlElement[Msg]
```

Helpers:

```scala
LiveLayout.identity
LiveLayout((content, ctx) => html)
```

```scala
trait LiveRootLayout[-A, -Ctx]:
  def key(ctx: LiveLayoutContext[A, Ctx]): String
  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]): HtmlElement[Msg]
```

Helpers:

```scala
LiveRootLayout.identity
LiveRootLayout(rootKey)((content, ctx) => html)
LiveRootLayout.dynamic(rootKeyFn)((content, ctx) => html)
```

### Mount aspects

```scala
final case class LiveMountRequest[+A](params: A, request: zio.http.Request):
  def url: zio.http.URL
```

```scala
enum LiveMountFailure:
  case Redirect(to: LiveLocation)
  case RedirectUnsafe(to: zio.http.URL)
  case Unauthorized(reason: Option[String])
  case Stale(reason: Option[String])
```

Constructors:

```scala
object LiveMountFailure:
  def redirect(to: LiveLocation): LiveMountFailure
  def redirectUnsafe(to: zio.http.URL): LiveMountFailure
  def unauthorized: LiveMountFailure
  def unauthorized(reason: String): LiveMountFailure
  def stale: LiveMountFailure
  def stale(reason: String): LiveMountFailure
```

```scala
final case class LiveMountAspect[R, A, -In, Claims, Ctx] private[scalive] (...):
  def map[Ctx2](f: Ctx => Ctx2): LiveMountAspect[R, A, In, Claims, Ctx2]
  def ++[R1, Claims2, Ctx2, Result](that): LiveMountAspect[R & R1, A, In, (Claims, Claims2), Result]
```

Constructors:

```scala
LiveMountAspect.make(disconnected, connected)
LiveMountAspect.fromRequest(disconnected, connected)
```

Context composition support:

```scala
trait ContextAppend[In, Out]
object ContextAppend
```

## Route Params Codec API

```scala
trait LiveParamsDecoder[PathParams, Params]:
  def decode(pathParams: PathParams, url: zio.http.URL): IO[LiveParamsCodec.DecodeError, Params]
  def mapDecodeOnly[Params2](
    decodeParams: Params => Params2
  ): LiveParamsDecoder[PathParams, Params2]

trait LiveParamsCodec[PathParams, Params]
    extends LiveParamsDecoder[PathParams, Params]:
  def encode(
    params: Params
  ): Either[LiveLocation.EncodeError, LiveParamsCodec.Encoded[PathParams]]
  def imap[Params2](decodeParams: Params => Params2)(
    encodeParams: Params2 => Params
  ): LiveParamsCodec[PathParams, Params2]
```

Encoded path and query values:

```scala
object LiveParamsCodec:
  final case class Encoded[PathParams](
    pathParams: PathParams,
    queryParams: zio.http.QueryParams
  )
```

Errors:

```scala
LiveParamsCodec.DecodeError(message, cause = None)
```

Constructors:

```scala
LiveParamsDecoder.custom(decodeFn)
LiveParamsCodec.path[A]
LiveParamsCodec.none
LiveParamsCodec.query[A]
LiveParamsCodec.fromZioHttp(codec)
LiveParamsCodec.fromQuery(codec)
LiveParamsCodec.custom(decodeFn, encodeFn)
```

`LiveParamsDecoder` is the explicit inbound-only contract used by `paramsDecodeOnly`. `LiveParamsCodec` adds outbound encoding. Standard path and query route builders supply a bidirectional codec automatically; custom irreversible decoding must stay decode-only rather than failing later with an unavailable encoder.

## Forms API

### `FormData`

```scala
final case class FormData private (raw: Vector[(String, String)]):
  def fields: Map[String, FormField]
  def get(name): Option[String]
  def get(path): Option[String]
  def string(name): Option[String]
  def string(path): Option[String]
  def values(name): Vector[String]
  def values(path): Vector[String]
  def getOrElse(name, fallback): String
  def contains(name): Boolean
  def contains(path): Boolean
  def asMap: Map[String, String]
  def nested(name): FormData
```

Constructors:

```scala
FormData.empty
FormData(raw)
FormData.fromMap(values)
FormData.fromUrlEncoded(value): Either[FormData.DecodeError, FormData]
FormData.fromUrlEncodedBody(
  body: zio.http.Body,
  maxBytes: Long
): IO[FormData.DecodeError, FormData]
FormData.fromZioHttpForm(
  form: zio.http.Form
): Either[FormData.DecodeError, FormData]
```

HTTP decoding errors are transport and representation failures, not domain validation errors:

```scala
enum FormData.UnsupportedFieldKind:
  case Binary
  case StreamingBinary

enum FormData.DecodeError:
  case InvalidContentType(actual: Option[zio.http.MediaType])
  case BodyTooLarge(maxBytes: Long)
  case BodyRead(cause: Throwable)
  case InvalidUrlEncoding(details: String)
  case UnsupportedField(name: String, kind: FormData.UnsupportedFieldKind)
```

`fromUrlEncoded` preserves source order, repeated names, empty values, and nested bracket names. `fromUrlEncodedBody` additionally requires an `application/x-www-form-urlencoded` body and reads at most `maxBytes + 1` bytes, so streaming bodies cannot bypass the configured limit. It defaults to UTF-8 unless the body declares a charset.

`fromZioHttpForm` preserves the order of `Simple` and `Text` fields already present in a ZIO HTTP form. It rejects the whole conversion when it encounters `Binary` or `StreamingBinary`; it never drops, materializes, or coerces those fields. For URL-encoded request bodies, prefer `fromUrlEncodedBody` because ZIO HTTP 3.10.1 collapses repeated query-style values while constructing `zio.http.Form`.

After transport decoding, pass the resulting `FormData` to `FormCodec.decode`. Keeping these operations separate preserves the distinction between malformed HTTP input and application validation errors.

### `FormField`

```scala
final case class FormField(values: Vector[String]):
  def value: String
```

### `FormCodec[A]`

```scala
trait FormCodec[A]:
  def decode(data: FormData): Either[FormErrors, A]
  def map[B](f: A => B): FormCodec[B]
  def emap[B](f: A => Either[FormErrors, B]): FormCodec[B]
```

Constructors:

```scala
FormCodec(f)
FormCodec.formData
FormCodec.requiredString(name, message = "can't be blank")
FormCodec.requiredString(path)
FormCodec.requiredString(path, message)
FormCodec.optionalString(name)
FormCodec.optionalString(path)
```

### `FormEvent[A]`

```scala
final case class FormEvent[+A](
  raw: FormData,
  value: Either[FormErrors, A],
  target: Option[FormPath] = None,
  submitter: Option[FormSubmitter] = None,
  recovery: Boolean = false,
  submitted: Boolean = false,
  metadata: Map[String, String] = Map.empty,
  componentId: Option[Int] = None,
  uploads: Option[zio.json.ast.Json] = None
):
  def state: FormState[A]
  def data: FormData
  def isValid: Boolean
  def errors: FormErrors
  def valueOption: Option[A]
```

### `FormState[A]`

```scala
final case class FormState[+A](
  raw: FormData,
  value: Either[FormErrors, A],
  used: Set[FormPath],
  submitted: Boolean
):
  def isValid: Boolean
  def errors: FormErrors
  def valueOption: Option[A]
  def isUsed(path): Boolean
  def isUsed(name): Boolean
  def errorsFor(path): Vector[FormError]
  def errorsFor(name): Vector[FormError]
```

Constructor:

```scala
FormState(raw, value, submitted)
```

### `Form[A]`

```scala
final case class Form[A](root: FormPath, state: FormState[A], codec: FormCodec[A]):
  def http(action: FormAction)(mods*): HtmlElement[Msg]
  def onChange(f): Mod.Attr[Msg]
  def onSubmit(f): Mod.Attr[Msg]
  def field(path): Form.Field
  def name(path): String
  def id(path): String
  def value(path): String
  def text(path, mods*): HtmlElement[Nothing]
  def text(path, explicitId, mods*): HtmlElement[Nothing]
  def email(path, mods*): HtmlElement[Nothing]
  def password(path, mods*): HtmlElement[Nothing]
  def hidden(path, mods*): HtmlElement[Nothing]
  def checkbox(path, mods*): HtmlElement[Nothing]
  def checkbox(path, checkedValue, mods*): HtmlElement[Nothing]
  def textarea(path, mods*): HtmlElement[Nothing]
  def select(path, options, mods*): HtmlElement[Nothing]
  def errors(path): HtmlElement[Nothing]
  def feedback(path, mods*): HtmlElement[Nothing]
  def errorsFor(path): Vector[FormError]
  def isUsed(path): Boolean
```

Constructors:

```scala
Form.of(name, state, codec)
Form.of(name, event, codec)
Form.http(action)(mods*)
```

`http` renders a normal browser form whose `action` and `method` come from a `FormAction`. The companion form supports action-only forms; the instance method combines the same wrapper with typed field helpers. Neither form adds `phx-change`, `phx-submit`, `phx-trigger-action`, or HTTP body decoding. Callers opt into Live bindings explicitly with `onChange`, `onSubmit`, and `phx.triggerAction`.

Finalized Live renders automatically add `_csrf_token` to checked POST actions. GET and `FormAction.unsafe` targets do not receive a token. The helper rejects caller-supplied `action` or `method` attributes; use `FormAction.unsafe` and the raw `form` tag when those attributes or CSRF behavior must be controlled manually.

### `FormAction`

```scala
final class FormAction:
  def method: FormAction.Method
  def href: String

object FormAction:
  enum Method:
    case Get
    case Post

    def attributeValue: String

  enum EncodeError:
    case UnsupportedMethod(method: zio.http.Method)
    case Path(details: String)

  final class EncodingException(error: EncodeError)

  def from[A](pattern: zio.http.RoutePattern[A], params: A): FormAction
  def from(pattern: zio.http.RoutePattern[Unit]): FormAction
  def fromEither[A](pattern, params): Either[EncodeError, FormAction]
  def fromEither(pattern): Either[EncodeError, FormAction]
  def unsafe(method: FormAction.Method, href: String): FormAction
```

`FormAction` reuses the same ZIO HTTP `RoutePattern` used for request dispatch, encodes its path as a root-relative URL, and accepts only the GET and POST methods browsers can submit faithfully. Direct construction throws `EncodingException` for route-definition invariant failures; `fromEither` preserves them explicitly. `unsafe` is the escape hatch for external URLs, fixed query strings, and unusual integrations.

```scala
val createSession = zio.http.Method.POST / "auth" / "session"

val routes = zio.http.Routes(
  createSession -> zio.http.handler(login)
)

val loginForm = Form.of("login", state, LoginForm.codec)

loginForm.http(FormAction.from(createSession))(
  loginForm.email("email"),
  loginForm.password("password"),
  button(typ := "submit", "Sign in")
)
```

### `LiveSecurity`, `CsrfProtection`, and `HttpFlash`

```scala
final class LiveSecurity:
  val csrf: CsrfProtection
  val flash: HttpFlash

object LiveSecurity:
  def apply(
    tokenConfig: TokenConfig,
    secureCookies: Boolean = false
  ): LiveSecurity
```

`LiveSecurity` keeps the router, ordinary-form CSRF, and HTTP flash transport on one signing and cookie policy. Pass the same value to `LiveRouter.withSecurity` and sibling ordinary HTTP handlers.

```scala
final class CsrfProtection:
  def validate(
    request: zio.http.Request,
    data: FormData
  ): Either[CsrfProtection.ValidationError, Unit]

object CsrfProtection:
  val CookieName: String
  val ParamName: String
  val MetaName: String

  enum ValidationError:
    case MissingCookie
    case InvalidCookie
    case MissingToken
    case DuplicateToken
    case InvalidToken
```

Decode the request body with an explicit bound, validate CSRF, and only then run application decoding:

```scala
for
  data <- FormData.fromUrlEncodedBody(request.body, maxBytes)
  _    <- ZIO.fromEither(security.csrf.validate(request, data))
  form <- ZIO.fromEither(LoginForm.codec.decode(data))
yield form
```

The capability uses two purpose-bound signed values containing the same random browser secret: an `HttpOnly` cookie and the submitted token. Validation requires exactly one bounded `_csrf_token` and compares secrets in constant time. Tokens are reusable until `TokenConfig.maxAge`; they are not one-time application tokens.

The cookie is host-only, scoped to `/`, `HttpOnly`, and `SameSite=Lax`. `secureCookies` must be enabled whenever the browser-facing endpoint is HTTPS; Scalive does not infer deployment TLS from forwarding headers. The token check binds a form to the browser cookie but does not add a separate `Origin` or `Referer` policy.

```scala
final class HttpFlash:
  def seeOther(
    to: LiveLocation,
    values: (FlashKind, String)*
  ): zio.http.Response

  def seeOtherUnsafe(
    to: zio.http.URL,
    values: (FlashKind, String)*
  ): zio.http.Response
```

`seeOther` returns a 303 response with purpose-bound signed flash values in a short-lived, root-scoped, `HttpOnly`, `SameSite=Lax` cookie. Use `seeOtherUnsafe` only for validated local URLs that do not have a typed Live route. The next successfully rendered Live route embeds valid values in its Live session and expires the browser cookie; redirect chains preserve it until then. This is browser-level consume-once behavior, not server-side replay prevention, and flash values are signed rather than encrypted.

### `Form.Field`

```scala
final case class Form.Field(form: Form[?], path: FormPath):
  def name: String
  def id: String
  def fieldValue: String
  def text(mods*): HtmlElement[Nothing]
  def text(explicitId, mods*): HtmlElement[Nothing]
  def email(mods*): HtmlElement[Nothing]
  def password(mods*): HtmlElement[Nothing]
  def hidden(mods*): HtmlElement[Nothing]
  def checkbox(mods*): HtmlElement[Nothing]
  def checkbox(checkedValue, mods*): HtmlElement[Nothing]
  def textarea(mods*): HtmlElement[Nothing]
  def select(options, mods*): HtmlElement[Nothing]
  def errors: HtmlElement[Nothing]
  def feedback(mods*): HtmlElement[Nothing]
```

### `FormPath`

```scala
final case class FormPath(segments: Vector[String]):
  def /(segment: String): FormPath
  def array: FormPath
  def isEmpty: Boolean
  def nonEmpty: Boolean
  def name: String
  override def toString: String
```

Constructors:

```scala
FormPath.empty
FormPath(first, rest*)
FormPath.parse(name)
```

### Form errors

```scala
final case class FormError(path: FormPath, message: String, code: Option[String] = None)
```

Constructors:

```scala
FormError(name, message)
FormError(name, message, code)
```

```scala
final case class FormErrors private (all: Vector[FormError]):
  def isEmpty: Boolean
  def nonEmpty: Boolean
  def +(error): FormErrors
  def ++(other): FormErrors
  def forPath(path): Vector[FormError]
  def forName(name): Vector[FormError]
  def messages(path): Vector[String]
  def messages(name): Vector[String]
```

Constructors:

```scala
FormErrors.empty
FormErrors(errors)
FormErrors.one(path, message, code = None)
FormErrors.one(name, message)
FormErrors.one(name, message, code)
```

```scala
final case class FormSubmitter(name: String, value: String)
```

## Streams API

### Stream placement

```scala
enum StreamAt:
  case First
  case Last
  case Index(value: Int)
```

```scala
enum StreamLimit:
  case KeepFirst(count: Int)
  case KeepLast(count: Int)
```

### Stream definitions and values

```scala
final case class LiveStreamDef[A](name: String, domId: A => String)
```

Constructor:

```scala
LiveStreamDef.byId(name)(id)
```

`LiveStream[A]` is an opaque rendering handle returned by the `Streams` facade. Render it with the `.stream` extension:

```scala
items.stream { (domId, item) =>
  li(idAttr := domId, item.toString)
}
```

`LiveStream` does not expose its entries or pending commands. Keep queryable, durable items in the application model rather than treating stream runtime state as business state.

Stream APIs are exported from `scalive.streams.api` into `scalive.*`.

## Upload API

Upload APIs are exported from `scalive.upload.api` into `scalive.*`.

### Uploaded entries

```scala
final case class LiveUploadedEntry(
  ref: String,
  name: String,
  contentType: String,
  bytes: Chunk[Byte],
  meta: zio.json.ast.Json.Obj = Json.Obj.empty
)
```

### Upload accept values

```scala
enum LiveUploadAccept:
  case Any
  case Exactly(values: List[String])
  def toHtmlValue: String
```

### Upload errors

```scala
enum LiveUploadError:
  case TooManyFiles
  case TooLarge
  case NotAccepted
  case ExternalClientFailure
  case WriterFailure(reason: String)
  case External(meta: zio.json.ast.Json.Obj)
  case Unknown(code: String)
```

Helpers:

```scala
LiveUploadError.fromReason(reason)
LiveUploadError.fromJson(value)
LiveUploadError.toJson(error)
```

### Upload state

```scala
final case class LiveUploadEntry(
  ref: String,
  clientName: String,
  clientRelativePath: Option[String],
  clientSize: Long,
  clientType: String,
  clientLastModified: Option[Long],
  progress: Int,
  preflighted: Boolean,
  done: Boolean,
  cancelled: Boolean,
  valid: Boolean,
  errors: List[LiveUploadError],
  meta: Option[zio.json.ast.Json.Obj]
)
```

```scala
final case class LiveUpload(
  name: UploadKey,
  ref: String,
  accept: LiveUploadAccept,
  maxEntries: Int,
  maxFileSize: Long,
  chunkSize: Int,
  chunkTimeout: Int,
  autoUpload: Boolean,
  external: Boolean,
  entries: List[LiveUploadEntry],
  errors: List[LiveUploadError]
)
```

### External uploads and writers

```scala
final case class LiveExternalUploadEntry(
  ref: String,
  name: String,
  relativePath: Option[String],
  size: Long,
  contentType: String,
  lastModified: Option[Long],
  clientMeta: Option[zio.json.ast.Json]
)
```

```scala
enum LiveExternalUploadResult:
  case Ok(meta: zio.json.ast.Json.Obj)
  case Error(meta: zio.json.ast.Json.Obj)
```

```scala
trait LiveUploadExternalUploader:
  def preflight(entry: LiveExternalUploadEntry): LiveIO[LiveExternalUploadResult]
```

```scala
enum LiveUploadWriterCloseReason:
  case Done
  case Cancel
  case Error(reason: String)
```

```scala
final case class LiveUploadWriterState(value: Any):
  def valueAs[A: ClassTag]: Option[A]
```

Custom upload writers can store their own state value in `LiveUploadWriterState`.
Use `valueAs[A]` to recover the expected state type in `meta`, `writeChunk`, and
`close`.

```scala
trait LiveUploadWriter:
  def init(uploadKey: UploadKey, entry: LiveExternalUploadEntry): Task[LiveUploadWriterState]
  def meta(state: LiveUploadWriterState): zio.json.ast.Json.Obj
  def writeChunk(data: Chunk[Byte], state: LiveUploadWriterState): Task[LiveUploadWriterState]
  def close(state: LiveUploadWriterState, reason: LiveUploadWriterCloseReason): Task[LiveUploadWriterState]
```

Built-in writer:

```scala
LiveUploadWriter.InMemory
```

### Upload progress and options

```scala
trait LiveUploadProgress:
  def onProgress(uploadKey: UploadKey, entry: LiveUploadEntry): LiveIO[Unit]
```

```scala
final case class LiveUploadOptions(
  accept: LiveUploadAccept,
  maxEntries: Int = 1,
  maxFileSize: Long = 8000000L,
  chunkSize: Int = 64000,
  chunkTimeout: Int = 10000,
  autoUpload: Boolean = false,
  external: Option[LiveUploadExternalUploader] = None,
  progress: Option[LiveUploadProgress] = None,
  writer: LiveUploadWriter = LiveUploadWriter.InMemory
)
```

## Lifecycle Hooks API

```scala
final case class LiveEvent(
  kind: String,
  bindingId: String,
  value: zio.json.ast.Json,
  params: Map[String, String],
  cid: Option[Int],
  meta: Option[zio.json.ast.Json]
)
```

```scala
final case class LiveAsyncEvent[+Msg](
  name: AsyncKey[Any],
  result: LiveAsyncResult[Msg]
)
```

```scala
enum LiveHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model)
```

Constructors:

```scala
LiveHookResult.cont(model)
LiveHookResult.halt(model)
```

```scala
enum LiveEventHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[zio.json.ast.Json] = None)
```

Constructors:

```scala
LiveEventHookResult.cont(model)
LiveEventHookResult.halt(model)
LiveEventHookResult.haltReply(model, value)
```

## Static Assets API

```scala
final case class StaticAssetConfig(
  source: StaticAssetSource,
  mountPath: zio.http.Path = Path.empty / "static",
  serveOriginals: Boolean = true,
  cache: StaticAssetCache = StaticAssetCache.default)
```

```scala
object StaticAssetConfig:
  def classpath(
    resourcePrefix: String,
    assets: Iterable[String],
    mountPath: zio.http.Path = Path.empty / "static",
    serveOriginals: Boolean = true,
    classLoader: ClassLoader = Thread.currentThread().getContextClassLoader
  ): StaticAssetConfig

  def directory(
    root: java.nio.file.Path,
    mountPath: zio.http.Path = Path.empty / "static",
    serveOriginals: Boolean = true,
    assets: Option[Iterable[String]] = None
  ): StaticAssetConfig
```

```scala
final class StaticAssets:
  def path(rel: String): String
  def pathOption(rel: String): Option[String]
  def entry(rel: String): StaticAssetEntry
  def stylesheet[Msg](rel: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def trackedStylesheet[Msg](rel: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def script[Msg](rel: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def trackedScript[Msg](rel: String, mods: Mod[Msg]*): HtmlElement[Msg]
  val routes: zio.http.Routes[Any, Nothing]
```

```scala
object StaticAssets:
  def load(config: StaticAssetConfig): Task[StaticAssets]
```

```scala
final case class StaticAssetEntry(
  originalPath: String,
  digestedPath: String,
  digest: String,
  size: Long,
  mediaType: zio.http.MediaType)

final case class StaticAssetCache(
  digested: zio.http.Header.CacheControl,
  original: zio.http.Header.CacheControl)
```

## Token API

```scala
final case class TokenConfig(secret: String, maxAge: scala.concurrent.duration.Duration)
```

Default configuration:

```scala
TokenConfig.default
```

`TokenConfig.default` reads `SCALIVE_TOKEN_SECRET` and `SCALIVE_TOKEN_MAX_AGE_SECONDS` when present.

## Attribute Encoding API

```scala
package scalive.codecs

class Encoder[ScalaType, DomType](val encode: ScalaType => DomType)
def AsIsEncoder[V](): Encoder[V, V]
val StringAsIsEncoder: Encoder[String, String]
val IntAsIsEncoder: Encoder[Int, Int]
val IntAsStringEncoder: Encoder[Int, String]
val DoubleAsIsEncoder: Encoder[Double, Double]
val DoubleAsStringEncoder: Encoder[Double, String]
val BooleanAsStringEncoder: Encoder[Boolean, String]
val BooleanAsIsEncoder: Encoder[Boolean, Boolean]
val BooleanAsAttrPresenceEncoder: Encoder[Boolean, String]
val BooleanAsTrueFalseStringEncoder: Encoder[Boolean, String]
val BooleanAsYesNoStringEncoder: Encoder[Boolean, String]
val BooleanAsOnOffStringEncoder: Encoder[Boolean, String]
```

## Generated DOM API

At compile time, Scalive generates HTML tags and attributes from Scala DOM Types.

Generated definitions include:

- `HtmlTags`, mixed into `scalive.*`, exposing HTML tag values such as `div`, `span`, `form`, `input`, `button`, and helpers such as `htmlTag`.
- `HtmlAttrs`, mixed into `scalive.*`, exposing HTML attribute values such as `idAttr`, `cls`, `href`, `nameAttr`, `value`, `checked`, `selected`, `typ`, `styleAttr`, and helpers such as `htmlAttr` and `dataAttr`.
- `NamespacedHtmlKeys`, mixed into `scalive.*`, exposing `aria.*` and `xlink.*` namespaced attributes.

The generated API is produced by `DomDefsGenerator.mill` from `com.raquo::domtypes`.
