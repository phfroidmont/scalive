package scalive.docs.examples

import zio.ZIO

import scalive.*

// docs:start shopping-cart-example
final class ShoppingCartExample
    extends LiveView[ShoppingCartExample.Msg, ShoppingCartExample.Model]:
  import ShoppingCartExample.*

  def mount(ctx: MountContext): LiveIO[Model] =
    ZIO.succeed(Model.empty)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Add(product)    => ZIO.succeed(model.add(product))
    case Msg.Remove(product) => ZIO.succeed(model.remove(product))
    case Msg.Clear           => ZIO.succeed(Model.empty)

  override def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      cls := "docs-cart",
      fieldSet(
        cls                          := "docs-cart-products",
        dataAttr("example-controls") := "",
        legend("Products"),
        div(
          cls := "docs-cart-product-grid",
          Product.all.map { product =>
            button(
              typ                 := "button",
              dataAttr("product") := product.sku,
              on.click(Msg.Add(product)),
              span(cls := "docs-cart-product-name", product.name),
              span(cls := "docs-cart-product-price", money(product.priceInCents))
            )
          }
        )
      ),
      sectionTag(
        cls        := "docs-cart-summary",
        aria.label := "Shopping cart",
        headerTag(
          div(
            h4("Cart"),
            p(
              dataAttr("cart-item-count") := "",
              role                        := "status",
              aria.live                   := "polite",
              aria.atomic                 := true,
              model.map(model => itemCountLabel(model.itemCount))
            )
          ),
          button(
            typ                    := "button",
            dataAttr("cart-clear") := "",
            disabled               := model.map(_.lines.isEmpty),
            on.click(Msg.Clear),
            "Clear"
          )
        ),
        model
          .map(_.lines.isEmpty).choose(
            p(dataAttr("cart-empty") := "", cls := "docs-cart-empty", "Add a product to begin."),
            div(
              cls := "docs-cart-table-scroll",
              table(
                cls        := "docs-cart-table",
                aria.label := "Cart contents",
                thead(
                  tr(
                    th("Product"),
                    th("Quantity"),
                    th("Subtotal"),
                    th(cls := "docs-visually-hidden", "Actions")
                  )
                ),
                tbody(
                  model.map(_.lines).splitBy(_.product.sku) { (sku, line) =>
                    tr(
                      dataAttr("cart-line") := sku,
                      td(
                        strong(line.map(_.product.name)),
                        span(
                          cls := "docs-cart-unit-price",
                          line.map(line => money(line.product.priceInCents))
                        )
                      ),
                      td(dataAttr("cart-quantity") := "", line.map(_.quantity.toString)),
                      td(
                        dataAttr("cart-subtotal") := "",
                        line.map(line => money(line.subtotalInCents))
                      ),
                      td(
                        button(
                          typ                        := "button",
                          dataAttr("remove-product") := sku,
                          aria.label := line.map(line => s"Remove one ${line.product.name}"),
                          on.click(line.map(line => Msg.Remove(line.product))),
                          "Remove one"
                        )
                      )
                    )
                  }
                ),
                tfoot(
                  tr(
                    th("Total"),
                    td(),
                    td(
                      dataAttr("cart-total") := "",
                      model.map(model => money(model.totalInCents))
                    ),
                    td()
                  )
                )
              )
            )
          )
      )
    )

  private def money(cents: Int): String =
    val dollars   = cents / 100
    val remainder = cents % 100
    f"$$$dollars%d.$remainder%02d"

  private def itemCountLabel(count: Int): String =
    if count == 1 then "1 item" else s"$count items"
end ShoppingCartExample

object ShoppingCartExample:
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
        case -1    => copy(lines = lines :+ Line(product, quantity = 1))
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
end ShoppingCartExample
// docs:end shopping-cart-example
