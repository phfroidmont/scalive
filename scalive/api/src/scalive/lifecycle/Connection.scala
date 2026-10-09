package scalive

import zio.json.ast.Json

/** Makes connected-only lifecycle capabilities explicit.
  *
  * Match on it to use connected capabilities. Use [[isConnected]] when only the phase matters.
  */
enum Connection[+Connected]:
  case Disconnected
  case Connected(capabilities: Connected)

  /** Whether this stage runs in a connected lifecycle rather than the disconnected HTTP render. */
  def isConnected: Boolean = this match
    case Disconnected => false
    case Connected(_) => true

/** Metadata supplied by a connected client. Values in `connectParams` are untrusted. */
trait ConnectedMetadata:
  def staticChanged: Boolean
  def connectParams: Map[String, Json]

trait RootMountConnected[Msg] extends ConnectedMetadata:
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]

  /** Resources owned by the current connected `LiveView` lifecycle. */
  def resources: ConnectedResources
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
