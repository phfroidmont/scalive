package scalive.runtime.connection

import zio.*
import zio.json.ast.Json
import zio.json.*
import zio.http.URL
import zio.stream.ZStream
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.CommandId
import scalive.runtime.kernel.RuntimeObserver

object RootConnectionSpec extends ZIOSpecDefault:
  private val config = ConnectionConfig.make(4, 4, 4, 4, 4).toOption.get
  private val metadata = RootConnectionMetadata(
    staticChanged = true,
    connectParams = Map("token" -> Json.Str("exact"))
  )

  private final class Counter(mounts: Ref[Int]) extends LiveView[Int, Int]:
    override def mount(ctx: MountContext): Task[Int] = mounts.updateAndGet(_ + 1).as(0)
    override def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
      amount => ZIO.succeed(model + amount)
    override def view(model: Signal[Int]): HtmlElement[Int] =
      button(on.click(1), model.map(_.toString))

  override def spec = suite("RootConnectionSpec")(
    test("mounts once, joins, and preserves the caller's command correlation") {
      ZIO.scoped {
        for
          mounts  <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          events  <- Ref.make(Vector.empty[LifecycleEvent])
          observer = RuntimeObserver.withLifecycleObserver(
                       LifecycleObserver.fromFunction(event => events.update(_ :+ event))
                     )
          connection <- RootConnection.start(
                          config,
                          metadata,
                          Counter(mounts),
                          outputs.offer(_).unit,
                          observer = observer
                        )
          joined     <- outputs.take
          binding = joined match
                      case ConnectionOutput.Joined(RenderDelta.Replace(tree), _) =>
                        val encoded = tree.root.attributes
                          .flatMap(_.value).collectFirst { case AttributeValue.Text(value) => value }.get
                        BindingId.fromEncoded(encoded)
                      case other => throw AssertionError(s"unexpected bootstrap output: $other")
          command = CommandId.fresh().toOption.get
          _       <- connection.submitEvent(command, binding, BindingPayload.Params(Map.empty))
          reply   <- outputs.take
          count   <- mounts.get
          observed <- events.get
          mountEvents = observed.collect { case event: LifecycleEvent.MountSucceeded => event }
        yield assertTrue(
          count == 1,
          mountEvents.size == 1,
          mountEvents.head.durationNanos >= 0L,
          mountEvents.head.mount.isInstanceOf[LifecycleMount.Connected],
          joined match
            case ConnectionOutput.Joined(_, effects) => effects.pageTitle.isEmpty
            case _                                   => false,
          reply match
            case ConnectionOutput.Reply(`command`, _, _) => true
            case _                                    => false
        )
      }
    },
    test("connected mount failures retain timing and failure stage") {
      ZIO.scoped {
        object Broken extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): Task[Unit] = ZIO.fail(Exception("mount failed"))
          def view(model: Signal[Unit])             = div()

        for
          events <- Ref.make(Vector.empty[LifecycleEvent])
          observer = RuntimeObserver.withLifecycleObserver(
                       LifecycleObserver.fromFunction(event => events.update(_ :+ event))
                     )
          result <- RootConnection
                      .start(config, metadata, Broken, _ => ZIO.unit, observer = observer).either
          observed <- events.get
          failures = observed.collect { case event: LifecycleEvent.MountFailed => event }
        yield assertTrue(
          result.isLeft,
          failures.size == 1,
          failures.head.durationNanos >= 0L,
          failures.head.error.failure ==
            LifecycleFailure.Stage(LifecycleFailureStage.Mount)
        )
      }
    },
    test("unknown bindings are correlated nonterminal rejections") {
      ZIO.scoped {
        for
          mounts  <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, Counter(mounts), outputs.offer(_).unit)
          _       <- outputs.take
          command = CommandId.fresh().toOption.get
          unknown = BindingId.fromEncoded("unknown")
          _       <- connection.submitEvent(command, unknown, BindingPayload.Params(Map.empty))
          rejected <- outputs.take
        yield assertTrue(
          rejected match
            case ConnectionOutput.Rejected(`command`, _) => true
            case _                                       => false
        )
      }
    },
    test("a committed reply precedes the next command rejection") {
      ZIO.scoped {
        for
          mounts  <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, Counter(mounts), outputs.offer(_).unit)
          joined     <- outputs.take
          binding     = bindingFrom(joined)
          observed <- ZIO.foreach(1 to 50) { _ =>
                        val accepted = CommandId.fresh().toOption.get
                        val rejected = CommandId.fresh().toOption.get
                        for
                          _ <- connection.offerEvent(
                                 accepted,
                                 binding,
                                 BindingPayload.Params(Map.empty)
                               )
                          _ <- connection.offerEvent(
                                 rejected,
                                 BindingId.fromEncoded("unknown"),
                                 BindingPayload.Params(Map.empty)
                               )
                          first  <- outputs.take
                          second <- outputs.take
                        yield first -> second
                      }
        yield assertTrue(observed.forall {
          case (
                ConnectionOutput.Reply(accepted, _, _),
                ConnectionOutput.Rejected(rejected, _)
              ) => accepted.value < rejected.value
          case _ => false
        })
      }
    },
    test("output ordering follows admission rather than command identity") {
      ZIO.scoped {
        for
          mounts  <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, Counter(mounts), outputs.offer(_).unit)
          joined     <- outputs.take
          accepted    = CommandId(1000L)
          rejected    = CommandId(1L)
          _ <- connection.offerEvent(
                 accepted,
                 bindingFrom(joined),
                 BindingPayload.Params(Map.empty)
               )
          _ <- connection.offerEvent(
                 rejected,
                 BindingId.fromEncoded("unknown"),
                 BindingPayload.Params(Map.empty)
               )
          first  <- outputs.take
          second <- outputs.take
        yield assertTrue(
          first.isInstanceOf[ConnectionOutput.Reply],
          second == ConnectionOutput.Rejected(
            rejected,
            scalive.runtime.kernel.SessionRejection.UnknownBinding(
              BindingId.fromEncoded("unknown")
            )
          )
        )
      }
    },
    test("a sink failure terminates the connection") {
      ZIO.scoped {
        for
          mounts <- Ref.make(0)
          events <- Ref.make(Vector.empty[LifecycleEvent])
          boom = RuntimeException("sink failed")
          observer = RuntimeObserver.withLifecycleObserver(
                       LifecycleObserver.fromFunction(event => events.update(_ :+ event))
                     )
          result <- RootConnection
                      .start(
                        config,
                        metadata,
                        Counter(mounts),
                        _ => ZIO.fail(boom),
                        observer = observer
                      ).either
          observed <- events.get
          terminations = observed.collect {
                           case event: LifecycleEvent.LifecycleTerminated => event.reason
                         }
        yield assertTrue(
          result == Left(ConnectionError.SinkFailed(boom)),
          terminations == Vector(
            LifecycleTerminationReason.Failed(
              LifecycleFailure.Stage(LifecycleFailureStage.Writer)
            )
          )
        )
      }
    },
    test("closing after an in-flight writer failure preserves the failure reason") {
      ZIO.scoped {
        for
          mounts  <- Ref.make(0)
          writes  <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          events  <- Ref.make(Vector.empty[LifecycleEvent])
          boom = RuntimeException("sink failed")
          observer = RuntimeObserver.withLifecycleObserver(
                       LifecycleObserver.fromFunction(event => events.update(_ :+ event))
                     )
          connection <- RootConnection.start(
                          config,
                          metadata,
                          Counter(mounts),
                          output =>
                            writes.updateAndGet(_ + 1).flatMap {
                              case 1 => outputs.offer(output).unit
                              case _ => ZIO.fail(boom)
                            },
                          observer = observer
                        )
          joined    <- outputs.take
          submitted <- connection
                         .submitEvent(
                           CommandId.fresh().toOption.get,
                           bindingFrom(joined),
                           BindingPayload.Params(Map.empty)
                         ).either.fork
          writerFailure <- connection.awaitWriterFailure
          _             <- connection.close
          _             <- submitted.await
          observed      <- events.get
          terminations = observed.collect {
                           case event: LifecycleEvent.LifecycleTerminated => event.reason
                         }
        yield assertTrue(
          writerFailure == SerialWriter.Error.WriteFailed(boom),
          terminations == Vector(
            LifecycleTerminationReason.Failed(
              LifecycleFailure.Stage(LifecycleFailureStage.Writer)
            )
          )
        )
      }
    },
    test("a failure remains observable after the connection has fully closed") {
      ZIO.scoped {
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): Task[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     _ => ZIO.fail(Exception("handler failed"))
                   def view(model: Signal[Int]) = div(model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _          <- outputs.take
          submitted  <- connection.submitInfo(1).either
          _          <- connection.awaitClosed
          failure    <- connection.pollFailure
        yield assertTrue(
          submitted.left.exists(_.isInstanceOf[ConnectionError.SessionFailed]),
          failure.exists(_.isInstanceOf[ConnectionError.SessionFailed])
        )
      }
    },
    test("connected mount and message contexts expose the exact metadata") {
      ZIO.scoped {
        for
          mounted <- Ref.make(Option.empty[(Boolean, Map[String, Json])])
          handled <- Ref.make(Option.empty[(Boolean, Map[String, Json])])
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): Task[Int] =
                     ctx.connection match
                       case Connection.Connected(connected) =>
                         mounted.set(Some(connected.staticChanged -> connected.connectParams)).as(0)
                       case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     message =>
                       handled.set(Some(ctx.staticChanged -> ctx.connectParams)).as(model + message)
                   def view(model: Signal[Int]): HtmlElement[Int] =
                     button(on.click(1), model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          joined     <- outputs.take
          binding = bindingFrom(joined)
          command = CommandId.fresh().toOption.get
          _            <- connection.submitEvent(command, binding, BindingPayload.Params(Map.empty))
          mountMetadata <- mounted.get
          messageMetadata <- handled.get
        yield assertTrue(
          mountMetadata.contains(metadata.staticChanged -> metadata.connectParams),
          messageMetadata.contains(metadata.staticChanged -> metadata.connectParams)
        )
      }
    },
    test("disconnected mount context stays pure until a deferred operation is invoked") {
      val context = RootMountContext.disconnected[Int, Int]
      for failure <- context.nav.redirectUnsafe("/").either
      yield assertTrue(
        context.connection == Connection.Disconnected,
        failure.isLeft
      )
    },
    test("static and dynamic event hooks keep order, replacement position, and halt the handler") {
      ZIO.scoped {
        for
          order <- Ref.make(Vector.empty[String])
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   override val hooks = LiveHooks.empty[Int, Int].onEvent { (model, _, _) =>
                     order.update(_ :+ "static").as(LiveHookResult.cont(model + 1))
                   }
                   def mount(ctx: MountContext): Task[Int] =
                     ctx.hooks.event.attach("a")((model, _, _) =>
                       order.update(_ :+ "old-a").as(LiveHookResult.cont(model + 1000))) *>
                       ctx.hooks.event.attach("b")((model, _, _) =>
                         order.update(_ :+ "b").as(LiveHookResult.halt(model + 100))) *>
                       ctx.hooks.event.attach("removed")((model, _, _) =>
                         order.update(_ :+ "removed").as(LiveHookResult.cont(model))) *>
                       ctx.hooks.event.attach("a")((model, _, _) =>
                         order.update(_ :+ "a").as(LiveHookResult.cont(model + 10))) *>
                       ctx.hooks.event.detach("removed").as(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     _ => order.update(_ :+ "handler").as(model + 10000)
                   def view(model: Signal[Int]) = button(on.click(1), model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          joined <- outputs.take
          command = CommandId.fresh().toOption.get
          _ <- connection.submitEvent(command, bindingFrom(joined), BindingPayload.Params(Map.empty))
          _ <- outputs.take
          seen <- order.get
          model <- connection.inspectModel
        yield assertTrue(seen == Vector("static", "a", "b"), model == 111)
      }
    },
    test("named browser hooks consume malformed payloads and bypass binding lookup") {
      ZIO.scoped {
        val named = BrowserToServerEvent[Int]("counter")
        for
          calls <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   override val hooks = LiveHooks.empty[Int, Int].onBrowserEvent(named) {
                     (model, amount, _) => calls.update(_ + 1).as(model + amount)
                   }
                   def mount(ctx: MountContext): Task[Int] = ZIO.succeed(5)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     amount => ZIO.succeed(model + amount)
                   def view(model: Signal[Int]) = div(model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _ <- outputs.take
          unknown = BindingId.fromEncoded("not-a-binding")
          good = CommandId.fresh().toOption.get
          _ <- connection.submitNamedEvent(good, unknown, BindingPayload.Params(Map.empty), "counter", "2")
          _ <- outputs.take
          afterGood <- connection.inspectModel
          bad = CommandId.fresh().toOption.get
          _ <- connection.submitNamedEvent(bad, unknown, BindingPayload.Params(Map.empty), "counter", "nope")
          _ <- outputs.take
          afterBad <- connection.inspectModel
          count <- calls.get
        yield assertTrue(afterGood == 7, afterBad == 7, count == 1)
      }
    },
    test("raw event hooks can halt with a correlated browser reply") {
      ZIO.scoped {
        val reply = Json.Obj("result" -> Json.Str("accepted"))
        val event = LiveEvent(
          kind = "hook",
          bindingId = "sandbox:eval",
          value = Json.Obj("value" -> Json.Str("socket.assigns")),
          params = Map("value" -> "socket.assigns"),
          cid = None,
          meta = None
        )
        for
          seen    <- Ref.make(Option.empty[LiveEvent])
          handled <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   override val hooks = LiveHooks.empty[Int, Int].onRawEvent { (model, event, _) =>
                     seen.set(Some(event)).as(LiveEventHookResult.haltReply(model + 1, reply))
                   }
                   def mount(ctx: MountContext): Task[Int] = ZIO.succeed(5)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     amount => handled.update(_ + 1).as(model + amount)
                   def view(model: Signal[Int]) = div(model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _           <- outputs.take
          command      = CommandId.fresh().toOption.get
          _ <- connection.submitRawEvent(
                 command,
                 BindingId.fromEncoded(event.bindingId),
                 BindingPayload.Params(event.params),
                 event
               )
          output    <- outputs.take
          observed  <- seen.get
          calls     <- handled.get
          model     <- connection.inspectModel
        yield assertTrue(
          observed.contains(event),
          calls == 0,
          model == 6,
          output match
            case ConnectionOutput.ReplyWithPayload(`command`, _, _, `reply`) => true
            case _                                                           => false
        )
      }
    },
    test("params hooks precede the callback and after-render hooks run on empty diffs") {
      ZIO.scoped {
        for
          order <- Ref.make(Vector.empty[String])
          renders <- Ref.make(Vector.empty[String])
          outputs <- Queue.unbounded[ConnectionOutput]
          lifecycle = RootLifecycle[Int, Int](
            URL.root,
            LiveHooks.empty[Int, Int]
              .onParams((model, _, _) => order.update(_ :+ "params").as(LiveHookResult.cont(model + 1)))
              .afterRender((_, _) => renders.update(_ :+ "static")),
            _ => None,
            ctx => ctx.hooks.afterRender.attach("dynamic")((_, _) => renders.update(_ :+ "dynamic")).as(0),
            (model, _, _) => ZIO.succeed(model),
            _ => ZIO.succeed(
              RootParamsHandler(
                runHooks = true,
                (model, _) => order.update(_ :+ "callback").as(model + 1)
              )
            ),
            state => div(state.map(_._1.toString))
          )
          connection <- RootConnection.startLifecycle(config, metadata, lifecycle, outputs.offer(_).unit)
          _ <- outputs.take
          _ <- connection.submitInfo(0)
          reply <- outputs.take
          seen <- order.get
          after <- renders.get
        yield assertTrue(
          seen == Vector("params", "callback"),
          after == Vector("static", "dynamic", "static", "dynamic"),
          reply.isInstanceOf[ConnectionOutput.Diff]
        )
      }
    },
    test("connected-turn guards skip bootstrap and precede every connected handler boundary") {
      ZIO.scoped {
        val destination = URL.decode("/params").toOption.get
        for
          order   <- Ref.make(Vector.empty[String])
          outputs <- Queue.unbounded[ConnectionOutput]
          lifecycle = RootLifecycle[String, Int](
                        initialUrl = URL.root,
                        hooks = LiveHooks.empty[String, Int]
                          .onRawEvent((model, _, _) =>
                            order.update(_ :+ "raw").as(LiveEventHookResult.cont(model)))
                          .onEvent((model, message, _) =>
                            order.update(_ :+ s"event:$message").as(LiveHookResult.cont(model)))
                          .onInfo((model, message, _) =>
                            order.update(_ :+ s"info:$message").as(LiveHookResult.cont(model)))
                          .onParams((model, _, _) =>
                            order.update(_ :+ "params").as(LiveHookResult.cont(model)))
                          .onAsync((model, _, _) =>
                            order.update(_ :+ "async").as(LiveHookResult.cont(model)))
                          .afterRender((_, _) => order.update(_ :+ "after-render")),
                        pageTitle = _ => None,
                        mount = _ => order.update(_ :+ "mount").as(0),
                        handleMessage = (model, _, message) =>
                          order.update(_ :+ s"handler:$message").as(model + 1),
                        prepareParams = _ =>
                          ZIO.succeed(
                            RootParamsHandler(
                              runHooks = true,
                              (model, _) => order.update(_ :+ "params-handler").as(model + 1)
                            )
                          ),
                        view = state => button(on.click("event"), state.map(_._1.toString)),
                        connectedTurnGuard = LiveConnectedTurnGuard(_ => order.update(_ :+ "guard"))
                      )
          connection <- RootConnection.startLifecycle(config, metadata, lifecycle, outputs.offer(_).unit)
          joined     <- outputs.take
          bootstrap  <- order.getAndSet(Vector.empty)
          eventId     = CommandId.fresh().toOption.get
          _ <- connection.submitEvent(
                 eventId,
                 bindingFrom(joined),
                 BindingPayload.Params(Map.empty)
               )
          _       <- outputs.take
          _       <- connection.submitInfo("info")
          _       <- outputs.take
          patchId  = CommandId.fresh().toOption.get
          _       <- connection.submitPatch(patchId, destination)
          _       <- outputs.take
          async    = LiveAsyncEvent(AsyncKey[Any]("work"), LiveAsyncResult.Succeeded("async"))
          _       <- connection.submitAsyncCompletion(async)
          _       <- outputs.take
          connected <- order.get
        yield assertTrue(
          bootstrap == Vector("mount", "params", "params-handler", "after-render"),
          connected == Vector(
            "guard",
            "raw",
            "event:event",
            "handler:event",
            "after-render",
            "guard",
            "info:info",
            "handler:info",
            "after-render",
            "guard",
            "params",
            "params-handler",
            "after-render",
            "guard",
            "async",
            "handler:async",
            "after-render"
          )
        )
      }
    },
    test("a guard halt settles a browser command without running application code") {
      ZIO.scoped {
        for
          rawCalls     <- Ref.make(0)
          handlerCalls <- Ref.make(0)
          renders      <- Ref.make(0)
          outputs      <- Queue.unbounded[ConnectionOutput]
          lifecycle = RootLifecycle.ordinary(
                        new LiveView[Int, Int]:
                          override val hooks = LiveHooks.empty[Int, Int]
                            .onRawEvent((model, _, _) =>
                              rawCalls.update(_ + 1).as(LiveEventHookResult.cont(model)))
                            .afterRender((_, _) => renders.update(_ + 1))
                          def mount(ctx: MountContext): Task[Int] = ZIO.succeed(7)
                          def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                            amount => handlerCalls.update(_ + 1).as(model + amount)
                          def view(model: Signal[Int]) = button(on.click(1), model.map(_.toString)),
                        connectedTurnGuard = LiveConnectedTurnGuard(_ =>
                          ZIO.fail(LiveConnectedTurnFailure.Halt)
                        )
                      )
          connection <- RootConnection.startLifecycle(config, metadata, lifecycle, outputs.offer(_).unit)
          joined     <- outputs.take
          beforeTree <- connection.inspectTree
          command     = CommandId.fresh().toOption.get
          _ <- connection.submitEvent(command, bindingFrom(joined), BindingPayload.Params(Map.empty))
          reply       <- outputs.take
          model       <- connection.inspectModel
          afterTree   <- connection.inspectTree
          rawCount    <- rawCalls.get
          handled     <- handlerCalls.get
          renderCount <- renders.get
          pending     <- connection.pendingCount
        yield assertTrue(
          reply match
            case ConnectionOutput.Reply(`command`, RenderDelta.Empty, _) => true
            case _                                                        => false,
          rawCount == 0,
          handled == 0,
          renderCount == 1,
          model == 7,
          afterTree == beforeTree,
          pending == 0
        )
      }
    },
    test("reload and disconnect guards emit terminal correlated replies and close cleanly") {
      val current = URL.decode("/committed?from=guard").toOption.get
      def run(
        failure: LiveConnectedTurnFailure
      ): ZIO[Scope, Nothing, (ConnectionOutput, CommandId, Boolean, Int)] =
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          lifecycle = RootLifecycle.ordinary(
                        new LiveView[Int, Int]:
                          def mount(ctx: MountContext): Task[Int] = ZIO.succeed(0)
                          def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                            amount => ZIO.succeed(model + amount)
                          def view(model: Signal[Int]) = button(on.click(1), model.map(_.toString)),
                        initialUrl = current,
                        connectedTurnGuard = LiveConnectedTurnGuard(_ => ZIO.fail(failure))
                      )
          connection <- RootConnection.startLifecycle(config, metadata, lifecycle, outputs.offer(_).unit).orDie
          joined     <- outputs.take
          command     = CommandId.fresh().toOption.get
          _ <- connection
                 .submitEvent(command, bindingFrom(joined), BindingPayload.Params(Map.empty)).orDie
          output  <- outputs.take
          _       <- connection.awaitClosed
          failed  <- connection.pollFailure
          pending <- connection.pendingCount
        yield (output, command, failed.isEmpty, pending)

      ZIO.scoped {
        for
          reload     <- run(LiveConnectedTurnFailure.Reload(Some("stale")))
          disconnect <- run(LiveConnectedTurnFailure.Disconnect(Some("gone")))
        yield assertTrue(
          reload._1 match
            case ConnectionOutput.ReplyNavigation(command, RenderDelta.Empty, navigation, _) =>
              command == reload._2 &&
                navigation.kind == scalive.runtime.kernel.NavigationKind.Redirect &&
                navigation.destination == current
            case _ => false,
          reload._3,
          reload._4 == 0,
          disconnect._1.isInstanceOf[ConnectionOutput.ReplyDisconnect],
          disconnect._1 match
            case ConnectionOutput.ReplyDisconnect(command, reason) =>
              command == disconnect._2 && reason.contains("gone")
            case _ => false,
          disconnect._3,
          disconnect._4 == 0
        )
      }
    },
    test("a connected-turn guard defect fails at its own stage without publishing an update") {
      ZIO.scoped {
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          lifecycle = RootLifecycle.ordinary(
                        new LiveView[Int, Int]:
                          def mount(ctx: MountContext): Task[Int] = ZIO.succeed(0)
                          def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                            amount => ZIO.succeed(model + amount)
                          def view(model: Signal[Int]) = div(model.map(_.toString)),
                        connectedTurnGuard = LiveConnectedTurnGuard(_ =>
                          ZIO.dieMessage("guard defect")
                        )
                      )
          connection <- RootConnection.startLifecycle(config, metadata, lifecycle, outputs.offer(_).unit)
          _          <- outputs.take
          result     <- connection.submitInfo(1).either
          _          <- connection.awaitClosed
          update     <- outputs.poll
        yield assertTrue(
          result match
            case Left(
                  ConnectionError.SessionFailed(
                    scalive.runtime.kernel.SessionFailure.StageFailed(
                      scalive.runtime.kernel.SessionStage.ConnectedTurnGuard,
                      details
                    )
                  )
                ) => details.contains("guard defect")
            case _ => false,
          update.isEmpty
        )
      }
    },
    test("info hook attachment survives patch acknowledgement and deferred replay") {
      ZIO.scoped {
        val destination = URL.decode("/next").toOption.get
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): Task[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     message =>
                       if message == 1 then
                         ctx.hooks.info.attach("persist")((value, amount, _) =>
                           ZIO.succeed(LiveHookResult.halt(value + amount * 10))) *>
                           ctx.nav.pushPatchUnsafe("/next").as(model + 1)
                       else ZIO.succeed(model + message)
                   def view(model: Signal[Int]) = button(on.click(1), model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          joined <- outputs.take
          eventId = CommandId.fresh().toOption.get
          _ <- connection.submitEvent(eventId, bindingFrom(joined), BindingPayload.Params(Map.empty))
          navigation <- outputs.take
          deferred <- connection.submitInfo(2).fork
          _ <- ZIO.yieldNow
          patchId = CommandId.fresh().toOption.get
          _ <- connection.submitPatch(patchId, destination)
          _ <- deferred.join
          model <- connection.inspectModel
        yield assertTrue(
          navigation.isInstanceOf[ConnectionOutput.ReplyNavigation],
          model == 21
        )
      }
    },
    test("URL synchronization updates the next lifecycle turn") {
      ZIO.scoped {
        val destination = URL.decode("/next?source=root").toOption.get
        val expected    = URL.decode("/next?source=child").toOption.get
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Unit, Unit]:
                   def mount(ctx: MountContext): Task[Unit] = ZIO.unit
                   def handleMessage(model: Unit, ctx: MessageContext): Unit => Task[Unit] =
                     _ => ctx.nav.pushPatchUnsafe("?source=child")
                   def view(model: Signal[Unit]): HtmlElement[Unit] = button(on.click(()))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          joined     <- outputs.take
          binding     = bindingFrom(joined)
          _           <- connection.synchronizeUrl(destination)
          _           <- outputs.take
          command     = CommandId.fresh().toOption.get
          _ <- connection.submitEvent(command, binding, BindingPayload.Params(Map.empty))
          navigation <- outputs.take
        yield assertTrue(
          navigation match
            case ConnectionOutput.ReplyNavigation(`command`, _, output, _) =>
              output.destination == expected
            case _ => false
        )
      }
    },
    test("flash commits transactionally and survives patch acknowledgement") {
      ZIO.scoped {
        val notice      = FlashKind("notice")
        val destination = URL.decode("/next").toOption.get
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): Task[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     message =>
                       message match
                         case 1 => ctx.flash.put(notice, "saved").as(model + 1)
                         case 2 => ctx.nav.pushPatchUnsafe("/next").as(model + 1)
                         case _ => ctx.flash.clear(notice).as(model + 1)
                   def view(model: Signal[Int]) =
                     button(
                       on.click(1),
                       "put",
                       flash(notice)(message => span(message))
                     )
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          joined     <- outputs.take
          binding     = bindingFrom(joined)
          putId       = CommandId.fresh().toOption.get
          _          <- connection.submitEvent(putId, binding, BindingPayload.Params(Map.empty))
          _          <- outputs.take
          afterPut   <- connection.inspectFlash
          patchId     = CommandId.fresh().toOption.get
          _          <- connection.submitInfo(2)
          navigation <- outputs.take
          _          <- connection.submitPatch(patchId, destination)
          _          <- outputs.take
          afterPatch <- connection.inspectFlash
          _          <- connection.submitInfo(3)
          _          <- outputs.take
          afterClear <- connection.inspectFlash
        yield assertTrue(
          afterPut == Map(notice -> "saved"),
          navigation.isInstanceOf[ConnectionOutput.DiffNavigation],
          afterPatch == afterPut,
          afterClear.isEmpty
        )
      }
    },
    test("terminal navigation flushes once and closes without failing the physical connection") {
      ZIO.scoped {
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): Task[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     case 1 => ctx.nav.pushNavigateUnsafe("/next").as(model + 1)
                     case n => ZIO.succeed(model + n)
                   def view(model: Signal[Int]) = div(model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _          <- outputs.take
          failure    <- connection.awaitFailure.fork
          _          <- connection.submitInfo(1)
          navigation <- outputs.take
          _          <- connection.isClosing.repeatUntil(identity)
          later      <- connection.submitInfo(2).either
          failed     <- failure.poll
          _          <- failure.interrupt
        yield assertTrue(
          navigation match
            case ConnectionOutput.DiffNavigation(_, output, _) =>
              output.kind == scalive.runtime.kernel.NavigationKind.PushNavigate &&
                output.destination == URL.decode("/next").toOption.get
            case _ => false,
          later == Left(ConnectionError.Closed),
          failed.isEmpty
        )
      }
    },
    test("page titles and client events commit in lifecycle order") {
      ZIO.scoped {
        val countEvent = ServerToBrowserEvent[Int]("count")
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   override val hooks = LiveHooks.empty[Int, Int].afterRender { (model, ctx) =>
                     ctx.connection match
                       case Connection.Connected(connected) =>
                         connected.client.push(countEvent, model + 10)
                       case Connection.Disconnected => ZIO.unit
                   }
                   override def pageTitle(model: Int): Option[String] = model match
                     case 0 => Some("zero")
                     case 1 => Some("one")
                     case _ => None
                   def mount(ctx: MountContext): Task[Int] =
                     ctx.connection match
                       case Connection.Connected(connected) =>
                         connected.client.push(countEvent, 0).as(0)
                       case Connection.Disconnected => ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     message =>
                       ctx.client.push(countEvent, message) *>
                         ctx.client.exec(JS) *>
                         ZIO.succeed(message)
                   def view(model: Signal[Int]) = button(on.click(1), model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          joined     <- outputs.take
          initialEffects = joined match
                             case ConnectionOutput.Joined(_, effects) => effects
                             case other => throw AssertionError(s"unexpected bootstrap output: $other")
          firstId = CommandId.fresh().toOption.get
          _ <- connection.submitEvent(
                 firstId,
                 bindingFrom(joined),
                 BindingPayload.Params(Map.empty)
               )
          first <- outputs.take
          firstEffects = first match
                           case ConnectionOutput.Reply(`firstId`, _, effects) => effects
                           case other => throw AssertionError(s"unexpected first output: $other")
          _      <- connection.submitInfo(2)
          second <- outputs.take
          secondEffects = second match
                             case ConnectionOutput.Diff(_, effects) => effects
                             case other => throw AssertionError(s"unexpected second output: $other")
        yield assertTrue(
          initialEffects.pageTitle.contains("zero"),
          initialEffects.clientEvents == Vector(
            scalive.runtime.kernel.ClientEffect("count", Json.Num(0)),
            scalive.runtime.kernel.ClientEffect("count", Json.Num(10))
          ),
          firstEffects.pageTitle.contains("one"),
          firstEffects.clientEvents == Vector(
            scalive.runtime.kernel.ClientEffect("count", Json.Num(1)),
            scalive.runtime.kernel.ClientEffect(
              "js:exec",
              Json.Obj("cmd" -> Json.Str("[]"))
            ),
            scalive.runtime.kernel.ClientEffect("count", Json.Num(11))
          ),
          secondEffects.pageTitle.contains(""),
          secondEffects.clientEvents == Vector(
            scalive.runtime.kernel.ClientEffect("count", Json.Num(2)),
            scalive.runtime.kernel.ClientEffect(
              "js:exec",
              Json.Obj("cmd" -> Json.Str("[]"))
            ),
            scalive.runtime.kernel.ClientEffect("count", Json.Num(12))
          )
        )
      }
    },
    test("async completion has its own hook boundary and honors halt") {
      ZIO.scoped {
        for
          handled <- Ref.make(false)
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): Task[Int] =
                     ctx.hooks.async.attach("async")((model, event, _) => event.result match
                       case LiveAsyncResult.Succeeded(value) =>
                         ZIO.succeed(LiveHookResult.halt(model + value * 10))
                       case _ => ZIO.succeed(LiveHookResult.cont(model))).as(1)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     message => handled.set(true).as(model + message)
                   def view(model: Signal[Int]) = div(model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _ <- outputs.take
          event = LiveAsyncEvent(AsyncKey[Any]("work"), LiveAsyncResult.Succeeded(2))
          _ <- connection.submitAsyncCompletion(event)
          _ <- outputs.take
          model <- connection.inspectModel
          called <- handled.get
        yield assertTrue(model == 21, !called)
      }
    },
    test("normal close is idempotent and rejects future submissions") {
      ZIO.scoped {
        for
          mounts  <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, Counter(mounts), outputs.offer(_).unit)
          _       <- outputs.take
          _       <- connection.close *> connection.close
          closed  <- connection.awaitClosed.timeout(1.second)
          command = CommandId.fresh().toOption.get
          result <- connection
                      .submitEvent(
                        command,
                        BindingId.fromEncoded("closed"),
                        BindingPayload.Params(Map.empty)
                      ).either
        yield assertTrue(closed.nonEmpty, result == Left(ConnectionError.Closed))
      }
    },
    test("connected resources return their value and release exactly once on close") {
      ZIO.scoped {
        for
          acquisitions <- Ref.make(0)
          releases     <- Ref.make(Vector.empty[Int])
          outputs      <- Queue.unbounded[ConnectionOutput]
          view = new LiveView.Eventless[Int]:
                   def mount(ctx: MountContext): Task[Int] = ctx.connection match
                     case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                     case Connection.Connected(connected) =>
                       connected.resources.acquireRelease(acquisitions.updateAndGet(_ + 1))(
                         value => releases.update(_ :+ value)
                       )
                   def view(model: Signal[Int]): HtmlElement[Nothing] = div(model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _          <- outputs.take
          model      <- connection.inspectModel
          before     <- releases.get
          _          <- connection.close *> connection.close
          after      <- releases.get
        yield assertTrue(model == 1, before.isEmpty, after == Vector(1))
      }
    },
    test("connected resource acquisition composes reentrantly") {
      ZIO.scoped {
        for
          releases <- Ref.make(Set.empty[String])
          outputs  <- Queue.unbounded[ConnectionOutput]
          view = new LiveView.Eventless[String]:
                   def mount(ctx: MountContext): Task[String] = ctx.connection match
                     case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                     case Connection.Connected(connected) =>
                       connected.resources.acquireRelease(
                         connected.resources
                           .acquireRelease(ZIO.succeed("inner"))(_ =>
                             releases.update(_ + "inner")
                           ).as("outer")
                       )(_ => releases.update(_ + "outer"))
                   def view(model: Signal[String]): HtmlElement[Nothing] = div(model)
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _          <- outputs.take
          model      <- connection.inspectModel
          _          <- connection.close
          finalized  <- releases.get
        yield assertTrue(model == "outer", finalized == Set("inner", "outer"))
      }
    },
    test("connected resources are independent across lifecycles sharing application state") {
      ZIO.scoped {
        for
          nextId   <- Ref.make(0)
          releases <- Ref.make(Vector.empty[Int])
          firstOut <- Queue.unbounded[ConnectionOutput]
          secondOut <- Queue.unbounded[ConnectionOutput]
          view = new LiveView.Eventless[Int]:
                   def mount(ctx: MountContext): Task[Int] = ctx.connection match
                     case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                     case Connection.Connected(connected) =>
                       connected.resources.acquireRelease(nextId.updateAndGet(_ + 1))(
                         id => releases.update(_ :+ id)
                       )
                   def view(model: Signal[Int]): HtmlElement[Nothing] = div(model.map(_.toString))
          first  <- RootConnection.start(config, metadata, view, firstOut.offer(_).unit)
          _      <- firstOut.take
          second <- RootConnection.start(config, metadata, view, secondOut.offer(_).unit)
          _      <- secondOut.take
          firstModel  <- first.inspectModel
          secondModel <- second.inspectModel
          _            <- first.close
          afterFirst   <- releases.get
          _            <- second.close
          afterSecond  <- releases.get
        yield assertTrue(
          firstModel != secondModel,
          afterFirst == Vector(firstModel),
          afterSecond.toSet == Set(firstModel, secondModel)
        )
      }
    },
    test("failed connected resource acquisition does not run release") {
      ZIO.scoped {
        for
          releases <- Ref.make(0)
          outputs  <- Queue.unbounded[ConnectionOutput]
          failure = RuntimeException("resource acquisition failed")
          view = new LiveView.Eventless[Unit]:
                   def mount(ctx: MountContext): Task[Unit] = ctx.connection match
                     case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                     case Connection.Connected(connected) =>
                       connected.resources
                         .acquireRelease[Unit](ZIO.fail(failure))(_ => releases.update(_ + 1))
                   def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
          result   <- RootConnection.start(config, metadata, view, outputs.offer(_).unit).either
          released <- releases.get
        yield assertTrue(
          result.left.exists(_.isInstanceOf[ConnectionError.SessionFailed]),
          released == 0
        )
      }
    },
    test("a connected resource capability captured from mount rejects use after close") {
      ZIO.scoped {
        for
          captured    <- Promise.make[Nothing, ConnectedResources]
          acquisitions <- Ref.make(0)
          releases     <- Ref.make(0)
          outputs      <- Queue.unbounded[ConnectionOutput]
          view = new LiveView.Eventless[Unit]:
                   def mount(ctx: MountContext): Task[Unit] = ctx.connection match
                     case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                     case Connection.Connected(connected) =>
                       captured.succeed(connected.resources).unit
                   def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _          <- outputs.take
          resources  <- captured.await
          _          <- connection.close
          result <- resources
                      .acquireRelease(acquisitions.updateAndGet(_ + 1))(_ =>
                        releases.update(_ + 1)
                      ).either
          acquired <- acquisitions.get
          released <- releases.get
        yield assertTrue(result.isLeft, acquired == 0, released == 0)
      }
    },
    test("startup failure after acquisition releases connected resources before returning") {
      ZIO.scoped {
        for
          acquired <- Ref.make(false)
          released <- Ref.make(false)
          outputs  <- Queue.unbounded[ConnectionOutput]
          view = new LiveView.Eventless[Unit]:
                   def mount(ctx: MountContext): Task[Unit] = ctx.connection match
                     case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                     case Connection.Connected(connected) =>
                       connected.resources
                         .acquireRelease(acquired.set(true))(_ => released.set(true))
                   def view(model: Signal[Unit]): HtmlElement[Nothing] =
                     div(model.map(_ => throw RuntimeException("initial render failed")))
          result       <- RootConnection.start(config, metadata, view, outputs.offer(_).unit).either
          didAcquire   <- acquired.get
          didRelease   <- released.get
        yield assertTrue(
          result.left.exists(_.isInstanceOf[ConnectionError.SessionFailed]),
          didAcquire,
          didRelease
        )
      }
    },
    test("connected resource cleanup defects do not suppress other finalizers or closure") {
      ZIO.scoped {
        for
          laterFinalized <- Ref.make(false)
          outputs        <- Queue.unbounded[ConnectionOutput]
          view = new LiveView.Eventless[Unit]:
                   def mount(ctx: MountContext): Task[Unit] = ctx.connection match
                     case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                     case Connection.Connected(connected) =>
                       connected.resources
                         .acquireRelease(ZIO.unit)(_ => laterFinalized.set(true)) *>
                         connected.resources
                           .acquireRelease(ZIO.unit)(_ => ZIO.dieMessage("resource cleanup defect"))
                           .unit
                   def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _          <- outputs.take
          _          <- connection.close.exit
          closed     <- connection.awaitClosed.timeout(1.second)
          laterRan   <- laterFinalized.get
        yield assertTrue(closed.nonEmpty, laterRan)
      }
    },
    test("concurrent connected resource close callers await the same finalization") {
      for
        resources <- ScopedConnectedResources.make
        entered   <- Promise.make[Nothing, Unit]
        allow     <- Promise.make[Nothing, Unit]
        _ <- resources
               .acquireRelease(ZIO.unit)(_ => entered.succeed(()).unit *> allow.await)
        first  <- resources.close.fork
        _      <- entered.await
        second <- resources.close.fork
        early  <- second.poll
        _      <- allow.succeed(())
        firstExit  <- first.await
        secondExit <- second.await
      yield assertTrue(early.isEmpty, firstExit.isSuccess, secondExit.isSuccess)
    },
    test("cleanup defects cannot suppress connection closure") {
      ZIO.scoped {
        for
          laterFinalized <- Promise.make[Nothing, Unit]
          defectStarted  <- Promise.make[Nothing, Unit]
          laterStarted   <- Promise.make[Nothing, Unit]
          outputs        <- Queue.unbounded[ConnectionOutput]
          view = new LiveView.Eventless[Unit]:
                   def mount(ctx: MountContext): Task[Unit] =
                     ctx.connection match
                       case Connection.Connected(connected) =>
                         connected.subscriptions.start(
                           SubscriptionKey("defect"),
                           SubscriptionDelivery.Lossless
                         )(
                           (ZStream.fromZIO(defectStarted.succeed(()).unit) *> ZStream.never)
                             .ensuring(ZIO.dieMessage("subscription cleanup defect"))
                         ) *>
                           connected.subscriptions.start(
                             SubscriptionKey("later"),
                             SubscriptionDelivery.Lossless
                           )(
                             (ZStream.fromZIO(laterStarted.succeed(()).unit) *> ZStream.never)
                               .ensuring(laterFinalized.succeed(()).unit)
                           )
                       case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                   def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          _          <- outputs.take
          _          <- defectStarted.await *> laterStarted.await
          closeExit  <- connection.close.exit
          closed     <- connection.awaitClosed.timeout(1.second)
          laterRan   <- laterFinalized.isDone
        yield assertTrue(closeExit.isSuccess, closed.nonEmpty, laterRan)
      }
    },
    test("interrupted close finishes cleanup before publishing closed") {
      ZIO.scoped {
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): Task[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     message =>
                       ZIO.uninterruptible(
                         entered.succeed(()).unit *> release.await.as(model + message)
                       )
                   def view(model: Signal[Int]): HtmlElement[Int] =
                     button(on.click(1), model.map(_.toString))
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          joined     <- outputs.take
          binding     = bindingFrom(joined)
          command     = CommandId.fresh().toOption.get
          submitting <- connection
                          .submitEvent(command, binding, BindingPayload.Params(Map.empty)).either.fork
          _           <- entered.await
          closing     <- connection.close.fork
          _           <- connection.isClosing.repeatUntil(identity)
          ownerStatus <- closing.status.repeatUntil(_.isSuspended)
          secondClose <- connection.close.fork
          waiterStatus <- secondClose.status.repeatUntil(_.isSuspended)
          _             <- closing.interruptFork
          _             <- secondClose.interruptFork
          ownerPending  <- closing.poll
          _             <- release.succeed(())
          ownerExit     <- closing.await
          waiterExit    <- secondClose.await
          result        <- submitting.join
          pending       <- connection.pendingCount
          ownerInterruptible = ownerStatus match
                                 case Fiber.Status.Suspended(flags, _, _) =>
                                   RuntimeFlags.interruption(flags)
                                 case _ => true
          waiterInterruptible = waiterStatus match
                                  case Fiber.Status.Suspended(flags, _, _) =>
                                    RuntimeFlags.interruption(flags)
                                  case _ => false
        yield assertTrue(
          !ownerInterruptible,
          waiterInterruptible,
          ownerPending.isEmpty,
          ownerExit.isInterrupted,
          waiterExit.isInterrupted,
          result == Left(ConnectionError.Closed),
          pending == 0
        )
      }
    },
    test("ingress saturation is terminal") {
      ZIO.scoped {
        val smallConfig = ConnectionConfig.make(1, 4, 4, 4, 4).toOption.get
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          outputs <- Queue.unbounded[ConnectionOutput]
          events  <- Ref.make(Vector.empty[LifecycleEvent])
          observer = RuntimeObserver.withLifecycleObserver(
                       LifecycleObserver.fromFunction(event => events.update(_ :+ event))
                     )
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): Task[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => Task[Int] =
                     message => entered.succeed(()).unit *> release.await.as(model + message)
                   def view(model: Signal[Int]): HtmlElement[Int] =
                     button(on.click(1), model.map(_.toString))
          connection <- RootConnection.start(
                          smallConfig,
                          metadata,
                          view,
                          outputs.offer(_).unit,
                          observer = observer
                        )
          joined     <- outputs.take
          binding = bindingFrom(joined)
          firstCommand  = CommandId.fresh().toOption.get
          secondCommand = CommandId.fresh().toOption.get
          thirdCommand  = CommandId.fresh().toOption.get
          first <- connection
                     .submitEvent(firstCommand, binding, BindingPayload.Params(Map.empty)).fork
          _      <- entered.await
          second <- connection
                      .submitEvent(secondCommand, binding, BindingPayload.Params(Map.empty)).fork
          _ <- connection.pendingCount.repeatUntil(_ == 2)
          saturated <- connection
                         .submitEvent(thirdCommand, binding, BindingPayload.Params(Map.empty)).either
          terminal <- connection.awaitFailure
          _        <- release.succeed(()) *> first.await *> second.await
          _        <- connection.awaitClosed
          observed <- events.get.repeatUntil(
                        _.exists(_.isInstanceOf[LifecycleEvent.LifecycleTerminated])
                      )
          pressure = observed.collect { case event: LifecycleEvent.QueuePressure => event }
          terminations = observed.collect {
                           case event: LifecycleEvent.LifecycleTerminated => event.reason
                         }
        yield assertTrue(
          saturated == Left(ConnectionError.IngressSaturated(1)),
          terminal == ConnectionError.IngressSaturated(1),
          pressure.exists(event =>
            event.queue == LifecycleQueue.ConnectionPendingCommands &&
              event.status == LifecycleQueueStatus.Saturated &&
              event.depth == 2 &&
              event.capacity == 2
          ),
          terminations == Vector(
            LifecycleTerminationReason.Failed(LifecycleFailure.IngressSaturated)
          )
        )
      }
    },
    test("event admission racing close always completes and leaves no pending correlation") {
      ZIO.foreach(1 to 40) { _ =>
        ZIO.scoped {
          for
            mounts  <- Ref.make(0)
            outputs <- Queue.unbounded[ConnectionOutput]
            connection <- RootConnection.start(
                            config,
                            metadata,
                            Counter(mounts),
                            outputs.offer(_).unit
                          )
            _ <- outputs.take
            submitCommand = CommandId.fresh().toOption.get
            offerCommand  = CommandId.fresh().toOption.get
            unknown       = BindingId.fromEncoded("close-race")
            raced <- connection
                       .submitEvent(submitCommand, unknown, BindingPayload.Params(Map.empty)).either
                       .zipPar(
                         connection
                           .offerEvent(offerCommand, unknown, BindingPayload.Params(Map.empty)).either
                       ).zipPar(connection.close)
            submitResult = raced._1
            offerResult  = raced._2
            pending <- connection.pendingCount
          yield assertTrue(
            submitResult.isRight || submitResult == Left(ConnectionError.Closed),
            offerResult.isRight || offerResult == Left(ConnectionError.Closed),
            pending == 0
          )
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    }
  )

  private def bindingFrom(output: ConnectionOutput): BindingId = output match
    case ConnectionOutput.Joined(RenderDelta.Replace(tree), _) =>
      val encoded = tree.root.attributes
        .flatMap(_.value).collectFirst { case AttributeValue.Text(value) => value }.get
      BindingId.fromEncoded(encoded)
    case other => throw AssertionError(s"unexpected bootstrap output: $other")
