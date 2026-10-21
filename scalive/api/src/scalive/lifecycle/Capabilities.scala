package scalive

import zio.Duration
import zio.IO
import zio.Task
import zio.UIO
import zio.ZIO
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

  /** Ends this LiveView and loads `to` with a full page request.
    *
    * This form leaves the application, for example for a payment page or an identity provider.
    */
  def redirect(to: ExternalUrl): Task[Unit] = redirectUnsafe(to.href)

  /** Like [[redirect]], for a raw URL that the route types do not check. */
  def redirectUnsafe(to: String): Task[Unit]
end MountNavigation

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
    * [[LiveView.Routed.handleParams]]. Use it for filters, pagination, and tabs. A location of
    * another route stops the LiveView, as Phoenix does; navigate there instead.
    */
  def pushPatch(to: LiveLocation): Task[Unit] = pushPatchUnsafe(to.href)

  /** Like [[pushPatch]], for a raw URL that the route types do not check. */
  def pushPatchUnsafe(to: String): Task[Unit]

  /** Changes the URL to `to`, replacing the current history entry, without remounting. */
  def replacePatch(to: LiveLocation): Task[Unit] = replacePatchUnsafe(to.href)

  /** Like [[replacePatch]], for a raw URL that the route types do not check. */
  def replacePatchUnsafe(to: String): Task[Unit]

/** The navigation of a [[LiveView.Routed LiveView.Routed]], which can also patch by parameters.
  *
  * Patching by parameters encodes them with the view's own route, so the patch always stays on that
  * route.
  */
trait RoutedNavigation[-Params] extends Navigation:
  /** Changes the URL to this view's route with `params` and adds a history entry.
    *
    * The LiveView stays mounted and receives `params` in [[LiveView.Routed.handleParams]]. Fails
    * with [[LiveLocation.EncodingException]] when `params` cannot be encoded.
    */
  def pushPatch(params: Params): Task[Unit]

  /** Changes the URL to this view's route with `params`, replacing the current history entry. */
  def replacePatch(params: Params): Task[Unit]

private[scalive] object RoutedNavigation:
  def apply[Params](
    underlying: Navigation,
    location: Params => Either[LiveLocation.EncodeError, LiveLocation]
  ): RoutedNavigation[Params] =
    new RoutedNavigation[Params]:
      def pushNavigateUnsafe(to: String): Task[Unit]    = underlying.pushNavigateUnsafe(to)
      def replaceNavigateUnsafe(to: String): Task[Unit] = underlying.replaceNavigateUnsafe(to)
      def redirectUnsafe(to: String): Task[Unit]        = underlying.redirectUnsafe(to)
      def pushPatchUnsafe(to: String): Task[Unit]       = underlying.pushPatchUnsafe(to)
      def replacePatchUnsafe(to: String): Task[Unit]    = underlying.replacePatchUnsafe(to)
      def pushPatch(params: Params): Task[Unit]         =
        encoded(params).flatMap(underlying.pushPatch)
      def replacePatch(params: Params): Task[Unit] =
        encoded(params).flatMap(underlying.replacePatch)

      private def encoded(params: Params) =
        ZIO.fromEither(location(params)).mapError(new LiveLocation.EncodingException(_))

/** Short messages shown to the visitor, keyed by [[FlashKind]].
  *
  * Render one kind with `flash(kind)(message => ...)`. Use flash for brief feedback such as a save
  * result, and keep state that must outlive the page in the model or a service. See
  * [flash](https://scalive.dev/guides/flash-titles-and-connection-status#render-keyed-flash).
  */
trait Flash:
  /** Sets the message for `kind`, replacing any previous one. */
  def put[A](kind: FlashKind[A], message: A): Task[Unit]

  /** Removes the message for `kind`. */
  def clear(kind: FlashKind[?]): Task[Unit]

  /** Removes every message. */
  def clearAll: Task[Unit]

  /** Returns the current message for `kind`, if any.
    *
    * A stored message that does not decode as `A` is absent.
    */
  def get[A](kind: FlashKind[A]): Task[Option[A]]

/** Allows, inspects, cancels, and consumes the file uploads of one LiveView or component.
  *
  * Scalive keeps each upload's state; render it with [[LiveUploadDef.render]], which follows it as
  * files are selected, uploaded, and cancelled. Failures caused by the current upload state are
  * [[LiveUploadOperationError]] values. See the [file uploads
  * guide](https://scalive.dev/guides/uploads-and-consumption#complete-a-bounded-in-memory-upload).
  */
