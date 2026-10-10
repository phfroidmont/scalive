package bindings

import scalive.*

object CartMarkup:
  final case class Product(sku: String, name: String, available: Boolean)

  final case class Line(product: Product, quantity: Int)

  final case class Model(lines: Vector[Line]):
    def itemCount: Int = lines.map(_.quantity).sum

  // docs:start bindings-messages
  enum Msg:
    case Add(product: Product)
    case Clear

  def cartButtons(product: Product): HtmlElement[Msg] =
    div(
      button(on.click(Msg.Add(product)), "Add"),
      button(on.click(Msg.Clear), "Clear")
    )
  // docs:end bindings-messages

  // docs:start bindings-tree
  def view(model: Signal[Model]): HtmlElement[Msg] =
    sectionTag(
      cls        := "cart",
      aria.label := "Shopping cart",
      h1("Cart"),
      p(model.map(cart => s"${cart.itemCount} items"))
    )
  // docs:end bindings-tree

  // docs:start bindings-token-list
  def panel(expanded: Signal[Boolean]): HtmlElement[Msg] =
    div(
      cls := "panel selected",
      cls := expanded.map(isExpanded => if isExpanded then "expanded" else ""),
      cls := "selected wide"
    )
  // docs:end bindings-token-list

  // docs:start bindings-namespaced-attributes
  def addButton(product: Product): HtmlElement[Msg] =
    button(
      typ                 := "button",
      dataAttr("product") := product.sku,
      aria.label          := s"Add ${product.name}",
      disabled            := !product.available,
      on.click(Msg.Add(product)),
      product.name
    )
  // docs:end bindings-namespaced-attributes

  // docs:start bindings-custom-attribute
  private val popover = htmlAttr("popover", scalive.codecs.StringAsIsEncoder)

  val helpPopover: HtmlElement[Msg] = div(popover := "manual", "Details")
  // docs:end bindings-custom-attribute

  // docs:start bindings-keyed-rows
  def lineRows(model: Signal[Model]): HtmlElement[Msg] =
    tbody(
      model.map(_.lines).splitBy(_.product.sku) { (sku, line) =>
        tr(
          idAttr := s"cart-line-$sku",
          td(line.map(_.product.name)),
          td(line.map(_.quantity.toString))
        )
      }
    )
  // docs:end bindings-keyed-rows
end CartMarkup
