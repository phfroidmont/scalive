package scalive

opaque type FlashKind = String

object FlashKind:
  def apply(value: String): FlashKind = value

  extension (kind: FlashKind) def value: String = kind
