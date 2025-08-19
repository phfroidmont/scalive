package scalive

trait LiveView[Cmd]:
  def handleCommand(cmd: Cmd): Unit
  val el: HtmlElement
