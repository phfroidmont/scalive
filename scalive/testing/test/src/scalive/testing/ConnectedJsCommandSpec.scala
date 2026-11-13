package scalive.testing

import java.time.Duration

import zio.*
import zio.http.{Request, URL}
import zio.test.*

import scalive.*

object ConnectedJsCommandSpec extends ZIOSpecDefault:
  /** A LiveView whose messages are strings it appends to a log that `content` renders from. */
  private class Recording(content: Signal[Vector[String]] => Mod[String])
      extends LiveView[String, Vector[String]]:
    def mount(ctx: MountContext) = ZIO.succeed(Vector.empty)
    def handleMessage(model: Vector[String], ctx: MessageContext) = msg =>
      ZIO.succeed(model :+ msg)
    override def view(model: Signal[Vector[String]]) =
      div(span(idAttr := "received", model.map(_.mkString(","))), content(model))

  private object Note:
    final case class Value(body: String)
    val Root       = FormRoot("note")
    val Body       = Root.text("body")
    val Definition = Root.product[Value]((body = Body))

  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("scalive.test"))
  ).toOption.get

  def spec = suite("ConnectedJsCommandSpec")(
    test("a phx-mounted push is sent once when the view joins") {
      val view = Recording(_ => div(dom.onMount(JS.push("opened"))))
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          received  <- connected.text("#received")
        yield assertTrue(received == "opened")
      }
    },
    test("click sends the push of a JS command binding") {
      val view = Recording(_ => button(idAttr := "go", on.click(JS.push("clicked"))))
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _         <- connected.click("#go")
          received  <- connected.text("#received")
        yield assertTrue(received == "clicked")
      }
    },
    test("an element an action adds sends its phx-mounted push before the action returns") {
      val view = Recording(log =>
        div(
          button(idAttr := "show", on.click("show")),
          log.map(_.contains("show")).when(div(idAttr := "panel", dom.onMount(JS.push("panel")))),
          div(dom.onMount(JS.push("static")), log.map(_.size.toString))
        )
      )
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          joined    <- connected.text("#received")
          _         <- connected.click("#show")
          shown     <- connected.text("#received")
          _         <- connected.click("#show")
          again     <- connected.text("#received")
        yield assertTrue(
          joined == "static",
          shown == "static,show,panel",
          again == "static,show,panel,show"
        )
      }
    },
    test("keyed rows that move do not mount again, and a new keyed row mounts once") {
      def rows(log: Vector[String]) =
        log.reverse.collectFirst { case s"rows:$keys" => keys.split(',').toVector }
          .getOrElse(Vector("a", "b"))
      val view = Recording(log =>
        ul(
          log
            .map(rows).splitBy(identity)((key, _) =>
              li(idAttr := s"row-$key", dom.onMount(JS.push(s"mounted:$key")), span(key))
            )
        )
      )
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _         <- connected.send("rows:b,a")
          moved     <- connected.text("#received")
          _         <- connected.send("rows:b,c,a")
          added     <- connected.text("#received")
        yield assertTrue(
          moved == "mounted:a,mounted:b,rows:b,a",
          added == "mounted:a,mounted:b,rows:b,a,rows:b,c,a,mounted:c"
        )
      }
    },
    test("a join runs phx-mounted, then phx-connected, and a reconnect runs phx-connected again") {
      val view = Recording(_ =>
        div(dom.onMount(JS.push("mounted")), connection.onConnect(JS.push("connected")))
      )
      ZIO.scoped {
        for
          client <- ConnectedRender.open(
                      scalive.Live.router(scalive.live -> view),
                      config,
                      Request.get(URL.root)
                    )
          first       <- client.join
          joined      <- first.text("#received")
          second      <- client.reconnect
          reconnected <- second.text("#received")
        yield assertTrue(joined == "mounted,connected", reconnected == "mounted,connected")
      }
    },
    test("a phx-mounted push inside a component goes to the component") {
      object Panel extends LiveComponent[Unit, String, Vector[String]]:
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(Vector.empty)
        def handleMessage(props: Unit, model: Vector[String], ctx: MessageContext) = msg =>
          ZIO.succeed(model :+ msg)
        override def view(props: Signal[Unit], model: Signal[Vector[String]]) =
          div(span(idAttr := "panel", dom.onMount(JS.push("opened")), model.map(_.mkString(","))))
      val view = Recording(_ => component(Panel, "panel").render(()))
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          root      <- connected.text("#received")
          panel     <- connected.text("#panel")
        yield assertTrue(root == "", panel == "opened")
      }
    },
    test("a phx-mounted push inside a nested LiveView goes to the nested LiveView") {
      val child  = Recording(_ => div(idAttr := "child-content", dom.onMount(JS.push("child"))))
      val parent = Recording(_ => div(idAttr := "parent", liveView("child", child)))
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(parent)
          nested    <- connected.joinNested("child")
          received  <- nested.text("#received")
          root      <- connected.selectAll("#received")
        yield assertTrue(received == "child", root.map(_.text) == Vector(""))
      }
    },
    test("lifecycle pushes have no event type on the wire, unlike a click") {
      val view = new Recording(_ =>
        div(dom.onMount(JS.push("mounted")), button(idAttr := "go", on.click(JS.push("clicked"))))
      ):
        override def hooks = LiveHooks.onRawEvent { (model, event, _) =>
          ZIO.succeed(LiveEventHookResult.cont(model :+ s"type=${event.kind.getOrElse("null")}"))
        }
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _         <- connected.click("#go")
          received  <- connected.text("#received")
        yield assertTrue(received == "type=null,mounted,type=click,clicked")
      }
    },
    test("a command sends its pushes in order and ignores its other operations") {
      val view = Recording(_ =>
        div(
          dom.onMount(
            JS.addClass("open").push("first").pushFocus().pushPatchUnsafe("/elsewhere").push(
              "second"
            )
          ),
          div(dom.onMount(JS.addClass("idle")))
        )
      )
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          received  <- connected.text("#received")
        yield assertTrue(received == "first,second")
      }
    },
    test("a phx-mounted push that keeps adding mounted elements fails, naming them") {
      val view = Recording(log =>
        ul(
          log
            .map(messages => (0 to messages.size).toVector).splitBy(identity)((index, _) =>
              li(idAttr := s"row-$index", dom.onMount(JS.push("more")))
            )
        )
      )
      ZIO.scoped {
        for error <- ConnectedRender.join(view).flip
        yield assertTrue(
          error.getMessage.contains("after 20 rounds"),
          error.getMessage.contains("<li id=\"row-20\">")
        )
      }
    },
    test("HTML the server sends on its own runs the phx-mounted pushes of the elements it adds") {
      val view = new Recording(log =>
        div(
          log.map(_.contains("late")).when(div(idAttr := "late", dom.onMount(JS.push("late-panel")))),
          log.map(_.contains("later")).when(div(idAttr := "later", dom.onMount(JS.push("later-panel"))))
        )
      ):
        // A `wait:<message>` message sends `<message>` back to the view a little later.
        override def handleMessage(model: Vector[String], ctx: MessageContext) =
          case s"wait:$message" =>
            ctx.subscriptions.sendAfter(SubscriptionKey(message), 20.millis)(message).as(model)
          case message => ZIO.succeed(model :+ message)
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _         <- connected.send("wait:late")
          _         <- connected.awaitDiff
          diffed    <- connected.text("#received")
          _         <- connected.send("wait:later")
          awaited   <- connected.awaitHtml("the later panel", 3.seconds)(_.contains("later-panel"))
        yield assertTrue(
          diffed == "late,late-panel",
          awaited.contains("late,late-panel,later,later-panel")
        )
      }
    },
    test("form actions send the pushes of JS command form bindings") {
      val view = Recording(_ =>
        div(
          form(
            idAttr := "raw",
            on.change(JS.push("raw-changed")),
            on.submit(JS.push("raw-submitted")),
            input(nameAttr := "q")
          ),
          form(
            idAttr := "typed",
            on.change(JS.push("typed-changed")),
            on.submit(JS.push("typed-submitted")),
            input(nameAttr := Note.Body.name)
          )
        )
      )
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _         <- connected.changeForm("#raw", Vector("q" -> "a"))
          _         <- connected.submitForm("#raw", Vector("q" -> "a"))
          _         <- connected.changeForm(Note.Definition)(Note.Body.initial("b"))
          _         <- connected.submitForm(Note.Definition)(Note.Body.initial("b"))
          received  <- connected.text("#received")
        yield assertTrue(received == "raw-changed,raw-submitted,typed-changed,typed-submitted")
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds)
end ConnectedJsCommandSpec
