package scalive

/** A nominal key that associates an asynchronous task name with its result type.
  *
  * Passing `AsyncKey[A]` to an async context requires a task producing `A`. Within one connected
  * LiveView or LiveComponent instance, starting the same key again replaces the previous task and
  * suppresses its completion. The key is invariant in `A`, preventing accidental widening of its
  * result type.
  *
  * @tparam A
  *   the result produced by the keyed task
  */
opaque type AsyncKey[A] = String

/** Creates and inspects [[AsyncKey]] values. */
object AsyncKey:
  /** Creates a typed async key from its exact runtime name.
    *
    * No validation or normalization is performed.
    */
  def apply[A](value: String): AsyncKey[A] = value

  /** Permits keys with different result parameters to be compared by runtime name.
    *
    * This is useful for comparing a typed key with the erased key exposed by [[LiveAsyncEvent]].
    */
  given [A, B]: CanEqual[AsyncKey[A], AsyncKey[B]] = CanEqual.derived

  /** Returns the exact runtime task name stored in `key`. */
  extension [A](key: AsyncKey[A]) def value: String = key
