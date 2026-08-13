package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json

import scalive.streams.*
import scalive.upload.*

/** Connection metadata shared by every LiveView and LiveComponent lifecycle context.
  *
  * A root LiveView, including each nested LiveView, has separate disconnected HTTP and connected
  * socket lifecycles. Its mount runs once in each lifecycle, and components are likewise mounted
  * independently while rendering each lifecycle. State and dynamically attached hooks from the
  * disconnected lifecycle are not carried into the connected lifecycle.
  *
  * Capabilities exposed by a phase context are the operations valid in that phase. Async tasks,
  * subscriptions, and client events are connected-only: calls made during disconnected rendering
  * are no-ops (client payloads are still encoded). Uploads and streams may be configured during
  * both lifecycles so their handles can participate in the corresponding render.
  */
trait LifecycleContext:
  /** Whether this callback belongs to a connected socket lifecycle.
    *
    * This is `false` during the disconnected HTTP render and `true` during socket mount and all
    * subsequent connected callbacks.
    */
  def connected: Boolean

  /** Whether the client's tracked static assets differ from those rendered by the server.
    *
    * This value is meaningful on connected join and remains stable for that socket lifecycle. It is
    * `false` during disconnected rendering.
    */
  def staticChanged: Boolean

  /** Browser-supplied LiveSocket parameters for the connected join.
    *
    * The map is empty during disconnected rendering. Its values are untrusted client input and must
    * not be used as authenticated session data.
    */
  def connectParams: Map[String, Json]
  private[scalive] def runtimeTraceSession: Option[String] = None

/** Capabilities available while mounting a root LiveView.
  *
  * Mount runs independently for the disconnected HTTP render and the connected socket join. The
  * restricted [[MountNavigation]] surface intentionally excludes live patches because no mounted
  * connected view is yet available to patch.
  *
  * @tparam Msg
  *   the messages accepted by the LiveView
  * @tparam Model
  *   the LiveView's model type
  */
trait MountContext[Msg, Model] extends LifecycleContext:
  /** Navigation requests valid during mount. */
  def nav: MountNavigation

  /** Flash state owned by this LiveView lifecycle. */
  def flash: Flash

  /** Upload configuration and operations owned by the root LiveView. */
  def uploads: Uploads

  /** Stream operations owned by the root LiveView. */
  def streams: Streams

  /** Connected async tasks owned by the root LiveView. */
  def async: Async[Msg]

  /** Connected message subscriptions owned by the root LiveView. */
  def subscriptions: Subscriptions[Msg]

  /** Connected browser event and JavaScript commands. */
  def client: Client

  /** Dynamic lifecycle hooks for this root LiveView. */
  def hooks: RootHooks[Msg, Model]

/** Capabilities available while a connected root LiveView handles a message.
  *
  * Messages can originate from browser bindings, subscriptions, async completions, or other server
  * delivery. Browser events run raw-event and typed-event hooks before the handler; subscription
  * and other server messages run info hooks, and async completions run async hooks.
  *
  * @tparam Msg
  *   the messages accepted by the LiveView
  * @tparam Model
  *   the LiveView's model type
  */
trait MessageContext[Msg, Model] extends LifecycleContext:
  /** Live patch, live navigation, and redirect requests for the current callback. */
  def nav: Navigation

  /** Flash state owned by this LiveView lifecycle. */
  def flash: Flash

  /** Upload operations owned by the root LiveView. */
  def uploads: Uploads

  /** Stream operations owned by the root LiveView. */
  def streams: Streams

  /** Async tasks owned by the root LiveView. */
  def async: Async[Msg]

  /** Message subscriptions owned by the root LiveView. */
  def subscriptions: Subscriptions[Msg]

  /** Browser event and JavaScript commands. */
  def client: Client

  /** Updates to already mounted live component instances. */
  def components: ComponentUpdates

  /** Dynamic lifecycle hooks for this root LiveView. */
  def hooks: RootHooks[Msg, Model]

/** Capabilities available while a routed root LiveView handles URL parameters.
  *
  * This phase runs after routed mount in both disconnected and connected lifecycles, and again for
  * each connected live patch. Params hooks run before the route's parameter handler and may halt
  * it. During the initial phase no component has rendered yet, so a component update has no
  * existing target.
  *
  * @tparam Msg
  *   the messages accepted by the LiveView
  * @tparam Model
  *   the LiveView's model type
  */
trait ParamsContext[Msg, Model] extends LifecycleContext:
  /** Live patch, live navigation, and redirect requests for the current callback. */
  def nav: Navigation

  /** Flash state owned by this LiveView lifecycle. */
  def flash: Flash

  /** Upload operations owned by the root LiveView. */
  def uploads: Uploads

  /** Stream operations owned by the root LiveView. */
  def streams: Streams

  /** Connected async tasks owned by the root LiveView. */
  def async: Async[Msg]

  /** Connected message subscriptions owned by the root LiveView. */
  def subscriptions: Subscriptions[Msg]

  /** Connected browser event and JavaScript commands. */
  def client: Client

  /** Updates to already mounted live component instances. */
  def components: ComponentUpdates

  /** Dynamic lifecycle hooks for this root LiveView. */
  def hooks: RootHooks[Msg, Model]

/** Capabilities available to a root after-render hook.
  *
  * After-render hooks run after the complete tree, including live components, has rendered and
  * before the connected diff is emitted. They run during both disconnected and connected renders,
  * cannot replace the model, and intentionally receive no resource or navigation capabilities.
  * Client operations are emitted only for a connected render.
  *
  * @tparam Msg
  *   the messages accepted by the LiveView
  * @tparam Model
  *   the rendered model type
  */
trait AfterRenderContext[Msg, Model] extends LifecycleContext:
  /** Browser event and JavaScript commands queued for this connected render. */
  def client: Client

  /** Dynamic lifecycle hooks for this root LiveView. */
  def hooks: RootHooks[Msg, Model]

