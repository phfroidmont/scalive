package scalive

import zio.json.ast.Json

/** Makes connected-only lifecycle capabilities explicit.
  *
  * Match on it to use connected capabilities. Use [[isConnected]] when only the phase matters. See
  * [two independent
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

/** Metadata supplied by a connected client. Values in `connectParams` are untrusted. */
trait ConnectedMetadata:
  /** Whether the browser's tracked static assets differ from the ones the server rendered.
    *
    * It compares the URLs of `phx-track-static` tags, ignoring query strings, fragments, and
    * origins, and stays the same for the whole lifecycle. Missing, malformed, or empty tracking
    * data from the browser yields `false`, and nested LiveViews always see `false`. Treat it as a
    * hint that a deployment changed, for example to trigger a full reload, not as a security
    * signal.
    */
  def staticChanged: Boolean

  /** The parameters the browser sent when it joined, as untrusted JSON.
    *
    * They include the `params` given to the browser's `LiveSocket` and Phoenix's own keys such as
    * `_mounts`. Decode only keys the application owns, and never base identity or authorization on
    * them.
    */
  def connectParams: Map[String, Json]

/** Capabilities that a [[LiveView]] can use only when it mounts in a connected lifecycle.
  *
  * Reach them by matching `ctx.connection` in `mount`. Work started here belongs to the lifecycle
  * and stops when it closes; a reconnect mounts again and starts it again.
  */
trait RootMountConnected[Msg] extends ConnectedMetadata:
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
trait RootParamsConnected[Msg] extends ConnectedMetadata:
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
trait RootAfterRenderConnected extends ConnectedMetadata:
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
