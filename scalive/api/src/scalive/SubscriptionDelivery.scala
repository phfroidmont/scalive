package scalive

/** Selects how messages from a managed subscription are delivered to its owning LiveView or
  * component. Interruption discards pending messages; restarting a dormant component's stream does
  * not replay messages from its previous run.
  */
enum SubscriptionDelivery:
  /** Delivers every message in order while the subscription remains active. */
  case Lossless

  /** Delivers only the latest message when newer messages supersede pending delivery. */
  case Latest
