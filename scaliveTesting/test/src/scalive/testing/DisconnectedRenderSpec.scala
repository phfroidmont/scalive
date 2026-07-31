package scalive.testing

import zio.*
import zio.http.*
import zio.test.*

import scalive.*

object DisconnectedRenderSpec extends ZIOSpecDefault:

  private enum Msg:
    case Changed, Submitted

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  def spec = suite("DisconnectedRenderSpec")(
    test("renders the disconnected lifecycle and queries a form semantically") {
      val view = new LiveView.Routed[Msg, Option[String], Option[String]]:
        def mount(ctx: MountContext) =
          ZIO.none

        override def handleParams(
          model: Option[String],
          notice: Option[String],
          url: URL,
          ctx: ParamsContext
        ) =
          ZIO.succeed(notice)

        def handleMessage(model: Option[String], ctx: MessageContext) =
          case Msg.Changed | Msg.Submitted => ZIO.succeed(model)

        def render(model: Option[String]) =
          div(
            p(model.getOrElse("missing")),
            form(
              idAttr            := "profile-form",
              action            := "/profiles",
              method            := "post",
              phx.onChange(Msg.Changed),
              phx.onSubmit(Msg.Submitted),
              phx.triggerAction := true,
              input(nameAttr := "profile[tag]", value := "first"),
              input(nameAttr := "profile[tag]", value := "second")
            )
          )

      val routes = scalive.Live.router(scalive.live.queryOptional[String]("notice")(view))

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
        def render(model: Unit) =
          div(
            form(action := "/first"),
            form(action := "/second")
          )

      val routes = scalive.Live.router(scalive.live(view))
      val all    = FormQuery()
      val missing = FormQuery(action = Some("/missing"))

      for page <- DisconnectedRender.run(routes, Request.get(URL.root))
      yield assertTrue(
        page.forms.map(_.method) == Vector(Method.GET, Method.GET),
        page.form(all) == Left(FormQueryError.MultipleMatches(all, 2)),
        page.form(missing) == Left(FormQueryError.NotFound(missing))
      )
    }
  )
end DisconnectedRenderSpec
