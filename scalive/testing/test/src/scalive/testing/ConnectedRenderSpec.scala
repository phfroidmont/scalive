package scalive.testing

import zio.*
import zio.stream.ZStream
import zio.test.*

import scalive.*

object ConnectedRenderSpec extends ZIOSpecDefault:
  private enum Msg:
    case Increment

  private enum FormMsg:
    case Changed(event: FormEvent[String])
    case Submitted(event: FormEvent[String])

  private enum ResourceMsg:
    case Tick

  private val Name = FormField.requiredString(FormPath("profile", "name"), "Name is required.")

  def spec = suite("ConnectedRenderSpec")(
    test("joins through production admission and dispatches bindings and typed messages") {
      val view = new LiveView[Msg, Int]:
        def mount(ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(model: Int, ctx: MessageContext) =
          case Msg.Increment => ZIO.succeed(model + 1)
        override def view(model: Signal[Int]) =
          div(
            span(dataAttr("count") := "", model.map(_.toString)),
            button(dataAttr("increment") := "", on.click(Msg.Increment), "Increment")
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          initial   <- connected.text("[data-count]")
          _         <- connected.click("[data-increment]")
          clicked   <- connected.text("[data-count]")
          _         <- connected.send(Msg.Increment)
          sent      <- connected.text("[data-count]")
          joined    <- connected.isJoined
          _         <- connected.leave
          left      <- connected.isJoined
        yield assertTrue(initial == "0", clicked == "1", sent == "2", joined, !left)
      }
    },
    test("dispatches typed form change and submit bindings") {
      val view = new LiveView[FormMsg, FormEvent[String] | Null]:
        def mount(ctx: MountContext) = ZIO.succeed(null)
        def handleMessage(model: FormEvent[String] | Null, ctx: MessageContext) =
          case FormMsg.Changed(event)   => ZIO.succeed(event)
          case FormMsg.Submitted(event) => ZIO.succeed(event)
        override def view(model: Signal[FormEvent[String] | Null]) =
          val event = model.map(Option(_))
          form(
            dataAttr("profile-form") := "",
            Name.onChange(FormMsg.Changed(_)),
            Name.onSubmit(FormMsg.Submitted(_)),
            input(nameAttr := Name.name),
            span(dataAttr("target") := "", event.map(_.flatMap(_.target).fold("")(_.name))),
            span(dataAttr("submitted") := "", event.map(_.exists(_.submitted).toString)),
            span(dataAttr("used") := "", event.map(_.exists(_.state.isUsed(Name.path)).toString))
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _ <- connected.changeForm(
                 "[data-profile-form]",
                 Vector(Name.name -> "", "profile[_unused_email]" -> ""),
                 target = Some(Name.name)
               )
          changedTarget <- connected.text("[data-target]")
          changedUsed   <- connected.text("[data-used]")
          _ <- connected.submitForm("[data-profile-form]", Vector(Name.name -> "Ada"))
          submitted <- connected.text("[data-submitted]")
          submitUsed <- connected.text("[data-used]")
        yield assertTrue(
          changedTarget == Name.name,
          changedUsed == "true",
          submitted == "true",
          submitUsed == "true"
        )
      }
    },
    test("infers component targets from the nearest rendered component root") {
      object CounterComponent extends LiveComponent[Unit, Unit, Int]:
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          case () => ZIO.succeed(model + 1)
        override def view(props: Signal[Unit], model: Signal[Int], self: ComponentRef[Unit]) =
          div(
            dataAttr("component-counter") := "",
            span(dataAttr("component-count") := "", model.map(_.toString)),
            button(on.click.to(self)(()), "Increase component")
          )

      val counter = component(CounterComponent, "counter")
      val parent = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) = div(counter.render(()))

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(parent)
          _         <- connected.click("[data-component-counter] button")
          count     <- connected.text("[data-component-count]")
        yield assertTrue(count == "1")
      }
    },
    test("joins nested views and releases their resources when the parent leaves") {
      for
        acquired <- Promise.make[Nothing, Unit]
        released <- Promise.make[Nothing, Unit]
        result <- ZIO.scoped {
                    val child = new LiveView[ResourceMsg, Unit]:
                      def mount(ctx: MountContext) =
                        val stream = ZStream.fromZIO(
                          ZIO.acquireReleaseWith(acquired.succeed(()).unit)(
                            _ => released.succeed(()).unit
                          )(_ => ZIO.never)
                        )
                        ctx.connection match
                          case Connection.Disconnected => ZIO.unit
                          case Connection.Connected(connected) =>
                            connected.subscriptions
                              .start(
                                SubscriptionKey("connected-render-resource"),
                                SubscriptionDelivery.Lossless
                              )(stream)
                              .as(())
                      def handleMessage(model: Unit, ctx: MessageContext) =
                        case ResourceMsg.Tick => ZIO.succeed(model)
                      override def view(model: Signal[Unit]) = div("resource child")

                    val parent = new LiveView.Eventless[Unit]:
                      def mount(ctx: MountContext) = ZIO.unit
                      override def view(model: Signal[Unit]) =
                        div(liveView("resource-child", child))

                    for
                      connected <- ConnectedRender.join(parent)
                      nested    <- connected.joinNested("resource-child")
                      joined    <- nested.isJoined
                      _ <- acquired.await.timeoutFail(
                             new RuntimeException("Nested resource did not start")
                           )(3.seconds)
                      _         <- connected.leave
                      _ <- released.await.timeoutFail(
                             new RuntimeException("Nested resource was not released")
                           )(3.seconds)
                      removed <- nested.isJoined
                    yield assertTrue(joined, !removed)
                  }
      yield result
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds)
end ConnectedRenderSpec
