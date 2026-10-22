package scalive

import zio.json.JsonDecoder
import zio.json.ast.Json

/** A typed key for one parameter the browser sends when it joins.
  *
  * Declare each key once and read it with `connected.connectParam(key)`. The browser sends the
  * values given in the `LiveSocket` `params` option, such as
  * `params: () => ({ timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone })`, read with
  * `ConnectParam[ZoneId]("timeZone")`. Values are untrusted: never base identity or authorization
  * on them.
  *
  * @param name
  *   The parameter's name. Names starting with `_` are reserved by Phoenix and rejected.
  */
final class ConnectParam[A] private (val name: String, decoder: JsonDecoder[A]):
  private[scalive] def decode(value: Json): Option[A] = decoder.fromJsonAST(value).toOption

  override def toString: String = s"ConnectParam($name)"

/** Creates [[ConnectParam]] keys. */
object ConnectParam:
  /** Creates a key for the parameter `name`, decoded as `A`. */
  def apply[A](name: String)(using decoder: JsonDecoder[A]): ConnectParam[A] =
    require(name.nonEmpty, "connect param name must not be empty")
    require(!name.startsWith("_"), s"connect param names starting with '_' are reserved: $name")
    new ConnectParam(name, decoder)

/** Whether a connected LiveView is joining for the first time or again. */
enum ConnectedJoin derives CanEqual:
  /** The first join of this page view, including after live navigation to it. */
  case First

  /** The same page view joining again, after the connection dropped or the LiveView crashed. */
  case Rejoin