/** Capabilities available while mounting a live component instance.
  *
  * A component mounts once when its `(component class, id)` identity first appears in a
  * disconnected or connected render. Upload names, stream names, and async keys are scoped to that
  * component instance; the connected runtime removes those resources when the component is
  * destroyed.
  *
  * @tparam Props
  *   the component's props type
  * @tparam Msg
  *   the component's message type
  * @tparam Model
  *   the component's model type
  */
trait ComponentMountContext[Props, Msg, Model] extends LifecycleContext:
  /** Flash state shared with the component's owning LiveView lifecycle. */
  def flash: Flash

  /** Upload configuration and operations scoped to this component instance. */
  def uploads: Uploads

  /** Stream operations scoped to this component instance. */
  def streams: Streams

  /** Connected async tasks scoped to this component instance. */
  def async: Async[Msg]

  /** Connected browser event and JavaScript commands. */
  def client: Client

  /** Dynamic lifecycle hooks scoped to this component instance. */
  def hooks: ComponentHooks[Props, Msg, Model]

/** Capabilities available while applying props to a live component.
  *
  * Update runs after initial mount and whenever parent props change or [[ComponentUpdates]] queues
  * explicit props. It receives the component's existing model; returning the next model preserves
  * component-local state across prop changes. Component-owned upload, stream, async, and hook
  * namespaces are the same ones used by its other phases.
  *
  * @tparam Props
  *   the component's props type
  * @tparam Msg
  *   the component's message type
  * @tparam Model
  *   the component's model type
  */
trait ComponentUpdateContext[Props, Msg, Model] extends LifecycleContext:
  /** Flash state shared with the component's owning LiveView lifecycle. */
  def flash: Flash

  /** Upload operations scoped to this component instance. */
  def uploads: Uploads

  /** Stream operations scoped to this component instance. */
  def streams: Streams

  /** Connected async tasks scoped to this component instance. */
  def async: Async[Msg]

  /** Connected browser event and JavaScript commands. */
  def client: Client

  /** Dynamic lifecycle hooks scoped to this component instance. */
  def hooks: ComponentHooks[Props, Msg, Model]

/** Capabilities available while a connected live component handles a message.
  *
  * Browser messages run the component's raw-event and typed-event hooks before its handler; async
  * completions run async hooks. The context is scoped to the exact component instance, while flash,
  * navigation, client events, and component-update routing operate through its owning socket.
  *
  * @tparam Props
  *   the component's current props type
  * @tparam Msg
  *   the component's message type
  * @tparam Model
  *   the component's model type
  */
trait ComponentMessageContext[Props, Msg, Model] extends LifecycleContext:
  /** Live patch, live navigation, and redirect requests for the current callback. */
  def nav: Navigation

  /** Flash state shared with the component's owning LiveView lifecycle. */
  def flash: Flash

  /** Upload operations scoped to this component instance. */
  def uploads: Uploads

  /** Stream operations scoped to this component instance. */
  def streams: Streams

  /** Async tasks scoped to this component instance. */
  def async: Async[Msg]

  /** Browser event and JavaScript commands. */
  def client: Client

  /** Updates to already mounted live component instances on the owning socket. */
  def components: ComponentUpdates

  /** Dynamic lifecycle hooks scoped to this component instance. */
  def hooks: ComponentHooks[Props, Msg, Model]

/** Capabilities available to a live component's after-render hooks.
  *
  * These hooks run after that component's subtree renders, in both disconnected and connected
  * lifecycles. They cannot replace the model and may only change subsequent dynamic hook
  * registration.
  *
  * @tparam Props
  *   the rendered props type
  * @tparam Msg
  *   the component's message type
  * @tparam Model
  *   the rendered model type
  */
trait ComponentAfterRenderContext[Props, Msg, Model] extends LifecycleContext:
  /** Dynamic lifecycle hooks scoped to this component instance. */
  def hooks: ComponentHooks[Props, Msg, Model]

/** Navigation available during root LiveView mount.
  *
  * A request is recorded and applied after the current lifecycle effect succeeds. At most one
  * navigation command may be requested in a lifecycle dispatch; requesting another fails its effect
  * with an `IllegalStateException` and leaves the first request in place. An unsafe target
  * beginning with `?` is resolved against the current path; other targets must be URLs accepted by
  * ZIO HTTP.
  *
  * During disconnected mount a successfully resolved command ends the initial render with an HTTP
  * redirect. During a connected lifecycle, live navigation mounts the destination LiveView while
  * preserving or replacing browser history, whereas redirect performs a full browser redirect.
  *
  * Methods accepting [[LiveLocation]] use a route-produced, encoded target. `Unsafe` variants
  * accept a raw href without route or encoding checks; an invalid href fails connected dispatch or
  * is ignored with a warning during initial lifecycle processing.
  */
trait MountNavigation:
  /** Live-navigates to `to` and pushes a browser history entry. */
  def pushNavigate(to: LiveLocation): LiveIO[Unit] = pushNavigateUnsafe(to.href)

  /** Live-navigates to an unchecked raw href and pushes a browser history entry. */
  def pushNavigateUnsafe(to: String): LiveIO[Unit]

  /** Live-navigates to `to` and replaces the current browser history entry. */
  def replaceNavigate(to: LiveLocation): LiveIO[Unit] = replaceNavigateUnsafe(to.href)

  /** Live-navigates to an unchecked raw href and replaces the current browser history entry. */
  def replaceNavigateUnsafe(to: String): LiveIO[Unit]

  /** Performs a full browser redirect to `to`. */
  def redirect(to: LiveLocation): LiveIO[Unit] = redirectUnsafe(to.href)

  /** Performs a full browser redirect to an unchecked raw href. */
  def redirectUnsafe(to: String): LiveIO[Unit]

/** Navigation available after mount, including same-LiveView URL patches.
  *
  * A live patch keeps the current LiveView process and model, updates the URL, and runs its params
  * lifecycle. Push and replace control whether a new browser history entry is created. Initial
  * params callbacks can also request a patch; disconnected initial patches become redirects, while
  * connected initial patches are followed before the first render.
  */
