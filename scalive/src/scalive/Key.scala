package scalive

/** A nominal browser keyboard-event key value used to filter key bindings.
  *
  * Values correspond to `KeyboardEvent.key`, including its exact spelling and case. Construct
  * uncommon values with [[Key.apply]] or use the constants in [[Key]].
  */
opaque type Key = String

/** Standard key values and the constructor for custom keys. */
object Key:
  /** Creates a custom key filter.
    *
    * The non-empty value is retained verbatim; it is not normalized or checked against a fixed key
    * list.
    *
    * @throws IllegalArgumentException
    *   if `value` is empty
    */
  def apply(value: String): Key =
    require(value.nonEmpty, "key must not be empty")
    value

  /** The Escape key (`"Escape"`). */
  val Escape: Key = "Escape"

  /** The Enter or Return key (`"Enter"`). */
  val Enter: Key = "Enter"

  /** The Tab key (`"Tab"`). */
  val Tab: Key = "Tab"

  /** The Space key, whose browser key value is a single space (`" "`). */
  val Space: Key = " "

  /** The upward arrow key (`"ArrowUp"`). */
  val ArrowUp: Key = "ArrowUp"

  /** The downward arrow key (`"ArrowDown"`). */
  val ArrowDown: Key = "ArrowDown"

  /** The left arrow key (`"ArrowLeft"`). */
  val ArrowLeft: Key = "ArrowLeft"

  /** The right arrow key (`"ArrowRight"`). */
  val ArrowRight: Key = "ArrowRight"

  extension (key: Key) private[scalive] def value: String = key
end Key
