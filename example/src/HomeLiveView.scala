import scalive.*
import zio.*
import zio.stream.ZStream

class HomeLiveView() extends LiveView[String, Unit]:
  val links = List(
    "/counter"     -> "Counter",
    "/list?q=test" -> "List",
    "/todo"        -> "Todo"
  )

  def init = ()

  def update(model: Unit) = _ => model

  def view(model: Dyn[Unit]) =
    ul(
      cls := "mx-auto menu bg-base-100 rounded-box shadow-xl w-56",
      links.map((path, name) =>
        li(
          link.navigate(path, name)
        )
      )
    )

  def subscriptions(model: Unit) = ZStream.empty
