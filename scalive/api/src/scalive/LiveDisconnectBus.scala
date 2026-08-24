package scalive

import zio.*

/** Cluster fanout for active LiveView session invalidation.
  *
  * A subscription must be ready to receive notifications when `subscribe` returns and remains
  * active for the lifetime of its enclosing scope. Implementations must fan each publication out to
  * every subscriber; competing-consumer delivery is not sufficient.
  */
trait LiveDisconnectBus[Id]:
  def publish(id: Id): Task[Unit]

  def subscribe(onDisconnect: Id => UIO[Unit]): ZIO[Scope, Throwable, Unit]
