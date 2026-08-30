package scaliveapi

import zio.test.*

object ConnectionCapabilitiesSpec extends ZIOSpecDefault:
  def spec = suite("ConnectionCapabilitiesSpec")(
    test("disconnected-capable root phases do not expose connected operations directly") {
      val mountErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: MountContext[Unit, Unit]) = ctx.async
      """)
      val paramsErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: ParamsContext[Unit, Unit]) = ctx.client
      """)
      val afterRenderErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: AfterRenderContext[Unit, Unit]) = ctx.client
      """)
      val legacyBooleanErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: MountContext[Unit, Unit]) = ctx.connected
      """)

      assertTrue(
        mountErrors.nonEmpty,
        paramsErrors.nonEmpty,
        afterRenderErrors.nonEmpty,
        legacyBooleanErrors.nonEmpty
      )
    },
    test("connected root capabilities require explicit connection handling") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.stream.ZStream

        def mount(ctx: MountContext[Unit, Unit]) =
          ctx.streams.create(LiveStreamDef.byId[Int, Int]("rows")(identity), List(1)) *>
            (ctx.connection match
              case Connection.Disconnected => ZIO.unit
              case Connection.Connected(connected) =>
                connected.async.start(AsyncKey[Int]("load"))(ZIO.succeed(1))(_ => ()) *>
                  connected.resources.acquireRelease(ZIO.unit)(_ => ZIO.unit) *>
                  connected.subscriptions.start(
                    SubscriptionKey("ticks"),
                    SubscriptionDelivery.Lossless
                  )(ZStream.succeed(())) *>
                  connected.subscriptions.replace(
                    SubscriptionKey("ticks"),
                    SubscriptionDelivery.Latest
                  )(ZStream.succeed(())))

        def params(ctx: ParamsContext[Unit, Unit]) =
          ctx.connection match
            case Connection.Disconnected => ZIO.unit
            case Connection.Connected(connected) => connected.client.exec(JS)

        def afterRender(ctx: AfterRenderContext[Unit, Unit]) =
          ctx.connection match
            case Connection.Disconnected => ZIO.unit
            case Connection.Connected(connected) => connected.client.exec(JS)
      """)

      assertTrue(errors.isEmpty)
    },
    test("connected resources are available only during root mount") {
      val paramsErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: ParamsContext[Unit, Unit]) = ctx.connection match
          case Connection.Disconnected => ()
          case Connection.Connected(connected) => connected.resources
      """)
      val afterRenderErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: AfterRenderContext[Unit, Unit]) = ctx.connection match
          case Connection.Disconnected => ()
          case Connection.Connected(connected) => connected.resources
      """)
      val messageErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: MessageContext[Unit, Unit]) = ctx.resources
      """)
      val componentErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: ComponentMountContext[Unit, Unit, Unit]) = ctx.connection match
          case Connection.Disconnected => ()
          case Connection.Connected(connected) => connected.resources
      """)

      assertTrue(
        paramsErrors.nonEmpty,
        afterRenderErrors.nonEmpty,
        messageErrors.nonEmpty,
        componentErrors.nonEmpty
      )
    },
    test("stream deletion requires the definition's domain ID type") {
      val validErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        final case class Row(id: Int)
        val definition = LiveStreamDef.byId[Row, Int]("rows")(_.id)
        def delete(streams: Streams) = streams.delete(definition, 1)
      """)
      val invalidErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        final case class Row(id: Int)
        val definition = LiveStreamDef.byId[Row, Int]("rows")(_.id)
        def delete(streams: Streams) = streams.delete(definition, "rows-1")
      """)

      assertTrue(validErrors.isEmpty, invalidErrors.nonEmpty)
    },
    test("subscription delivery policy is required") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.stream.ZStream

        def invalid(ctx: MessageContext[Unit, Unit]) =
          ctx.subscriptions.start(SubscriptionKey("ticks"))(ZStream.succeed(()))
      """)

      assertTrue(errors.nonEmpty)
    },
    test("component disconnected phases gate connected operations") {
      val directErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid(ctx: ComponentMountContext[Unit, Unit, Unit]) = ctx.async
      """)
      val connectedErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        def valid(ctx: ComponentUpdateContext[Unit, Unit, Unit]) =
          ctx.connection match
            case Connection.Disconnected => ZIO.unit
            case Connection.Connected(connected) => connected.client.exec(JS)
      """)

      assertTrue(directErrors.nonEmpty, connectedErrors.isEmpty)
    },
    test("message phases expose connected capabilities directly") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*

        def root(ctx: MessageContext[Unit, Unit]) =
          ctx.async.cancel(AsyncKey[Int]("load")) *> ctx.client.exec(JS)

        def component(ctx: ComponentMessageContext[Unit, Unit, Unit]) =
          ctx.async.cancel(AsyncKey[Int]("load")) *> ctx.client.exec(JS)
      """)

      assertTrue(errors.isEmpty)
    }
  )
