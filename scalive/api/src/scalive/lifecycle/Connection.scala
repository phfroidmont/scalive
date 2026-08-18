package scalive

import zio.json.ast.Json

/** Makes connected-only lifecycle capabilities explicit. */
enum Connection[+Connected]:
  case Disconnected
  case Connected(capabilities: Connected)

/** Metadata supplied by a connected client. Values in `connectParams` are untrusted. */
trait ConnectedMetadata:
  def staticChanged: Boolean
  def connectParams: Map[String, Json]

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
