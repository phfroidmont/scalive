package scalive

opaque type AsyncKey[A] = String

object AsyncKey:
  def apply[A](value: String): AsyncKey[A] = value

  given [A, B]: CanEqual[AsyncKey[A], AsyncKey[B]] = CanEqual.derived

  extension [A](key: AsyncKey[A]) def value: String = key
