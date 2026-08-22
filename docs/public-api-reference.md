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

### `ConnectedRender` and `ConnectedView`

`ConnectedRender` performs the disconnected bootstrap and then joins through the production admission and connection lifecycle without starting a network server:

```scala
object ConnectedRender:
  def join[Msg, Model](
    liveView: LiveView[Msg, Model]
  ): zio.RIO[zio.Scope, ConnectedView[Msg]]

  def join[R](
    application: LiveApplication[R],
    config: ZioHttpConfig,
    request: zio.http.Request,
    connectParams: Map[String, zio.json.ast.Json] = Map.empty
  ): zio.ZIO[R & zio.Scope, Throwable, ConnectedView[Nothing]]

final class ConnectedView[-Msg]:
  val topic: String
  def html: zio.UIO[String]
  def text(selector: String): zio.Task[String]
  def click(selector: String): zio.Task[Unit]
  def clickButton(label: String): zio.Task[Unit]
  def changeForm(selector: String, fields: Vector[(String, String)], target: Option[String] = None): zio.Task[Unit]
  def submitForm(selector: String, fields: Vector[(String, String)]): zio.Task[Unit]
  def send(message: Msg): zio.Task[Unit]
  def awaitDiff: zio.Task[Unit]
  def joinNested(instanceId: String): zio.RIO[zio.Scope, ConnectedView[Nothing]]
  def upload(uploadRef: String, entryRef: String, fileName: String, mediaType: String, bytes: zio.Chunk[Byte]): zio.Task[Unit]
  def isJoined: zio.UIO[Boolean]
  def leave: zio.UIO[Unit]
```

The direct LiveView overload uses a validated test configuration and retains the view's message type for `send`. The application overload accepts the transport-neutral `LiveApplication`, explicit validated transport configuration, request, and untrusted connect params; heterogeneous route assembly therefore returns `ConnectedView[Nothing]`. A `Scope` owns the connected session.

Actions resolve exactly one binding from the latest committed semantic HTML and wait for its correlated lifecycle reply. `awaitDiff` instead waits for uncorrelated async, subscription, or component output. Nested joins resolve a registered nested LiveView by instance ID. Upload support is for hosted uploads. `topic` is diagnostic text, not a runtime handle.

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
  def pageTitle(model: Model): Option[String] = None

  def mount(ctx: MountContext): Task[Model]
  def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model]
  def view(model: Signal[Model]): HtmlElement[Msg]
```

Lifecycle methods:

- `mount` creates the initial model for disconnected and connected lifecycle phases.
- `handleMessage` handles typed messages produced by HTML bindings, JS push commands, async tasks, and subscriptions.
- `view` constructs one signal-backed view graph of HTML for each disconnected request or connected socket. Its read-only model signal drives dynamic scalar slots and explicit staged structures without rebuilding the ordinary tree after every update.
- `pageTitle` derives optional document-title state from the model. Root layouts render it during the disconnected request and connected diffs update `document.title`.
- `hooks` installs static lifecycle hooks, including typed browser events and low-level raw event interception.
- Connected-only work is obtained explicitly from `ctx.connection` where a callback can run in either phase, or directly from message contexts which are always connected.

### `LiveView.Eventless[Model]`

Use `LiveView.Eventless` when a view has no server messages. It fixes the message type to `Nothing` and supplies the no-op `handleMessage`, so application code only needs to define `mount` and `view`. The `Nothing` message type also prevents server event bindings from appearing in the rendered HTML.

```scala
trait LiveView.Eventless[Model] extends LiveView[Nothing, Model]
```

Routes, route factories, and nested `liveView` content accept eventless views directly, including values widened to `LiveView[Nothing, Model]`.

### `LiveView.Routed[Msg, Model, Params]`

`LiveView.Routed` is a `LiveView` whose route declares typed URL params. Plain `LiveView`s do not run the params lifecycle.

```scala
trait LiveView.Routed[Msg, Model, Params] extends LiveView[Msg, Model]:
  type ParamsContext = scalive.ParamsContext[Msg, Model]

  def mount(params: Params, ctx: MountContext): Task[Model]

  def handleParams(
    model: Model,
    params: Params,
    url: zio.http.URL,
    ctx: ParamsContext
  ): Task[Model]

  def handleParamsDecodeError(
    model: Model,
    error: LiveParamsCodec.DecodeError,
    url: zio.http.URL,
    ctx: ParamsContext
  ): Task[Model]
```

Params lifecycle methods:

- `mount` receives successfully decoded route parameters and constructs the initial model directly from them.
- `handleParams` runs after mount and whenever a live patch changes the current URL.
- Initial decode failures fail before mount because no model exists yet. Use a permissive raw parameter schema with `mapParams` when malformed external query values should normalize to valid domain values.
- `handleParamsDecodeError` runs for subsequent parameter changes that cannot decode, when an existing model is available.
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

  def mount(props: Props, ctx: MountContext): Task[Model]
  def update(props: Props, model: Model, ctx: UpdateContext): Task[Model]
  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => Task[Model]
  def view(
    props: Signal[Props],
    model: Signal[Model],
    self: ComponentRef[Msg]
  ): HtmlElement[Msg]

final case class LiveComponentInstance[Props, Msg, Model](
  component: LiveComponent[Props, Msg, Model],
  id: String
):
  def render(props: Props): Mod[Nothing]
  def render(props: Signal[Props]): Mod[Nothing]
```

Create one stable instance handle when a parent needs to render, target, or update a specific
component instance:

```scala
val counter = component(CounterComponent, "counter")

counter.render(CounterComponent.Props(...))
on.click.to(counter)(CounterComponent.Msg.Increment)
ctx.components.sendUpdate(counter, CounterComponent.Props(...))
```

The handle keeps the component type, logical ID, props, and message type aligned. Instance-targeted
events resolve the mounted component by logical identity and do not require a DOM ID, CSS selector,
or client-provided component ID.

`ComponentRef[Msg]` is an opaque semantic target supplied to `LiveComponent.view`. It carries no public CID, selector, string value, or constructor; use it only with APIs such as `on.click.to(self)(message)` and `phx.target(self)`. Its type keeps the accepted component message aligned while the runtime resolves the current component identity.

### `LiveComponent.Eventless[Props, Model]`

Use `LiveComponent.Eventless` when a component receives props and owns state but has no component messages. It fixes the message type and `ComponentRef` type to `Nothing` and supplies the no-op `handleMessage`.

```scala
trait LiveComponent.Eventless[Props, Model]
    extends LiveComponent[Props, Nothing, Model]
```

### `Task[A]`

