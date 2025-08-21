import zio.*
import zio.http.*
import scalive.*

object Example extends ZIOAppDefault:

  val liveRouter =
    LiveRouter(
      RootLayout(_),
      List(
        LiveRoute(
          Root,
          (_, req) =>
            val q = req.queryParam("q").map("Param : " ++ _).getOrElse("No param")
            ExampleLiveView(q)
        )
      )
    )

  override val run = Server.serve(liveRouter.routes).provide(Server.default)
