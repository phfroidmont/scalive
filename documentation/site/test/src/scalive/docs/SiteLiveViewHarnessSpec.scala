package scalive.docs

import zio.*
import zio.stream.ZStream
import zio.test.*

import scalive.*
import scalive.testing.ConnectedRender

object SiteLiveViewHarnessSpec extends ZIOSpecDefault:
  private enum Msg:
    case Increment

  private enum ResourceMsg:
    case Tick

  private enum FormMsg:
    case Changed(event: FormEvent[String])
    case Submitted(event: FormEvent[String])

  private val ResourceSubscription = SubscriptionKey("docs-test-resource")

  private val Name = FormField.requiredString(FormPath("profile", "name"), "Name is required.")

  private val formLiveView = new LiveView[FormMsg, FormEvent[String] | Null]:
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

  private val testLiveView = new LiveView[Msg, Int]:
    def mount(ctx: MountContext) = ZIO.succeed(0)

    def handleMessage(model: Int, ctx: MessageContext) =
      case Msg.Increment => ZIO.succeed(model + 1)

    override def view(model: Signal[Int]) =
      div(
        span(dataAttr("count") := "", model.map(_.toString)),
        button(dataAttr("increment") := "", on.click(Msg.Increment), "Increment")
      )

  override def spec = suite("SiteLiveViewHarnessSpec")(
    test("joins, dispatches bindings, and exposes committed HTML") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(testLiveView)
          initial <- harness.text("[data-count]")
          _       <- harness.click("[data-increment]")
          clicked <- harness.text("[data-count]")
          _       <- harness.send(Msg.Increment)
          sent    <- harness.text("[data-count]")
          _       <- harness.leave
        yield assertTrue(
          initial == "0",
          clicked == "1",
          sent == "2"
        )
      }
    },
    test("dispatches typed form change and submit bindings") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(formLiveView)
          _ <- harness.changeForm(
                 "[data-profile-form]",
                 Vector(Name.name -> "", "profile[_unused_email]" -> ""),
                 target = Some(Name.name)
               )
          changedTarget <- harness.text("[data-target]")
          changedUsed   <- harness.text("[data-used]")
          _ <- harness.submitForm(
                 "[data-profile-form]",
                 Vector(Name.name -> "Ada")
               )
          submitted <- harness.text("[data-submitted]")
          submitUsed <- harness.text("[data-used]")
        yield assertTrue(
          changedTarget == Name.name,
          changedUsed == "true",
          submitted == "true",
          submitUsed == "true"
        )
      }
    },
    test("releases nested LiveView resources when the parent leaves") {
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
                              .start(ResourceSubscription, SubscriptionDelivery.Lossless)(stream)
                              .as(())

                      def handleMessage(model: Unit, ctx: MessageContext) =
                        case ResourceMsg.Tick => ZIO.succeed(model)

                      override def view(model: Signal[Unit]) = div("resource child")

                    val parent = new LiveView.Eventless[Unit]:
                      def mount(ctx: MountContext) = ZIO.unit
                      override def view(model: Signal[Unit]) =
                        div(liveView("resource-child", child))

                    for
                      harness <- ConnectedRender.join(parent)
                      child   <- harness.joinNested("resource-child")
                      joined  <- child.isJoined
                      _ <- acquired.await.timeoutFail(
                             new RuntimeException("Nested resource did not start")
                           )(3.seconds)
                      _       <- harness.leave
                      _ <- released.await.timeoutFail(
                             new RuntimeException("Nested resource was not released")
                           )(3.seconds)
                      removed <- child.isJoined
                    yield assertTrue(joined, !removed)
                  }
      yield result
    }
  )
end SiteLiveViewHarnessSpec