Lifecycle callbacks and context facades use `zio.Task[A]`. Return effects explicitly:

```scala
def mount(ctx: MountContext): Task[Model] =
  ZIO.succeed(Model.empty)
```

## Phase Context API

Lifecycle callbacks receive explicit phase contexts. Contexts expose domain facades directly and do not require application code to provide or request ZIO environment services.

### Context Availability

```scala
trait LifecycleContext[+Connected]:
  def connection: Connection[Connected]

enum Connection[+Connected]:
  case Disconnected
  case Connected(capabilities: Connected)

trait ConnectedMetadata:
  def staticChanged: Boolean
  def connectParams: Map[String, zio.json.ast.Json]

trait RootMountConnected[Msg] extends ConnectedMetadata:
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client

trait RootParamsConnected[Msg] extends RootMountConnected[Msg]:
  def components: ComponentUpdates

trait RootAfterRenderConnected extends ConnectedMetadata:
  def client: Client

trait ComponentConnected[Msg] extends ConnectedMetadata:
  def async: Async[Msg]
  def client: Client

trait MountContext[Msg, Model] extends LifecycleContext[RootMountConnected[Msg]]:
  def nav: MountNavigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: RootHooks[Msg, Model]

trait MessageContext[Msg, Model] extends ConnectedMetadata:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def components: ComponentUpdates
  def hooks: RootHooks[Msg, Model]

trait ParamsContext[Msg, Model] extends LifecycleContext[RootParamsConnected[Msg]]:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: RootHooks[Msg, Model]

trait AfterRenderContext[Msg, Model] extends LifecycleContext[RootAfterRenderConnected]:
  def hooks: RootHooks[Msg, Model]
```

```scala
trait ComponentMountContext[Props, Msg, Model]
    extends LifecycleContext[ComponentConnected[Msg]]:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentUpdateContext[Props, Msg, Model]
    extends LifecycleContext[ComponentConnected[Msg]]:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentMessageContext[Props, Msg, Model] extends ConnectedMetadata:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def client: Client
  def components: ComponentUpdates
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentAfterRenderContext[Props, Msg, Model]
    extends LifecycleContext[ConnectedMetadata]:
  def hooks: ComponentHooks[Props, Msg, Model]
```

`Connection` makes phase-dependent capabilities explicit: match `Disconnected` or `Connected(capabilities)` before starting async work, subscriptions, client commands, or component updates. `staticChanged` and `connectParams` exist only in connected metadata; connect params are untrusted. Message contexts are produced only for connected delivery, so they expose connected metadata and capabilities directly rather than wrapping them in another `Connection`.

### Navigation

```scala
trait MountNavigation:
  def pushNavigate(to: LiveLocation): Task[Unit]
  def pushNavigateUnsafe(to: String): Task[Unit]

  def replaceNavigate(to: LiveLocation): Task[Unit]
  def replaceNavigateUnsafe(to: String): Task[Unit]

  def redirect(to: LiveLocation): Task[Unit]
  def redirectUnsafe(to: String): Task[Unit]

trait Navigation extends MountNavigation:
  def pushPatch(to: LiveLocation): Task[Unit]
  def pushPatchUnsafe(to: String): Task[Unit]

  def replacePatch(to: LiveLocation): Task[Unit]
  def replacePatchUnsafe(to: String): Task[Unit]
```

The methods without an `Unsafe` suffix require a full location derived from a Live route declaration. Use the explicit unsafe methods for external or dead routes and raw query-only patches such as `ctx.nav.pushPatchUnsafe("?page=2")`.

### Flash

Runtime resources and client payload contracts use explicit typed identifiers. Each companion provides `apply(String)` and each value exposes `.value: String`; there are no implicit string conversions.

```scala
opaque type FlashKind = String
opaque type AsyncKey[A] = String
opaque type SubscriptionKey = String
opaque type ServerToBrowserEvent[A] = String
opaque type BrowserToServerEvent[A] = String
```

```scala
trait Flash:
  def put(kind: FlashKind, message: String): Task[Unit]
  def clear(kind: FlashKind): Task[Unit]
  def clearAll: Task[Unit]
  def get(kind: FlashKind): Task[Option[String]]
  def snapshot: Task[Map[FlashKind, String]]
```

### Uploads

```scala
trait Uploads:
  def allow[R](definition: LiveUploadDef[R]): Task[LiveUpload[R]]
  def disallow[R](definition: LiveUploadDef[R]): Task[Unit]
  def get[R](definition: LiveUploadDef[R]): Task[Option[LiveUpload[R]]]
  def cancel[R](entry: LiveUploadEntry[R]): Task[LiveUpload[R]]
  def consume[R, A](entry: LiveUploadEntry[R])(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(A, LiveUpload[R])]
  def consumeCompleted[R, A](definition: LiveUploadDef[R])(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(List[A], LiveUpload[R])]
```

### Streams

```scala
trait Streams:
  def create[A](definition: LiveStreamDef[A], items: Iterable[A]): Task[LiveStream[A]]
  def insertAll[A](definition: LiveStreamDef[A], items: Iterable[A], at: StreamAt = StreamAt.Last): Task[LiveStream[A]]
  def reset[A](definition: LiveStreamDef[A], items: Iterable[A], at: StreamAt = StreamAt.Last): Task[LiveStream[A]]
  def insert[A](definition: LiveStreamDef[A], item: A, at: StreamAt = StreamAt.Last, updateOnly: Boolean = false): Task[LiveStream[A]]
  def delete[A](definition: LiveStreamDef[A], item: A): Task[LiveStream[A]]
  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): Task[LiveStream[A]]
```

`create` requires a new stream name. The remaining mutation operations require an existing stream.
`create` preserves item iteration order and does not accept placement. `insertAll` and `reset` match
Phoenix semantics by inserting each item at the requested position; batches at a non-terminal index
therefore appear in reverse iteration order.

### Async And Subscriptions

```scala
trait Async[Msg]:
  def start[A](key: AsyncKey[A])(task: zio.Task[A])(toMsg: LiveAsyncResult[A] => Msg): Task[Unit]
  def cancel[A](key: AsyncKey[A], reason: Option[String] = None): Task[Unit]

trait Subscriptions[Msg]:
  def start(key: SubscriptionKey, delivery: SubscriptionDelivery)(stream: zio.stream.ZStream[Any, Nothing, Msg]): Task[Unit]
  def replace(key: SubscriptionKey, delivery: SubscriptionDelivery)(stream: zio.stream.ZStream[Any, Nothing, Msg]): Task[Unit]
  def cancel(key: SubscriptionKey): Task[Unit]

enum SubscriptionDelivery:
  case Lossless
  case Latest
```

