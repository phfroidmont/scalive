# Scalive Public API Reference

This document describes the current public API exposed by Scalive.

Most application code starts with:

```scala
import scalive.*
```

The package object exports the generated HTML tag and attribute definitions, stream APIs, upload APIs, helpers for LiveViews and components, and Phoenix LiveView-style `phx-*` bindings.

## Core LiveView API

### `LiveView[Msg, Model]`

`LiveView` is the root application abstraction. A LiveView owns a typed model and receives typed messages.

```scala
trait LiveView[Msg, Model]:
  def mount: LiveIO[LiveView.InitContext, Model]
  def handleMessage(model: Model): Msg => LiveIO[LiveView.UpdateContext, Model]
  def render(model: Model): HtmlElement[Msg]
  def subscriptions(model: Model): ZStream[LiveView.SubscriptionsContext, Nothing, Msg]

  val queryCodec: LiveQueryCodec[?] = LiveQueryCodec.none

  def handleParams(
    model: Model,
    query: queryCodec.Out,
    url: zio.http.URL
  ): LiveIO[LiveView.ParamsContext, Model]

  def handleParamsDecodeError(
    model: Model,
    error: LiveQueryCodec.DecodeError,
    url: zio.http.URL
  ): LiveIO[LiveView.ParamsContext, Model]

  def interceptEvent(
    model: Model,
    event: String,
    value: zio.json.ast.Json
  ): LiveIO[LiveView.InterceptContext, InterceptResult[Model]]
```

Lifecycle methods:

- `mount` creates the initial model for disconnected and connected lifecycle phases.
- `handleMessage` handles typed messages produced by HTML bindings, JS push commands, async tasks, and subscriptions.
- `render` returns the current HTML tree.
- `subscriptions` returns a stream of messages for server-side subscriptions.
- `queryCodec` decodes query parameters for `handleParams`.
- `handleParams` runs when URL parameters are decoded successfully.
- `handleParamsDecodeError` runs when `queryCodec` cannot decode the current URL.
- `interceptEvent` can continue or halt a raw client event before it becomes a normal typed message.

Context aliases:

```scala
object LiveView:
  type BaseContext       = LiveContext.BaseCapabilities
  type NavigationContext = LiveContext.NavigationCapabilities
  type InitContext       = NavigationContext
  type SubscriptionsContext = BaseContext
  type UpdateContext     = NavigationContext
  type ParamsContext     = NavigationContext
  type InterceptContext  = NavigationContext
```

### `InterceptResult[Model]`

`InterceptResult` is returned by `LiveView.interceptEvent`.

```scala
enum InterceptResult[Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[zio.json.ast.Json])
```

Constructors:

```scala
InterceptResult.cont(model)
InterceptResult.halt(model)
InterceptResult.haltReply(model, value)
```

### `LiveIO[-R, +A]`

`LiveIO` is an opaque alias over `zio.RIO[R, A]` used by lifecycle callbacks.

```scala
opaque type LiveIO[-R, +A] = zio.RIO[R, A]
```

Helpers:

```scala
LiveIO.pure(value)
```

Given conversions allow plain values and `RIO` values to satisfy `LiveIO` return types.

## Live Context API

`LiveContext` is the service available inside `LiveIO` effects. Capabilities are split into traits and exposed through type aliases on `LiveView` and `LiveComponent`.

### `LiveContext`

```scala
final case class LiveContext(
  staticChanged: Boolean,
  connected: Boolean = false,
  uploads: UploadRuntime = UploadRuntime.Disabled,
  streams: StreamRuntime = StreamRuntime.Disabled,
  clientEvents: ClientEventRuntime = ClientEventRuntime.Disabled,
  navigation: LiveNavigationRuntime = LiveNavigationRuntime.Disabled,
  title: TitleRuntime = TitleRuntime.Disabled,
  components: ComponentUpdateRuntime = ComponentUpdateRuntime.Disabled,
  nestedLiveViews: NestedLiveViewRuntime = NestedLiveViewRuntime.Disabled,
  flash: FlashRuntime = FlashRuntime.Disabled,
  async: LiveAsyncRuntime = LiveAsyncRuntime.Disabled,
  hooks: LiveHookRuntime = LiveHookRuntime.Disabled
)
```

