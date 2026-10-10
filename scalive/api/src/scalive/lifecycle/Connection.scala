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

  /** The stage runs in a connected lifecycle; `capabilities` holds what only it can do. */
  case Connected(capabilities: Connected)

  /** Whether this stage runs in a connected lifecycle rather than the disconnected HTTP render. */
  def isConnected: Boolean = this match
    case Disconnected => false
    case Connected(_) => true

/** Metadata supplied by a connected client. Values in `connectParams` are untrusted. */
trait ConnectedMetadata:
  def staticChanged: Boolean
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

trait RootParamsConnected[Msg] extends ConnectedMetadata:
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def components: ComponentUpdates

trait RootAfterRenderConnected extends ConnectedMetadata:
  def client: Client

trait ComponentConnected[Msg] extends ConnectedMetadata:
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