`start` converts every task outcome into a typed message. Async hooks run before
that message reaches `handleMessage` and may halt delivery. Explicit cancellation
produces `LiveAsyncResult.Cancelled`; socket shutdown, task replacement, and
component removal interrupt obsolete work without producing application messages.

### Client And Components

```scala
trait Client:
  def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): Task[Unit]
  def exec[Msg](js: JSCommands.JSCommand[Msg]): Task[Unit]

trait ComponentUpdates:
  def sendUpdate[Props, Msg, Model](instance: LiveComponentInstance[Props, Msg, Model], props: Props): Task[Unit]
  def sendUpdate[Props, Msg, Model, Output](instance: LiveComponentOutputInstance[Props, Msg, Model, Output], props: Props): Task[Unit]
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](id: String, props: LiveComponent.PropsOf[C]): Task[Unit]
```

`ServerToBrowserEvent[A]` guarantees that Scala push sites use the declared payload type and have a matching JSON encoder. JavaScript still subscribes by string and interprets the encoded payload dynamically.

`BrowserToServerEvent[A]` declares the payload expected from a JavaScript hook. Register it with `onBrowserEvent`; the hook requires a `JsonDecoder[A]`, receives the decoded payload, and returns the next model. Matching events are consumed automatically. Malformed matching payloads are logged and consumed without changing the model. Root handlers ignore component-targeted events.

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
LiveHooks.empty.onBrowserEvent(event)(handler)
LiveHooks.empty.onRawEvent(hook)
LiveHooks.empty.onEvent(hook)
LiveHooks.empty.onParams(hook)
LiveHooks.empty.onInfo(hook)
LiveHooks.empty.onAsync(hook)
LiveHooks.empty.afterRender(effect)

ComponentLiveHooks.empty.onBrowserEvent(event)(handler)
ComponentLiveHooks.empty.onRawEvent(hook)
ComponentLiveHooks.empty.onEvent(hook)
ComponentLiveHooks.empty.onAsync(hook)
ComponentLiveHooks.empty.afterRender(effect)
```

Static hooks are unnamed, immutable, and run in declaration order. `onRawEvent` is the protocol-level escape hatch; it receives the complete `LiveEvent` envelope and does not filter event names. `bindingId` is an opaque rendered binding identifier and `cid: Option[Long]` is protocol target metadata, not a `ComponentRef`. Raw hooks receive events in declaration order until one halts. `afterRender` effects return `Task[Unit]`, observe the rendered model, and cannot replace it.

### Dynamic Hooks

```scala
ctx.hooks.rawEvent.attach(hookId)(hook)
ctx.hooks.rawEvent.detach(hookId)
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

Generated HTML tags are available through `import scalive.*`. Custom tag names are validated when created:

```scala
htmlTag(name, void = false)
HtmlTag(name, void = false)
```

### `HtmlAttr[V]`

```scala
class HtmlAttr[V](val name: String, val codec: Encoder[V, String]):
  def :=(value: V): Mod.Attr[Nothing]
```

Generated HTML attributes are available through `import scalive.*`. Custom attribute names are validated when created:

```scala
htmlAttr(name, codec)
dataAttr(name)
```

Namespaced attributes are available under `aria` and `xlink`.

### `HtmlAttrBinding`

`HtmlAttrBinding` backs semantic event bindings such as `on.click`.

```scala
class HtmlAttrBinding(val name: String):
  def debounce(duration: FiniteDuration): HtmlAttrBinding
  def debounceOnBlur: HtmlAttrBinding
  def throttle(duration: FiniteDuration): HtmlAttrBinding
  def to[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model]
  )(message: Msg): Mod.Attr[Nothing]
  def to[Msg](ref: ComponentRef[Msg])(message: Msg): Mod.Attr[Msg]
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
  on.click.toComponent(CounterComponent)(CounterComponent.Msg.Increment),
  phx.target(DomSelector.css("#counter"))
)
```

Keeping the protocol-level `phx.target` separate preserves Phoenix selector semantics, including
selectors that match multiple component instances. Events rendered inside a component normally use
the typed component reference directly:

```scala
button(on.click.to(self)(Msg.Increment))
```

For a single known instance, prefer `to(instance)(message)`. It targets the instance's stable logical
identity without a selector:

```scala
val counter = component(CounterComponent, "counter")

button(on.click.to(counter)(CounterComponent.Msg.Increment))
```

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
Mod.Attr.Group(attrs)
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
component(liveComponent, id: String): LiveComponentInstance[Props, Msg, Model]
liveComponent(component, id: String, props): Mod[Nothing]
liveComponent(component, id: Int, props): Mod[Nothing]
liveView(id, liveView, sticky = false, linkParentOnCrash = false): Mod[Nothing]
flash(kind: FlashKind)(f): Mod[Nothing]
liveTitle(pageTitle, default, prefix = "", suffix = ""): HtmlElement[Nothing]
portal(id, target: DomSelector, container = "div", wrapperClass = None)(mods*): HtmlElement[Msg]
```

`liveTitle` renders the root `<title>` with Phoenix-compatible default, prefix, and suffix metadata. Blank or missing page titles use `default`.

`portal` renders a `<template data-phx-portal="...">` containing a stable wrapper. The client moves that wrapper to the required `DomSelector`; `container` must be a valid tag name and `wrapperClass` applies to the moved wrapper.

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

## Semantic HTML API

The `on` object contains general event bindings. Specialized behavior lives under focused domains or
on the value it configures. Bindings produce typed messages or declarative `JS` commands rather than
application-defined event name strings. The separate `live` value remains exclusively the root route
seed.

### Event bindings

```scala
on.click
on.clickAway
on.blur
on.focus
on.windowBlur
on.windowFocus
on.keyDown
on.keyUp
on.windowKeyDown
on.windowKeyUp
on.viewportTop
on.viewportBottom
on.change
on.submit
```

Key filters and rate limits configure the binding they affect:

```scala
on.windowKeyDown
  .key(Key.Escape)
  .throttle(500.millis)(Msg.Close)

on.change.debounceOnBlur(Msg.Validate)
```

### Forms and uploads

Prefer `Form.onChange`, `Form.onSubmit`, `Form.onRecover`, `Form.disableRecovery`, and
`Form.triggerHttpSubmitWhen` for typed forms. `FormField` and `RootedFormField` expose matching
`onChange`, `onSubmit`, and `onRecover` methods. Low-level codec bindings remain available through
`on.change.form(codec)` and `on.submit.form(codec)`.

Upload snapshots own their DOM modifiers:

```scala
upload.dropTarget
upload.onProgress(Msg.Progress)
```

### DOM, connection, submission, and flash

```scala
dom.onMount
dom.onRemove
dom.hook(name, id: DomRef)
dom.ignoreUpdates(id: DomRef)

