package scaliveapi

import zio.test.*

import scalive.*

object RuntimeIdentifierTypesSpec extends ZIOSpecDefault:
  override def spec = suite("RuntimeIdentifierTypesSpec")(
    test("runtime identifiers expose explicit string values") {
      assertTrue(
        DomRef("profile_form").value == "profile_form",
        AsyncKey[Int]("load").value == "load",
        ServerToBrowserEvent[Int]("counter:changed").value == "counter:changed",
        BrowserToServerEvent[Int]("counter:clicked").value == "counter:clicked",
        FlashKind("info").value == "info",
        SubscriptionKey("clock").value == "clock"
      )
    },
    test("DOM references validate identifiers and selectors remain nominal") {
      def runtimeRef(value: String) = DomRef(value)

      val invalidLiteralErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val ref = DomRef("profile.form")
      """)
      val rawSelectorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val command = JS.show(to = "#profile")
      """)
      val rawTargetErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val target = phx.target("#profile")
      """)
      val typedErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val ref = DomRef("profile_form")
        val command = JS.show(to = ref.selector)
        val target = phx.target(DomSelector.css("[data-profile]"))
      """)

      assertTrue(
        invalidLiteralErrors.nonEmpty,
        rawSelectorErrors.nonEmpty,
        rawTargetErrors.nonEmpty,
        typedErrors.isEmpty,
        scala.util.Try(runtimeRef("profile.form")).isFailure
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
          ctx.connection match
            case Connection.Connected(connected) =>
              connected.async.start(key)(ZIO.succeed("wrong"))(_ => ())
            case Connection.Disconnected => ZIO.unit
      """)
      val rawNameErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        def start(ctx: MountContext[Unit, Unit]) =
          ctx.connection match
            case Connection.Connected(connected) =>
              connected.async.start("load")(ZIO.succeed(1))(_ => ())
            case Connection.Disconnected => ZIO.unit
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
          ctx.connection match
            case Connection.Connected(connected) =>
              connected.subscriptions.start(
                FlashKind("clock"),
                SubscriptionDelivery.Lossless
              )(ZStream.succeed(()))
            case Connection.Disconnected => zio.ZIO.unit
      """)
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.stream.ZStream
        def start(ctx: MountContext[Unit, Unit]) =
          ctx.connection match
            case Connection.Connected(connected) =>
              connected.subscriptions.start("clock", SubscriptionDelivery.Lossless)(
                ZStream.succeed(())
              )
            case Connection.Disconnected => zio.ZIO.unit
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
    test("browser event declarations require inbound events, payload decoders, and messages") {
      val accepted = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*
        case class Payload(value: Int) derives JsonDecoder
        enum Msg:
          case Changed(payload: Payload)
          case Reset
        val changed = BrowserToServerEvent[Payload]("counter:changed")
        val reset   = BrowserToServerEvent[ast.Json]("counter:reset")
        object View extends LiveView[Msg, Int]:
          override def browserEvents = BrowserEvents.on(changed)(Msg.Changed(_)).on(reset)(_ => Msg.Reset)
          def mount(ctx: MountContext) = ZIO.succeed(0)
          def handleMessage(model: Int, ctx: MessageContext) = _ => ZIO.succeed(model)
          def view(model: Signal[Int]) = div()
        object Counter extends LiveComponent[Unit, Msg, Int]:
          override def browserEvents = BrowserEvents.on(changed)(Msg.Changed(_))
          def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
          def handleMessage(props: Unit, model: Int, ctx: MessageContext) = _ => ZIO.succeed(model)
          def view(props: Signal[Unit], model: Signal[Int]) = div()
      """)
      val directionErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.json.*
        case class Payload(value: Int) derives JsonEncoder, JsonDecoder
        val event  = ServerToBrowserEvent[Payload]("counter:changed")
        val events = BrowserEvents.on(event)(_.value)
      """)
      val decoderErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        case class Payload(value: Int)
        val event  = BrowserToServerEvent[Payload]("counter:changed")
        val events = BrowserEvents.on(event)(_.value)
      """)
      val eventlessErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        object View extends LiveView.Eventless[Unit]:
          override def browserEvents =
            BrowserEvents.on(BrowserToServerEvent[zio.json.ast.Json]("ping"))(_ => ())
          def mount(ctx: MountContext) = zio.ZIO.unit
          def view(model: Signal[Unit]) = div()
      """)

      assertTrue(
        accepted.isEmpty,
        directionErrors.nonEmpty,
        decoderErrors.nonEmpty,
        eventlessErrors.nonEmpty
      )
    },
    test("raw hooks remain typed attributes and semantic hooks require a DOM reference") {
      val rawHookErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val markup = div(phx.hook := "MyHook")
      """)
      val rawHookTypeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val markup = div(phx.hook := 42)
      """)
      val rawIdErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val markup = div(scalive.dom.hook("MyHook", "my-hook"))
      """)
      val semanticHookErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val markup = div(scalive.dom.hook("MyHook", DomRef("my-hook")))
      """)

      assertTrue(
        rawHookErrors.isEmpty,
        rawHookTypeErrors.nonEmpty,
        rawIdErrors.nonEmpty,
        semanticHookErrors.isEmpty
      )
    },
    test("phx.update rejects raw strings") {
      val rawUpdateErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val markup = div(phx.update := "append")
      """)
      val typedUpdateErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val markup = div(phx.update := PhxUpdate.Stream)
      """)

      assertTrue(rawUpdateErrors.nonEmpty, typedUpdateErrors.isEmpty)
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
