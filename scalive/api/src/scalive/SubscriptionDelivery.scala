package scalive

/** Selects how messages from a managed subscription are delivered to its LiveView. */
enum SubscriptionDelivery:
  /** Delivers every message emitted by the subscription stream. */
  case Lossless

  /** Delivers only the latest message when newer messages supersede pending delivery. */
  case Latest
