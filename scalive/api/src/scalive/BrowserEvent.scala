package scalive

import zio.json.JsonDecoder
import zio.json.ast.Json

/** The complete client event envelope visible to raw lifecycle hooks.
  *
  * @param kind
  *   The client's event type, such as `click`, `keyup`, `form`, or `hook` for an event pushed from
  *   JavaScript.
  * @param bindingId
  *   The event name: the rendered binding's ID for a Scalive binding, or the name itself for an
  *   event pushed from JavaScript or set with a raw `phx` attribute.
  * @param value
  *   The event's unmodified JSON value.
  * @param params
  *   The value as string parameters, such as the fields of a form event. Empty when the value has
  *   none that can be read.
  * @param cid
  *   The numeric ID of the component that the event targets, or `None` for the LiveView itself.
  * @param meta
  *   The optional JSON metadata sent with a form event, such as `_target`, the changed field.
  */
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
  * encoding error fails the returned [[zio.Task]]. Browser JavaScript subscribes to the stored
  * string name and interprets the encoded payload. The type is invariant in `A`, so an event cannot
  * be widened to a different payload contract.
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
  * Declare it in a LiveView's or LiveComponent's [[BrowserEvents]] with the message it becomes. The
  * type is invariant in `A`, so an event cannot be widened to a different payload contract.
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

/** The events that browser JavaScript pushes to a LiveView or LiveComponent, each with the message
  * it becomes.
  *
  * A hook's `this.pushEvent(name, payload)` reaches the LiveView, and `this.pushEventTo(target,
  * name, payload)` reaches the component that renders `target`. The payload is decoded as the
  * event's type, and the message then goes through event hooks and `handleMessage`, like a
  * binding's message. A payload that does not decode is logged at debug level and consumed without
  * changing the model, and so is an event that isn't declared. Build it with `on`, as in
  * `BrowserEvents.on(CopyResultEvent)(Msg.CopyFinished(_)).on(ScrollEvent)(Msg.Scrolled(_))`.
  *
  * @tparam Msg
  *   the messages the events become
  */
final class BrowserEvents[+Msg] private (private val entries: Vector[BrowserEvents.Entry[Msg]]):
  /** Receives `event` as the message that `message` builds from its decoded payload.
    *
    * This form adds it to the events already declared. It throws `IllegalArgumentException` when an
    * event with the same name is already declared.
    */
  def on[A: JsonDecoder, M >: Msg](event: BrowserToServerEvent[A])(message: A => M)
    : BrowserEvents[M] =
    require(
      !entries.exists(_.name == event.value),
      s"browser event '${event.value}' is declared twice"
    )
    val decoder = summon[JsonDecoder[A]]
    BrowserEvents(entries :+ BrowserEvents.Entry(event.value, decoder.fromJsonAST(_).map(message)))

  /** Decodes the payload of the event named `name`, or returns `None` when it is not declared. */
  private[scalive] def decode(name: String, payload: Json): Option[Either[String, Msg]] =
    entries.find(_.name == name).map(_.decode(payload))

/** Constructors for [[BrowserEvents]]. */
object BrowserEvents:
  /** Receives no browser events. */
  val none: BrowserEvents[Nothing] = BrowserEvents(Vector.empty)

  /** Receives `event` as the message that `message` builds from its decoded payload.
    *
    * This form starts the declarations with it, like `none.on`.
    */
  def on[A: JsonDecoder, Msg](event: BrowserToServerEvent[A])(message: A => Msg)
    : BrowserEvents[Msg] =
    none.on(event)(message)

  final private case class Entry[+Msg](name: String, decode: Json => Either[String, Msg])
