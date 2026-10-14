package scaliveapi

import zio.test.*

object LiveHooksApiSpec extends ZIOSpecDefault:
  def spec = suite("LiveHooksApiSpec")(
    test("companion shortcuts infer root hook types from the declared hooks type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        final class View extends LiveView[View.Msg, Int]:
          override def hooks: LiveHooks[View.Msg, Int] =
            LiveHooks.afterRender { (model, ctx) =>
              ctx.connection match
                case Connection.Connected(_) => ZIO.logDebug(model.toString)
                case Connection.Disconnected => ZIO.unit
            }

          def mount(ctx: MountContext) = ZIO.succeed(0)
          def handleMessage(model: Int, ctx: MessageContext) =
            case View.Msg.Increment => ZIO.succeed(model + 1)
          def view(model: Signal[Int]) = div(model.map(_.toString))

        object View:
          enum Msg:
            case Increment

        val event: LiveHooks[View.Msg, Int] =
          LiveHooks.onEvent((model, _, _) => ZIO.succeed(LiveHookResult.cont(model)))
        val params: LiveHooks[View.Msg, Int] =
          LiveHooks.onParams((model, _, _) => ZIO.succeed(LiveHookResult.cont(model)))
        val info: LiveHooks[View.Msg, Int] =
          LiveHooks.onInfo((model, _, _) => ZIO.succeed(LiveHookResult.cont(model)))
        val async: LiveHooks[View.Msg, Int] =
          LiveHooks.onAsync((model, _, _) => ZIO.succeed(LiveHookResult.cont(model)))
        val raw: LiveHooks[View.Msg, Int] =
          LiveHooks.onRawEvent((model, _, _) => ZIO.succeed(LiveEventHookResult.cont(model)))
        val browser: LiveHooks[View.Msg, Int] =
          LiveHooks.onBrowserEvent(BrowserToServerEvent[String]("rename"))((model, _, _) =>
            ZIO.succeed(model)
          )
      """)

      assertTrue(errors.isEmpty)
    },
    test("companion shortcuts infer component hook types from the declared hooks type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        final class Counter extends LiveComponent[String, Counter.Msg, Int]:
          override def hooks: ComponentLiveHooks[String, Counter.Msg, Int] =
            ComponentLiveHooks.afterRender((_, _, _) => ZIO.unit)

          def mount(props: String, ctx: MountContext) = ZIO.succeed(0)
          def handleMessage(props: String, model: Int, ctx: MessageContext) =
            case Counter.Msg.Increment => ZIO.succeed(model + 1)
          def view(props: Signal[String], model: Signal[Int]) =
            div(model.map(_.toString))

        object Counter:
          enum Msg:
            case Increment

        val event: ComponentLiveHooks[String, Counter.Msg, Int] =
          ComponentLiveHooks.onEvent((_, model, _, _) => ZIO.succeed(LiveHookResult.cont(model)))
        val async: ComponentLiveHooks[String, Counter.Msg, Int] =
          ComponentLiveHooks.onAsync((_, model, _, _) => ZIO.succeed(LiveHookResult.cont(model)))
        val raw: ComponentLiveHooks[String, Counter.Msg, Int] =
          ComponentLiveHooks.onRawEvent((_, model, _, _) =>
            ZIO.succeed(LiveEventHookResult.cont(model))
          )
        val browser: ComponentLiveHooks[String, Counter.Msg, Int] =
          ComponentLiveHooks.onBrowserEvent(BrowserToServerEvent[String]("rename"))(
            (_, model, _, _) => ZIO.succeed(model)
          )
      """)

      assertTrue(errors.isEmpty)
    }
  )
end LiveHooksApiSpec
