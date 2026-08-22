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

  private def htmlResponse(value: String): Response =
    Response(body = Body.fromString(value))

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
    test("submits an ordinary POST form and follows its redirect with updated cookies") {
      for
        received <- Ref.make(Option.empty[(String, Option[String], Option[String], Boolean)])
        routes = Routes(
                   Method.GET / "forms" / "new" -> handler(
                     htmlResponse(
                         """<form action="submit?source=profile#ignored" method="post">
                           |  <input name="_csrf_token" value="rendered-token">
                           |</form>""".stripMargin
                       ).addCookie(Cookie.Response("browser", "seed"))
                   ),
                   Method.POST / "forms" / "submit" -> handler((request: Request) =>
                     request.body.asString.orDie.flatMap { body =>
                       received.set(
                         Some(
                           (
                             body,
                             request.cookie("browser").map(_.content),
                             request.body.mediaType.map(_.fullType),
                             request.url.fragment.isEmpty
                           )
                         )
                       ) *> ZIO.succeed(
                         Response
                           .seeOther(url("/done#ignored"))
                           .addCookie(
                             Cookie.Response(
                               "browser",
                               "",
                               maxAge = Some(zio.Duration.Zero)
                             )
                           ).addCookie(Cookie.Response("session", "ready"))
                       )
                     }
                   ),
                   Method.GET / "done" -> handler((request: Request) =>
                     Response.text(
                       s"browser=${request.cookie("browser").map(_.content)};" +
                         s"session=${request.cookie("session").map(_.content)};" +
                         s"fragment=${request.url.fragment.isEmpty}"
                     )
                   )
                 )
        page <- DisconnectedRender.run(
                  routes,
                  Request.get(url("/forms/new?return=profile"))
                )
        renderedForm <- ZIO
                          .fromEither(page.form())
                          .orDieWith(error =>
                            new AssertionError(
                              s"$error for ${page.response.status}: ${page.html}"
                            )
                          )
        redirect <- renderedForm.submit(
                      routes,
                      FormData(
                        Vector(
                          "_csrf_token" -> "rendered-token",
                          "profile[tag]" -> "first",
                          "profile[tag]" -> "second"
                        )
                      ),
                      submitter = Some(FormSubmitter("save", "profile"))
                    )
        submission <- received.get
        destination <- redirect.followSeeOther(routes)
      yield assertTrue(
        redirect.response.status == Status.SeeOther,
        redirect.response.header(Header.Location).exists(_.url.encode == "/done#ignored"),
        submission.contains(
          (
            "_csrf_token=rendered-token&profile%5Btag%5D=first&profile%5Btag%5D=second&save=profile",
            Some("seed"),
            Some("application/x-www-form-urlencoded"),
            true
          )
        ),
        destination.response.status == Status.Ok,
        destination.text == "browser=None;session=Some(ready);fragment=true"
      )
    },
    test("submits a relative GET form by replacing its action query") {
      val routes = Routes(
        Method.GET / "forms" / "new" -> handler(
          htmlResponse("""<form action="search?fixed=discarded"></form>""")
        ),
        Method.GET / "forms" / "search" -> handler((request: Request) =>
          Response.text(
            s"${request.url.encode};cookie=${request.cookie("initial").map(_.content)}"
          )
        )
      )

      for
        page <- DisconnectedRender.run(
                  routes,
                  Request
                    .get(url("/forms/new?source=current"))
                    .addCookie(Cookie.Request("initial", "retained"))
                )
        renderedForm <- ZIO
                          .fromEither(page.form())
                          .orDieWith(error => new AssertionError(error.toString))
        result <- renderedForm.submit(
                    routes,
                    FormData(Vector("tag" -> "first", "tag" -> "second", "q" -> "two words"))
                  )
      yield assertTrue(
        result.response.status == Status.Ok,
        result.text.contains("/forms/search?"),
        result.text.contains("tag=first"),
        result.text.contains("tag=second"),
        result.text.contains("q=two+words"),
        result.text.contains("cookie=Some(retained)"),
        !result.text.contains("fixed=discarded"),
        !result.text.contains("source=current")
      )
    },
    test("honors the document base and same-origin absolute actions") {
      val routes = Routes(
        Method.GET / "forms" / "base" -> handler(
          htmlResponse("""<base href="/account/"><form action="save"></form>""")
        ),
        Method.GET / "account" / "save" -> handler(Response.text("base action")),
        Method.GET / "absolute" -> handler(
          htmlResponse(
            """<base href="https://cdn.example/">
              |<form action="https://app.example/account/save"></form>""".stripMargin
          )
        )
      )

      for
        based <- DisconnectedRender.run(routes, Request.get(url("/forms/base")))
        basedForm <- ZIO
                       .fromEither(based.form())
                       .orDieWith(error => new AssertionError(error.toString))
        basedResult <- basedForm.submit(routes, FormData.empty)
        absolute <- DisconnectedRender.run(
                      routes,
                      Request.get(url("https://app.example/absolute"))
                    )
        absoluteForm <- ZIO
                          .fromEither(absolute.form())
                          .orDieWith(error => new AssertionError(error.toString))
        absoluteResult <- absoluteForm.submit(routes, FormData.empty)
      yield assertTrue(
        basedResult.text == "base action",
        absoluteResult.text == "base action"
      )
    },
    test("rejects external form actions and non-303 redirect following") {
      val routes = Routes(
        Method.GET / "external" -> handler(
          htmlResponse("""<form action="https://example.test/submit"></form>""")
        ),
        Method.GET / "scheme-relative" -> handler(
          htmlResponse("""<form action="//evil.example/ordinary"></form>""")
        ),
        Method.GET / "multipart" -> handler(
          htmlResponse(
            """<form action="/ordinary" method="post" enctype="multipart/form-data"></form>"""
          )
        ),
        Method.GET / "invalid-encoding" -> handler(
          htmlResponse(
            """<form action="/ordinary-post" method="post" enctype="invalid"></form>"""
          )
        ),
        Method.GET / "ordinary" -> handler(Response.text("ordinary")),
        Method.POST / "ordinary-post" -> handler(Response.text("ordinary post")),
        Method.GET / "missing-location" -> handler(Response(status = Status.SeeOther))
      )

      for
        external <- DisconnectedRender.run(routes, Request.get(url("/external")))
        renderedForm <- ZIO
                          .fromEither(external.form())
                          .orDieWith(error => new AssertionError(error.toString))
        rejected <- renderedForm.submit(routes, FormData.empty).either
        schemeRelative <- DisconnectedRender.run(
                            routes,
                            Request.get(url("/scheme-relative"))
                          )
        schemeRelativeForm <- ZIO
                                .fromEither(schemeRelative.form())
                                .orDieWith(error => new AssertionError(error.toString))
        rejectedSchemeRelative <- schemeRelativeForm.submit(routes, FormData.empty).either
        multipart <- DisconnectedRender.run(routes, Request.get(url("/multipart")))
        multipartForm <- ZIO
                           .fromEither(multipart.form())
                           .orDieWith(error => new AssertionError(error.toString))
        rejectedEncoding <- multipartForm.submit(routes, FormData.empty).either
        invalidEncoding <- DisconnectedRender.run(
                             routes,
                             Request.get(url("/invalid-encoding"))
                           )
        invalidEncodingForm <- ZIO
                                 .fromEither(invalidEncoding.form())
                                 .orDieWith(error => new AssertionError(error.toString))
        defaultEncoding <- invalidEncodingForm.submit(routes, FormData.empty)
        ordinary <- DisconnectedRender.run(routes, Request.get(url("/ordinary")))
        notRedirect <- ordinary.followSeeOther(routes).either
        missing <- DisconnectedRender.run(routes, Request.get(url("/missing-location")))
        noLocation <- missing.followSeeOther(routes).either
      yield assertTrue(
        rejected.left.exists(_.getMessage.contains("external form action")),
        rejectedSchemeRelative.left.exists(_.getMessage.contains("external form action")),
        rejectedEncoding.left.exists(_.getMessage.contains("Unsupported POST form encoding")),
        defaultEncoding.text == "ordinary post",
        notRedirect.left.exists(_.getMessage.contains("303 See Other")),
        noLocation.left.exists(_.getMessage.contains("no Location"))
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
