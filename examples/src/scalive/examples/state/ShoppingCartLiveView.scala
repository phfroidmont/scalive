package scalive.examples.state

import zio.ZIO

import scalive.*

final class ShoppingCartLiveView
    extends LiveView[ShoppingCartLiveView.Msg, ShoppingCartLiveView.Model]:
  import ShoppingCartLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model.empty)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Add(product)    => ZIO.succeed(model.add(product))
    case Msg.Remove(product) => ZIO.succeed(model.remove(product))
    case Msg.Clear           => ZIO.succeed(Model.empty)

  def render(model: Model) =
    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "State"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Connection-local shopping cart"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "Typed messages update an immutable model. Totals are derived from that model, and each cart row has a stable key."
        )
      ),
      sectionTag(
        cls := "mb-8",
        h2(cls := "mb-3 text-xl font-semibold", "Products"),
        div(
          cls := "grid gap-3 sm:grid-cols-3",
          Product.all.map { product =>
            button(
              typ := "button",
              cls := "btn h-auto justify-between border-base-300 bg-base-100 px-4 py-3",
              phx.onClick(Msg.Add(product)),
              span(product.name),
              span(cls := "font-mono text-sm text-base-content/65", money(product.priceInCents))
            )
          }
        )
      ),
      sectionTag(
        cls := "overflow-hidden rounded-box border border-base-300 bg-base-100",
        div(
          cls := "flex items-center justify-between border-b border-base-300 px-5 py-4",
          div(
            h2(cls := "text-xl font-semibold", "Cart"),
            p(cls  := "text-sm text-base-content/60", s"${model.itemCount} items")
          ),
          button(
            typ      := "button",
            cls      := "btn btn-ghost btn-sm",
            disabled := model.lines.isEmpty,
            phx.onClick(Msg.Clear),
            "Clear"
          )
        ),
        if model.lines.isEmpty then
          p(cls := "px-5 py-10 text-center text-base-content/60", "Add a product to begin.")
        else
          div(
            cls := "overflow-x-auto",
            table(
              cls := "table",
              thead(
                tr(
                  th("Product"),
                  th(cls := "text-right", "Quantity"),
                  th(cls := "text-right", "Subtotal"),
                  th()
                )
              ),
              tbody(
                model.lines.splitBy(_.product.sku) { (_, line) =>
                  tr(
                    td(
                      div(cls := "font-semibold", line.product.name),
                      div(cls := "text-sm text-base-content/55", money(line.product.priceInCents))
                    ),
                    td(cls := "text-right font-mono", line.quantity.toString),
                    td(cls := "text-right font-mono", money(line.subtotalInCents)),
                    td(
                      cls := "text-right",
                      button(
                        typ := "button",
                        cls := "btn btn-ghost btn-sm",
                        phx.onClick(Msg.Remove(line.product)),
                        "Remove one"
                      )
                    )
                  )
                }
              ),
              tfoot(
                tr(
                  th("Total"),
                  th(),
                  th(cls := "text-right font-mono text-base-content", money(model.totalInCents)),
                  th()
                )
              )
            )
          )
      )
    )

  private def money(cents: Int): String =
    "$" + f"${cents / 100.0}%.2f"
end ShoppingCartLiveView

object ShoppingCartLiveView:
  enum Product(val sku: String, val name: String, val priceInCents: Int):
    case Coffee   extends Product("coffee", "Coffee beans", 1299)
    case Notebook extends Product("notebook", "Notebook", 850)
    case Sticker  extends Product("sticker", "Scalive sticker", 250)

  object Product:
    val all = Vector(Product.Coffee, Product.Notebook, Product.Sticker)

  final case class Line(product: Product, quantity: Int):
    def subtotalInCents: Int = product.priceInCents * quantity

  final case class Model(lines: Vector[Line]):
    def add(product: Product): Model =
      lines.indexWhere(_.product == product) match
        case -1    => copy(lines = lines :+ Line(product, 1))
        case index =>
          val current = lines(index)
          copy(lines = lines.updated(index, current.copy(quantity = current.quantity + 1)))

    def remove(product: Product): Model =
      copy(lines = lines.flatMap { line =>
        if line.product != product then Some(line)
        else if line.quantity > 1 then Some(line.copy(quantity = line.quantity - 1))
        else None
      })

    def itemCount: Int = lines.map(_.quantity).sum

    def totalInCents: Int = lines.map(_.subtotalInCents).sum

  object Model:
    val empty = Model(Vector.empty)

  enum Msg:
    case Add(product: Product)
    case Remove(product: Product)
    case Clear
end ShoppingCartLiveView
