package scalive

opaque type SubscriptionKey = String

object SubscriptionKey:
  def apply(value: String): SubscriptionKey = value

  extension (key: SubscriptionKey) def value: String = key
