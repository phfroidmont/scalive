import scalive.*

object ExampleRoutes:
  val home    = live
  val counter = live / "counter"
  val list    = (live / "list").query[ListLiveView.ListParams]
  val todo    = live / "todo"
