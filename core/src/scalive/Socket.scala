package scalive

import zio.json.*

final case class Socket[Cmd](lv: LiveView[Cmd]):

  lv.el.syncAll()

  private var clientInitialized = false
  val id: String                = "scl-123"

  def receiveCommand(cmd: Cmd): Unit =
    lv.handleCommand(cmd)

  def renderHtml: String =
    lv.el.syncAll()
    HtmlBuilder.build(lv.el, isRoot = true)

  def syncClient: Unit =
    lv.el.syncAll()
    println(DiffBuilder.build(lv.el, trackUpdates = clientInitialized).toJsonPretty)
    clientInitialized = true
    lv.el.setAllUnchanged()
