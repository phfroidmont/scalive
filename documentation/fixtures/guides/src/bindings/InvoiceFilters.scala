package bindings

import zio.*
import zio.schema.annotation.caseName
import zio.schema.{Schema, derived}

import scalive.*

// docs:start bindings-small-form
enum InvoiceStatus derives Schema:
  @caseName("open") case Open
  @caseName("paid") case Paid

final case class InvoiceFilters(
  status: Option[InvoiceStatus],
  practitioners: Set[String],
  days: Int = 30)
    derives Schema

enum FilterMsg:
  case FiltersChanged(filters: InvoiceFilters)
  case PageSizeChanged(size: Int)

def invoiceFilters(practitioners: Seq[String]): HtmlElement[FilterMsg] =
  div(
    form(
      idAttr := "invoice-filters",
      on.change.fields[InvoiceFilters](FilterMsg.FiltersChanged(_)),
      select(
        nameAttr := "status",
        option(value := "", "Any status"),
        option(value := "open", "Open"),
        option(value := "paid", "Paid")
      ),
      practitioners.map(name =>
        label(input(typ := "checkbox", nameAttr := "practitioners", value := name), name)
      ),
      input(typ := "number", nameAttr := "days", value := "30")
    ),
    form(
      idAttr := "page-size",
      on.change.field("size", 20)(FilterMsg.PageSizeChanged(_)),
      select(nameAttr := "size", option(value := "20", "20"), option(value := "50", "50"))
    )
  )
// docs:end bindings-small-form

final class InvoiceListLiveView extends LiveView[FilterMsg, (InvoiceFilters, Int)]:
  def mount(ctx: MountContext) = ZIO.succeed(InvoiceFilters(None, Set.empty) -> 20)

  def handleMessage(model: (InvoiceFilters, Int), ctx: MessageContext) =
    case FilterMsg.FiltersChanged(filters) => ZIO.succeed(filters -> model._2)
    case FilterMsg.PageSizeChanged(size)   => ZIO.succeed(model._1 -> size)

  def view(model: Signal[(InvoiceFilters, Int)]): HtmlElement[FilterMsg] =
    div(invoiceFilters(Seq("Ada", "Bob")), p(idAttr := "shown", model.map(_.toString)))
