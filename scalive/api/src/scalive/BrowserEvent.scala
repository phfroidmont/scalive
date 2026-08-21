package scalive

import zio.json.ast.Json

/** The complete client event envelope visible to raw lifecycle hooks. */
final case class LiveEvent(
  kind: String,
  bindingId: String,
  value: Json,
  params: Map[String, String],
  cid: Option[Long],
  meta: Option[Json])

/** A named event pushed from the server to the browser with a typed JSON payload.
  *
  * Pass this event and an `A` to `ctx.client.push`; a `zio.json.JsonEncoder[A]` is required and an
  * encoding error fails the returned [[LiveIO]]. Browser JavaScript subscribes to the stored string
  * name and interprets the encoded payload. The type is invariant in `A`, so an event cannot be
  * widened to a different payload contract.
  *
  * @tparam A
  *   the payload pushed to the browser
  */
opaque type ServerToBrowserEvent[A] = String

/** Creates and inspects [[ServerToBrowserEvent]] values. */
object ServerToBrowserEvent:
  /** Creates an outbound browser event from its exact runtime name.
    *
    * No validation or normalization is performed.
    */
  def apply[A](value: String): ServerToBrowserEvent[A] = value

  /** Returns the exact browser event name stored in `event`. */
  extension [A](event: ServerToBrowserEvent[A]) def value: String = event

/** A named event pushed from browser JavaScript to the server with a typed JSON payload.
  *
  * Register this event with a LiveView or LiveComponent `onBrowserEvent` hook. Registration
  * requires a `zio.json.JsonDecoder[A]`; matching payloads are decoded before the handler runs. A
  * malformed matching payload is logged and consumed without changing the model. Root handlers
  * ignore events targeted at a component. The type is invariant in `A`, so an event cannot be
  * widened to a different payload contract.
  *
  * @tparam A
  *   the payload expected from the browser
  */
opaque type BrowserToServerEvent[A] = String

/** Creates and inspects [[BrowserToServerEvent]] values. */
object BrowserToServerEvent:
  /** Creates an inbound browser event from its exact runtime name.
    *
    * No validation or normalization is performed.
    */
  def apply[A](value: String): BrowserToServerEvent[A] = value

  /** Returns the exact browser event name stored in `event`. */
  extension [A](event: BrowserToServerEvent[A]) def value: String = event
