package scalive.runtime.connection

import zio.*
import zio.stream.ZStream
import zio.test.*

import scalive.*
import scalive.runtime.kernel.{RuntimeObserver, SessionRejection}

object ManagedSubscriptionsSpec extends ZIOSpecDefault:
  private val config = ConnectionConfig.make(4, 4, 1, 4, 4).toOption.get
  private val metadata = RootConnectionMetadata(staticChanged = true, connectParams = Map.empty)

  private enum Message:
    case Start(
      key: SubscriptionKey,
      delivery: SubscriptionDelivery,
      stream: ZStream[Any, Nothing, Message])
    case Replace(
      key: SubscriptionKey,
      delivery: SubscriptionDelivery,
      stream: ZStream[Any, Nothing, Message])
    case Cancel(key: SubscriptionKey)
    case Block(entered: Promise[Nothing, Unit], release: Promise[Nothing, Unit])
    case Value(value: Int)

  private final class Fixture extends LiveView[Message, Vector[Int]]:
    def mount(ctx: MountContext): Task[Vector[Int]] = ZIO.succeed(Vector.empty)

    def handleMessage(
      model: Vector[Int],
      ctx: MessageContext
    ): Message => Task[Vector[Int]] =
      case Message.Start(key, delivery, stream) =>
        ctx.subscriptions.start(key, delivery)(stream).as(model)
      case Message.Replace(key, delivery, stream) =>
        ctx.subscriptions.replace(key, delivery)(stream).as(model)
      case Message.Cancel(key) =>
        ctx.subscriptions.cancel(key).as(model)
      case Message.Block(entered, release) =>
        entered.succeed(()).unit *> release.await.as(model)
      case Message.Value(value) => ZIO.succeed(model :+ value)

    def view(model: Signal[Vector[Int]]) = div(model.map(_.mkString(",")))

  override def spec = suite("ManagedSubscriptionsSpec")(
    test("start rejects a duplicate active key without starting the replacement") {
      ZIO.scoped {
        for
          first       <- Queue.unbounded[Message]
          second      <- Queue.unbounded[Message]
          firstStart  <- Promise.make[Nothing, Unit]
          interrupted <- Promise.make[Nothing, Unit]
          secondStart <- Promise.make[Nothing, Unit]
          connection  <- startConnection
          key          = SubscriptionKey("duplicate")
          firstStream  = (ZStream.fromZIO(firstStart.succeed(()).unit) *> ZStream.fromQueue(first))
                           .ensuring(interrupted.succeed(()).unit)
          secondStream = ZStream.fromZIO(secondStart.succeed(()).unit) *> ZStream.fromQueue(second)
          _           <- connection.submitInfo(Message.Start(key, SubscriptionDelivery.Lossless, firstStream))
          _           <- firstStart.await
          duplicate   <- connection.submitInfo(Message.Start(key, SubscriptionDelivery.Lossless, secondStream)).either
          _           <- interrupted.await
          wasStarted  <- secondStart.isDone
        yield assertTrue(
          duplicate.left.exists(_.getMessage.contains("subscription 'duplicate' is already active")),
          !wasStarted
        )
      }
    },
    test("replace interrupts the prior stream and suppresses its stale values") {
      ZIO.scoped {
        for
          oldQueue     <- Queue.unbounded[Message]
          newQueue     <- Queue.unbounded[Message]
          oldStarted   <- Promise.make[Nothing, Unit]
          interrupted  <- Promise.make[Nothing, Unit]
          replacementOrder <- Promise.make[Nothing, Boolean]
          connection   <- startConnection
          key           = SubscriptionKey("replace")
          oldStream     = (ZStream.fromZIO(oldStarted.succeed(()).unit) *> ZStream.fromQueue(oldQueue))
                            .ensuring(interrupted.succeed(()).unit)
          _            <- connection.submitInfo(Message.Start(key, SubscriptionDelivery.Lossless, oldStream))
          _            <- oldStarted.await
          replacementStream = ZStream.fromZIO(
                                interrupted.isDone.flatMap(replacementOrder.succeed)
                              ) *> ZStream.fromQueue(newQueue)
          _            <- connection.submitInfo(
                            Message.Replace(key, SubscriptionDelivery.Lossless, replacementStream)
                          )
          _            <- interrupted.await
          ordered      <- replacementOrder.await
          _            <- oldQueue.offer(Message.Value(1))
          _            <- newQueue.offer(Message.Value(2))
          model        <- awaitModel(connection)(_.nonEmpty)
          _            <- ZIO.yieldNow
          finalModel   <- connection.inspectModel
        yield assertTrue(ordered, model == Vector(2), finalModel == Vector(2))
      }
    },
    test("cancel is a no-op when absent and stops an active subscription") {
      ZIO.scoped {
        for
          queue        <- Queue.unbounded[Message]
          interrupted  <- Promise.make[Nothing, Unit]
          connection   <- startConnection
          key           = SubscriptionKey("cancel")
          _            <- connection.submitInfo(Message.Cancel(key))
          afterAbsent  <- connection.inspectModel
          stream        = ZStream.fromQueue(queue).ensuring(interrupted.succeed(()).unit)
          _            <- connection.submitInfo(Message.Start(key, SubscriptionDelivery.Lossless, stream))
          _            <- queue.offer(Message.Value(1))
          _            <- awaitModel(connection)(_.nonEmpty)
          _            <- connection.submitInfo(Message.Cancel(key))
          _            <- interrupted.await
          _            <- queue.offer(Message.Value(2))
          _            <- ZIO.yieldNow
          finalModel   <- connection.inspectModel
        yield assertTrue(afterAbsent.isEmpty, finalModel == Vector(1))
      }
    },
    test("Lossless preserves every value in FIFO order with a blocked handler and small mailbox") {
      ZIO.scoped {
        for
          gate        <- Promise.make[Nothing, Unit]
          entered     <- Promise.make[Nothing, Unit]
          release     <- Promise.make[Nothing, Unit]
          emitted     <- Ref.make(0)
          connection  <- startConnection
          stream       = ZStream.fromZIO(gate.await) *>
                           ZStream.fromIterable(1 to 8).mapZIO(value =>
                             emitted.update(_ + 1).as(Message.Value(value))
                           )
          _           <- connection.submitInfo(Message.Start(SubscriptionKey("lossless"), SubscriptionDelivery.Lossless, stream))
          blocked     <- connection.submitInfo(Message.Block(entered, release)).fork
          _           <- entered.await *> gate.succeed(())
          _           <- emitted.get.repeatUntil(_ >= 2)
          observed    <- emitted.get
          _           <- release.succeed(()) *> blocked.join
          model       <- awaitModel(connection)(_.size == 8)
        yield assertTrue(observed < 8, model == (1 to 8).toVector)
      }
    },
    test("Latest conflates pending values and delivers the newest while the handler is blocked") {
      ZIO.scoped {
        for
          gate        <- Promise.make[Nothing, Unit]
          entered     <- Promise.make[Nothing, Unit]
          release     <- Promise.make[Nothing, Unit]
          produced    <- Promise.make[Nothing, Unit]
          connection  <- startConnection
          stream       = (ZStream.fromZIO(gate.await) *>
                           ZStream.fromIterable(1 to 8).map(Message.Value.apply))
                           .ensuring(produced.succeed(()).unit)
          _           <- connection.submitInfo(Message.Start(SubscriptionKey("latest"), SubscriptionDelivery.Latest, stream))
          blocked     <- connection.submitInfo(Message.Block(entered, release)).fork
          _           <- entered.await *> gate.succeed(()) *> produced.await
          _           <- release.succeed(()) *> blocked.join
          model       <- awaitModel(connection)(_.lastOption.contains(8))
        yield assertTrue(model == Vector(1, 8))
      }
    },
    test("finite completion unregisters its generation so the same key can start again") {
      ZIO.scoped {
        for
          outputCount <- Ref.make(0)
          completed   <- Promise.make[Nothing, Unit]
          connection  <- RootConnection.start(
                           config,
                           metadata,
                           Fixture(),
                           _ =>
                             outputCount.updateAndGet(_ + 1).flatMap(count =>
                               completed.succeed(()).unit.when(count == 4).unit
                             )
                         )
          key          = SubscriptionKey("finite")
          _           <- connection.submitInfo(Message.Start(key, SubscriptionDelivery.Lossless, ZStream.succeed(Message.Value(1))))
          _           <- completed.await
          firstModel  <- connection.inspectModel
          restarted  <- connection.submitInfo(Message.Start(key, SubscriptionDelivery.Lossless, ZStream.succeed(Message.Value(2)))).either
          model       <- awaitModel(connection)(_.size == 2)
        yield assertTrue(firstModel == Vector(1), restarted.isRight, model == Vector(1, 2))
      }
    },
    test("a lossless stream defect stops and unregisters only that subscription") {
      ZIO.scoped {
        for
          started    <- Promise.make[Nothing, Unit]
          stopped    <- Promise.make[Nothing, Unit]
          outputCount <- Ref.make(0)
          retired     <- Promise.make[Nothing, Unit]
          events      <- Ref.make(Vector.empty[LifecycleEvent])
          observer = RuntimeObserver.withLifecycleObserver(
                       LifecycleObserver.fromFunction(event => events.update(_ :+ event))
                     )
          connection  <- RootConnection.start(
                           config,
                           metadata,
                           Fixture(),
                           _ =>
                             outputCount.updateAndGet(_ + 1).flatMap(count =>
                               retired.succeed(()).unit.when(count == 3).unit
                             ),
                           observer = observer
                         )
          key         = SubscriptionKey("defect")
          broken      = (ZStream.fromZIO(started.succeed(()).unit) *> ZStream.dieMessage("broken"))
                          .ensuring(stopped.succeed(()).unit)
          _         <- connection.submitInfo(Message.Start(key, SubscriptionDelivery.Lossless, broken))
          _         <- started.await *> stopped.await *> retired.await
          restarted <- connection
                         .submitInfo(
                           Message.Start(
                             key,
                             SubscriptionDelivery.Lossless,
                             ZStream.succeed(Message.Value(3))
                           )
                          ).either
          model  <- awaitModel(connection)(_.contains(3))
          values <- events.get
          failures = values.collect { case event: LifecycleEvent.SubscriptionFailed => event }
        yield assertTrue(
          restarted.isRight,
          model == Vector(3),
          failures.size == 1,
          failures.head.delivery == SubscriptionDelivery.Lossless,
          failures.head.error.failure == LifecycleFailure.SubscriptionDefect
        )
      }
    }
  ) @@ TestAspect.timeout(10.seconds)

  private def startConnection =
    RootConnection.start(config, metadata, Fixture(), _ => ZIO.unit)

  private def awaitModel(
    connection: RootConnection[Message, Vector[Int]]
  )(predicate: Vector[Int] => Boolean): IO[ConnectionError, Vector[Int]] =
    def inspectWhenAvailable: IO[ConnectionError, Vector[Int]] =
      connection.inspectModel.catchSome {
        case ConnectionError.KernelRejected(SessionRejection.MailboxSaturated(_)) =>
          ZIO.yieldNow *> inspectWhenAvailable
      }
    (ZIO.yieldNow *> inspectWhenAvailable).repeatUntil(predicate)
end ManagedSubscriptionsSpec
