package scalive

opaque type ClientEvent[A] = String

object ClientEvent:
  def apply[A](value: String): ClientEvent[A] = value

  extension [A](event: ClientEvent[A]) def value: String = event
