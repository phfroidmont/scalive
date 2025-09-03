package scalive

trait LiveView[Event]:
  def handleEvent: Event => Unit
  val el: HtmlElement

  private[scalive] def diff(trackUpdates: Boolean = true): Diff =
    el.syncAll()
    val diff = DiffBuilder.build(el, trackUpdates = trackUpdates)
    el.setAllUnchanged()
    diff