Capability traits:

```scala
LiveContext.HasConnected
LiveContext.HasStaticChanged
LiveContext.HasUploads
LiveContext.HasStreams
LiveContext.HasClientEvents
LiveContext.HasNavigation
LiveContext.HasTitle
LiveContext.HasComponents
LiveContext.HasNestedLiveViews
LiveContext.HasFlash
LiveContext.HasAsync
LiveContext.HasHooks
LiveContext.BaseCapabilities
LiveContext.NavigationCapabilities
```

### Connection and static tracking

```scala
LiveContext.connected: URIO[LiveContext.HasConnected, Boolean]
LiveContext.staticChanged: URIO[LiveContext.HasStaticChanged, Boolean]
```

### Upload context methods

```scala
LiveContext.allowUpload(name, options): RIO[LiveContext.HasUploads, LiveUpload]
LiveContext.disallowUpload(name): RIO[LiveContext.HasUploads, Unit]
LiveContext.upload(name): URIO[LiveContext.HasUploads, Option[LiveUpload]]
LiveContext.cancelUpload(name, entryRef): RIO[LiveContext.HasUploads, Unit]
LiveContext.consumeUploadedEntries(name): URIO[LiveContext.HasUploads, List[LiveUploadedEntry]]
LiveContext.consumeUploadedEntry(entryRef): URIO[LiveContext.HasUploads, Option[LiveUploadedEntry]]
LiveContext.dropUploadedEntry(entryRef): URIO[LiveContext.HasUploads, Unit]
```

### Stream context methods

```scala
LiveContext.stream(definition, items, at = StreamAt.Last, reset = false, limit = None): RIO[LiveContext.HasStreams, LiveStream[A]]
LiveContext.streamInsert(definition, item, at = StreamAt.Last, limit = None, updateOnly = false): RIO[LiveContext.HasStreams, LiveStream[A]]
LiveContext.streamDelete(definition, item): RIO[LiveContext.HasStreams, LiveStream[A]]
LiveContext.streamDeleteByDomId(definition, domId): RIO[LiveContext.HasStreams, LiveStream[A]]
LiveContext.streamState(definition): URIO[LiveContext.HasStreams, Option[LiveStream[A]]]
```

### Client event methods

```scala
LiveContext.pushEvent[A: JsonEncoder](name, payload): URIO[LiveContext.HasClientEvents, Unit]
LiveContext.pushJs(command): URIO[LiveContext.HasClientEvents, Unit]
```

### Navigation methods

```scala
LiveContext.pushPatch(to: String): RIO[LiveContext.HasNavigation, Unit]
LiveContext.pushPatch(codec, value): RIO[LiveContext.HasNavigation, Unit]
LiveContext.replacePatch(to: String): RIO[LiveContext.HasNavigation, Unit]
LiveContext.replacePatch(codec, value): RIO[LiveContext.HasNavigation, Unit]
LiveContext.pushNavigate(to: String): RIO[LiveContext.HasNavigation, Unit]
LiveContext.pushNavigate(codec, value): RIO[LiveContext.HasNavigation, Unit]
LiveContext.replaceNavigate(to: String): RIO[LiveContext.HasNavigation, Unit]
LiveContext.replaceNavigate(codec, value): RIO[LiveContext.HasNavigation, Unit]
LiveContext.redirect(to: String): RIO[LiveContext.HasNavigation, Unit]
LiveContext.redirect(codec, value): RIO[LiveContext.HasNavigation, Unit]
```

### Title methods

```scala
LiveContext.putTitle(title): URIO[LiveContext.HasTitle, Unit]
```

### Flash methods

```scala
LiveContext.putFlash(kind, message): URIO[LiveContext.HasFlash, Unit]
LiveContext.clearFlash(kind): URIO[LiveContext.HasFlash, Unit]
LiveContext.clearFlash: URIO[LiveContext.HasFlash, Unit]
LiveContext.flash(kind): URIO[LiveContext.HasFlash, Option[String]]
LiveContext.flash: URIO[LiveContext.HasFlash, Map[String, String]]
```

