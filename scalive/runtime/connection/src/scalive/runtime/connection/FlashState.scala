package scalive.runtime.connection

import scalive.FlashKey
import scalive.FlashValue

/** The flash messages a LiveView shows, and the last put number of each kind it has put.
  *
  * The put number outlives a clear, so a kind cleared and put again in one turn still gets a new
  * number: only the turn's end reaches the browser, which would otherwise patch the element in
  * place.
  */
final private[connection] case class FlashState private (
  shown: Map[FlashKey, FlashValue],
  puts: Map[FlashKey, Int]):

  def put(key: FlashKey, encoded: String): FlashState =
    val put = puts.get(key).fold(0)(_ + 1)
    FlashState(shown.updated(key, FlashValue(encoded, put)), puts.updated(key, put))

  def clear(key: FlashKey): FlashState = copy(shown = shown - key)

  def clearAll: FlashState = copy(shown = Map.empty)

private[connection] object FlashState:
  val empty: FlashState = FlashState(Map.empty, Map.empty)

  /** Starts from messages carried in, such as the flash of a navigation. */
  def of(shown: Map[FlashKey, FlashValue]): FlashState =
    FlashState(shown, shown.view.mapValues(_.put).toMap)
