package scalive

import zio.json.*

final case class Socket[CliEvt: JsonCodec, SrvEvt](
  id: String,
  token: String,
  lv: LiveView[CliEvt, SrvEvt]):
  val clientEventCodec = JsonCodec[CliEvt]

  private var clientInitialized = false

  lv.el.syncAll()

  def renderHtml(rootLayout: HtmlElement => HtmlElement = identity): String =
    lv.el.syncAll()
    HtmlBuilder.build(
      rootLayout(
        div(
          idAttr      := id,
          phx.session := token,
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