### Async methods

```scala
LiveContext.startAsync(name, mode = AsyncStartMode.Restart)(effect)(toMsg): URIO[LiveContext.HasAsync, Unit]
LiveContext.assignAsync(model, mode = AsyncStartMode.Restart, reset = false)(field)(effect): URIO[LiveContext.HasAsync, Model]
LiveContext.cancelAsync(name, reason = None): URIO[LiveContext.HasAsync, Unit]
LiveContext.cancelAssignAsync(model)(field, reason = None): URIO[LiveContext.HasAsync, Unit]
```

### Component update method

```scala
LiveContext.sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](id, props): URIO[LiveContext.HasComponents, Unit]
```

### Lifecycle hook methods

```scala
LiveContext.attachEventHook(id)(hook): RIO[LiveContext.HasHooks, Unit]
LiveContext.detachEventHook(id): RIO[LiveContext.HasHooks, Unit]
LiveContext.attachParamsHook(id)(hook): RIO[LiveContext.HasHooks, Unit]
LiveContext.detachParamsHook(id): RIO[LiveContext.HasHooks, Unit]
LiveContext.attachInfoHook(id)(hook): RIO[LiveContext.HasHooks, Unit]
LiveContext.detachInfoHook(id): RIO[LiveContext.HasHooks, Unit]
LiveContext.attachAsyncHook(id)(hook): RIO[LiveContext.HasHooks, Unit]
LiveContext.detachAsyncHook(id): RIO[LiveContext.HasHooks, Unit]
LiveContext.attachAfterRenderHook(id)(hook): RIO[LiveContext.HasHooks, Unit]
LiveContext.detachAfterRenderHook(id): RIO[LiveContext.HasHooks, Unit]
```

## Async API

### `AsyncValue[A]`

`AsyncValue` models field-level async state.

```scala
enum AsyncValue[+A]:
  case Empty
  case Loading(previous: Option[A])
  case Ok(value: A)
  case Failed(previous: Option[A], cause: zio.Cause[Throwable])
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
  case Ok(value: A)
  case Failed(cause: zio.Cause[Throwable])
  case Cancelled(reason: Option[String])
```

### `AsyncStartMode`

```scala
enum AsyncStartMode:
  case Restart
  case KeepExisting
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
  def apply(message: ComponentTargetMessage): Mod.Attr[Nothing]
  def apply[Msg](cmd: JSCommand[Msg]): Mod.Attr[Msg]
  def apply[Msg](msg: Msg): Mod.Attr[Msg]
  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg]
  def form[Msg](f: FormData => Msg): Mod.Attr[Msg]
  def form[A, Msg](codec: FormCodec[A])(f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def withValue[Msg](f: String => Msg): Mod.Attr[Msg]
  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg]
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

### Package-level rendering helpers

```scala
rawHtml(html): Mod[Nothing]
component(cid, element): Mod[Msg]
component[C <: LiveComponent[?, ?, ?]: ClassTag](message): ComponentTargetMessage
liveComponent(component, id: String, props): Mod[Nothing]
liveComponent(component, id: Int, props): Mod[Nothing]
liveView(id, liveView, sticky = false): Mod[Nothing]
flash(kind)(f): Mod[Nothing]
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

The `link` object renders LiveView-aware anchors.

```scala
link.navigate(path, mods*)
link.patch(path, mods*)
link.patch(codec, value, mods*)
link.patchReplace(path, mods*)
link.patchReplace(codec, value, mods*)
```

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
JS.navigate(href, replace = false)
JS.patch(href, replace = false)
JS.patch(codec, value)
JS.patch(codec, value, replace)
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

`transition` arguments accept either a space-separated class string or a tuple of three class strings.

## Components API

### `LiveComponent[Props, Msg, Model]`

