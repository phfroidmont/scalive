package scalive.docs

import zio.*
import zio.stream.ZStream
import zio.test.*

import scalive.*

object SiteLiveViewHarnessSpec extends ZIOSpecDefault:
  private enum Msg:
    case Increment

  private enum ResourceMsg:
    case Tick

  private val ResourceSubscription = SubscriptionKey("docs-test-resource")

  private val testLiveView = new LiveView[Msg, Int]:
    def mount(ctx: MountContext) = ZIO.succeed(0)

    def handleMessage(model: Int, ctx: MessageContext) =
      case Msg.Increment => ZIO.succeed(model + 1)

    def render(model: Int) =
      div(
        span(dataAttr("count") := "", model.toString),
        button(dataAttr("increment") := "", on.click(Msg.Increment), "Increment")
      )

  override def spec = suite("SiteLiveViewHarnessSpec")(
    test("joins, dispatches bindings, collects output, and exposes committed HTML") {
      ZIO.scoped {
        for
          harness <- SiteLiveViewHarness.join(testLiveView)
          initial <- harness.text("[data-count]")
          _       <- harness.click("[data-increment]")
          clicked <- harness.text("[data-count]")
          _       <- harness.sendServer(Msg.Increment)
          sent    <- harness.text("[data-count]")
          outputs <- harness.outputs
          _       <- harness.leave
        yield assertTrue(
          initial == "0",
          clicked == "1",
          sent == "2",
          outputs.size >= 3
        )
      }
    },
    test("releases nested LiveView resources when the parent leaves") {
      for
        released <- Promise.make[Nothing, Unit]
        result <- ZIO.scoped {
                    val child = new LiveView[ResourceMsg, Unit]:
                      def mount(ctx: MountContext) =
                        val stream = ZStream.fromZIO(
                          ZIO.acquireReleaseWith(ZIO.unit)(_ => released.succeed(()).unit)(_ => ZIO.never)
                        )
                        ctx.subscriptions.start(ResourceSubscription)(stream).as(())

                      def handleMessage(model: Unit, ctx: MessageContext) =
                        case ResourceMsg.Tick => ZIO.succeed(model)

                      def render(model: Unit) = div("resource child")

                    val parent = new LiveView.Eventless[Unit]:
                      def mount(ctx: MountContext) = ZIO.unit
                      def render(model: Unit)     = div(liveView("resource-child", child))

                    for
                      harness <- SiteLiveViewHarness.join(parent)
                      child   <- harness.joinNested("resource-child")
                      joined  <- harness.socketExists(child.topic)
                      _       <- harness.leave
                      _ <- released.await.timeoutFail(
                             new RuntimeException("Nested resource was not released")
                           )(3.seconds)
                      removed <- harness.socketExists(child.topic)
                    yield assertTrue(joined, !removed)
                  }
      yield result
    }
  )
end SiteLiveViewHarnessSpec
