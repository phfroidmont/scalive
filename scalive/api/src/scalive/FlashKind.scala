package scalive

/** A nominal key for one category of flash message.
  *
  * The same kind is used to put, retrieve, clear, render, and transfer a flash message across
  * navigation. Distinct declared values keep application flash categories explicit and prevent
  * accidental use of unrelated string identifiers.
  */
opaque type FlashKind = String

/** Creates and inspects [[FlashKind]] values. */
object FlashKind:
  /** Creates a flash kind from its exact runtime key.
    *
    * No validation or normalization is performed.
    */
  def apply(value: String): FlashKind = value

  /** Returns the exact runtime flash key stored in `kind`. */
  extension (kind: FlashKind) def value: String = kind