```scala
trait LiveComponent[Props, Msg, Model]:
  def mount(props: Props): LiveIO[LiveComponent.InitContext, Model]
  def update(props: Props, model: Model): LiveIO[LiveComponent.UpdateContext, Model]
  def handleMessage(model: Model): Msg => LiveIO[LiveComponent.UpdateContext, Model]
  def render(model: Model, self: ComponentRef[Msg]): HtmlElement[Msg]
```

Context aliases:

```scala
object LiveComponent:
  type InitContext = LiveContext.BaseCapabilities
  type UpdateContext = LiveContext.NavigationCapabilities
  type PropsOf[C]
  type MsgOf[C]
```

Supporting types:

```scala
final case class ComponentRef[Msg] private[scalive] (cid: Int)
final case class ComponentTargetMessage private[scalive] (componentClass: Class[?], message: Any)
trait ComponentUpdateRuntime
```

### Built-in component helpers

```scala
focusWrap(id, mods*)(content*)
liveFileInput(upload, mods*)
uploadErrors(upload)
uploadErrors(upload, entry)
uploadErrors(entry)
```

## Routing API

### `Live`

`Live` is the entry point for route and router construction.

```scala
object Live:
  val router: LiveRouter[Any]
  def route[A](path: PathCodec[A]): LiveRouteSeed[A]
  def session(name: String): LiveSessionSeed
  def socketAt(path: PathCodec[Unit]): LiveSocketMount
  def tokenConfig(config: TokenConfig): LiveTokenConfig
```

The package-level `live` value is equivalent to an empty route seed.

```scala
val live: LiveRouteSeed[Unit]
```

### Route seeds and builders

`LiveRouteSeed[A]` starts a route from a typed `PathCodec[A]`.

```scala
seed / pathCodec
seed @@ aspect
seed @@ layout
seed @@ rootLayout
seed(view)
seed -> view
seed((params, request, context) => view)
seed((params, request) => view)
seed(request => view)
seed((params, request, c1, c2) => view)
```

`LiveRouteBuilder[R, A, Need, Ctx]` is produced after modifiers are applied.

```scala
builder @@ aspect
builder @@ layout
builder @@ rootLayout
builder(view)
builder -> view
builder((params, request, context) => view)
builder((params, request, c1, c2) => view)
```

### Live sessions

```scala
Live.session(name)(route, routes*)
Live.session(name) @@ aspect
Live.session(name) @@ layout
Live.session(name) @@ rootLayout
```

`LiveSessionBuilder` supports additional `@@` composition and then applies to one or more routes.

### Router

```scala
Live.router @@ layout
Live.router @@ rootLayout
Live.router @@ Live.socketAt(path)
Live.router @@ Live.tokenConfig(config)
Live.router(route, routes*)
```

The resulting value is a `zio.http.Routes` value.

Supporting route types:

```scala
trait LiveRouteFragment[-R, -Need]
final class LiveRoute[R, A, -Need, Ctx, Msg, Model] private[scalive] (...)
final case class LiveSocketMount(pathCodec: PathCodec[Unit])
final case class LiveTokenConfig(config: TokenConfig)
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
  case Redirect(to: zio.http.URL)
  case Unauthorized(reason: Option[String])
  case Stale(reason: Option[String])
```

Constructors:

```scala
LiveMountFailure.redirect(to)
LiveMountFailure.unauthorized
LiveMountFailure.unauthorized(reason)
LiveMountFailure.stale
LiveMountFailure.stale(reason)
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

## Query Codec API

```scala
trait LiveQueryCodec[A]:
  type Out = A
  def decode(url: zio.http.URL): IO[LiveQueryCodec.DecodeError, A]
  def href(value: A): Either[LiveQueryCodec.EncodeError, String]
```

Errors:

```scala
LiveQueryCodec.DecodeError(message, cause = None)
LiveQueryCodec.EncodeError(message, cause = None)
```

Constructors:

```scala
LiveQueryCodec.none
LiveQueryCodec[A]
LiveQueryCodec.fromZioHttp(codec)
LiveQueryCodec.custom(decodeFn, encodeFn)
```

## Navigation Runtime API

```scala
enum LiveNavigationCommand:
  case PushPatch(to: String)
  case ReplacePatch(to: String)
  case PushNavigate(to: String)
  case ReplaceNavigate(to: String)
  case Redirect(to: String)
