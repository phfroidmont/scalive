package scalive.testing

import java.time.Duration

import zio.*
import zio.http.*
import zio.test.*

import scalive.*

object DisconnectedRenderSpec extends ZIOSpecDefault:

  private enum Msg:
    case Changed, Submitted

  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false
  ).toOption.get

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  def spec = suite("DisconnectedRenderSpec")(
    test("renders the production disconnected lifecycle and queries a form semantically") {
      val view = new LiveView.Routed[Msg, Option[String], Option[String]]:
        def mount(notice: Option[String], ctx: MountContext) =
          ZIO.succeed(notice)

        override def handleParams(
          model: Option[String],
          notice: Option[String],
          url: URL,
          ctx: ParamsContext
        ) =
          ZIO.succeed(notice)

        def handleMessage(model: Option[String], ctx: MessageContext) =
          case Msg.Changed | Msg.Submitted => ZIO.succeed(model)

        override def view(model: Signal[Option[String]]) =
          div(
            p(model.map(_.getOrElse("missing"))),
            form(
              idAttr            := "profile-form",
              action            := "/profiles",
              method            := "post",
              scalive.on.change(Msg.Changed),
              scalive.on.submit(Msg.Submitted),
              phx.triggerAction := true,
              input(nameAttr := "profile[tag]", value := "first"),
              input(nameAttr := "profile[tag]", value := "second")
            )
          )

      val application = scalive.Live.router(scalive.live.queryOptional[String]("notice")(view))
      val routes      = ZioHttp.routes(application, config)

      for
        page <- DisconnectedRender.run(routes, Request.get(url("/?notice=ready")))
        renderedForm <- ZIO
                          .fromEither(
                            page.form(
                              FormQuery(
                                action = Some("/profiles"),
                                method = Some(Method.POST)
                              )
                            )
                          ).orDieWith(error => new AssertionError(error.toString))
        replayedBody <- page.response.body.asString
      yield assertTrue(
        page.response.status == Status.Ok,
        replayedBody == page.html,
        page.text.contains("ready"),
        renderedForm.id.contains("profile-form"),
        renderedForm.action.contains("/profiles"),
        renderedForm.method == Method.POST,
        renderedForm.names == Vector("profile[tag]", "profile[tag]"),
        renderedForm.values(FormPath("profile", "tag")) == Vector("first", "second"),
        renderedForm.hasChangeBinding,
        renderedForm.hasSubmitBinding,
        renderedForm.triggersAction
      )
    },
    test("reports zero and multiple form matches explicitly") {
      val view = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) =
          div(
            form(action := "/first"),
            form(action := "/second")
          )

      val routes  = ZioHttp.routes(scalive.Live.router(scalive.live(view)), config)
      val all     = FormQuery()
      val missing = FormQuery(action = Some("/missing"))

      for page <- DisconnectedRender.run(routes, Request.get(URL.root))
      yield assertTrue(
        page.forms.map(_.method) == Vector(Method.GET, Method.GET),
        page.form(all) == Left(FormQueryError.MultipleMatches(all, 2)),
        page.form(missing) == Left(FormQueryError.NotFound(missing))
      )
    },
    test("renders every initial stream row before connected limits are applied") {
      final case class Item(id: Int)
      val items = LiveStreamDef.byId[Item, Int]("items")(_.id).keepLast(1)
      val view = new LiveView.Eventless[LiveStream[Item]]:
        def mount(ctx: MountContext) = ctx.streams.create(items, List(Item(1), Item(2), Item(3)))
        override def view(model: Signal[LiveStream[Item]]) =
          ul(
            idAttr     := "items",
            phx.update := PhxUpdate.Stream,
            model.stream((domId, item) => li(idAttr := domId, item.map(_.id.toString)))
          )

      val routes = ZioHttp.routes(scalive.Live.router(scalive.live(view)), config)

      for page <- DisconnectedRender.run(routes, Request.get(URL.root))
      yield assertTrue(
        page.response.status == Status.Ok,
        page.html.contains("id=\"items-1\""),
        page.html.contains("id=\"items-2\""),
        page.html.contains("id=\"items-3\"")
      )
    }
  )
end DisconnectedRenderSpec