trait Navigation extends MountNavigation:
  /** Patches to `to` and pushes a browser history entry. */
  def pushPatch(to: LiveLocation): LiveIO[Unit] = pushPatchUnsafe(to.href)

  /** Patches to an unchecked raw href and pushes a browser history entry. */
  def pushPatchUnsafe(to: String): LiveIO[Unit]

  /** Patches to `to` and replaces the current browser history entry. */
  def replacePatch(to: LiveLocation): LiveIO[Unit] = replacePatchUnsafe(to.href)

  /** Patches to an unchecked raw href and replaces the current browser history entry. */
  def replacePatchUnsafe(to: String): LiveIO[Unit]

/** Mutable flash messages for the current LiveView lifecycle.
  *
  * A kind has at most one message, and `put` replaces it. Changes are visible to subsequent
  * rendering in the same lifecycle. During disconnected rendering flash is transferred through the
  * session or redirect cookie. During connected navigation only flash changes made in the
  * navigation-triggering lifecycle are carried to the destination; stale messages from earlier
  * callbacks are not forwarded. Components use the owning LiveView's flash store.
  */
trait Flash:
  /** Stores `message` under `kind`, replacing any existing message of that kind. */
  def put(kind: FlashKind, message: String): LiveIO[Unit]

  /** Removes `kind`; succeeds without change when it is absent. */
  def clear(kind: FlashKind): LiveIO[Unit]

  /** Removes every flash message. */
  def clearAll: LiveIO[Unit]

  /** Returns the current message for `kind`. */
  def get(kind: FlashKind): LiveIO[Option[String]]

  /** Returns an immutable snapshot of all current flash messages. */
  def snapshot: LiveIO[Map[FlashKind, String]]

/** Upload operations owned by a root LiveView or one live component instance.
  *
  * An upload definition's name identifies a registration within its owner; component names are
  * transparently scoped so equal names in different components do not collide. Disconnected and
  * connected mounts have independent registrations, so an upload used by both renders must be
  * allowed in both mounts.
  *
  * [[LiveUpload]] and [[LiveUploadEntry]] are immutable snapshots. They do not update as the
  * browser reports progress: retain the handle returned by each mutating operation or call [[get]]
  * for a fresh snapshot.
  *
  * Invalid mutating operations fail the returned effect with a [[LiveUploadOperationError]], such
  * as an active reconfiguration, mismatched or absent definition, inactive entry, incomplete entry,
  * or entries still in progress. [[get]] instead returns `None` for an absent or mismatched
  * definition. Consume and progress callback failures propagate; lifecycle cleanup failures are
  * logged and ignored. Bulk consumption is ordered but not transactional: entries consumed before a
  * later callback fails remain consumed.
  */
trait Uploads:
  /** Allows `definition`, replacing an idle registration with the same name.
    *
    * The effect fails with `LiveUploadOperationError.ActiveEntries` when that name still has active
    * entries.
    */
  def allow[R](definition: LiveUploadDef[R]): LiveIO[LiveUpload[R]]

  /** Disallows a matching definition and releases its tracked destination resources.
    *
    * Initialized incomplete writers are aborted and completed or prepared results are discarded.
    * Cleanup is best-effort: failures are logged and ignored. The effect fails when the name is not
    * allowed or names a different upload destination.
    */
  def disallow[R](definition: LiveUploadDef[R]): LiveIO[Unit]

  /** Returns the latest snapshot for a matching definition, or `None` if absent or mismatched. */
  def get[R](definition: LiveUploadDef[R]): LiveIO[Option[LiveUpload[R]]]

  /** Cancels an active entry and returns the new snapshot.
    *
    * Any tracked destination resource is released best-effort; cleanup failures are logged and
    * ignored.
    */
  def cancel[R](entry: LiveUploadEntry[R]): LiveIO[LiveUpload[R]]

  /** Visits one valid completed entry and applies the callback's consume or postpone decision.
    *
    * `Consume` removes the entry and transfers ownership of its completed result to the callback;
    * `Postpone` keeps it available. The returned pair contains the callback value and fresh upload
    * snapshot.
    */
  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): LiveIO[(A, LiveUpload[R])]

  /** Visits all valid completed entries in selection order and returns their callback values.
    *
    * Invalid entries are skipped. The effect fails before callbacks run while any valid entry is
    * still in progress. Each callback may independently consume or postpone its entry, and the
    * returned upload is the snapshot after all successful decisions.
    */
  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): LiveIO[(List[A], LiveUpload[R])]
end Uploads

/** DOM stream operations owned by a root LiveView or one live component instance.
  *
  * A stream definition's name identifies a stream within its owner; component streams are
  * transparently scoped by component instance. [[LiveStream]] is an immutable render handle, not
  * durable application state. Each operation updates the runtime and returns a replacement handle
  * containing the current snapshot and pending DOM operations; models should retain and render the
  * latest returned handle. Owner scoping does not alter rendered IDs: stream container names and
  * row DOM IDs must still be unique across the whole document, including across component
  * instances.
  *
  * Except for [[create]], operations require an existing stream with the same name. Effects fail
  * for empty or duplicate/missing names, invalid indices or limits, empty DOM ids, exceptions from
  * the definition's DOM-id function, or values incompatible with the stream's definition.
  */
trait Streams:
  /** Creates a stream with `items`; fails if its name already exists for this owner. */
  def create[A](
    definition: LiveStreamDef[A],
    items: Iterable[A]
  ): LiveIO[LiveStream[A]]

  /** Inserts all `items` into an existing stream at `at`, applying its configured limit. */
  def insertAll[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): LiveIO[LiveStream[A]]

  /** Resets the client container, discards the prior stream snapshot, and inserts `items`. */
  def reset[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): LiveIO[LiveStream[A]]

  /** Inserts or updates `item` by its generated DOM id.
    *
    * An existing DOM id is updated in place. If `updateOnly` is `true`, a missing id is ignored;
    * otherwise it is inserted at `at`. The stream's configured limit is applied afterward.
    */
  def insert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt = StreamAt.Last,
    updateOnly: Boolean = false
  ): LiveIO[LiveStream[A]]

  /** Deletes the entry whose DOM id is generated from `item`. */
  def delete[A](definition: LiveStreamDef[A], item: A): LiveIO[LiveStream[A]]

  /** Deletes the entry with the non-empty `domId` without evaluating the definition's id function.
    *
    * Use only a trusted ID belonging to this stream. The operation emits the supplied document ID
    * even when it is absent from the current server snapshot.
    */
  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): LiveIO[LiveStream[A]]