```

```scala
trait LiveNavigationRuntime:
  def request(command: LiveNavigationCommand): Task[Unit]
```

Disabled runtime:

```scala
LiveNavigationRuntime.Disabled
```

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
FormData.fromUrlEncoded(value)
```

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
```

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

```scala
final case class LiveStreamEntry[+A](domId: String, value: A)
```

```scala
final case class LiveStream[+A] private[scalive] (
  name: String,
  entries: Vector[LiveStreamEntry[A]],
  ...
):
  def isEmpty: Boolean
  def nonEmpty: Boolean
```

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
  name: String,
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
  def preflight(entry: LiveExternalUploadEntry): RIO[LiveContext, LiveExternalUploadResult]
```

```scala
enum LiveUploadWriterCloseReason:
  case Done
  case Cancel
  case Error(reason: String)
```

```scala
final case class LiveUploadWriterState private[scalive] (value: Any)
```

```scala
trait LiveUploadWriter:
  def init(uploadName: String, entry: LiveExternalUploadEntry): Task[LiveUploadWriterState]
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
  def onProgress(uploadName: String, entry: LiveUploadEntry): RIO[LiveContext, Unit]
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
final case class LiveAsyncEvent(name: String)
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
enum LiveEventResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[zio.json.ast.Json] = None)
```

Constructors:

```scala
LiveEventResult.cont(model)
LiveEventResult.halt(model)
LiveEventResult.haltReply(model, value)
```

```scala
trait LiveHookRuntime:
  def attachEvent(id)(hook): Task[Unit]
  def detachEvent(id): Task[Unit]
  def attachParams(id)(hook): Task[Unit]
  def detachParams(id): Task[Unit]
  def attachInfo(id)(hook): Task[Unit]
  def detachInfo(id): Task[Unit]
  def attachAsync(id)(hook): Task[Unit]
  def detachAsync(id): Task[Unit]
  def attachAfterRender(id)(hook): Task[Unit]
  def detachAfterRender(id): Task[Unit]
```

Disabled runtime:

```scala
LiveHookRuntime.Disabled
```

## Runtime Capability Traits

These runtime traits back `LiveContext` operations.

```scala
trait ClientEventRuntime:
  def push(name: String, payload: zio.json.ast.Json): UIO[Unit]
```

```scala
trait ComponentUpdateRuntime:
  def sendUpdate[Props](componentClass: Class[?], id: String, props: Props): UIO[Unit]
```

```scala
trait NestedLiveViewRuntime:
  def register[Msg, Model](spec: NestedLiveViewSpec[Msg, Model]): Task[NestedLiveViewRegistration]
```

```scala
trait FlashRuntime:
  def put(kind: String, message: String): UIO[Unit]
  def clear(kind: String): UIO[Unit]
  def clearAll: UIO[Unit]
  def get(kind: String): UIO[Option[String]]
  def snapshot: UIO[Map[String, String]]
```

```scala
trait TitleRuntime:
  def set(title: String): UIO[Unit]
  def drain: UIO[Option[String]]
```

```scala
trait StreamRuntime:
  def stream(definition, items, at, reset, limit): Task[LiveStream[A]]
  def insert(definition, item, at, limit, updateOnly): Task[LiveStream[A]]
  def delete(definition, item): Task[LiveStream[A]]
  def deleteByDomId(definition, domId): Task[LiveStream[A]]
  def get(definition): UIO[Option[LiveStream[A]]]
```

```scala
trait UploadRuntime:
  def allow(name: String, options: LiveUploadOptions): Task[LiveUpload]
  def disallow(name: String): Task[Unit]
  def get(name: String): UIO[Option[LiveUpload]]
  def cancel(name: String, entryRef: String): Task[Unit]
  def consumeCompleted(name: String): UIO[List[LiveUploadedEntry]]
  def consume(entryRef: String): UIO[Option[LiveUploadedEntry]]
  def drop(entryRef: String): UIO[Unit]
```

