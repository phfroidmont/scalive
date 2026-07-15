import scalive.*
import scalive.LiveIO.given

class HomeLiveView() extends LiveView[String, Unit]:
  val links = List(
    ExampleRoutes.counter.location                                     -> "Counter",
    ExampleRoutes.list.location(ListLiveView.ListParams(Some("test"))) -> "List",
    ExampleRoutes.todo.location                                        -> "Todo"
  )

  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    _ => model

  def render(model: Unit) =
    ul(
      cls := "mx-auto menu bg-base-100 rounded-box shadow-xl w-56",
      links.map((location, name) => li(link.navigate(location, name)))
    )
