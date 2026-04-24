import zio.*
import zio.stream.ZStream

import scalive.*

class HomeLiveView() extends LiveView[String, Unit]:
  val links = List(
    "/counter"     -> "Counter",
    "/list?q=test" -> "List",
    "/todo"        -> "Todo"
  )

  def mount = ()

  def handleMessage(model: Unit) = _ => model

  def render(model: Unit) =
    ul(
      cls := "mx-auto menu bg-base-100 rounded-box shadow-xl w-56",
      links.map((path, name) =>
        li(
          link.navigate(path, name)
        )
      )
    )

  def subscriptions(model: Unit) = ZStream.empty