Disabled runtimes are available as `ClientEventRuntime.Disabled`, `FlashRuntime.Disabled`, `TitleRuntime.Disabled`, `StreamRuntime.Disabled`, `UploadRuntime.Disabled`, `ComponentUpdateRuntime.Disabled`, and `NestedLiveViewRuntime.Disabled`.

## Static Assets API

```scala
object StaticAssetHasher:
  def hashedPath(rel: String, root: java.nio.file.Path = Paths.get("public")): Task[String]
```

```scala
object ServeHashedResourcesMiddleware:
  def apply(path: zio.http.Path, resourcePrefix: String = "public"): Middleware[Any]
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

```scala
final case class Token[T] private (
  version: Int,
  liveViewId: String,
  payload: T,
  issuedAt: Long,
  salt: String
)
```

```scala
Token.sign(secret, liveViewId, payload)
Token.verify(secret, token, maxAge)
```

## Low-Level Socket and Protocol API

These public types model the websocket protocol and runtime socket.

### `Socket[Msg, Model]`

```scala
final case class Socket[Msg, Model] private (
  id: String,
  token: String,
  inbox: Queue[(WebSocketMessage.Payload.Event, WebSocketMessage.Meta)],
  livePatch: (String, WebSocketMessage.Meta) => Task[WebSocketMessage.Payload.Reply],
  allowUpload: WebSocketMessage.Payload.AllowUpload => Task[WebSocketMessage.Payload.Reply],
  progressUpload: WebSocketMessage.Payload.Progress => Task[WebSocketMessage.Payload.Reply],
  uploadJoin: (String, String) => Task[WebSocketMessage.Payload.Reply],
  uploadChunk: (String, Chunk[Byte]) => Task[WebSocketMessage.Payload.Reply],
  outbox: ZStream[Any, Nothing, (WebSocketMessage.Payload, WebSocketMessage.Meta)],
  shutdown: UIO[Unit]
)
```

Constructor:

```scala
Socket.start(id, token, liveView, ctx, meta, tokenConfig = TokenConfig.default, initialUrl = URL.root, initialFlash = Map.empty, renderRoot = None)
```

### `WebSocketMessage`

```scala
final case class WebSocketMessage(
  joinRef: Option[Int],
  messageRef: Option[Int],
  topic: String,
  eventType: String,
  payload: WebSocketMessage.Payload
):
  val meta: WebSocketMessage.Meta
  def okReply: WebSocketMessage
```

Supporting values and types:

```scala
WebSocketMessage.Meta
WebSocketMessage.Protocol
WebSocketMessage.Payload
WebSocketMessage.ReplyStatus
WebSocketMessage.UploadClientConfig
WebSocketMessage.LivePatchKind
WebSocketMessage.UploadJoinToken
WebSocketMessage.JoinErrorReason
WebSocketMessage.UploadJoinErrorReason
WebSocketMessage.UploadChunkErrorReason
WebSocketMessage.LiveResponse
WebSocketMessage.decodeBinaryPush(bytes)
```

Protocol constants:

```scala
WebSocketMessage.Protocol.EventHeartbeat
WebSocketMessage.Protocol.EventJoin
WebSocketMessage.Protocol.EventLeave
WebSocketMessage.Protocol.EventClose
WebSocketMessage.Protocol.EventEvent
WebSocketMessage.Protocol.EventLivePatch
WebSocketMessage.Protocol.EventAllowUpload
WebSocketMessage.Protocol.EventProgress
WebSocketMessage.Protocol.EventReply
WebSocketMessage.Protocol.EventError
WebSocketMessage.Protocol.EventDiff
WebSocketMessage.Protocol.EventRedirect
WebSocketMessage.Protocol.EventLiveRedirect
WebSocketMessage.Protocol.BinaryChunkEvent
WebSocketMessage.Protocol.LiveViewVersion
```

Payload cases:

```scala
enum WebSocketMessage.Payload:
  case Heartbeat
  case Join(url, redirect, session, static, params, flash, sticky)
  case UploadJoin(token)
  case Leave
  case Close
  case LivePatch(url)
  case AllowUpload(ref, entries, cid)
  case Progress(event, ref, entry_ref, progress, cid)
  case UploadChunk(bytes)
  case LiveNavigation(to, kind)
  case LiveRedirect(to, kind, flash)
  case Redirect(to, flash)
  case Error
  case Reply(status, response)
  case Diff(diff)
  case Event(`type`, event, value, uploads = None, cid = None, meta = None)
