// docs:start bindings-liveview
package bindings

import zio.{Task, ZIO, durationInt}

import scalive.*

final class ProductSearchLiveView
    extends LiveView[ProductSearchLiveView.Msg, ProductSearchLiveView.Model]:
  import ProductSearchLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(query = "", shown = PageSize, helpOpen = false))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Search(query) => ZIO.succeed(model.copy(query = query, shown = PageSize))
    case Msg.LoadMore      => ZIO.succeed(model.copy(shown = model.shown + PageSize))
    case Msg.OpenHelp      => ZIO.succeed(model.copy(helpOpen = true))
    case Msg.CloseHelp     => ZIO.succeed(model.copy(helpOpen = false))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    mainTag(
      input(
        typ        := "search",
        aria.label := "Search products",
        on.keyUp.debounce(300.millis).withValue(Msg.Search(_))
      ),
      button(typ := "button", on.click(Msg.OpenHelp), "Help"),
      model
        .map(_.helpOpen).when(
          div(
            on.clickAway(Msg.CloseHelp),
            on.windowKeyDown.key(Key.Escape)(Msg.CloseHelp),
            p("Results update as you type. Scroll down to load more.")
          )
        ),
      ul(
        idAttr     := "results",
        aria.label := "Results",
        on.viewportBottom(Msg.LoadMore),
        model.map(_.visible).splitBy(_.sku) { (sku, product) =>
          li(idAttr := s"product-$sku", product.map(_.name))
        }
      )
    )
end ProductSearchLiveView

object ProductSearchLiveView:
  final case class Product(sku: String, name: String)

  val PageSize = 50

  val Catalog: Vector[Product] =
    Vector.tabulate(500)(index => Product(s"p${index + 1}", s"Product ${index + 1}"))

  final case class Model(query: String, shown: Int, helpOpen: Boolean):
    def visible: Vector[Product] =
      Catalog.filter(_.name.toLowerCase.contains(query.toLowerCase)).take(shown)

  enum Msg:
    case Search(query: String)
    case LoadMore
    case OpenHelp
    case CloseHelp
// docs:end bindings-liveview
