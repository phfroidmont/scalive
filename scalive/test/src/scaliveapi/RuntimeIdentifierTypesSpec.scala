package scaliveapi

import zio.test.*

import scalive.*

object RuntimeIdentifierTypesSpec extends ZIOSpecDefault:
  override def spec = suite("RuntimeIdentifierTypesSpec")(
    test("runtime identifiers expose explicit string values") {
      assertTrue(
        AsyncKey[Int]("load").value == "load",
        ClientEvent[Int]("counter:changed").value == "counter:changed",
        FlashKind("info").value == "info",
        SubscriptionKey("clock").value == "clock",
        UploadKey("avatar").value == "avatar"
      )
    },
    test("runtime identifiers remain nominal and invariant") {
      val stringToKeyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val key: UploadKey = "avatar"
      """)
      val keyToStringErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val value: String = FlashKind("info")
      """)
      val crossFamilyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val key: UploadKey = FlashKind("avatar")
      """)
      val asyncVarianceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val key: AsyncKey[Any] = AsyncKey[String]("load")
      """)
      val eventVarianceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val event: ClientEvent[Any] = ClientEvent[String]("changed")
      """)

      assertTrue(
        stringToKeyErrors.nonEmpty,
        keyToStringErrors.nonEmpty,
        crossFamilyErrors.nonEmpty,
        asyncVarianceErrors.nonEmpty,
        eventVarianceErrors.nonEmpty
      )
    },
    test("async keys fix task result types and reject raw names") {
      val resultErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        def start(ctx: MountContext[Unit, Unit]) =
          val key = AsyncKey[Int]("load")
          ctx.async.start(key)(ZIO.succeed("wrong"))(_ => ())
      """)
      val rawNameErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        def start(ctx: MountContext[Unit, Unit]) =
          ctx.async.start("load")(ZIO.succeed(1))(_ => ())
      """)

      assertTrue(resultErrors.nonEmpty, rawNameErrors.nonEmpty)
    },
    test("subscription operations reject raw names") {
      val directErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val key: SubscriptionKey = "clock"
      """)
      val wrongFamilyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.stream.ZStream
        def start(ctx: MountContext[Unit, Unit]) =
          ctx.subscriptions.start(FlashKind("clock"))(ZStream.succeed(()))
      """)
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.stream.ZStream
        def start(ctx: MountContext[Unit, Unit]) =
          ctx.subscriptions.start("clock")(ZStream.succeed(()))
      """)

      assertTrue(directErrors.nonEmpty, wrongFamilyErrors.nonEmpty, errors.nonEmpty)
    },
    test("client events fix payload types and reject raw names") {
      val payloadErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.json.*
        case class Payload(value: Int) derives JsonEncoder
        val event = ClientEvent[Payload]("counter:changed")
        def push(ctx: MessageContext[Unit, Unit]) = ctx.client.push(event, "wrong")
      """)
      val removedMethodErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def push(ctx: MessageContext[Unit, Unit]) = ctx.client.pushEvent("ready", 1)
      """)

      assertTrue(payloadErrors.nonEmpty, removedMethodErrors.nonEmpty)
    },
    test("upload operations reject raw names") {
      val rawNameErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def get(ctx: MessageContext[Unit, Unit]) = ctx.uploads.get("avatar")
      """)

      assertTrue(rawNameErrors.nonEmpty)
    },
    test("flash APIs reject raw kinds") {
      val rawContextErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def put(ctx: MessageContext[Unit, Unit]) = ctx.flash.put("info", "Saved")
      """)
      val rawRenderErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val content = flash("info")(message => div(message))
      """)

      assertTrue(rawContextErrors.nonEmpty, rawRenderErrors.nonEmpty)
    }
  )
end RuntimeIdentifierTypesSpec
