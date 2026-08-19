package scalive.runtime.connection

import zio.*
import zio.json.ast.Json
import zio.json.*
import zio.http.URL
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.CommandId

object RootConnectionSpec extends ZIOSpecDefault:
  private val config = ConnectionConfig.make(4, 4, 4, 4, 4).toOption.get
  private val metadata = RootConnectionMetadata(
    staticChanged = true,
    connectParams = Map("token" -> Json.Str("exact"))
  )

  private final class Counter(mounts: Ref[Int]) extends LiveView[Int, Int]:
    override def mount(ctx: MountContext): LiveIO[Int] = mounts.updateAndGet(_ + 1).as(0)
    override def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
      amount => ZIO.succeed(model + amount)
    override def view(model: Signal[Int]): HtmlElement[Int] =
      button(on.click(1), model.map(_.toString))

  override def spec = suite("RootConnectionSpec")(
    test("mounts once, joins, and preserves the caller's command correlation") {
      ZIO.scoped {
        for
          mounts  <- Ref.make(0)
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, Counter(mounts), outputs.offer(_).unit)
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
        yield assertTrue(
          count == 1,
          joined match
            case ConnectionOutput.Joined(_, effects) => effects.pageTitle.isEmpty
            case _                                   => false,
          reply match
            case ConnectionOutput.Reply(`command`, _, _) => true
            case _                                    => false
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
          boom = RuntimeException("sink failed")
          result <- RootConnection.start(config, metadata, Counter(mounts), _ => ZIO.fail(boom)).either
        yield assertTrue(result == Left(ConnectionError.SinkFailed(boom)))
      }
    },
    test("connected mount and message contexts expose the exact metadata") {
      ZIO.scoped {
        for
          mounted <- Ref.make(Option.empty[(Boolean, Map[String, Json])])
          handled <- Ref.make(Option.empty[(Boolean, Map[String, Json])])
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): LiveIO[Int] =
                     ctx.connection match
                       case Connection.Connected(connected) =>
                         mounted.set(Some(connected.staticChanged -> connected.connectParams)).as(0)
                       case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
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
                   def mount(ctx: MountContext): LiveIO[Int] =
                     ctx.hooks.event.attach("a")((model, _, _) =>
                       order.update(_ :+ "old-a").as(LiveHookResult.cont(model + 1000))) *>
                       ctx.hooks.event.attach("b")((model, _, _) =>
                         order.update(_ :+ "b").as(LiveHookResult.halt(model + 100))) *>
                       ctx.hooks.event.attach("removed")((model, _, _) =>
                         order.update(_ :+ "removed").as(LiveHookResult.cont(model))) *>
                       ctx.hooks.event.attach("a")((model, _, _) =>
                         order.update(_ :+ "a").as(LiveHookResult.cont(model + 10))) *>
                       ctx.hooks.event.detach("removed").as(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
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
                   def mount(ctx: MountContext): LiveIO[Int] = ZIO.succeed(5)
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
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
          reply.isInstanceOf[ConnectionOutput.Reply]
        )
      }
    },
    test("info hook attachment survives patch acknowledgement and deferred replay") {
      ZIO.scoped {
        val destination = URL.decode("/next").toOption.get
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): LiveIO[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
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
    test("flash commits transactionally and survives patch acknowledgement") {
      ZIO.scoped {
        val notice      = FlashKind("notice")
        val destination = URL.decode("/next").toOption.get
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): LiveIO[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
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
          navigation.isInstanceOf[ConnectionOutput.ReplyNavigation],
          afterPatch == afterPut,
          afterClear.isEmpty
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
                   def mount(ctx: MountContext): LiveIO[Int] =
                     ctx.connection match
                       case Connection.Connected(connected) =>
                         connected.client.push(countEvent, 0).as(0)
                       case Connection.Disconnected => ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
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
                            case ConnectionOutput.Reply(_, _, effects) => effects
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
                   def mount(ctx: MountContext): LiveIO[Int] =
                     ctx.hooks.async.attach("async")((model, event, _) => event.result match
                       case LiveAsyncResult.Succeeded(value) =>
                         ZIO.succeed(LiveHookResult.halt(model + value * 10))
                       case _ => ZIO.succeed(LiveHookResult.cont(model))).as(1)
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
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
          command = CommandId.fresh().toOption.get
          result <- connection
                      .submitEvent(
                        command,
                        BindingId.fromEncoded("closed"),
                        BindingPayload.Params(Map.empty)
                      ).either
        yield assertTrue(result == Left(ConnectionError.Closed))
      }
    },
    test("interrupted close finishes cleanup before publishing closed") {
      ZIO.scoped {
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          outputs <- Queue.unbounded[ConnectionOutput]
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): LiveIO[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
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
          view = new LiveView[Int, Int]:
                   def mount(ctx: MountContext): LiveIO[Int] = ZIO.succeed(0)
                   def handleMessage(model: Int, ctx: MessageContext): Int => LiveIO[Int] =
                     message => entered.succeed(()).unit *> release.await.as(model + message)
                   def view(model: Signal[Int]): HtmlElement[Int] =
                     button(on.click(1), model.map(_.toString))
          connection <- RootConnection.start(smallConfig, metadata, view, outputs.offer(_).unit)
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
        yield assertTrue(
          saturated == Left(ConnectionError.IngressSaturated(1)),
          terminal == ConnectionError.IngressSaturated(1)
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