connection.onConnect
connection.onDisconnect
connection.visibleWhenConnected
connection.visibleWhenDisconnected

submission.disable
submission.replaceTextWith(text)

flash(kind)(render)
flash.clearOnClick
flash.clearOnClick(kind)
```

`dom.hook` emits the `phx-hook` attribute and required stable DOM ID together.
`visibleWhenConnected` and `visibleWhenDisconnected` use sticky current-element `hidden` updates,
so they require no DOM ID or display-style duplication. Streams should use `LiveStream.renderIn`,
which owns `phx-update="stream"` and all required DOM IDs.

## Phoenix Protocol Attributes

The `phx` object is the explicit compatibility layer for named `phx-*` attributes. Common values
remain typed, but these attributes intentionally expose the upstream protocol shape.

```scala
phx.click
phx.clickAway
phx.blur
phx.focus
phx.windowBlur
phx.windowFocus
phx.keyDown
phx.keyUp
phx.windowKeyDown
phx.windowKeyUp
phx.viewportTop
phx.viewportBottom
phx.change
phx.submit
phx.autoRecover
phx.triggerAction
phx.progress
phx.dropTarget
phx.connected
phx.disconnected
phx.mounted
phx.remove
phx.update
phx.hook
phx.target(ref)
phx.target(selector)
phx.debounce
phx.throttle
phx.value(key)
phx.disableWith
phx.feedbackFor
phx.trackStatic
```

`phx.update` accepts `PhxUpdate.Replace`, `PhxUpdate.Stream`, or `PhxUpdate.Ignore`.
`phx.feedbackFor` is exposed only for compatibility with older upstream behavior.

## Link API

The `link` object renders LiveView-aware anchors. Its default methods require full route-derived locations.

```scala
object link:
  def pushNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg]
  def pushNavigate[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg]
  def replaceNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg]
  def replaceNavigate[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg]
  def pushPatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg]
  def pushPatch[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg]
  def replacePatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg]
  def replacePatch[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg]

  def pushNavigateUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def pushNavigateUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg]
  def replaceNavigateUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def replaceNavigateUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg]
  def pushPatchUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def pushPatchUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg]
  def replacePatchUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg]
  def replacePatchUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg]
```

Signal-backed overloads keep an anchor's ordinary `href` synchronized with a reactive destination. Unsafe links are the explicit escape hatch for destinations that cannot be derived from a Live route. Query-only patches remain unsafe and explicit, for example `link.pushPatchUnsafe("?page=2", "Next")`.

## JS Command API

`JS` is the empty JS command builder.

```scala
val JS: JSCommands.JSCommand[Nothing]
```

`JSCommand[Msg]` is an opaque command list with a JSON encoder.

```scala
opaque type JSCommand[+Msg] = List[Op[Msg]]
```

Reusable DOM references and explicit selectors are nominal:

```scala
opaque type DomRef = String
opaque type DomSelector

val panel = DomRef("settings-panel")
div(panel.attr)
JS.show(to = panel.selector)
JS.hide(to = DomSelector.css("[data-temporary]"))
```

`DomRef` validates a CSS-safe identifier and keeps `id` assignment paired with its exact selector. `DomSelector.current` targets the command source and is the default for selector parameters. Raw strings are not accepted as selectors.

Command builder methods:

```scala
JS.addClass(names, to = DomSelector.current, transition = "", time = 200, blocking = true)
JS.toggleClass(names, to = DomSelector.current, transition = "", time = 200, blocking = true)
JS.removeClass(names, to = DomSelector.current, transition = "", time = 200, blocking = true)
JS.dispatch(event, to = DomSelector.current, detail = Map.empty, bubbles = true, blocking = false)
JS.exec(attr, to = DomSelector.current)
JS.focus(to = DomSelector.current)
JS.focusFirst(to = DomSelector.current)
JS.hide(to = DomSelector.current, transition = "", time = 200, blocking = true)
JS.ignoreAttributes(attrs = Seq.empty, to = DomSelector.current)
JS.popFocus()
JS.push(event, target = DomSelector.current, loading = DomSelector.current, pageLoading = false)
JS.pushFocus(to = DomSelector.current)
JS.removeAttribute(attr, to = DomSelector.current)
JS.setAttribute((name, value), to = DomSelector.current)
JS.show(to = DomSelector.current, transition = "", time = 200, blocking = true, display = "block")
JS.toggle(to = DomSelector.current, in = "", out = "", time = 200, blocking = true, display = "block")
JS.toggleAttribute(name, value, altValue = "", to = DomSelector.current)
JS.transition(transition = "", to = DomSelector.current, time = 200, blocking = true)
```

Navigation command signatures:

```scala
extension [Msg](ops: JSCommand[Msg])
  def pushNavigate(to: LiveLocation): JSCommand[Msg]
  def replaceNavigate(to: LiveLocation): JSCommand[Msg]
  def pushNavigateUnsafe(href: String): JSCommand[Msg]
  def replaceNavigateUnsafe(href: String): JSCommand[Msg]
  def pushPatch(to: LiveLocation): JSCommand[Msg]
  def replacePatch(to: LiveLocation): JSCommand[Msg]
  def pushPatchUnsafe(href: String): JSCommand[Msg]
  def replacePatchUnsafe(href: String): JSCommand[Msg]
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
  def seeOther: zio.http.Response
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

`href` exposes the encoded relative URL for diagnostics and APIs outside Scalive's navigation helpers. `seeOther` creates a typed HTTP 303 redirect to the location without converting it through a raw URL. `withFragment` and `withFragmentEither` accept already percent-encoded URI-fragment syntax. They validate but do not encode decoded text; the caller must encode spaces, for example by passing `"profile%20details"` instead of `"profile details"`. `withFragment` is the direct fragment API; `withFragmentEither` preserves a checked `EncodeError.Fragment`.

Direct `location`, `withFragment`, and no-argument `Unit` variants use `LiveLocation.EncodingException` only for path, query, fragment, or domain invariant violations reported as `EncodeError`. Use the corresponding `Either` methods for deliberately partial codecs. A `LiveLocation` does not prove that a patch targets the current view or that navigation remains in the same live session.

### `Live`

`Live` is the entry point for route and router construction.

```scala
object Live:
  val router: LiveRouter
  def route[A](path: PathCodec[A]): LiveRouteSeed[A]
  def session(name: String): LiveSessionBuilder[Any, Any]
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
seed(request => view)
seed((path, request, context) => view)
seed.from((path, request, environment) => view)
```

