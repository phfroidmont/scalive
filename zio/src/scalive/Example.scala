package scalive

import zio.*
import zio.http.*

object Example extends ZIOAppDefault:

  val liveRouter =
    LiveRouter(
      RootLayout(_),
      List(
        LiveRoute(
          Root / "test",
          (_, req) =>
            val q = req.queryParam("q").map("Param : " ++ _).getOrElse("No param")
            TestView(q)
        )
      )
    )

  override val run = Server.serve(liveRouter.routes).provide(Server.default)

final case class MyModel(elems: List[NestedModel], cls: String = "text-xs")
final case class NestedModel(name: String, age: Int)

class TestView(someParam: String) extends LiveView[Nothing]:

  val model = Var(
    MyModel(
      List(
        NestedModel("a", 10),
        NestedModel("b", 15),
        NestedModel("c", 20)
      )
    )
  )

  def handleCommand(cmd: Nothing): Unit = ()

  val el =
    div(
      h1(someParam),
      idAttr := "42",
      cls    := model(_.cls),
      ul(
        model(_.elems).splitByIndex((_, elem) =>
          li(
            "Nom: ",
            elem(_.name),
            " Age: ",
            elem(_.age.toString)
          )
        )
      )
    )
end TestView
