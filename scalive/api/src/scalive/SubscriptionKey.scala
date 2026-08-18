package scalive

/** A nominal runtime key for one managed LiveView subscription.
  *
  * A subscription key names a stream within its owning LiveView lifecycle. In a connected
  * lifecycle, starting an already active key fails, replacing a key swaps its stream, and
  * cancelling an absent key is a no-op. Connected subscription operations reject empty names.
  */
opaque type SubscriptionKey = String

/** Creates and inspects [[SubscriptionKey]] values. */
object SubscriptionKey:
  /** Creates a subscription key from its exact runtime name.
    *
    * Construction does not validate the name; connected subscription operations reject an empty
    * value.
    */
  def apply(value: String): SubscriptionKey = value

  /** Returns the exact runtime subscription name stored in `key`. */
  extension (key: SubscriptionKey) def value: String = key
