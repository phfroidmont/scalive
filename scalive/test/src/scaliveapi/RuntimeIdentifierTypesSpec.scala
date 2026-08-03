package scaliveapi

import zio.test.*

import scalive.*

object RuntimeIdentifierTypesSpec extends ZIOSpecDefault:
  override def spec = suite("RuntimeIdentifierTypesSpec")(
    test("runtime identifiers expose explicit string values") {
      assertTrue(
        AsyncKey[Int]("load").value == "load",
        ServerToBrowserEvent[Int]("counter:changed").value == "counter:changed",
        BrowserToServerEvent[Int]("counter:clicked").value == "counter:clicked",
        FlashKind("info").value == "info",
        SubscriptionKey("clock").value == "clock"
      )
    },
    test("runtime identifiers remain nominal and invariant") {
      val keyToStringErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val value: String = FlashKind("info")
      """)
      val asyncVarianceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val key: AsyncKey[Any] = AsyncKey[String]("load")
      """)
      val eventVarianceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val event: ServerToBrowserEvent[Any] = ServerToBrowserEvent[String]("changed")
      """)
      val inboundEventVarianceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val event: BrowserToServerEvent[Any] = BrowserToServerEvent[String]("changed")
      """)

      assertTrue(
        keyToStringErrors.nonEmpty,
        asyncVarianceErrors.nonEmpty,
        eventVarianceErrors.nonEmpty,
        inboundEventVarianceErrors.nonEmpty
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
        val event = ServerToBrowserEvent[Payload]("counter:changed")
        def push(ctx: MessageContext[Unit, Unit]) = ctx.client.push(event, "wrong")
      """)
      val directionErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.json.*
        case class Payload(value: Int) derives JsonEncoder
        val event = BrowserToServerEvent[Payload]("counter:changed")
        def push(ctx: MessageContext[Unit, Unit]) = ctx.client.push(event, Payload(1))
      """)
      val removedMethodErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def push(ctx: MessageContext[Unit, Unit]) = ctx.client.pushEvent("ready", 1)
      """)

      assertTrue(payloadErrors.nonEmpty, directionErrors.nonEmpty, removedMethodErrors.nonEmpty)
    },
    test("browser event handlers require inbound events and payload decoders") {
      val directionErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.json.*
        case class Payload(value: Int) derives JsonEncoder, JsonDecoder
        val event = ServerToBrowserEvent[Payload]("counter:changed")
        val hooks = LiveHooks.empty[Unit, Unit].onBrowserEvent(event)((model, _, _) => zio.ZIO.succeed(model))
      """)
      val decoderErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        case class Payload(value: Int)
        val event = BrowserToServerEvent[Payload]("counter:changed")
        val hooks = LiveHooks.empty[Unit, Unit].onBrowserEvent(event)((model, _, _) => zio.ZIO.succeed(model))
      """)

      assertTrue(directionErrors.nonEmpty, decoderErrors.nonEmpty)
    },
    test("hook markup requires a name and DOM id together") {
      val oldSyntaxErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val markup = div(phx.hook := "MyHook")
      """)
      val helperErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val markup = div(phx.hook("MyHook", id = "my-hook"))
      """)

      assertTrue(oldSyntaxErrors.nonEmpty, helperErrors.isEmpty)
    },
    test("upload operations reject raw names") {
      val rawNameErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def get(ctx: MessageContext[Unit, Unit]) = ctx.uploads.get("avatar")
      """)

      assertTrue(rawNameErrors.nonEmpty)
    },
    test("upload definitions fix result types") {
      val wrongResultErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        val definition: LiveUploadDef[String] =
          LiveUploadDef.inMemory("avatar", LiveUploadAccept.Any)
      """)
      assertTrue(wrongResultErrors.nonEmpty)
    },
    test("upload drop targets reject raw references") {
      val rawReferenceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val target = phx.dropTarget := "upload-ref"
      """)
      val typedReferenceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def target(ref: UploadRef) = phx.dropTarget := ref
      """)
      assertTrue(rawReferenceErrors.nonEmpty, typedReferenceErrors.isEmpty)
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