end Streams

/** Managed background tasks owned by a root LiveView or live component instance.
  *
  * Root and component owners have independent key namespaces. Starting an existing key silently
  * interrupts and replaces its previous task; replacement does not emit a cancellation message.
  * Completion, task failure, and explicit cancellation are represented as [[LiveAsyncResult]] and
  * mapped to an owner message. Async hooks run before that message is handled.
  *
  * If the result mapper throws, the framework logs the failure, invokes async hooks with a failed
  * event, and does not invoke the message handler. Removing a component or closing its socket
  * interrupts its outstanding tasks without delivery. During disconnected rendering calls are
  * no-ops and tasks are not evaluated.
  *
  * @tparam Msg
  *   the owning LiveView or component message type
  */
trait Async[Msg]:
  /** Starts `task` under `key`, replacing any task with the same key for this owner.
    *
    * `toMsg` receives success, failure, or cancellation and produces the message delivered to the
    * owner.
    */
  def start[A](
    key: AsyncKey[A]
  )(
    task: Task[A]
  )(
    toMsg: LiveAsyncResult[A] => Msg
  ): LiveIO[Unit]

  /** Cancels `key` and delivers a mapped cancellation result, or succeeds unchanged if absent. */
  def cancel[A](key: AsyncKey[A], reason: Option[String] = None): LiveIO[Unit]

/** Managed message streams owned by a connected root LiveView.
  *
  * Emitted messages enter the info-hook phase and then the LiveView message handler. Changing the
  * registered set switches the running merged stream: all registered streams are interrupted and
  * the remaining set is resubscribed. Registrations live for the socket lifecycle. During
  * disconnected rendering operations are no-ops and streams are not run.
  *
  * @tparam Msg
  *   the root LiveView message type emitted by subscriptions
  */
trait Subscriptions[Msg]:
  /** Starts `stream` under `key`.
    *
    * The effect fails with an `IllegalArgumentException` for an empty key or one that is already
    * registered.
    */
  def start(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit]

  /** Starts or replaces `key` with `stream`; an empty key fails the returned effect. */
  def replace(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit]

  /** Cancels `key`; succeeds unchanged if it is absent. */
  def cancel(key: SubscriptionKey): LiveIO[Unit]

/** Commands queued for execution by the connected browser.
  *
  * Calls retain order and are attached to the next rendered connected diff; calls from an
  * after-render hook are included in that render's diff. During disconnected rendering no command
  * is emitted, although `push` still encodes its payload and can fail.
  */
trait Client:
  /** Pushes a typed event and JSON-encoded payload to client hooks.
    *
    * The effect fails with an `IllegalArgumentException` if the payload cannot be encoded.
    */
  def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): LiveIO[Unit]

  /** Sends a composed [[JSCommands.JSCommand]] to the browser's LiveView client for execution. */
  def exec[Msg](js: JSCommands.JSCommand[Msg]): LiveIO[Unit]

/** Queues props for an already mounted live component identity.
  *
  * A target is identified by `(component runtime class, id)`. If the target remains in the next
  * parent render, the update is consumed there and invokes the component's update phase with its
  * existing model, preserving component-local state unless that phase changes it. If several
  * updates are queued for one target before a render, only the last props are applied.
  *
  * A target that does not exist when `sendUpdate` is called is ignored with a warning; this
  * includes calls before the initial component render or after removal. The returned effect still
  * succeeds.
  */
trait ComponentUpdates:
  /** Queues `props` for the exact component class and id represented by `instance`. */
  def sendUpdate[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model],
    props: Props
  ): LiveIO[Unit]

  /** Queues typed `props` for component type `C` and `id`.
    *
    * A runtime `ClassTag` for `C` supplies the component class used in the target identity.
    */
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): LiveIO[Unit]

/** Dynamic lifecycle-hook registries for one root LiveView lifecycle.
  *
  * Static hooks declared by `LiveView.hooks` are installed first. Dynamic `attach` calls append to
  * their phase in call order and remain installed for this disconnected or connected lifecycle; the
  * two lifecycles have independent registries. An id must be unique among dynamic hooks in the same
  * phase. Duplicate attachment fails with an `IllegalArgumentException`. Detaching an absent id
  * succeeds and cannot remove static hooks.
  *
  * Each dispatch snapshots its phase registry. Attachments and detachments made while hooks are
  * running therefore affect the next dispatch, not the current one. Hook effects run sequentially;
  * a failure aborts the current lifecycle and prevents later hooks and the handler from running.
  *
  * @tparam Msg
  *   the root LiveView message type
  * @tparam Model
  *   the root LiveView model type threaded through hooks
  */
trait RootHooks[Msg, Model]:
  /** Raw browser-event hooks. */
  def rawEvent: RootRawEventHooks[Msg, Model]

  /** Decoded root browser-message hooks. */
  def event: RootEventHooks[Msg, Model]

  /** Routed URL-parameter hooks. */
  def params: RootParamsHooks[Msg, Model]

  /** Non-browser server-message hooks, including subscription messages. */
  def info: RootInfoHooks[Msg, Model]

  /** Managed async-completion hooks. */
  def async: RootAsyncHooks[Msg, Model]

  /** Post-render hooks. */
  def afterRender: RootAfterRenderHooks[Msg, Model]

