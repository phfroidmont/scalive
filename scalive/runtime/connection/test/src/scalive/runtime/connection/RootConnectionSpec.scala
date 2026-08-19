package scalive.runtime.connection

import zio.*
import zio.json.ast.Json
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
                      case ConnectionOutput.Joined(RenderDelta.Replace(tree)) =>
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
          reply match
            case ConnectionOutput.Reply(`command`, _) => true
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
                ConnectionOutput.Reply(accepted, _),
                ConnectionOutput.Rejected(rejected, _)
              ) => accepted.value < rejected.value
          case _ => false
        })
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
          _ <- connection.ingressDepth.repeatUntil(_ == 1)
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
    case ConnectionOutput.Joined(RenderDelta.Replace(tree)) =>
      val encoded = tree.root.attributes
        .flatMap(_.value).collectFirst { case AttributeValue.Text(value) => value }.get
      BindingId.fromEncoded(encoded)
    case other => throw AssertionError(s"unexpected bootstrap output: $other")
