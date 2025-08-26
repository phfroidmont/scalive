package scalive

import zio.json.*

import java.util.Base64
import scala.util.Random

final case class Socket[Cmd](lv: LiveView[Cmd]):

  private var clientInitialized = false
  val id: String                =
    s"phx-${Base64.getUrlEncoder().withoutPadding().encodeToString(Random().nextBytes(8))}"
  private val token = Token.sign("secret", id, "")

  lv.el.syncAll()

  def receiveCommand(cmd: Cmd): Unit =
    lv.handleCommand(cmd)

  def renderHtml(rootLayout: HtmlElement => HtmlElement = identity): String =
    lv.el.syncAll()
    HtmlBuilder.build(
      rootLayout(
        div(
          idAttr                  := id,
          dataAttr("phx-session") := token,
          lv.el
        )
      )
    )

  def syncClient: Unit =
    lv.el.syncAll()
    println(DiffBuilder.build(lv.el, trackUpdates = clientInitialized).toJsonPretty)
    clientInitialized = true
    lv.el.setAllUnchanged()

  def diff: Diff =
    lv.el.syncAll()
    val diff = DiffBuilder.build(lv.el, trackUpdates = clientInitialized)
    clientInitialized = true
    lv.el.setAllUnchanged()
    diff
end Socket
