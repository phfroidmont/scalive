package bindings

import scalive.*

object CartDialog:
  enum Msg:
    case Close

  // docs:start bindings-portal
  val dialog: HtmlElement[Msg] =
    portal(DomRef("cart-dialog"), target = DomSelector.css("#modal-root"))(
      sectionTag(aria.label := "Cart", h2("Cart"), p("Review your items."))
    )
  // docs:end bindings-portal

  // docs:start bindings-focus-wrap
  val dialogBody: HtmlElement[Msg] =
    focusWrap(
      DomRef("cart-dialog-focus"),
      cls := "dialog-body",
      on.windowKeyDown.key(Key.Escape)(Msg.Close)
    )(
      h2("Cart"),
      button(typ := "button", on.click(Msg.Close), "Close")
    )
  // docs:end bindings-focus-wrap
