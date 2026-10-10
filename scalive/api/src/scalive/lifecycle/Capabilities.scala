package scalive

import scala.reflect.ClassTag

import zio.Task
import zio.UIO
import zio.json.JsonEncoder
import zio.stream.ZStream

import scalive.streams.*
import scalive.upload.*

/** Leaves the current LiveView for another page.
  *
  * Build destinations with a route's `location` so they stay in sync with the router. Each `Unsafe`
  * variant takes a raw URL string instead. See [patch or
  * navigate](https://scalive.dev/guides/routes-and-navigation#choose-patch-or-navigate).
  */
trait MountNavigation:
  /** Mounts the LiveView at `to` and adds a browser history entry.
    *
    * Within the same named live session and root layout, the browser keeps the document and the
    * WebSocket; otherwise it loads `to` with a full page request.
    */
  def pushNavigate(to: LiveLocation): Task[Unit] = pushNavigateUnsafe(to.href)

  /** Like [[pushNavigate]], for a raw URL that the route types do not check. */
  def pushNavigateUnsafe(to: String): Task[Unit]

  /** Mounts the LiveView at `to`, replacing the current browser history entry. */
  def replaceNavigate(to: LiveLocation): Task[Unit] = replaceNavigateUnsafe(to.href)

  /** Like [[replaceNavigate]], for a raw URL that the route types do not check. */
  def replaceNavigateUnsafe(to: String): Task[Unit]

  /** Ends this LiveView and loads `to` with a full page request.
    *
    * During the disconnected render, the HTTP response becomes a redirect. Use it when the
    * destination is not a LiveView or must start a new document.
    */
  def redirect(to: LiveLocation): Task[Unit] = redirectUnsafe(to.href)

  /** Like [[redirect]], for a raw URL that the route types do not check. */
  def redirectUnsafe(to: String): Task[Unit]

/** Changes the URL or leaves the current LiveView while it handles a message.
  *
  * Patches keep the LiveView mounted and call [[LiveView.Routed.handleParams]] with the new URL;
  * navigation mounts another LiveView. See [patch or
  * navigate](https://scalive.dev/guides/routes-and-navigation#choose-patch-or-navigate).
  */
trait Navigation extends MountNavigation:
  /** Changes the URL to `to`, a location of this LiveView's route, and adds a history entry.
    *
    * The LiveView stays mounted and receives the new parameters in
    * [[LiveView.Routed.handleParams]]. Use it for filters, pagination, and tabs.
    */
  def pushPatch(to: LiveLocation): Task[Unit] = pushPatchUnsafe(to.href)

  /** Like [[pushPatch]], for a raw URL that the route types do not check. */
  def pushPatchUnsafe(to: String): Task[Unit]

  /** Changes the URL to `to`, replacing the current history entry, without remounting. */
  def replacePatch(to: LiveLocation): Task[Unit] = replacePatchUnsafe(to.href)

  /** Like [[replacePatch]], for a raw URL that the route types do not check. */
  def replacePatchUnsafe(to: String): Task[Unit]

/** Short messages shown to the visitor, keyed by [[FlashKind]].
  *
  * Render one kind with `flash(kind)(message => ...)`. Use flash for brief feedback such as a save
  * result, and keep state that must outlive the page in the model or a service. See
  * [flash](https://scalive.dev/guides/flash-titles-and-connection-status#render-keyed-flash).
  */
trait Flash:
  /** Sets the message for `kind`, replacing any previous one. */
  def put(kind: FlashKind, message: String): Task[Unit]

  /** Removes the message for `kind`. */
  def clear(kind: FlashKind): Task[Unit]

  /** Removes every message. */
  def clearAll: Task[Unit]

  /** Returns the current message for `kind`, if any. */
  def get(kind: FlashKind): Task[Option[String]]

  /** Returns every current message. */
  def snapshot: Task[Map[FlashKind, String]]

trait Uploads:
  def allow[R](definition: LiveUploadDef[R]): Task[LiveUpload[R]]
  def disallow[R](definition: LiveUploadDef[R]): Task[Unit]
  def get[R](definition: LiveUploadDef[R]): Task[Option[LiveUpload[R]]]
  def cancel[R](entry: LiveUploadEntry[R]): Task[LiveUpload[R]]
  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(A, LiveUpload[R])]
  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(List[A], LiveUpload[R])]

