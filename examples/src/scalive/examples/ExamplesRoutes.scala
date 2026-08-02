package scalive.examples

import scalive.*
import scalive.examples.navigation.{RawSearchParams, SearchParams}

object ExamplesRoutes:
  val home          = live
  val shoppingCart  = live / "state" / "shopping-cart"
  val guestbook     = live / "services" / "guestbook"
  val subscriptions = live / "processing" / "subscriptions"
  val async         = live / "processing" / "async"
  val login         = live / "auth" / "login"
  val profile       = live / "auth" / "profile"
  val profileForm   = live / "forms" / "profile"
  val documents     = live / "uploads" / "documents"
  val search        = (live / "navigation" / "search")
    .query[RawSearchParams]
    .mapParams(SearchParams.fromRaw)(_.toRaw)
  val activity       = live / "collections" / "activity"
  val voting         = live / "components" / "voting"
  val browserInterop = live / "interop" / "browser"
  val notifications  = live / "lifecycle" / "notifications"
