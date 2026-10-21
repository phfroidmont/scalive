package scalive

import zio.ZIO
import zio.json.ast.Json

/** Makes connected-only lifecycle capabilities explicit.
  *
  * Match on it to use connected capabilities, or use [[whenConnected]] for connected-only work. Use
  * [[isConnected]] when only the phase matters. See [two independent
  * mounts](https://scalive.dev/learn/lifecycle-and-connection-behavior#two-independent-mounts).
  */
enum Connection[+Connected]:
  /** The stage runs for the disconnected HTTP render, before the browser connects. */
  case Disconnected

  /** The stage runs in a connected lifecycle; `capabilities` holds what only it can do.
    *
    * @param capabilities
    *   The capabilities available only in a connected lifecycle.
    */
  case Connected(capabilities: Connected)

  /** Whether this stage runs in a connected lifecycle rather than the disconnected HTTP render. */
  def isConnected: Boolean = this match
    case Disconnected => false
    case Connected(_) => true

  /** Runs `effect` with the connected capabilities, and does nothing in the disconnected render.
    *
    * Use it for connected-only work whose result the stage does not need, as in
    * `ctx.connection.whenConnected(_.async.start(Load)(load)(Msg.Loaded(_))).as(model)`.
    */
  def whenConnected[R, E](effect: Connected => ZIO[R, E, Any]): ZIO[R, E, Unit] = this match
    case Disconnected            => ZIO.unit
    case Connected(capabilities) => effect(capabilities).unit

/** Metadata supplied by a connected client. Values in `connectParams` are untrusted. */
trait ConnectedMetadata:
  /** Whether the running server's document tracks an asset the browser has not loaded.
    *
    * It is `true` when a `phx-track-static` URL of the root layout, or of the scripts Scalive adds
    * to it, is missing from the URLs the browser reports, usually because a deployment changed
    * them. Query strings, fragments, and origins are ignored, and tracked tags inside LiveView
    * content are not compared. It stays the same for the whole lifecycle. Missing, malformed, or
    * empty tracking data from the browser yields `false`, and nested LiveViews always see `false`.
    * Ordinary layouts read the same value through [[LiveLayoutContext.connection]], so one layout
    * can offer a reload on every page. Treat it as a hint, not as a security signal.
    */
  def staticChanged: Boolean

  /** The value the browser sent for `param` when it joined.
    *
    * `None` when the browser did not send it or it does not decode as `A`. The value is untrusted.
    */
  final def connectParam[A](param: ConnectParam[A]): Option[A] =
    connectParams.get(param.name).flatMap(param.decode)

  /** Whether this is the page view's first join or a rejoin after a reconnect or a crash.
    *
    * The browser reports it, so use it for visit counts or to skip a welcome animation, never for
    * security. Each nested LiveView reports its own.
    */
  final def join: ConnectedJoin =
    connectParams.get("_mounts") match
      case Some(Json.Num(mounts)) if mounts.signum > 0 => ConnectedJoin.Rejoin
      case _                                           => ConnectedJoin.First

  /** Every parameter the browser sent, including Phoenix's own keys such as `_mounts`. */
  private[scalive] def connectParams: Map[String, Json]
end ConnectedMetadata

/** Capabilities that a [[LiveView]] can use only when it mounts in a connected lifecycle.
  *
  * Reach them by matching `ctx.connection` in `mount`. Work started here belongs to the lifecycle
  * and stops when it closes; a reconnect mounts again and starts it again.
  */
trait MountConnected[Msg] extends ConnectedMetadata:
  /** Starts finite background work whose result returns as a message.
    *
    * See [asynchronous
    * work](https://scalive.dev/guides/async-work#start-work-when-the-page-connects).
    */
  def async: Async[Msg]

  /** Starts message streams that run while the page stays connected.
    *
    * See
    * [subscriptions](https://scalive.dev/guides/subscriptions#start-a-subscription-when-the-page-connects).
    */
  def subscriptions: Subscriptions[Msg]

  /** Resources owned by the current connected `LiveView` lifecycle. */
  def resources: ConnectedResources

  /** Pushes events and JavaScript commands to the browser. */
  def client: Client

  /** Sends new props to stateful components rendered by this view.
    *
    * An update sent here reaches a component rendered by the first connected render.
    */
  def components: ComponentUpdates

/** Capabilities that a [[LiveView.Routed LiveView.Routed]] can use only when it handles route
  * parameters in a connected lifecycle.
  *
  * Reach them by matching `ctx.connection` in `handleParams`. Work started here belongs to the
  * lifecycle and stops when it closes.
  */
trait ParamsConnected[Msg] extends ConnectedMetadata:
  /** Starts finite background work whose result returns as a message. */
  def async: Async[Msg]

  /** Starts message streams that run while the page stays connected. */
  def subscriptions: Subscriptions[Msg]

  /** Pushes events and JavaScript commands to the browser. */
  def client: Client

  /** Sends new props to stateful components rendered by this view. */
  def components: ComponentUpdates

/** Capabilities that the after-render hooks of a [[LiveView]] can use only in a connected
  * lifecycle.
  *
  * Reach them by matching `ctx.connection` in the hook.
  */
trait AfterRenderConnected extends ConnectedMetadata:
  /** Pushes events and JavaScript commands to the browser. */
  def client: Client

/** Capabilities that a [[LiveComponent]] can use only when it mounts or updates in a connected
  * lifecycle.
  *
  * Reach them by matching `ctx.connection` in `mount` or `update`. Work started here belongs to
  * this component instance and stops when the instance is removed or the lifecycle closes.
  */
trait ComponentConnected[Msg] extends ConnectedMetadata:
  /** Starts finite background work owned by this component instance, whose result returns as a
    * message.
    */
  def async: Async[Msg]

  /** Starts message streams owned by this component instance. */
  def subscriptions: Subscriptions[Msg]

  /** Pushes events and JavaScript commands to the browser. */
  def client: Client