trait Streams:
  def create[A, Id](definition: LiveStreamDef[A, Id], items: Iterable[A]): Task[LiveStream[A]]
  def insertAll[A, Id](
    definition: LiveStreamDef[A, Id],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): Task[LiveStream[A]]
  def reset[A, Id](
    definition: LiveStreamDef[A, Id],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): Task[LiveStream[A]]
  def insert[A, Id](
    definition: LiveStreamDef[A, Id],
    item: A,
    at: StreamAt = StreamAt.Last,
    updateOnly: Boolean = false
  ): Task[LiveStream[A]]
  def delete[A, Id](definition: LiveStreamDef[A, Id], id: Id): Task[LiveStream[A]]
  def deleteByDomId[A, Id](definition: LiveStreamDef[A, Id], domId: String): Task[LiveStream[A]]

/** Runs finite background tasks owned by a connected LiveView or component.
  *
  * Each task's result returns as a message, so the model stays the only input to rendering. Scalive
  * interrupts the owner's tasks when it closes. See [asynchronous
  * work](https://scalive.dev/guides/async-work).
  */
trait Async[Msg]:
  /** Runs `task` in the background and sends `toMsg(result)` to the owner when it completes.
    *
    * The task starts once the current mount or message commits. Starting a `key` that is already
    * running interrupts the previous task, which then delivers nothing; use this to refresh or to
    * follow the latest query. Task failures arrive as [[LiveAsyncResult.Failed]] rather than
    * failing the lifecycle.
    */
  def start[A](key: AsyncKey[A])(task: Task[A])(toMsg: LiveAsyncResult[A] => Msg): Task[Unit]

  /** Interrupts the task running under `key` and sends its [[LiveAsyncResult.Cancelled]] message.
    *
    * Cancelling a key with no running task does nothing.
    */
  def cancel[A](key: AsyncKey[A], reason: Option[String] = None): Task[Unit]

/** Runs message streams owned by a connected LiveView or component.
  *
  * Each value the stream emits arrives in `handleMessage`. Scalive stops the owner's streams when
  * it closes; a reconnect mounts again and must start them again. See
  * [subscriptions](https://scalive.dev/guides/subscriptions).
  */
trait Subscriptions[Msg]:
  /** Runs `stream` under `key` and sends each value it emits to the owner.
    *
    * The stream starts once the current mount or message commits. Starting a key that is already
    * registered is treated as a bug and crashes the lifecycle; use [[replace]] when restarting is
    * intended. `delivery` chooses whether every value is delivered or only the latest.
    */
  def start(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): Task[Unit]

  /** Runs `stream` under `key`, interrupting the stream already registered under it, if any. */
  def replace(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): Task[Unit]

  /** Stops the stream registered under `key`. Cancelling an absent key does nothing. */
  def cancel(key: SubscriptionKey): Task[Unit]

/** Acquisition and finalization owned by one connected `LiveView` lifecycle.
  *
  * This capability is exposed through [[RootMountConnected.resources]] and route/session connected
  * resource initializers. Applications must not retain it for later acquisition.
  */
trait ConnectedResources:
  /** Acquires a resource and registers its finalizer with the current connected lifecycle.
    *
    * Acquisition and finalization run uninterruptibly. When `acquire` succeeds, `release` is
    * registered before the acquired value is returned and runs exactly once when the lifecycle
    * closes. If `acquire` fails, `release` does not run. Calls made after lifecycle closure fail
    * before acquisition starts.
    *
    * Keep both effects short and bounded because either can delay lifecycle shutdown. Recover any
    * expected cleanup failure inside the `UIO` finalizer.
    */
  def acquireRelease[A](acquire: Task[A])(release: A => UIO[Unit]): Task[A]

/** Sends events and JavaScript commands from the server to the browser.
  *
  * See [browser integration](https://scalive.dev/guides/browser-integration).
  */
trait Client:
  /** Sends `payload` to browser hooks that handle `event`, once the current turn commits. */
  def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): Task[Unit]

  /** Runs the client-only command `js` in the browser, once the current turn commits.
    *
    * A command that pushes a typed message belongs on a rendered binding instead.
    */
  def exec[Msg](js: JSCommands.JSCommand[Msg]): Task[Unit]

/** Sends new props to stateful components that are already mounted. */
trait ComponentUpdates:
  /** Sends `props` to a mounted component, which receives them in [[LiveComponent.update]].
    *
    * An update to a component that is not mounted is ignored with a warning. When several updates
    * reach one instance before it renders, it receives the last props.
    */
  def sendUpdate[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model],
    props: Props
  ): Task[Unit]

  /** Sends `props` to a mounted component, which receives them in [[LiveComponent.update]].
    *
    * This form addresses a component that emits outputs.
    */
  def sendUpdate[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output],
    props: Props
  ): Task[Unit]

  /** Sends `props` to a mounted component, which receives them in [[LiveComponent.update]].
    *
    * This form addresses the component of class `C` with logical ID `id`.
    */
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): Task[Unit]
