package scalive

opaque type Key = String

object Key:
  def apply(value: String): Key =
    require(value.nonEmpty, "key must not be empty")
    value

  val Escape: Key     = "Escape"
  val Enter: Key      = "Enter"
  val Tab: Key        = "Tab"
  val Space: Key      = " "
  val ArrowUp: Key    = "ArrowUp"
  val ArrowDown: Key  = "ArrowDown"
  val ArrowLeft: Key  = "ArrowLeft"
  val ArrowRight: Key = "ArrowRight"

  extension (key: Key) private[scalive] def value: String = key