/** Dynamic hooks that intercept every ordinary browser event before binding lookup and component
  * routing.
  *
  * Hooks receive the model produced by the preceding hook. `Continue` runs the next raw hook and
  * then normal event processing. `Halt` skips all remaining event processing, renders its model,
  * and may return a JSON reply to the originating browser event.
  */
trait RootRawEventHooks[Msg, Model]:
  /** Appends `hook` to the raw-event phase under `hookId`. */
  def attach(
    hookId: String
  )(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): LiveIO[Unit]

  /** Removes the dynamic raw-event hook named `hookId`, if present. */
  def detach(hookId: String): LiveIO[Unit]

/** Dynamic hooks that intercept a browser binding after it produces a root `Msg`.
  *
  * Hooks run in registration order and thread their models. `Halt` skips later hooks and
  * `LiveView.handleMessage`, renders its model, and may return a JSON reply to the originating
  * browser event. Server and async messages use their dedicated hook phases instead.
  */
trait RootEventHooks[Msg, Model]:
  /** Appends `hook` to the typed browser-event phase under `id`. */
  def attach(
    id: String
  )(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): LiveIO[Unit]

  /** Removes the dynamic typed browser-event hook named `id`, if present. */
  def detach(id: String): LiveIO[Unit]

/** Dynamic hooks that run before a routed LiveView handles decoded parameters.
  *
  * Hooks run for initial parameters in each lifecycle and for connected live patches. They thread
  * their models in registration order; `Halt` skips later hooks and the route's parameter handler.
  */
trait RootParamsHooks[Msg, Model]:
  /** Appends `hook` to the params phase under `id`. */
  def attach(
    id: String
  )(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]

  /** Removes the dynamic params hook named `id`, if present. */
  def detach(id: String): LiveIO[Unit]

/** Dynamic hooks that run before non-browser server messages are handled.
  *
  * This includes messages emitted by [[Subscriptions]]. Hooks thread their models in registration
  * order; `Halt` skips later hooks and `LiveView.handleMessage`, but its model is still rendered.
  */
trait RootInfoHooks[Msg, Model]:
  /** Appends `hook` to the info phase under `id`. */
  def attach(
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]

  /** Removes the dynamic info hook named `id`, if present. */
  def detach(id: String): LiveIO[Unit]

/** Dynamic hooks that run when a root-owned managed async task completes.
  *
  * A successful event contains the mapped `Msg`; failures retain their cause and cancellations
  * retain their reason. Hooks run before message handling and thread their models in registration
  * order. `Halt` skips later hooks and the mapped message handler, but its model is still rendered.
  */
trait RootAsyncHooks[Msg, Model]:
  /** Appends `hook` to the async-completion phase under `id`. */
  def attach(
    id: String
  )(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]

  /** Removes the dynamic async-completion hook named `id`, if present. */
  def detach(id: String): LiveIO[Unit]

/** Dynamic hooks that run after the root tree has rendered.
  *
  * Every hook receives the same rendered model and runs in registration order. After-render hooks
  * cannot halt or replace the model. A failed hook prevents later hooks and aborts the render.
  */
trait RootAfterRenderHooks[Msg, Model]:
  /** Appends `hook` to the root after-render phase under `id`. */
  def attach(
    id: String
  )(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
  ): LiveIO[Unit]

  /** Removes the dynamic root after-render hook named `id`, if present. */
  def detach(id: String): LiveIO[Unit]

/** Dynamic lifecycle-hook registries for one live component instance.
  *
  * These registries follow the ordering, duplicate-id, dispatch-snapshot, detachment, and failure
  * contracts described by [[RootHooks]], but are scoped to the component's `(class, id)` identity
  * and current lifecycle. Static `ComponentLiveHooks` run before dynamically attached hooks. The
  * registry survives that instance's rerenders and is discarded when the instance is removed.
  *
  * @tparam Props
  *   the component's props type
  * @tparam Msg
  *   the component's message type
  * @tparam Model
  *   the component's model type threaded through hooks
  */
trait ComponentHooks[Props, Msg, Model]:
  /** Raw browser-event hooks for this component instance. */
  def rawEvent: ComponentRawEventHooks[Props, Msg, Model]

  /** Decoded browser-message hooks for this component instance. */
  def event: ComponentEventHooks[Props, Msg, Model]

  /** Managed async-completion hooks for this component instance. */
  def async: ComponentAsyncHooks[Props, Msg, Model]

  /** Post-render hooks for this component instance. */
  def afterRender: ComponentAfterRenderHooks[Props, Msg, Model]

/** Dynamic hooks that intercept a browser event routed to one component before its typed-event
  * hooks and message handler.
  *
  * Hooks receive current props and thread their models in registration order. `Halt` skips the
  * remaining component event phases and handler, renders its model, and may return a JSON reply to
  * the originating browser event.
  */