`LiveRouteBuilder[A]` is the common path-only builder. Starting a mount aspect produces `LiveRouteMountAspectBuilder[R, A, Need, Ctx]`, which accepts a view or a `(path, request, context)` factory after the aspect pipeline has produced its typed context.

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
builder(request => view)
builder((path, request, context) => view)
builder.from((path, request, environment) => view)
```

`params` and `query` produce an encodable `LiveEncodableRouteParamsBuilder[A, Params]`, a subtype of `LiveRouteParamsBuilder[A, Params]`. `paramsDecodeOnly` produces the base decode-only builder. Both accept a `LiveView.Routed[Msg, Model, Params]`.

All direct and factory route forms accept `LiveView.Eventless`, `LiveView.Routed.Eventless`, and widened `Nothing`-message values.

```scala
paramsBuilder.mapParamsDecodeOnly(decode)
paramsBuilder(view)
paramsBuilder -> view
paramsBuilder(request => view)
paramsBuilder.from((path, request, environment) => view)

encodable.mapParams(decode)(encode)
encodable.location(params)
encodable.locationEither(params)
```

Parameterized routes created after a mount aspect use `LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params]`:

```scala
aspectBuilder.params(codec)
aspectBuilder.params
aspectBuilder.query(codec)
aspectBuilder.query[QueryParams]

aspectParams.location(params)
aspectParams.locationEither(params)
aspectParams(view)
aspectParams((path, request, context) => view)
aspectParams.from((path, request, context) => view)
```

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

link.pushNavigate(settings, "Settings")
ctx.nav.pushNavigate(settings)
JS.pushPatch(settings)
```

`locationEither` returns `Either[LiveLocation.EncodeError, LiveLocation]`. `LiveEncodableRouteParamsBuilder` exposes both location methods using the final parameter type after `mapParams`. `paramsDecodeOnly` and `mapParamsDecodeOnly` return `LiveRouteParamsBuilder` values that can still mount a `LiveView.Routed` but do not expose location construction.

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
val application = Live.router(route, routes*)

ZioHttp.routes(application, config)
ZioHttp.routes(application, security)
```

`Live.router(...)` produces a transport-neutral `LiveApplication[R]`. `ZioHttp.routes` validates and finalizes it as `zio.http.Routes[R, Nothing]` using either a validated `ZioHttpConfig` or a `LiveSecurity` that wraps that configuration.

Use one `LiveSecurity` value for the Live router and sibling ordinary HTTP handlers that validate protected forms or redirect with flash:

```scala
val config = ZioHttpConfig(
  signingSecret = secret,
  sessionMaxAge = java.time.Duration.ofDays(7),
  secureCookie = true
).fold(error => throw IllegalArgumentException(error.toString), identity)
val security = LiveSecurity(config)

val auth       = AuthService.inMemory()
val application = Live.router(
  AuthLab.loginRoute,
  AuthLab.protectedSession(auth)
)
val liveRoutes = ZioHttp.routes(application, security)
val httpRoutes = AuthHttpRoutes(auth, security).routes
val routes     = liveRoutes ++ httpRoutes

Server.serve(routes)
```

`AuthHttpRoutes`, `AuthLab`, and `AuthService` are application code from the
documentation authentication lab, not framework types. Construct one service and
pass it to both the ordinary HTTP handlers and protected Live route so login,
reset, and Live authentication share one session store. See the
[authentication guide](../documentation/content/guides/authentication.md),
[`AuthLab.scala`](../documentation/site/src/scalive/docs/auth/AuthLab.scala), and
[`AuthService.scala`](../documentation/site/src/scalive/docs/auth/AuthService.scala)
for the complete composition.

Supporting route types:

```scala
trait LiveRouteFragment[-R]:
  type Input

final class LiveRoute[R, A] private[scalive] (...)
    extends LiveRouteFragment[R]
```

### Layouts

```scala
final case class LiveLayoutContext[+A, +Ctx](
  params: Signal[A],
  request: Signal[zio.http.Request],
  currentUrl: Signal[zio.http.URL],
  context: Ctx
)
```

```scala
trait LiveLayout[-A, -Ctx]:
  def view[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]): HtmlElement[Msg]
```

Helpers:

```scala
LiveLayout.identity
LiveLayout((content, ctx) => html)
```

```scala
final case class LiveRootLayoutContext[+A, +Ctx](
  params: A,
  request: zio.http.Request,
  currentUrl: zio.http.URL,
  context: Ctx
)

trait LiveRootLayout[-A, -Ctx]:
  def key(ctx: LiveRootLayoutContext[A, Ctx]): String
  def render[Msg](content: HtmlElement[Msg], pageTitle: Option[String], ctx: LiveRootLayoutContext[A, Ctx]): HtmlElement[Msg]
```

Helpers:

```scala
LiveRootLayout.identity
LiveRootLayout(rootKey)((content, pageTitle, ctx) => html)
LiveRootLayout.dynamic(rootKeyFn)((content, pageTitle, ctx) => html)
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
LiveMountAspect.authenticated(cookieName, onUnauthenticated)(authenticate, resume)
```

`authenticated` reads the named cookie during disconnected mount, signs the returned claims into the Live session, and calls `resume` during connected mount. Missing, invalid, and no-longer-resumable sessions redirect to `onUnauthenticated`. Claims are signed but not encrypted and must not contain secrets. Use `fromRequest` when authentication depends on route parameters or requires custom failure behavior.

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

### Rooted form definitions

The preferred application API declares one root and derives fields, a codec, initial
state, event state, and rendered fields from that root:

```scala
val Profile = FormRoot("profile")
val Name    = Profile.string("name").map(_.trim).required()
val Email   = Profile.optionalString("email")
val Tags    = Profile.strings("tags")

val Definition = Profile.form(ProfileData.apply)(Name, Email)

val empty     = Definition.initial()
val populated = Definition.initial(Name.initial("Ada"), Email.initial("ada@example.com"))
val changed   = Definition.from(event)

val nameField = changed.field(Name)
```

```scala
final class FormRoot:
  val path: FormPath
  type Field[A] = RootedFormField[self.type, A]
  type Codec[A] = RootedFormCodec[self.type, A]
  type InitialValue = FormInitialValue[self.type]
  def field[A](path)(decode: Vector[String] => Either[FormErrors, A]): Field[A]
  def string(path, duplicateMessage = "must be submitted at most once"): Field[String]
  def requiredString(path, blankMessage = "can't be blank", duplicateMessage = "must be submitted exactly once"): Field[String]
  def optionalString(path, duplicateMessage = "must be submitted at most once"): Field[Option[String]]
  def strings(path): Field[Vector[String]]
  def form[A](codec: Codec[A]): FormDefinition[self.type, A]
  def form[A1, Result](construct: A1 => Result)(field1: Field[A1]): FormDefinition[self.type, Result]
  // Constructor overloads are available for two through five fields.

