package scalive.runtime.connection

import zio.*
import zio.json.ast.Json
import zio.http.URL
import zio.test.*

import scalive.*
import scalive.render.EvaluatedNode
import scalive.runtime.contracts.CommandId

object ComponentRuntimeSpec extends ZIOSpecDefault:
  private val config = ConnectionConfig.make(8, 8, 8, 8, 8).toOption.get
  private val metadata = RootConnectionMetadata(
    staticChanged = true,
    connectParams = Map("token" -> Json.Str("component"))
  )

  private enum RootMsg:
    case Output(value: Int)

  private def componentToken(node: EvaluatedNode): Option[Object] = node match
    case value: EvaluatedNode.Component => value.resolution.map(_.instanceToken)
    case value: EvaluatedNode.Element   => value.children.iterator.flatMap(componentToken).nextOption()
    case value: EvaluatedNode.Choice    => value.child.flatMap(componentToken)
    case value: EvaluatedNode.Flash     => value.child.flatMap(componentToken)
    case value: EvaluatedNode.Keyed     =>
      value.rows.iterator.flatMap(row => componentToken(row.child)).nextOption()
    case _ => None

  private def renderedText(node: EvaluatedNode): String = node match
    case value: EvaluatedNode.Text    => value.value
    case value: EvaluatedNode.Element => value.children.map(renderedText).mkString
    case value: EvaluatedNode.Component =>
      value.resolution.map(result => renderedText(result.child.root)).getOrElse("")
    case value: EvaluatedNode.Choice => value.child.map(renderedText).getOrElse("")
    case value: EvaluatedNode.Flash  => value.child.map(renderedText).getOrElse("")
    case value: EvaluatedNode.Keyed  => value.rows.map(row => renderedText(row.child)).mkString
    case _                           => ""

  private final class RecordingComponent(order: Ref[Vector[String]])
      extends LiveComponent.WithOutput[Int, Int, Int, Int]:
    override val hooks = ComponentLiveHooks.empty[Int, Int, Int]
      .onEvent((_, model, _, _) => order.update(_ :+ "hook").as(LiveHookResult.cont(model + 1)))
      .onAsync((_, model, _, _) => order.update(_ :+ "async-hook").as(LiveHookResult.halt(model + 10)))
      .afterRender((_, _, _) => order.update(_ :+ "after-render"))

    def mount(props: Int, ctx: MountContext): LiveIO[Int] =
      order.update(_ :+ "mount").as(0)

    override def update(props: Int, model: Int, ctx: UpdateContext): LiveIO[Int] =
      order.update(_ :+ s"update:$props").as(model + props)

    def handleMessage(props: Int, model: Int, ctx: MessageContext): Int => LiveIO[Int] =
      message => order.update(_ :+ "message") *> ctx.emit(model + message).as(model + message)

    def view(props: Signal[Int], model: Signal[Int], self: ComponentRef[Int]): HtmlElement[Int] =
      div(model.map(_.toString))

  private def root(
    instances: Vector[LiveComponentOutputInstance[Int, Int, Int, Int]],
    output: Ref[Vector[Int]]
  ): LiveView[RootMsg, Unit] = new LiveView[RootMsg, Unit]:
    def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def handleMessage(model: Unit, ctx: MessageContext): RootMsg => LiveIO[Unit] =
      case RootMsg.Output(value) => output.update(_ :+ value)
    def view(model: Signal[Unit]): HtmlElement[RootMsg] =
      div(instances.map(_.render(1, RootMsg.Output.apply))* )

  override def spec = suite("ComponentRuntimeSpec")(
    test("runs mount, update, render and after-render and routes outputs through the owner") {
      ZIO.scoped {
        for
          order      <- Ref.make(Vector.empty[String])
          rootOutput <- Ref.make(Vector.empty[Int])
          sink       <- Queue.unbounded[ConnectionOutput]
          definition = RecordingComponent(order)
          instance   = component(definition, "one")
          connection <- RootConnection.start(
                          config,
                          metadata,
                          root(Vector(instance), rootOutput),
                          sink.offer(_).unit
                        )
          _      <- sink.take
          ids    <- connection.inspectComponentIds
          before <- connection.inspectComponentModel[Int](ids.head)
          command = CommandId.fresh().toOption.get
          _       <- connection.submitComponentMessage(command, ids.head, 3)
          _       <- sink.take
          _       <- sink.take
          after   <- connection.inspectComponentModel[Int](ids.head)
          emitted <- rootOutput.get
          events  <- order.get
        yield assertTrue(
          before.contains(1),
          after.contains(5),
          emitted == Vector(5),
          events.take(3) == Vector("mount", "update:1", "after-render"),
          events.containsSlice(Vector("hook", "message", "after-render"))
        )
      }
    },
    test("targets exact component identities, honors async halt, and observes removal cleanup") {
      ZIO.scoped {
        for
          order      <- Ref.make(Vector.empty[String])
          rootOutput <- Ref.make(Vector.empty[Int])
          sink       <- Queue.unbounded[ConnectionOutput]
          definition = RecordingComponent(order)
          first      = component(definition, "first")
          second     = component(definition, "second")
          connection <- RootConnection.start(
                          config,
                          metadata,
                          root(Vector(first, second), rootOutput),
                          sink.offer(_).unit
                        )
          _   <- sink.take
          ids <- connection.inspectComponentIds
          event = LiveAsyncEvent(
                    AsyncKey[Int]("work").asInstanceOf[AsyncKey[Any]],
                    LiveAsyncResult.Succeeded(99)
                  )
          command = CommandId.fresh().toOption.get
          _      <- connection.submitComponentAsyncCompletion(command, ids(1), event)
          _      <- sink.take
          left   <- connection.inspectComponentModel[Int](ids.head)
          right  <- connection.inspectComponentModel[Int](ids(1))
          _      <- connection.close
          closed <- ZIO.foreach(ids)(connection.componentWasClosed)
        yield assertTrue(left.contains(1), right.contains(11), closed.forall(identity))
      }
    },
    test("resolves component tokens by exact reference identity") {
      ZIO.scoped {
        for
          order      <- Ref.make(Vector.empty[String])
          rootOutput <- Ref.make(Vector.empty[Int])
          sink       <- Queue.unbounded[ConnectionOutput]
          definition = RecordingComponent(order)
          instance   = component(definition, "token")
          connection <- RootConnection.start(
                          config,
                          metadata,
                          root(Vector(instance), rootOutput),
                          sink.offer(_).unit
                        )
          joined <- sink.take
          token = joined match
                    case ConnectionOutput.Joined(scalive.render.RenderDelta.Replace(tree), _) =>
                      componentToken(tree.root).get
                    case other => throw AssertionError(s"unexpected join: $other")
          expected <- connection.inspectComponentIds.map(_.head)
          resolved <- connection.componentForToken(token)
          unknown  <- connection.componentForToken(new Object())
        yield assertTrue(resolved.contains(expected), unknown.isEmpty)
      }
    },
    test("root sendUpdate journals typed props with last-write-wins and ignores absent targets") {
      ZIO.scoped {
        val definition = new LiveComponent.Eventless[Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          override def update(props: Int, model: Int, ctx: UpdateContext) = ZIO.succeed(props)
          def view(props: Signal[Int], model: Signal[Int], self: ComponentRef[Nothing]) = div()
        val present = component(definition, "present")
        val absent  = component(definition, "absent")
        val view = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] = _ =>
            ctx.components.sendUpdate(present, 2) *>
              ctx.components.sendUpdate(absent, 9) *>
              ctx.components.sendUpdate(present, 3)
          def view(model: Signal[Unit]) = div(present.render(1))
        for
          sink       <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, sink.offer(_).unit)
          _          <- sink.take
          id         <- connection.inspectComponentIds.map(_.head)
          _          <- connection.submitInfo(())
          props      <- connection.inspectComponentProps[Int](id)
          model      <- connection.inspectComponentModel[Int](id)
        yield assertTrue(props.contains(3), model.contains(3))
      }
    },
    test("dynamic component hooks replace in place and raw hooks consume malformed payloads") {
      ZIO.scoped {
        val rawEvent = BrowserToServerEvent[Int]("raw")
        val definition = new LiveComponent[Int, Int, Int]:
          override val hooks = ComponentLiveHooks.empty[Int, Int, Int]
            .onEvent((_, model, _, _) => ZIO.succeed(LiveHookResult.cont(model + 1)))
            .onBrowserEvent(rawEvent)((_, model, value, _) => ZIO.succeed(model + value))
          def mount(props: Int, ctx: MountContext) =
            ctx.hooks.event.attach("dynamic")((_, model, _, _) =>
              ZIO.succeed(LiveHookResult.cont(model + 10))) *>
              ctx.hooks.event.attach("dynamic")((_, model, _, _) =>
                ZIO.succeed(LiveHookResult.cont(model + 20))).as(0)
          def handleMessage(props: Int, model: Int, ctx: MessageContext): Int => LiveIO[Int] =
            value => ZIO.succeed(model + value)
          def view(props: Signal[Int], model: Signal[Int], self: ComponentRef[Int]) = div()
        val instance = component(definition, "hooks")
        val view = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] = _ => ZIO.unit
          def view(model: Signal[Unit]) = div(instance.render(1))
        for
          sink       <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, sink.offer(_).unit)
          _          <- sink.take
          id         <- connection.inspectComponentIds.map(_.head)
          message    = CommandId.fresh().toOption.get
          _          <- connection.submitComponentMessage(message, id, 1)
          afterMessage <- connection.inspectComponentModel[Int](id)
          raw = CommandId.fresh().toOption.get
          _ <- connection.submitComponentNamedEvent(
                 raw,
                 id,
                 scalive.render.BindingId.fromEncoded("ignored"),
                 BindingPayload.Params(Map.empty),
                 "raw",
                 "5"
               )
          afterRaw <- connection.inspectComponentModel[Int](id)
          malformed = CommandId.fresh().toOption.get
          _ <- connection.submitComponentNamedEvent(
                 malformed,
                 id,
                 scalive.render.BindingId.fromEncoded("ignored"),
                 BindingPayload.Params(Map.empty),
                 "raw",
                 "not-an-int"
               )
          afterMalformed <- connection.inspectComponentModel[Int](id)
        yield assertTrue(
          afterMessage.contains(22),
          afterRaw.contains(27),
          afterMalformed.contains(27)
        )
      }
    },
    test("component flash and client effects are threaded into the committed root turn") {
      ZIO.scoped {
        val pushed = ServerToBrowserEvent[Int]("component-effect")
        val definition = new LiveComponent[Unit, Unit, Unit]:
          def mount(props: Unit, ctx: MountContext) = ZIO.unit
          def handleMessage(props: Unit, model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] =
            _ => ctx.flash.put(FlashKind("info"), "from-component") *> ctx.client.push(pushed, 7)
          def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Unit]) = div()
        val instance = component(definition, "effects")
        val view = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] = _ => ZIO.unit
          def view(model: Signal[Unit]) = div(instance.render(()))
        for
          sink       <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, sink.offer(_).unit)
          _          <- sink.take
          id         <- connection.inspectComponentIds.map(_.head)
          command    = CommandId.fresh().toOption.get
          _          <- connection.submitComponentMessage(command, id, ())
          reply      <- sink.take
          flash      <- connection.inspectFlash
        yield assertTrue(
          flash.get(FlashKind("info")).contains("from-component"),
          reply match
            case ConnectionOutput.Reply(`command`, _, effects) =>
              effects.clientEvents.exists(_.name == "component-effect")
            case _ => false
        )
      }
    },
    test("component callback flash is visible in its committed component projection") {
      ZIO.scoped {
        val kind = FlashKind("component-visible")
        val definition = new LiveComponent[Unit, String, Unit]:
          def mount(props: Unit, ctx: MountContext) = ZIO.unit
          def handleMessage(props: Unit, model: Unit, ctx: MessageContext): String => LiveIO[Unit] =
            message => ctx.flash.put(kind, message)
          def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[String]) =
            div(scalive.flash(kind)(message => span(message)))
        val instance = component(definition, "flash-visible")
        val view = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] = _ => ZIO.unit
          def view(model: Signal[Unit]) = div(instance.render(()))
        for
          sink       <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, sink.offer(_).unit)
          _          <- sink.take
          id         <- connection.inspectComponentIds.map(_.head)
          command    = CommandId.fresh().toOption.get
          _          <- connection.submitComponentMessage(command, id, "committed flash")
          _          <- sink.take
          tree       <- connection.inspectComponentTree(id)
        yield assertTrue(tree.exists(value => renderedText(value.root) == "committed flash"))
      }
    },
    test("failed component flash callback does not publish its candidate render") {
      ZIO.scoped {
        val kind = FlashKind("component-rollback")
        val definition = new LiveComponent[Unit, Unit, Unit]:
          def mount(props: Unit, ctx: MountContext) = ZIO.unit
          def handleMessage(props: Unit, model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] =
            _ => ctx.flash.put(kind, "must-not-commit") *> ZIO.fail(Exception("rollback"))
          def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Unit]) =
            div(scalive.flash(kind)(message => span(message)))
        val instance = component(definition, "flash-rollback")
        val view = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] = _ => ZIO.unit
          def view(model: Signal[Unit]) = div(instance.render(()))
        for
          sink       <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, sink.offer(_).unit)
          _          <- sink.take
          id         <- connection.inspectComponentIds.map(_.head)
          command    = CommandId.fresh().toOption.get
          failed     <- connection.submitComponentMessage(command, id, ()).either
          _          <- connection.awaitClosed
          unpublished <- sink.poll
        yield assertTrue(
          failed.left.exists(_.isInstanceOf[ConnectionError.SessionFailed]),
          unpublished.isEmpty
        )
      }
    },
    test("component patch navigation waits for the matching acknowledgement") {
      ZIO.scoped {
        val destination = URL.decode("/component-patch").toOption.get
        val definition = new LiveComponent[Unit, Unit, Unit]:
          def mount(props: Unit, ctx: MountContext) = ZIO.unit
          def handleMessage(props: Unit, model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] =
            _ => ctx.nav.pushPatchUnsafe(destination.encode)
          def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Unit]) = div()
        val instance = component(definition, "patch")
        val view = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] = _ => ZIO.unit
          def view(model: Signal[Unit]) = div(instance.render(()))
        for
          sink       <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, sink.offer(_).unit)
          _          <- sink.take
          id         <- connection.inspectComponentIds.map(_.head)
          command    = CommandId.fresh().toOption.get
          _          <- connection.submitComponentMessage(command, id, ())
          navigation <- sink.take
          patch       = CommandId.fresh().toOption.get
          _          <- connection.submitPatch(patch, destination)
          acknowledged <- sink.take
        yield assertTrue(
          navigation match
            case ConnectionOutput.ReplyNavigation(`command`, _, output, _) =>
              output.destination == destination
            case _ => false,
          acknowledged.isInstanceOf[ConnectionOutput.Reply]
        )
      }
    },
    test("component redirect publishes navigation and closes") {
      ZIO.scoped {
        val destination = URL.decode("/component-redirect").toOption.get
        val definition = new LiveComponent[Unit, Unit, Unit]:
          def mount(props: Unit, ctx: MountContext) = ZIO.unit
          def handleMessage(props: Unit, model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] =
            _ => ctx.nav.redirectUnsafe(destination.encode)
          def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Unit]) = div()
        val instance = component(definition, "redirect")
        val view = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] = _ => ZIO.unit
          def view(model: Signal[Unit]) = div(instance.render(()))
        for
          sink       <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, sink.offer(_).unit)
          _          <- sink.take
          id         <- connection.inspectComponentIds.map(_.head)
          command    = CommandId.fresh().toOption.get
          _          <- connection.submitComponentMessage(command, id, ())
          navigation <- sink.take
          _          <- connection.awaitClosed
        yield assertTrue(
          navigation match
            case ConnectionOutput.ReplyNavigation(`command`, _, output, _) =>
              output.destination == destination && !output.kind.isPatch
            case _ => false
        )
      }
    },
    test("later-milestone resources fail explicitly while dynamic hooks remain available") {
      for
        turn <- ComponentTurn.make[Unit, Unit, Int, Int, Int](
                  scalive.runtime.kernel.TurnDraft(
                    RootState(
                      (),
                      URL.root,
                      RootHookRegistry.fromStatic(LiveHooks.empty[Unit, Unit])
                    )
                  ),
                  ComponentHookRegistry.empty[Int, Int, Int]
                )
        context = ComponentMountContextImpl[Int, Int, Int](metadata, turn)
        async <- context.connection match
                   case Connection.Connected(value) =>
                     value.async.start(AsyncKey[Int]("unsupported"))(ZIO.succeed(1))(_ => 1).either
                   case Connection.Disconnected => ZIO.dieMessage("expected connected context")
        upload <- context.uploads.get(null).either
        hook   <- context.hooks.event.detach("missing").either
      yield assertTrue(async.isLeft, upload.isLeft, hook.isRight)
    }
  )
end ComponentRuntimeSpec
