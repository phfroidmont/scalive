package scalive

enum PhxUpdate(private[scalive] val value: String):
  case Replace extends PhxUpdate("replace")
  case Stream  extends PhxUpdate("stream")
  case Ignore  extends PhxUpdate("ignore")
