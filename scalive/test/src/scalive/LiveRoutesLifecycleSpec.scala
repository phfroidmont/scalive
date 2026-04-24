package scalive

import zio.*
import zio.http.*
import zio.http.codec.HttpCodec
import zio.stream.ZStream
import zio.test.*

object LiveRoutesLifecycleSpec extends ZIOSpecDefault:

  private val identityLayout: HtmlElement => HtmlElement = element => element

  private def runRequest(routes: Routes[Any, Nothing], path: String) =
    URL.decode(path) match
      case Left(error) => ZIO.die(error)
      case Right(url)  => ZIO.scoped(routes.runZIO(Request.get(url)))

  override def spec = suite("LiveRoutesLifecycleSpec")(
    test("runs mount then handleParams before disconnected render") {
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Unit]:

               override val queryCodec: LiveQueryCodec[Option[String]] =
                 LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

               def mount = callsRef.update(_ :+ "mount").as(())

                 override def handleParams(model: Unit, params: Option[String], _url: URL) =
                  callsRef.update(_ :+ s"params:${params.getOrElse("")}").as(model)

               def handleMessage(model: Unit) = _ => ZIO.succeed(model)

               def render(model: Unit): HtmlElement =
                 div("ok")

               def subscriptions(model: Unit) = ZStream.empty
        routes =
          LiveRoutes(layout = identityLayout)(
            Method.GET / Root -> liveHandler(lv)
          )
        response <- runRequest(routes, "/?q=1")
        calls    <- callsRef.get
      yield assertTrue(response.status == Status.Ok, calls == List("mount", "params:1"))
    },
    test("honors initial pushPatch with HTTP redirect") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit

        override def handleParams(model: Unit, _query: queryCodec.Out, _url: URL) =
          LiveContext.pushPatch("/target").as(model)

        def handleMessage(model: Unit) = _ => ZIO.succeed(model)

        def render(model: Unit): HtmlElement =
          div("ok")

        def subscriptions(model: Unit) = ZStream.empty

      val routes =
        LiveRoutes(layout = identityLayout)(
          Method.GET / Root -> liveHandler(lv)
        )

      for response <- runRequest(routes, "/")
      yield assertTrue(
        response.status.isRedirection,
        response.rawHeader("location").contains("/target")
      )
    }
  )
end LiveRoutesLifecycleSpec