final class RootedFormField[Owner, A]:
  def path: FormPath
  def name: String
  def id: String
  def codec: RootedFormCodec[Owner, A]
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def map[B](f: A => B): RootedFormField[Owner, B]
  def validate(message: String, code: Option[String] = None)(predicate: A => Boolean): RootedFormField[Owner, A]
  def required(message: String = "can't be blank", code: Option[String] = None)(using A =:= String): RootedFormField[Owner, String]
  def initial(values: String*): FormInitialValue[Owner]

final class RootedFormCodec[Owner, A]:
  def map[B](f: A => B): RootedFormCodec[Owner, B]
  def emap[B](f: A => Either[FormErrors, B]): RootedFormCodec[Owner, B]
  def zip[B](that: RootedFormCodec[Owner, B]): RootedFormCodec[Owner, (A, B)]

final class FormDefinition[Owner, A]:
  type Form = RootedForm[Owner, A]
  type Field[B] = RootedFormField[Owner, B]
  type InitialValue = FormInitialValue[Owner]
  val root: FormPath
  val codec: FormCodec[A]
  def initial(values: InitialValue*): Form
  def from(state: FormState[A]): Form
  def from(event: FormEvent[A]): Form

final class RootedForm[Owner, A]:
  def state: FormState[A]
  def http[Msg](target: FormAction)(mods*): HtmlElement[Msg]
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def disableRecovery: Mod.Attr[Nothing]
  def triggerHttpSubmitWhen(condition: Boolean): Mod.Attr[Nothing]
  def field[B](definition: RootedFormField[Owner, B]): FormFieldView[B]
```

The path-dependent `Owner` type prevents fields and initial values from one
`FormRoot` being used with another root's definition or rendered form. Relative
field paths become absolute browser names such as `profile[name]` when declared.
Use `FormDefinition.initial` for fresh or pre-populated state and
`FormDefinition.from` after a Live form event or when restoring a `FormState`.
Use a stable definition's `Form` alias, such as `ProfileDefinition.Form`, in
application model types instead of spelling its owner type.

### `FormData`

```scala
final case class FormData private (raw: Vector[(String, String)]):
  def fields: Map[String, FormValues]
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
FormData.fromUrlEncoded(value): Either[FormData.RepresentationError, FormData]
FormData.fromUrlEncodedBody(
  body: zio.http.Body,
  maxBytes: Long
): IO[FormData.DecodeError, FormData]
FormData.fromZioHttpForm(
  form: zio.http.Form
): Either[FormData.RepresentationError, FormData]
```

HTTP decoding errors are transport and representation failures, not domain validation errors:

```scala
enum FormData.UnsupportedFieldKind:
  case Binary
  case StreamingBinary

enum FormData.BodyError:
  case TooLarge(maxBytes: Long)
  case Read(cause: Throwable)

enum FormData.RepresentationError:
  case InvalidContentType(actual: Option[zio.http.MediaType])
  case InvalidUrlEncoding(details: String)
  case UnsupportedField(name: String, kind: FormData.UnsupportedFieldKind)

enum FormData.DecodeError:
  case Body(error: FormData.BodyError)
  case Representation(error: FormData.RepresentationError)
```

`fromUrlEncoded` preserves source order, repeated names, empty values, and nested bracket names. `fromUrlEncodedBody` additionally requires an `application/x-www-form-urlencoded` body and reads at most `maxBytes + 1` bytes, so streaming bodies cannot bypass the configured limit. It defaults to UTF-8 unless the body declares a charset.

`fromZioHttpForm` preserves the order of `Simple` and `Text` fields already present in a ZIO HTTP form. It rejects the whole conversion when it encounters `Binary` or `StreamingBinary`; it never drops, materializes, or coerces those fields. For URL-encoded request bodies, prefer `fromUrlEncodedBody` because ZIO HTTP 3.11.4 collapses repeated query-style values while constructing `zio.http.Form`.

Pure representation decoders expose only `RepresentationError`; bounded body decoding additionally exposes `BodyError` through `DecodeError`.

### `FormValues`

```scala
final case class FormValues(values: Vector[String]):
  def value: String
```

### `FormField[A]`

```scala
final class FormField[A]:
  val path: FormPath
  val codec: FormCodec[A]
  def name: String
  def id: String
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def map[B](f: A => B): FormField[B]
  def validate(message: String, code: Option[String] = None)(
    predicate: A => Boolean
  ): FormField[A]
  def required(message: String = "can't be blank", code: Option[String] = None)(
    using A =:= String
  ): FormField[String]
```

Constructors:

```scala
FormField(path)(decodeValues)
FormField.string(path, duplicateMessage)
FormField.requiredString(path, blankMessage, duplicateMessage)
FormField.optionalString(path, duplicateMessage)
FormField.strings(path)
```

`FormField` is the low-level, absolute-path field API used when a rooted definition
does not fit. It owns the browser name, generated ID, and decoder. Scalar
constructors reject duplicate values instead of silently choosing one.
Use `string(...).map(...)` followed by `required(...)` when normalization must
happen before blank validation.

### `FormCodec[A]`

```scala
trait FormCodec[A]:
  def decode(data: FormData): Either[FormErrors, A]
  def map[B](f: A => B): FormCodec[B]
  def emap[B](f: A => Either[FormErrors, B]): FormCodec[B]
  def zip[B](that: FormCodec[B]): FormCodec[(A, B)]
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
  metadata: Map[String, String] = Map.empty
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
  def onRecover(f): Mod.Attr[Msg]
  def disableRecovery: Mod.Attr[Nothing]
  def triggerHttpSubmitWhen(condition: Boolean): Mod.Attr[Nothing]
  def field(path): FormFieldView
  def field[B](definition: FormField[B]): FormFieldView[B]
  def name(path): String
  def id(path): String
  def value(path): String
  def text[Msg](path, mods*): HtmlElement[Msg]
  def text[Msg](path, explicitId, mods*): HtmlElement[Msg]
  def email[Msg](path, mods*): HtmlElement[Msg]
  def password[Msg](path, mods*): HtmlElement[Msg]
  def hidden[Msg](path, mods*): HtmlElement[Msg]
  def checkbox[Msg](path, mods*): HtmlElement[Msg]
  def checkbox[Msg](path, checkedValue, mods*): HtmlElement[Msg]
  def textarea[Msg](path, mods*): HtmlElement[Msg]
  def select[Msg](path, options, mods*): HtmlElement[Msg]
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