trait ComponentRawEventHooks[Props, Msg, Model]:
  /** Appends `hook` to this component's raw-event phase under `hookId`. */
  def attach(
    hookId: String
  )(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveIO[Unit]

  /** Removes the dynamic component raw-event hook named `hookId`, if present. */
  def detach(hookId: String): LiveIO[Unit]

/** Dynamic hooks that intercept a component browser binding after it produces a component `Msg`.
  *
  * Hooks receive current props and thread their models in registration order. `Halt` skips later
  * hooks and `LiveComponent.handleMessage`, renders its model, and may return a JSON reply.
  */
trait ComponentEventHooks[Props, Msg, Model]:
  /** Appends `hook` to this component's typed browser-event phase under `id`. */
  def attach(
    id: String
  )(
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveIO[Unit]

  /** Removes the dynamic component browser-event hook named `id`, if present. */
  def detach(id: String): LiveIO[Unit]

/** Dynamic hooks that run when a task owned by this component instance completes.
  *
  * Hooks receive current props and the mapped async event, and thread their models in registration
  * order. `Halt` skips later hooks and the component message handler, but its model is still
  * rendered.
  */
trait ComponentAsyncHooks[Props, Msg, Model]:
  /** Appends `hook` to this component's async-completion phase under `id`. */
  def attach(
    id: String
  )(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveIO[Unit]

  /** Removes the dynamic component async-completion hook named `id`, if present. */
  def detach(id: String): LiveIO[Unit]

/** Dynamic hooks that run after one live component subtree has rendered.
  *
  * Every hook receives the phase's props and rendered model and runs in registration order. Hooks
  * cannot halt or replace the model. A failed hook prevents later hooks and aborts the parent
  * render.
  */
trait ComponentAfterRenderHooks[Props, Msg, Model]:
  /** Appends `hook` to this component's after-render phase under `id`. */
  def attach(
    id: String
  )(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit]
  ): LiveIO[Unit]

  /** Removes the dynamic component after-render hook named `id`, if present. */
  def detach(id: String): LiveIO[Unit]

final private[scalive] case class LiveContext(
  staticChanged: Boolean,
  connected: Boolean = false,
  connectParams: Map[String, Json] = Map.empty,
  csrfToken: Option[String] = None,
  uploads: UploadRuntime = UploadRuntime.Disabled,
  streams: StreamRuntime = StreamRuntime.Disabled,
  clientEvents: ClientEventRuntime = ClientEventRuntime.Disabled,
  navigation: LiveNavigationRuntime = LiveNavigationRuntime.Disabled,
  components: ComponentUpdateRuntime = ComponentUpdateRuntime.Disabled,
  nestedLiveViews: NestedLiveViewRuntime = NestedLiveViewRuntime.Disabled,
  flash: FlashRuntime = FlashRuntime.Disabled,
  async: LiveAsyncRuntime = LiveAsyncRuntime.Disabled,
  subscriptions: SubscriptionRuntime[Any] = SubscriptionRuntime.Disabled,
  hooks: LiveHookRuntime = LiveHookRuntime.Disabled,
  runtimeTrace: RuntimeTrace = RuntimeTrace.Disabled):

  def mountContext[Msg, Model]: MountContext[Msg, Model] =
    new LiveContext.RuntimeMountContext(this)

  def messageContext[Msg, Model]: MessageContext[Msg, Model] =
    new LiveContext.RuntimeMessageContext(this)

  def paramsContext[Msg, Model]: ParamsContext[Msg, Model] =
    new LiveContext.RuntimeParamsContext(this)

  def afterRenderContext[Msg, Model]: AfterRenderContext[Msg, Model] =
    new LiveContext.RuntimeAfterRenderContext(this)

  def componentMountContext[Props, Msg, Model]: ComponentMountContext[Props, Msg, Model] =
    new LiveContext.RuntimeComponentMountContext(this)

  def componentUpdateContext[Props, Msg, Model]: ComponentUpdateContext[Props, Msg, Model] =
    new LiveContext.RuntimeComponentUpdateContext(this)

  def componentMessageContext[Props, Msg, Model]: ComponentMessageContext[Props, Msg, Model] =
    new LiveContext.RuntimeComponentMessageContext(this)

  def componentAfterRenderContext[Props, Msg, Model]
    : ComponentAfterRenderContext[Props, Msg, Model] =
    new LiveContext.RuntimeComponentAfterRenderContext(this)
end LiveContext

private[scalive] object LiveContext:
  private class RuntimeMountNavigation(runtime: LiveContext) extends MountNavigation:
    def pushNavigateUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.PushNavigate(to))

    def replaceNavigateUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.ReplaceNavigate(to))

    def redirectUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.Redirect(to))

  final private class RuntimeNavigation(runtime: LiveContext)
      extends RuntimeMountNavigation(runtime)
      with Navigation:
    def pushPatchUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.PushPatch(to))

    def replacePatchUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.ReplacePatch(to))

  final private class RuntimeFlash(runtime: LiveContext) extends Flash:
    def put(kind: FlashKind, message: String): LiveIO[Unit] =
      runtime.flash.put(kind.value, message)
    def clear(kind: FlashKind): LiveIO[Unit]         = runtime.flash.clear(kind.value)
    def clearAll: LiveIO[Unit]                       = runtime.flash.clearAll
    def get(kind: FlashKind): LiveIO[Option[String]] = runtime.flash.get(kind.value)
    def snapshot: LiveIO[Map[FlashKind, String]]     =
      runtime.flash.snapshot.map(_.map { case (kind, message) => FlashKind(kind) -> message })

  final private class RuntimeUploads(runtime: LiveContext) extends Uploads:
    def allow[R](definition: LiveUploadDef[R]): LiveIO[LiveUpload[R]] =
      runtime.uploads.allow(definition)
    def disallow[R](definition: LiveUploadDef[R]): LiveIO[Unit] =
      runtime.uploads.disallow(definition)
    def get[R](definition: LiveUploadDef[R]): LiveIO[Option[LiveUpload[R]]] =
      runtime.uploads.get(definition)
    def cancel[R](entry: LiveUploadEntry[R]): LiveIO[LiveUpload[R]] =
      runtime.uploads.cancel(entry)
    def consume[R, A](
      entry: LiveUploadEntry[R]
    )(
      callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
    ): LiveIO[(A, LiveUpload[R])] = runtime.uploads.consume(entry)(callback)
    def consumeCompleted[R, A](
      definition: LiveUploadDef[R]
    )(
      callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
    ): LiveIO[(List[A], LiveUpload[R])] = runtime.uploads.consumeCompleted(definition)(callback)

  final private class RuntimeStreams(runtime: LiveContext) extends Streams:
    def create[A](
      definition: LiveStreamDef[A],
      items: Iterable[A]
    ): LiveIO[LiveStream[A]] =
      runtime.streams.create(definition, items)

    def insertAll[A](
      definition: LiveStreamDef[A],
      items: Iterable[A],
      at: StreamAt
    ): LiveIO[LiveStream[A]] =
      runtime.streams.insertAll(definition, items, at)

    def reset[A](
      definition: LiveStreamDef[A],
      items: Iterable[A],
      at: StreamAt
    ): LiveIO[LiveStream[A]] =
      runtime.streams.reset(definition, items, at)

    def insert[A](
      definition: LiveStreamDef[A],
      item: A,
      at: StreamAt,
      updateOnly: Boolean
    ): LiveIO[LiveStream[A]] =
      runtime.streams.insert(definition, item, at, updateOnly)

    def delete[A](definition: LiveStreamDef[A], item: A): LiveIO[LiveStream[A]] =
      runtime.streams.delete(definition, item)

    def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): LiveIO[LiveStream[A]] =
      runtime.streams.deleteByDomId(definition, domId)
  end RuntimeStreams

  final private class RuntimeAsync[Msg](runtime: LiveContext) extends Async[Msg]:
    def start[A](
      key: AsyncKey[A]
    )(
      task: Task[A]
    )(
      toMsg: LiveAsyncResult[A] => Msg
    ): LiveIO[Unit] =
      runtime.async.start(key.value)(task)(toMsg)

    def cancel[A](key: AsyncKey[A], reason: Option[String]): LiveIO[Unit] =
      runtime.async.cancel(key.value, reason)

  final private class RuntimeSubscriptions[Msg](runtime: LiveContext) extends Subscriptions[Msg]:
    private def subscriptions = runtime.subscriptions.asInstanceOf[SubscriptionRuntime[Msg]]

    def start(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit] =
      subscriptions.start(key.value)(stream)

    def replace(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit] =
      subscriptions.replace(key.value)(stream)

    def cancel(key: SubscriptionKey): LiveIO[Unit] =
      subscriptions.cancel(key.value)

  final private case class PushJsPayload(cmd: String) derives JsonEncoder
  private val PushJsEvent = ServerToBrowserEvent[PushJsPayload]("js:exec")

  final private class RuntimeClient(runtime: LiveContext) extends Client:
    def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): LiveIO[Unit] =
      payload.toJsonAST match
        case Right(encoded) => runtime.clientEvents.push(event.value, encoded)
        case Left(error)    =>
          ZIO.fail(
            new IllegalArgumentException(
              s"Could not encode client event '${event.value}': $error"
            )
          )

    def exec[Msg](js: JSCommands.JSCommand[Msg]): LiveIO[Unit] =
      import JSCommands.JSCommand.given
      push(PushJsEvent, PushJsPayload(js.toJson))

  final private class RuntimeComponents(runtime: LiveContext) extends ComponentUpdates:
    def sendUpdate[Props, Msg, Model](
      instance: LiveComponentInstance[Props, Msg, Model],
      props: Props
    ): LiveIO[Unit] =
      runtime.components.sendUpdate(instance.component.getClass, instance.id, props)

    def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
      id: String,
      props: LiveComponent.PropsOf[C]
    ): LiveIO[Unit] =
      runtime.components.sendUpdate(summon[ClassTag[C]].runtimeClass, id, props)

  private[scalive] trait RuntimeContextBase extends LifecycleContext:
    protected def runtime: LiveContext
    def connected: Boolean                                            = runtime.connected
    def staticChanged: Boolean                                        = runtime.staticChanged
    def connectParams: Map[String, Json]                              = runtime.connectParams
    override private[scalive] def runtimeTraceSession: Option[String] = runtime.runtimeTrace.session

  final private[scalive] class RuntimeMountContext[Msg, Model](protected val runtime: LiveContext)
      extends MountContext[Msg, Model]
      with RuntimeContextBase:
    val nav: MountNavigation              = RuntimeMountNavigation(runtime)
    val flash: Flash                      = RuntimeFlash(runtime)
    val uploads: Uploads                  = RuntimeUploads(runtime)
    val streams: Streams                  = RuntimeStreams(runtime)
    val async: Async[Msg]                 = RuntimeAsync(runtime)
    val subscriptions: Subscriptions[Msg] = RuntimeSubscriptions(runtime)
    val client: Client                    = RuntimeClient(runtime)
    val hooks: RootHooks[Msg, Model]      = RuntimeRootHooks(runtime)

  final private[scalive] class RuntimeMessageContext[Msg, Model](protected val runtime: LiveContext)
      extends MessageContext[Msg, Model]
      with RuntimeContextBase:
    val nav: Navigation                   = RuntimeNavigation(runtime)
    val flash: Flash                      = RuntimeFlash(runtime)
    val uploads: Uploads                  = RuntimeUploads(runtime)
    val streams: Streams                  = RuntimeStreams(runtime)
    val async: Async[Msg]                 = RuntimeAsync(runtime)
    val subscriptions: Subscriptions[Msg] = RuntimeSubscriptions(runtime)
    val client: Client                    = RuntimeClient(runtime)
    val components: ComponentUpdates      = RuntimeComponents(runtime)
    val hooks: RootHooks[Msg, Model]      = RuntimeRootHooks(runtime)

  final private[scalive] class RuntimeParamsContext[Msg, Model](protected val runtime: LiveContext)
      extends ParamsContext[Msg, Model]
      with RuntimeContextBase:
    val nav: Navigation                   = RuntimeNavigation(runtime)
    val flash: Flash                      = RuntimeFlash(runtime)
    val uploads: Uploads                  = RuntimeUploads(runtime)
    val streams: Streams                  = RuntimeStreams(runtime)
    val async: Async[Msg]                 = RuntimeAsync(runtime)
    val subscriptions: Subscriptions[Msg] = RuntimeSubscriptions(runtime)
    val client: Client                    = RuntimeClient(runtime)
    val components: ComponentUpdates      = RuntimeComponents(runtime)
    val hooks: RootHooks[Msg, Model]      = RuntimeRootHooks(runtime)

  final private[scalive] class RuntimeAfterRenderContext[Msg, Model](
    protected val runtime: LiveContext)
      extends AfterRenderContext[Msg, Model]
      with RuntimeContextBase:
    val client: Client               = RuntimeClient(runtime)
    val hooks: RootHooks[Msg, Model] = RuntimeRootHooks(runtime)

  final private[scalive] class RuntimeComponentMountContext[Props, Msg, Model](
    protected val runtime: LiveContext)
      extends ComponentMountContext[Props, Msg, Model]
      with RuntimeContextBase:
    val flash: Flash                             = RuntimeFlash(runtime)
    val uploads: Uploads                         = RuntimeUploads(runtime)
    val streams: Streams                         = RuntimeStreams(runtime)
    val async: Async[Msg]                        = RuntimeAsync(runtime)
    val client: Client                           = RuntimeClient(runtime)
    val hooks: ComponentHooks[Props, Msg, Model] = RuntimeComponentHooks(runtime)

  final private[scalive] class RuntimeComponentUpdateContext[Props, Msg, Model](
    protected val runtime: LiveContext)
      extends ComponentUpdateContext[Props, Msg, Model]
      with RuntimeContextBase:
    val flash: Flash                             = RuntimeFlash(runtime)
    val uploads: Uploads                         = RuntimeUploads(runtime)
    val streams: Streams                         = RuntimeStreams(runtime)
    val async: Async[Msg]                        = RuntimeAsync(runtime)
    val client: Client                           = RuntimeClient(runtime)
    val hooks: ComponentHooks[Props, Msg, Model] = RuntimeComponentHooks(runtime)

  final private[scalive] class RuntimeComponentMessageContext[Props, Msg, Model](
    protected val runtime: LiveContext)
      extends ComponentMessageContext[Props, Msg, Model]
      with RuntimeContextBase:
    val nav: Navigation                          = RuntimeNavigation(runtime)
    val flash: Flash                             = RuntimeFlash(runtime)
    val uploads: Uploads                         = RuntimeUploads(runtime)
    val streams: Streams                         = RuntimeStreams(runtime)
    val async: Async[Msg]                        = RuntimeAsync(runtime)
    val client: Client                           = RuntimeClient(runtime)
    val components: ComponentUpdates             = RuntimeComponents(runtime)
    val hooks: ComponentHooks[Props, Msg, Model] = RuntimeComponentHooks(runtime)

  final private[scalive] class RuntimeComponentAfterRenderContext[Props, Msg, Model](
    protected val runtime: LiveContext)
      extends ComponentAfterRenderContext[Props, Msg, Model]
      with RuntimeContextBase:
    val hooks: ComponentHooks[Props, Msg, Model] = RuntimeComponentHooks(runtime)

  final private class RuntimeRootHooks[Msg, Model](runtime: LiveContext)
      extends RootHooks[Msg, Model]:
    val rawEvent: RootRawEventHooks[Msg, Model]       = RuntimeRootRawEventHooks(runtime)
    val event: RootEventHooks[Msg, Model]             = RuntimeRootEventHooks(runtime)
    val params: RootParamsHooks[Msg, Model]           = RuntimeRootParamsHooks(runtime)
    val info: RootInfoHooks[Msg, Model]               = RuntimeRootInfoHooks(runtime)
    val async: RootAsyncHooks[Msg, Model]             = RuntimeRootAsyncHooks(runtime)
    val afterRender: RootAfterRenderHooks[Msg, Model] = RuntimeRootAfterRenderHooks(runtime)

  final private class RuntimeRootRawEventHooks[Msg, Model](runtime: LiveContext)
      extends RootRawEventHooks[Msg, Model]:
    def attach(
      hookId: String
    )(
      hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
    ): LiveIO[Unit] = runtime.hooks.attachRawEvent(hookId)(hook)
    def detach(hookId: String): LiveIO[Unit] = runtime.hooks.detachRawEvent(hookId)

  final private class RuntimeRootEventHooks[Msg, Model](runtime: LiveContext)
      extends RootEventHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachEvent(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachEvent(id)

  final private class RuntimeRootParamsHooks[Msg, Model](runtime: LiveContext)
      extends RootParamsHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): LiveIO[Unit] = runtime.hooks.attachParams(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachParams(id)

  final private class RuntimeRootInfoHooks[Msg, Model](runtime: LiveContext)
      extends RootInfoHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): LiveIO[Unit] = runtime.hooks.attachInfo(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachInfo(id)

  final private class RuntimeRootAsyncHooks[Msg, Model](runtime: LiveContext)
      extends RootAsyncHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
        LiveHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachAsync(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachAsync(id)

  final private class RuntimeRootAfterRenderHooks[Msg, Model](runtime: LiveContext)
      extends RootAfterRenderHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
    ): LiveIO[Unit] = runtime.hooks.attachAfterRender(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachAfterRender(id)

  final private class RuntimeComponentHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentHooks[Props, Msg, Model]:
    val rawEvent: ComponentRawEventHooks[Props, Msg, Model] =
      RuntimeComponentRawEventHooks(runtime)
    val event: ComponentEventHooks[Props, Msg, Model] = RuntimeComponentEventHooks(runtime)
    val async: ComponentAsyncHooks[Props, Msg, Model] = RuntimeComponentAsyncHooks(runtime)
    val afterRender: ComponentAfterRenderHooks[Props, Msg, Model] =
      RuntimeComponentAfterRenderHooks(runtime)

  final private class RuntimeComponentRawEventHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentRawEventHooks[Props, Msg, Model]:
    def attach(
      hookId: String
    )(
      hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachComponentRawEvent(hookId)(hook)
    def detach(hookId: String): LiveIO[Unit] = runtime.hooks.detachRawEvent(hookId)

  final private class RuntimeComponentEventHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentEventHooks[Props, Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachComponentEvent(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachEvent(id)

  final private class RuntimeComponentAsyncHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentAsyncHooks[Props, Msg, Model]:
    def attach(
      id: String
    )(
      hook: (
        Props,
        Model,
        LiveAsyncEvent[Msg],
        ComponentMessageContext[Props, Msg, Model]
      ) => LiveIO[
        LiveHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachComponentAsync(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachAsync(id)

  final private class RuntimeComponentAfterRenderHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentAfterRenderHooks[Props, Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit]
    ): LiveIO[Unit] = runtime.hooks.attachComponentAfterRender(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachAfterRender(id)
end LiveContext
