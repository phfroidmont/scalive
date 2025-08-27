package scalive

trait LiveView[ClientEvt, ServerEvent]:
  def handleClientEvent(evt: ClientEvt): Unit   = ()
  def handleServerEvent(evt: ServerEvent): Unit = ()
  val el: HtmlElement
