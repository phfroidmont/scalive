package scalive

opaque type ServerToBrowserEvent[A] = String

object ServerToBrowserEvent:
  def apply[A](value: String): ServerToBrowserEvent[A] = value

  extension [A](event: ServerToBrowserEvent[A]) def value: String = event

opaque type BrowserToServerEvent[A] = String

object BrowserToServerEvent:
  def apply[A](value: String): BrowserToServerEvent[A] = value

  extension [A](event: BrowserToServerEvent[A]) def value: String = event
