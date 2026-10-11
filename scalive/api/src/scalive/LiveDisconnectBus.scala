package scalive

import zio.*

/** Cluster fanout for active LiveView session invalidation.
  *
  * A subscription must be ready to receive notifications when `subscribe` returns and remains
  * active for the lifetime of its enclosing scope. Implementations must fan each publication out to
  * every subscriber; competing-consumer delivery is not sufficient.
  */
trait LiveDisconnectBus[Id]:
  /** Sends `id` to every subscriber on every node.
    *
    * Fail when the ID cannot be published; [[LiveConnections.disconnect]] reports that failure
    * after closing the connections on this node.
    */
  def publish(id: Id): Task[Unit]

  /** Registers `onDisconnect` to receive every published ID until the enclosing scope closes.
    *
    * The subscription must be ready when the effect returns. Duplicate or out-of-order delivery is
    * harmless.
    */
  def subscribe(onDisconnect: Id => UIO[Unit]): ZIO[Scope, Throwable, Unit]
