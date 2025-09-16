import scalive.*
import zio.*
import zio.stream.ZStream

class HomeLiveView() extends LiveView[String, Unit]:
  val links = List(
    "/counter"     -> "Counter",
    "/list?q=test" -> "List"
  )

  def init = ZIO.succeed(())

  def update(model: Unit) = _ => ZIO.succeed(model)

  def view(model: Dyn[Unit]) =
    ul(
      cls := "space-y-2",
      links.map((path, name) =>
        li(
          link.navigate(
            path,
            cls := "block px-4 py-2 rounded-lg text-gray-700 hover:bg-gray-100 hover:text-gray-900 font-medium transition",
            name
          )
        )
      )
    )

  def subscriptions(model: Unit) = ZStream.empty
