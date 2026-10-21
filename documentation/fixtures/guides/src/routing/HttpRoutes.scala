package routing

import zio.*
import zio.http.codec.PathCodec
import zio.http.{Body, Header, MediaType, Request, Response, URL}

import scalive.*

final case class Reader(name: String)

object ReaderSession:
  val aspect = LiveSessionMountAspect.fromRequest[Any, String, Reader](
    request =>
      request.request.cookie("reader") match
        case Some(cookie) => ZIO.succeed(cookie.content -> Reader(cookie.content))
        case None         => ZIO.fail(Response.seeOther(URL.root)),
    (name, _) => ZIO.succeed(Reader(name))
  )

final class Invoices:
  def pdf(reader: Reader, id: Int): UIO[Option[Chunk[Byte]]] =
    ZIO.succeed(
      Option.when(id == 1)(Chunk.fromArray(s"%PDF invoice $id for ${reader.name}".getBytes))
    )

// docs:start routing-http-route
object InvoiceRoutes:
  val invoice = live / "invoices" / PathCodec.int("id")
  val pdf     = live / "invoices" / PathCodec.int("id") / "invoice.pdf"

def invoicePdf(id: Int, reader: Reader, request: Request): RIO[Invoices, Response] =
  ZIO
    .serviceWithZIO[Invoices](_.pdf(reader, id))
    .someOrFail(LiveNotFound(s"invoice $id"))
    .map(bytes =>
      Response(body = Body.fromChunk(bytes))
        .addHeader(Header.ContentType(MediaType.application.pdf))
        .addHeader(Header.ContentDisposition.attachment("invoice.pdf"))
    )
// docs:end routing-http-route

// docs:start routing-http-route-link
final class InvoiceLiveView extends LiveView.Routed.Eventless[Int, Int]:
  def mount(id: Int, ctx: MountContext) = ZIO.succeed(id)

  def view(model: Signal[Int]): HtmlElement[Nothing] =
    a(href := model.map(id => InvoiceRoutes.pdf.location(id).href), "Download the PDF")
// docs:end routing-http-route-link

object HttpRoutes:
  // docs:start routing-http-route-registration
  val application = Live.router(
    Live
      .session("reader").withMountAspect(ReaderSession.aspect)(
        InvoiceRoutes.invoice -> InvoiceLiveView(),
        InvoiceRoutes.pdf.http((id, reader: Reader, request) => invoicePdf(id, reader, request))
      )
  )
  // docs:end routing-http-route-registration
