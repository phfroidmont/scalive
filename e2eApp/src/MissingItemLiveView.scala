import zio.*
import zio.http.codec.PathCodec
import zio.http.{Request, Status}

import scalive.*

/** Items that exist, are missing, or vanish between the page request and the join. */
object MissingItems:
  val list   = live / "items"
  val item   = live / "items" / PathCodec.string("id")
  val report = live / "items" / "report.txt"

  object Page extends LiveErrorPage:
    // Upstream expects every response of a failed dead mount, assets included, to be a 500.
    override def renders(status: Status)                     = status == Status.NotFound
    override def pageTitle(status: Status, request: Request) = Some(s"Error ${status.code}")
    def view(status: Status, request: Request)               =
      mainTag(h1(idAttr := "error-status", s"Error ${status.code}"))

class MissingItemsLiveView extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext) = ZIO.unit

  override def view(model: Signal[Unit]) =
    ul(
      li(link.pushNavigate(MissingItems.item.location("present"), "Present")),
      li(link.pushNavigate(MissingItems.item.location("missing"), "Missing")),
      li(link.pushNavigate(MissingItems.report.location, "Report"))
    )

class MissingItemLiveView extends LiveView.Routed.Eventless[String, String]:
  def mount(id: String, ctx: MountContext) =
    (id, ctx.connection) match
      case ("missing", _)                        => ZIO.fail(LiveNotFound(s"item $id"))
      case ("vanished", Connection.Connected(_)) => ZIO.fail(LiveNotFound(s"item $id"))
      case _                                     => ZIO.succeed(id)

  override def view(model: Signal[String]) = h1(idAttr := "item", model)