```

Payload supporting types and helpers:

```scala
final case class WebSocketMessage.UploadPreflightEntry(
  ref: String,
  name: String,
  relative_path: Option[String],
  size: Long,
  `type`: String,
  last_modified: Option[Long] = None,
  meta: Option[zio.json.ast.Json] = None
)

WebSocketMessage.Payload.okReply(response)
WebSocketMessage.Payload.errorReply(response)
```

Reply and navigation enums:

```scala
enum WebSocketMessage.ReplyStatus:
  case Ok
  case Error

enum WebSocketMessage.LivePatchKind:
  case Push
  case Replace
```

Upload protocol types:

```scala
final case class WebSocketMessage.UploadClientConfig(
  max_file_size: Long,
  max_entries: Int,
  chunk_size: Int,
  chunk_timeout: Int
)

final case class WebSocketMessage.UploadJoinToken(
  liveViewTopic: String,
  uploadRef: String,
  entryRef: String
)

enum WebSocketMessage.UploadJoinErrorReason:
  case InvalidToken
  case AlreadyRegistered
  case Disallowed
  case WriterError

enum WebSocketMessage.UploadChunkErrorReason:
  case FileSizeLimitExceeded
  case WriterError
  case Disallowed
```

Join errors and responses:

```scala
enum WebSocketMessage.JoinErrorReason:
  case Unauthorized
  case Stale

enum WebSocketMessage.LiveResponse:
  case Empty
  case Raw(value)
  case InitDiff(rendered)
  case Diff(diff)
  case InterceptReply(reply, diff = None)
  case JoinError(reason)
  case UploadJoinError(reason)
  case UploadChunkError(reason, limit = None)
  case UploadPreflightSuccess(ref, config, entries, errors)
  case UploadPreflightFailure(ref, error)
```

### `Diff`

```scala
enum Diff:
  case Tag(
    static: Seq[String] = Seq.empty,
    dynamic: Seq[Diff.Dynamic] = Seq.empty,
    events: Seq[Diff.Event] = Seq.empty,
    root: Boolean = false,
    title: Option[String] = None,
    components: Map[Int, Diff] = Map.empty,
    templates: Map[Int, Seq[String]] = Map.empty,
    templateRef: Option[Int] = None
  )
  case Comprehension(
    static: Seq[String] = Seq.empty,
    entries: Seq[Diff.Dynamic | Diff.IndexChange | Diff.IndexMerge] = Seq.empty,
    count: Int = 0,
    stream: Option[Diff.Stream] = None,
    staticRef: Option[Int] = None
  )
  case Value(value: String)
  case ComponentRef(cid: Int)
  case Dynamic(index: Int, diff: Diff)
  case Deleted
```

Supporting diff types:

```scala
final case class Diff.IndexChange(index: Int, previousIndex: Int)
final case class Diff.IndexMerge(index: Int, previousIndex: Int, diff: Diff)
final case class Diff.Event(name: String, payload: zio.json.ast.Json)
final case class Diff.StreamInsert(domId: String, at: Int, limit: Option[Int], updateOnly: Option[Boolean])
final case class Diff.Stream(ref: String, inserts: Seq[Diff.StreamInsert], deleteIds: Seq[String], reset: Boolean)
```

Extension:

```scala
diff.isEmpty
```

### HTML builder and escaping

```scala
HtmlBuilder.build(el, isRoot = false): String
Escaping.validTag(s): Boolean
Escaping.validAttrName(s): Boolean
Escaping.escape(text, writer): Unit
Escaping.escape(text): String
StringWriter.writeEscaped(text)
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
