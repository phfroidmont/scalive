package scalive

trait LiveView[Cmd]:
  def mount(state: LiveState): LiveState
  def handleCommand(cmd: Cmd, state: LiveState): LiveState
  def render: HtmlElement