`Form.of` remains the low-level escape hatch for manually pairing a root name,
`FormState`, and `FormCodec`; prefer `FormRoot` and `FormDefinition` for application
forms. `http` renders a normal browser form whose `action` and `method` come from a `FormAction`. The companion form supports action-only forms; the instance method combines the same wrapper with field helpers. Neither form adds `phx-change`, `phx-submit`, `phx-trigger-action`, or HTTP body decoding. Callers opt into Live bindings explicitly with `onChange`, `onSubmit`, and `triggerHttpSubmitWhen`.

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

val Login      = FormRoot("login")
val email      = Login.requiredString("email")
val password   = Login.requiredString("password")
val definition = Login.form(LoginCredentials.apply)(email, password)
val loginForm  = definition.initial()

loginForm.http(FormAction.from(createSession))(
  loginForm.field(email).email(),
  loginForm.field(password).password(),
  button(typ := "submit", "Sign in")
)
```

### `LiveSecurity`, `CsrfProtection`, and `HttpFlash`

```scala
final class LiveSecurity:
  val config: ZioHttpConfig
  val cookies: CookiePolicy
  val csrf: CsrfProtection
  val flash: HttpFlash

object LiveSecurity:
  def apply(config: ZioHttpConfig): LiveSecurity

final class ZioHttpConfig:
  val sessionMaxAge: java.time.Duration
  val secureCookie: Boolean

object ZioHttpConfig:
  enum Error:
    case SecretTooShort(actualUtf8Bytes: Int)
    case NonPositiveSessionMaxAge

  def apply(
    signingSecret: String,
    sessionMaxAge: java.time.Duration,
    secureCookie: Boolean
  ): Either[ZioHttpConfig.Error, ZioHttpConfig]
```

```scala
final case class CookiePolicy(secure: Boolean):
  def make(name: String, content: String, maxAge: Option[zio.Duration] = None): Cookie.Response
  def expire(name: String): Cookie.Response
```

`ZioHttpConfig` validates a signing secret of at least 32 UTF-8 bytes and a positive session age before transport assembly. `LiveSecurity` keeps Live transport, ordinary-form CSRF, HTTP flash, and application cookies on that one hardened policy. Pass the same value to `ZioHttp.routes` and sibling ordinary HTTP handlers, and create application cookies through `security.cookies`.

```scala
final class CsrfProtection:
  def validate(
    request: zio.http.Request,
    data: FormData
  ): zio.IO[CsrfProtection.ValidationError, Unit]

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

`HttpFormDecoder` composes bounded URL-encoded body decoding, CSRF validation, and application decoding while keeping every failure category explicit:

```scala
val decoder = HttpFormDecoder.urlEncoded(LoginForm.Definition.codec, maxBytes, security.csrf)
decoder.decode(request)

decoder.respond(request, _ => invalidLoginResponse) { credentials =>
  authenticate(credentials)
}

enum HttpFormDecoder.Error:
  case Body(error: FormData.BodyError)
  case Representation(error: FormData.RepresentationError)
  case Csrf(error: CsrfProtection.ValidationError)
  case Validation(errors: FormErrors)

  def code: String
  def toResponse(onValidation: FormErrors => zio.http.Response): zio.http.Response
```

`respond` maps transport and CSRF failures to their standard HTTP responses, delegates
application validation to the supplied function, and runs the successful decoded-value
handler. Its optional `onRejected` callback observes any rejected request for logging or
metrics without changing the response mapping. Use `decode` when the full error channel
must remain available to application code.

`code` supplies a stable category suitable for structured logs:
`body_too_large`, `body_read`, `invalid_content_type`,
`invalid_url_encoding`, `unsupported_binary`, `unsupported_streaming_binary`,
`csrf`, or `validation`. `toResponse` maps an oversized body to 413, an invalid
content type to 415, other body or representation failures to 400, and CSRF to
403. Application validation is delegated to `onValidation`, allowing a handler to
redirect, render, or otherwise apply its own validation policy.

The capability uses two purpose-bound signed values containing the same random browser secret: an `HttpOnly` cookie and the submitted token. Validation requires exactly one bounded `_csrf_token` and compares secrets in constant time. Tokens are reusable until `ZioHttpConfig.sessionMaxAge`; they are not one-time application tokens.

Cookies created by `CookiePolicy` are host-only, scoped to `/`, `HttpOnly`, and `SameSite=Lax`. `secure` must be enabled whenever the browser-facing endpoint is HTTPS; Scalive does not infer deployment TLS from forwarding headers. The token check binds a form to the browser cookie but does not add a separate `Origin` or `Referer` policy.

```scala
final class HttpFlash:
  def seeOther(
    to: LiveLocation,
    values: (FlashKind, String)*
  ): zio.UIO[zio.http.Response]

  def seeOtherUnsafe(
    to: zio.http.URL,
    values: (FlashKind, String)*
  ): zio.UIO[zio.http.Response]
```

`seeOther` returns a 303 response with purpose-bound signed flash values in a short-lived, root-scoped, `HttpOnly`, `SameSite=Lax` cookie. Use `seeOtherUnsafe` only for validated local URLs that do not have a typed Live route. The next successfully rendered Live route embeds valid values in its Live session and expires the browser cookie; redirect chains preserve it until then. This is browser-level consume-once behavior, not server-side replay prevention, and flash values are signed rather than encrypted.

### `FormFieldView[A]`

```scala
final class FormFieldView[A] private[scalive] (...):
  def path: FormPath
  def name: String
  def id: String
  def errorId: String
  def rawValues: Vector[String]
  def fieldValue: String
  def decoded: Either[FormErrors, A]
  def errors: Vector[FormError]
  def isUsed: Boolean
  def visibleErrors: Vector[FormError]
  def hasVisibleErrors: Boolean
  def validationAttributes: Vector[Mod.Attr[Nothing]]
  def text[Msg](mods*): HtmlElement[Msg]
  def text[Msg](explicitId, mods*): HtmlElement[Msg]
  def email[Msg](mods*): HtmlElement[Msg]
  def password[Msg](mods*): HtmlElement[Msg]
  def hidden[Msg](mods*): HtmlElement[Msg]
  def checkbox[Msg](mods*): HtmlElement[Msg]
  def checkbox[Msg](checkedValue, mods*): HtmlElement[Msg]
  def textarea[Msg](mods*): HtmlElement[Msg]
  def select[Msg](options, mods*): HtmlElement[Msg]
  def errorFeedback(mods*): HtmlElement[Nothing]
  def feedback(mods*): HtmlElement[Nothing]
```

