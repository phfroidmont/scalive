// docs:start learn-cart
package quickstart

import zio.{Task, ZIO}

import scalive.*

final class CartLiveView extends LiveView[CartLiveView.Msg, CartLiveView.Model]:
  import CartLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model.empty)

  // docs:start learn-cart-handle-message
  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Add(product)    => ZIO.succeed(model.add(product))
    case Msg.Remove(product) => ZIO.succeed(model.remove(product))
    case Msg.Clear           => ZIO.succeed(Model.empty)
  // docs:end learn-cart-handle-message

  // docs:start learn-cart-view
  def view(model: Signal[Model]): HtmlElement[Msg] =
    mainTag(
      h1("Cart"),
      // docs:start learn-cart-products
      div(
        Product.values.toVector.map { product =>
          button(on.click(Msg.Add(product)), s"Add ${product.name}")
        }
      ),
      // docs:end learn-cart-products
      // docs:start learn-cart-option
      model.map(_.lastAdded).option { product =>
        p(role := "status", product.map(product => s"Added ${product.name}."))
      },
      // docs:end learn-cart-option
      // docs:start learn-cart-choose
      model
        .map(_.lines.isEmpty).choose(
          p("Your cart is empty."),
          table(
            tbody(
              model.map(_.lines).splitBy(_.product.sku)((_, line) => lineRow(line))
            )
          )
        ),
      // docs:end learn-cart-choose
      p("Total: ", model.map(cart => money(cart.totalInCents))),
      // docs:start learn-cart-when
      model.map(_.totalInCents >= FreeShippingFromCents).when(p("Your order ships free.")),
      // docs:end learn-cart-when
      button(disabled := model.map(_.lines.isEmpty), on.click(Msg.Clear), "Clear")
    )
  // docs:end learn-cart-view

  // docs:start learn-cart-row
  private def lineRow(line: Signal[Line]): HtmlElement[Msg] =
    tr(
      td(line.map(_.product.name)),
      td(line.map(_.quantity.toString)),
      td(line.map(line => money(line.subtotalInCents))),
      td(button(on.click(line.map(line => Msg.Remove(line.product))), "Remove one"))
    )
  // docs:end learn-cart-row
end CartLiveView

object CartLiveView:
  // docs:start learn-cart-model
  enum Product(val sku: String, val name: String, val priceInCents: Int):
    case Coffee   extends Product("coffee", "Coffee beans", 1299)
    case Notebook extends Product("notebook", "Notebook", 850)
    case Sticker  extends Product("sticker", "Scalive sticker", 250)

  final case class Line(product: Product, quantity: Int):
    def subtotalInCents: Int = product.priceInCents * quantity

  final case class Model(lines: Vector[Line], lastAdded: Option[Product]):
    def add(product: Product): Model =
      val updated = lines.indexWhere(_.product == product) match
        case -1    => lines :+ Line(product, quantity = 1)
        case index => lines.updated(index, lines(index).copy(quantity = lines(index).quantity + 1))
      Model(updated, lastAdded = Some(product))

    def remove(product: Product): Model =
      val updated = lines.flatMap { line =>
        if line.product != product then Some(line)
        else if line.quantity > 1 then Some(line.copy(quantity = line.quantity - 1))
        else None
      }
      Model(updated, lastAdded = None)

    def totalInCents: Int = lines.map(_.subtotalInCents).sum

  object Model:
    val empty = Model(Vector.empty, lastAdded = None)

  enum Msg:
    case Add(product: Product)
    case Remove(product: Product)
    case Clear
  // docs:end learn-cart-model

  // docs:start learn-cart-money
  val FreeShippingFromCents = 3000

  def money(cents: Int): String = f"$$${cents / 100}%d.${cents % 100}%02d"
  // docs:end learn-cart-money
end CartLiveView
// docs:end learn-cart
