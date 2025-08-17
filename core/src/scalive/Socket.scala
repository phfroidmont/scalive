package scalive

import zio.json.*

final case class Socket[Cmd](lv: LiveView[Cmd]):
  private var state: LiveState         = lv.mount(LiveState.empty)
  private var fingerprint: Fingerprint = Fingerprint.empty
  val id: String                       = "scl-123"

  def receiveCommand(cmd: Cmd): Unit =
    state = lv.handleCommand(cmd, state)

  def renderHtml: String =
    HtmlBuilder.build(Rendered.render(lv.render, state), isRoot = true)

  def syncClient: Unit =
    val r = Rendered.render(lv.render, state)
    println(DiffBuilder.build(r, fingerprint).toJsonPretty)
    fingerprint = Fingerprint(r)
    state = state.setAllUnchanged
