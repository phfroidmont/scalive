package scalive.examples

import scalive.*
import scalive.examples.navigation.SearchParams

object ExamplesRoutes:
  val home           = live
  val shoppingCart   = live / "state" / "shopping-cart"
  val guestbook      = live / "services" / "guestbook"
  val subscriptions  = live / "processing" / "subscriptions"
  val async          = live / "processing" / "async"
  val login          = live / "auth" / "login"
  val profile        = live / "auth" / "profile"
  val profileForm    = live / "forms" / "profile"
  val documents      = live / "uploads" / "documents"
  val search         = (live / "navigation" / "search").query[SearchParams]
  val activity       = live / "collections" / "activity"
  val voting         = live / "components" / "voting"
  val browserInterop = live / "interop" / "browser"
  val notifications  = live / "lifecycle" / "notifications"
