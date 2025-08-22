package scalive

import zio.json.*

import java.util.Base64
import scala.util.Random

final case class Socket[Cmd](lv: LiveView[Cmd]):

  private var clientInitialized = false
  val id: String      = s"phx-${Base64.getEncoder().encodeToString(Random().nextBytes(8))}"
  private val token   = Token.sign("secret", id, "")
  private val element = lv.el.prepended(idAttr := id, dataAttr("phx-session") := token)

  element.syncAll()

  def receiveCommand(cmd: Cmd): Unit =
    lv.handleCommand(cmd)

  def renderHtml(rootLayout: HtmlElement => HtmlElement = identity): String =
    element.syncAll()
    HtmlBuilder.build(rootLayout(element))

  def syncClient: Unit =
    element.syncAll()
    println(DiffBuilder.build(element, trackUpdates = clientInitialized).toJsonPretty)
    clientInitialized = true
    element.setAllUnchanged()
