package scalive

import zio.json.*

final case class Socket(view: LiveView, initState: LiveState):
  private var state: LiveState         = initState
  private var fingerprint: Fingerprint = Fingerprint.empty
  val id: String                       = "scl-123"

  def setState(newState: LiveState): Unit          = state = newState
  def updateState(f: LiveState => LiveState): Unit = state = f(state)
  def renderHtml: String                           =
    HtmlBuilder.build(Rendered.render(view.render, state), isRoot = true)
  def syncClient: Unit =
    val r = Rendered.render(view.render, state)
    println(DiffBuilder.build(r, fingerprint).toJsonPretty)
    fingerprint = Fingerprint(r)
    println(fingerprint)
    state = state.setAllUnchanged