`visibleErrors` hides validation errors until the field is used or the form is
submitted. Pass `validationAttributes` to the rendered control and render
`errorFeedback` beside it to produce matching `aria-describedby`,
`aria-invalid`, `aria-live`, and `phx-feedback-for` markup.

### `FormPath`

```scala
final case class FormPath(segments: Vector[String]):
  def /(segment: String): FormPath
  def array: FormPath
  def isEmpty: Boolean
  def nonEmpty: Boolean
  def name: String
  def id: String
  def startsWith(prefix: FormPath): Boolean
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
final case class LiveStreamDef[A](
  name: String,
  domId: A => String,
  limit: Option[StreamLimit] = None
)
```

Constructor:

```scala
LiveStreamDef.byId(name)(id)

definition.keepFirst(count)
definition.keepLast(count)
definition.withLimit(limit)
definition.withoutLimit
```

`LiveStream[A]` is an opaque rendering handle returned by the `Streams` facade. `renderIn` is the
preferred rendering API. It derives the container ID from the stream, sets `phx-update="stream"`, and
applies each generated DOM ID to its projected root element:

```scala
items.renderIn(ul, cls := "items") { item =>
  li(item.toString)
}
```

Use the lower-level `.stream` extension when the container needs unusual stream-aware markup:

```scala
items.stream { (domId, item) =>
  li(idAttr := domId, item.toString)
}
```

`LiveStream` does not expose its entries or pending commands. Keep queryable, durable items in the application model rather than treating stream runtime state as business state.

Stream APIs are exported from `scalive.streams.api` into `scalive.*`.

## Upload API

Upload APIs are exported from `scalive.upload.api` into `scalive.*`.

### Typed declarations

```scala
val Documents: LiveUploadDef[Chunk[Byte]] = LiveUploadDef.inMemory(
  name = "documents",
  accept = LiveUploadAccept.only(".txt", ".md"),
  maxEntries = 2,
  maxFileSize = 1024L * 1024L
)
```

`LiveUploadDef[Result]` is both the declaration and the typed runtime identity. Define it
once and pass the same value to `allow`, `get`, `disallow`, and
`consumeCompleted`. The result type records what the configured destination produces.
Available constructors are `inMemory`, `hosted`, `external`, and `validated`.
Chunk timeout configuration uses `zio.Duration`; sizes are explicitly named in bytes.

### Upload accept values

```scala
sealed trait LiveUploadAccept:
  def toHtmlValue: String

object LiveUploadAccept:
  case object Any
  def only(first: String, rest: String*): LiveUploadAccept
  def validated(values: Iterable[String]): Either[IllegalArgumentException, LiveUploadAccept]
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
enum LiveUploadEntryStatus:
  case Selected
  case Preflighted
  case Uploading(progress: Int)
  case Completed
  case Invalid(errors: List[LiveUploadError])
```

```scala
final class LiveUploadEntry[Result]:
  val ref: UploadEntryRef
  val client: UploadClientMetadata
  val status: LiveUploadEntryStatus
  val metadata: Option[zio.json.ast.Json.Obj]
  def progress: Int
  def errors: List[LiveUploadError]

final class LiveUpload[Result]:
  val definition: LiveUploadDef[Result]
  val ref: UploadRef
  val entries: List[LiveUploadEntry[Result]]
  val errors: List[LiveUploadError]
```

`UploadRef` and `UploadEntryRef` expose `.value` only for wire-facing attributes.
Snapshots are runtime-owned and cannot be fabricated. `allow`, `cancel`, `consume`, and
`consumeCompleted` return fresh snapshots; use `get` for generic validate/progress
messages when the model needs refreshing.

Expected lifecycle failures use the sealed `LiveUploadOperationError` hierarchy, including
active entries, definition mismatches, stale entries, incomplete entries, and uploads with
entries still in progress.

Call `allow` during mount without an `Option` model or a hand-built connecting
placeholder. Disconnected rendering receives the runtime snapshot needed to render
`liveFileInput`; file transfer and progress begin after connection.

### Completion and ownership

```scala
final class CompletedUpload[Result]:
  val ref: UploadEntryRef
  val client: UploadClientMetadata
  val result: Result
  val metadata: zio.json.ast.Json.Obj

enum ConsumeDecision[+A]:
  case Consume(value: A)
  case Postpone(value: A)
```

Consumption is callback-based so ownership is explicit. Return `Consume(value)` only
after application persistence succeeds; the runtime then removes the entry and transfers
its result to the application. Return `Postpone(value)` when persistence should be retried, leaving the
completed entry runtime-owned and available to a later consume call.

### External uploads and writers

```scala
final class UploadClientMetadata:
  val fileName: String
  val relativePath: Option[String]
  val sizeBytes: Long
  val mediaType: String
  val lastModifiedMillis: Option[Long]
  val metadata: Option[zio.json.ast.Json]
```

```scala
enum LiveExternalUploadResult[+Result]:
  case Ready(clientConfig: ExternalUploadClientConfig, result: Result)
  case Error(meta: zio.json.ast.Json.Obj)
```

`ExternalUploadClientConfig` validates that the client configuration contains a
non-empty `uploader` identifier before preflight can succeed.

```scala
trait LiveUploadExternalUploader[Result]:
  def preflight(client: UploadClientMetadata): Task[LiveExternalUploadResult[Result]]
  def discard(result: Result): Task[Unit]
```

```scala
enum LiveUploadAbortReason:
  case Cancelled
  case Disallowed
  case ComponentRemoved
  case SocketShutdown
  case Failed(reason: String)
```

```scala
trait LiveUploadWriter[State, Result]:
  def init(client: UploadClientMetadata): Task[State]
  def writeChunk(data: Chunk[Byte], state: State): Task[State]
  def complete(state: State): Task[Result]
  def abort(state: State, reason: LiveUploadAbortReason): Task[Unit]
  def discard(result: Result): Task[Unit]
  def metadata(result: Result): zio.json.ast.Json.Obj
```

Writer state and completed results are independently typed; no `Any` state wrapper or
runtime cast is part of the public extension point. `complete` transfers the writer
state into its application-facing result. `discard` releases a completed result when
runtime ownership ends without application consumption.

### Upload progress and options

```scala
trait LiveUploadProgress[Result]:
  def onProgress(entry: LiveUploadEntry[Result]): Task[Unit]
```

## Lifecycle Hooks API

```scala
final case class LiveEvent(
  kind: String,
  bindingId: String,
  value: zio.json.ast.Json,
  params: Map[String, String],
  cid: Option[Long],
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