trait Uploads:
  /** Allows uploads for `definition`.
    *
    * Call it during every mount; the disconnected and connected mounts own independent uploads.
    * Allowing the same definition again changes nothing. Allowing a different definition with the
    * same name replaces the upload, and fails with [[LiveUploadOperationError.ActiveEntries]] while
    * it still tracks entries.
    */
  def allow[R](definition: LiveUploadDef[R]): Task[Unit]

  /** Removes the upload for `definition`, cancelling its entries and releasing every result the
    * runtime still owns.
    *
    * Allow the definition again to start over.
    */
  def disallow[R](definition: LiveUploadDef[R]): Task[Unit]

  /** Returns the current state of the upload for `definition`, or `None` when it is not allowed.
    *
    * Use it in a handler that depends on the upload, for example to check that no entry is still in
    * progress. The view renders the state with [[LiveUploadDef.render]] instead.
    */
  def get[R](definition: LiveUploadDef[R]): Task[Option[LiveUpload[R]]]

  /** Cancels `entry`.
    *
    * A stale or already removed entry fails with a [[LiveUploadOperationError]].
    */
  def cancel[R](entry: LiveUploadEntry[R]): Task[Unit]

  /** Passes the completed `entry` to `callback` and returns its value.
    *
    * The callback's [[ConsumeDecision]] decides whether the application takes ownership of the
    * result. An invalid or unfinished entry fails with
    * [[LiveUploadOperationError.EntryNotCompleted]].
    */
  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[A]

  /** Passes each completed entry of `definition` to `callback`, in selection order, and returns
    * their values.
    *
    * Invalid entries are skipped. It fails with [[LiveUploadOperationError.EntriesInProgress]]
    * while a valid entry is still in progress. Entries are consumed one at a time, so if a callback
    * fails, the entries consumed before it stay consumed.
    */
  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[List[A]]
end Uploads

/** Creates and updates the rendered streams of one LiveView or component.
  *
  * Scalive keeps each stream's items and sends the operations of a turn when it renders; the view
  * renders a stream with `renderIn` or `stream` on its definition. Every operation after `create`
  * must use the definition that created the stream.
  */
trait Streams:
  /** Creates the stream for `definition` with `items` as its initial entries.
    *
    * Call it once per lifecycle, usually in mount; creating the same name twice for one owner
    * fails. The disconnected render keeps every item, whatever the definition's retention limit.
    */
  def create[A, Id](definition: LiveStreamDef[A, Id], items: Iterable[A]): Task[Unit]

  /** Inserts `items` at `at`, like repeated [[insert]] calls at the same position.
    *
    * Repeatedly inserting at [[StreamAt.First]] or at one fixed index reverses the order of
    * `items`.
    */
  def insertAll[A, Id](
    definition: LiveStreamDef[A, Id],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): Task[Unit]

  /** Replaces every entry with `items` and makes the browser rebuild the stream container. */
  def reset[A, Id](
    definition: LiveStreamDef[A, Id],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): Task[Unit]

  /** Inserts `item` at `at`, or updates its row in place when an entry with the same ID exists.
    *
    * With `updateOnly = true`, an item whose ID is absent is ignored instead of inserted.
    */
  def insert[A, Id](
    definition: LiveStreamDef[A, Id],
    item: A,
    at: StreamAt = StreamAt.Last,
    updateOnly: Boolean = false
  ): Task[Unit]

  /** Removes the entry whose domain ID is `id`.
    *
    * An `id` that is not in the stream is not an error: as in Phoenix, the delete still reaches the
    * browser, which removes nothing when no element has that row's DOM ID.
    */
  def delete[A, Id](definition: LiveStreamDef[A, Id], id: Id): Task[Unit]

  /** Removes the entry whose rendered DOM ID is `domId`.
    *
    * Prefer [[delete]] with a typed ID. Pass only a trusted DOM ID that belongs to this stream,
    * never untrusted browser input.
    */
  def deleteByDomId[A, Id](definition: LiveStreamDef[A, Id], domId: String): Task[Unit]
end Streams

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
    * follow the latest query. A typed failure arrives as [[LiveAsyncResult.Failed]] and a defect as
    * [[LiveAsyncResult.Died]]; neither fails the lifecycle. A `Task` fails with `Throwable`, so its
    * exceptions arrive as `Failed`.
    */
  def start[E, A](key: AsyncKey[A])(task: IO[E, A])(toMsg: LiveAsyncResult[E, A] => Msg): Task[Unit]

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
    * intended. `delivery` chooses whether every value is delivered, the default, or only the
    * latest.
    */
  def start(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery = SubscriptionDelivery.Lossless
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): Task[Unit]

  /** Runs `stream` under `key`, interrupting the stream already registered under it, if any. */
  def replace(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery = SubscriptionDelivery.Lossless
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): Task[Unit]

  /** Stops the stream registered under `key`. Cancelling an absent key does nothing. */
  def cancel(key: SubscriptionKey): Task[Unit]

  /** Sends `message` to the owner once `delay` has passed, like Phoenix's `Process.send_after`.
    *
    * It replaces the timer or stream already registered under `key`, and [[cancel]] stops it before
    * it fires.
    */
  final def sendAfter(key: SubscriptionKey, delay: Duration)(message: Msg): Task[Unit] =
    replace(key)(ZStream.fromZIO(ZIO.sleep(delay).as(message)))
end Subscriptions

/** Acquisition and finalization owned by one connected `LiveView` lifecycle.
  *
  * This capability is exposed through [[MountConnected.resources]] and route/session connected
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
    * A command that pushes a typed message does not compile here: it belongs on a rendered binding.
    */
  def exec(js: JSCommands.JSCommand[Nothing]): Task[Unit]

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
