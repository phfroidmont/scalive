package scalive.runtime.connection

import zio.*
import zio.stream.ZStream
import zio.test.*

import scalive.*
import scalive.render.EvaluatedNode
import scalive.runtime.contracts.{CommandId, ComponentInstanceId}
import scalive.runtime.kernel.SessionRejection

object ManagedComponentSubscriptionsSpec extends ZIOSpecDefault:
  private val config = ConnectionConfig.make(4, 4, 1, 4, 4).toOption.get
  private val metadata = RootConnectionMetadata(staticChanged = true, connectParams = Map.empty)

  private final case class Subscription(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery,
    stream: ZStream[Any, Nothing, ComponentMessage]
  )

  private final case class Props(replacement: Option[Subscription] = None)

  private enum ComponentMessage:
    case Start(subscription: Subscription)
    case Replace(subscription: Subscription)
    case Cancel(key: SubscriptionKey)
    case Block(entered: Promise[Nothing, Unit], release: Promise[Nothing, Unit])
    case Value(value: Int)
    case StartThenFail(subscription: Subscription)

  private final case class RootModel(show: Boolean, props: Props)

  private enum RootMessage:
    case Show(value: Boolean)
    case Props(value: ManagedComponentSubscriptionsSpec.Props)
    case Start(subscription: SubscriptionKey, stream: ZStream[Any, Nothing, RootMessage])
    case Value(value: Int)
    case ComponentOutput(value: Int)

  private final class RecordingComponent(
    mountSubscription: Option[Subscription],
    mounts: Ref[Int],
    updates: Ref[Int],
    received: Ref[Vector[Int]],
    order: Ref[Vector[String]],
    emitValues: Boolean = false
  ) extends LiveComponent.WithOutput[Props, ComponentMessage, Vector[Int], Int]:
    override val hooks = ComponentLiveHooks.empty[Props, ComponentMessage, Vector[Int]]
      .onEvent((_, model, _, _) => order.update(_ :+ "hook").as(LiveHookResult.cont(model)))

    def mount(props: Props, ctx: MountContext): Task[Vector[Int]] =
      mounts.update(_ + 1) *>
        ZIO.foreachDiscard(mountSubscription) { subscription =>
          ctx.connection match
            case Connection.Connected(connected) =>
              connected.subscriptions.start(subscription.key, subscription.delivery)(subscription.stream)
            case Connection.Disconnected => ZIO.unit
        }.as(Vector.empty)

    override def update(props: Props, model: Vector[Int], ctx: UpdateContext): Task[Vector[Int]] =
      updates.update(_ + 1) *>
        ZIO.foreachDiscard(props.replacement) { subscription =>
          ctx.connection match
            case Connection.Connected(connected) =>
              connected.subscriptions.replace(subscription.key, subscription.delivery)(subscription.stream)
            case Connection.Disconnected => ZIO.unit
        }.as(model)

    def handleMessage(
      props: Props,
      model: Vector[Int],
      ctx: MessageContext
    ): ComponentMessage => Task[Vector[Int]] =
      case ComponentMessage.Start(subscription) =>
        ctx.subscriptions.start(subscription.key, subscription.delivery)(subscription.stream).as(model)
      case ComponentMessage.Replace(subscription) =>
        ctx.subscriptions.replace(subscription.key, subscription.delivery)(subscription.stream).as(model)
      case ComponentMessage.Cancel(key) =>
        ctx.subscriptions.cancel(key).as(model)
      case ComponentMessage.Block(entered, release) =>
        entered.succeed(()).unit *> release.await.as(model)
      case ComponentMessage.Value(value) =>
        order.update(_ :+ "handler") *>
          received.update(_ :+ value) *>
          ctx.emit(value).when(emitValues).as(model :+ value)
      case ComponentMessage.StartThenFail(subscription) =>
        ctx.subscriptions.start(subscription.key, subscription.delivery)(subscription.stream) *>
          ZIO.fail(Exception("candidate failed"))

    def view(
      props: Signal[Props],
      model: Signal[Vector[Int]],
      self: ComponentRef[ComponentMessage]
    ) = div(model.map(_.mkString(",")))

  private final case class Running(
    connection: RootConnection[RootMessage, RootModel],
    component: LiveComponentOutputInstance[Props, ComponentMessage, Vector[Int], Int],
    parentOutput: Ref[Vector[Int]],
    rootReceived: Ref[Vector[Int]],
    mounts: Ref[Int],
    updates: Ref[Int],
    received: Ref[Vector[Int]],
    order: Ref[Vector[String]]
  )

  override def spec = suite("ManagedComponentSubscriptionsSpec")(
    test("connected mount starts a component-owned subscription and routes output to its parent") {
      ZIO.scoped {
        for
          queue <- Queue.unbounded[ComponentMessage]
          running <- start(
                       Some(
                         Subscription(
                           SubscriptionKey("mount"),
                           SubscriptionDelivery.Lossless,
                           ZStream.fromQueue(queue)
                         )
                       ),
                       emitValues = true
                     )
          id <- componentId(running.connection)
          _  <- queue.offer(ComponentMessage.Value(7))
          model  <- awaitComponent(running.connection, id)(_.contains(7))
          parent <- running.parentOutput.get.repeatUntil(_.contains(7))
          root   <- running.rootReceived.get
          order  <- running.order.get
        yield assertTrue(
          model == Vector(7),
          parent == Vector(7),
          root.isEmpty,
          order == Vector("hook", "handler", "parent")
        )
      }
    },
    test("connected update replaces the prior stream and suppresses queued old-run messages") {
      ZIO.scoped {
        for
          oldQueue       <- Queue.unbounded[ComponentMessage]
          newQueue       <- Queue.unbounded[ComponentMessage]
          oldStarted     <- Promise.make[Nothing, Unit]
          oldInterrupted <- Promise.make[Nothing, Unit]
          newStarted     <- Promise.make[Nothing, Unit]
          key             = SubscriptionKey("update-replace")
          oldStream = (ZStream.fromZIO(oldStarted.succeed(()).unit) *> ZStream.fromQueue(oldQueue))
                        .ensuring(oldInterrupted.succeed(()).unit)
          newStream = ZStream.fromZIO(newStarted.succeed(()).unit) *> ZStream.fromQueue(newQueue)
          running <- start(Some(Subscription(key, SubscriptionDelivery.Lossless, oldStream)))
          id      <- componentId(running.connection)
          _       <- oldStarted.await
          _ <- running.connection.submitInfo(
                 RootMessage.Props(
                   Props(Some(Subscription(key, SubscriptionDelivery.Lossless, newStream)))
                 )
               )
          _       <- oldInterrupted.await *> newStarted.await
          _       <- oldQueue.offer(ComponentMessage.Value(1))
          _       <- newQueue.offer(ComponentMessage.Value(2))
          model   <- awaitComponent(running.connection, id)(_.nonEmpty)
          updates <- running.updates.get
        yield assertTrue(model == Vector(2), updates == 2)
      }
    },
    test("component messages start, replace, and cancel subscriptions") {
      ZIO.scoped {
        for
          first       <- Queue.unbounded[ComponentMessage]
          second      <- Queue.unbounded[ComponentMessage]
          interrupted <- Promise.make[Nothing, Unit]
          cancelled   <- Promise.make[Nothing, Unit]
          running     <- start(None)
          id          <- componentId(running.connection)
          key          = SubscriptionKey("messages")
          oldStream    = ZStream.fromQueue(first).ensuring(interrupted.succeed(()).unit)
          _ <- send(running.connection, id, ComponentMessage.Start(
                 Subscription(key, SubscriptionDelivery.Lossless, oldStream)
               ))
          _ <- send(running.connection, id, ComponentMessage.Replace(
                 Subscription(
                   key,
                   SubscriptionDelivery.Lossless,
                   ZStream.fromQueue(second).ensuring(cancelled.succeed(()).unit)
                 )
               ))
          _     <- interrupted.await
          _     <- first.offer(ComponentMessage.Value(1))
          _     <- second.offer(ComponentMessage.Value(2))
          model <- awaitComponent(running.connection, id)(_.contains(2))
          _     <- send(running.connection, id, ComponentMessage.Cancel(key))
          _     <- cancelled.await
          _     <- second.offer(ComponentMessage.Value(3))
          finalModel <- running.connection.inspectComponentModel[Vector[Int]](id)
        yield assertTrue(model == Vector(2), finalModel.contains(Vector(2)))
      }
    },
    test("equal keys are isolated between sibling components and the root") {
      ZIO.scoped {
        for
          leftQueue  <- Queue.unbounded[ComponentMessage]
          rightQueue <- Queue.unbounded[ComponentMessage]
          rootQueue  <- Queue.unbounded[RootMessage]
          mounts     <- Ref.make(0)
          updates    <- Ref.make(0)
          leftSeen   <- Ref.make(Vector.empty[Int])
          rightSeen  <- Ref.make(Vector.empty[Int])
          parent     <- Ref.make(Vector.empty[Int])
          rootSeen   <- Ref.make(Vector.empty[Int])
          order      <- Ref.make(Vector.empty[String])
          key         = SubscriptionKey("shared")
          leftDef = RecordingComponent(
                      Some(Subscription(key, SubscriptionDelivery.Lossless, ZStream.fromQueue(leftQueue))),
                      mounts,
                      updates,
                      leftSeen,
                      order
                    )
          rightDef = RecordingComponent(
                       Some(Subscription(key, SubscriptionDelivery.Lossless, ZStream.fromQueue(rightQueue))),
                       mounts,
                       updates,
                       rightSeen,
                       order
                     )
          left  = component(leftDef, "left")
          right = component(rightDef, "right")
          view = rootView(Vector(left, right), parent, rootSeen, order)
          connection <- RootConnection.start(config, metadata, view, _ => ZIO.unit)
          ids        <- connection.inspectComponentIds.repeatUntil(_.size == 2)
          _ <- connection.submitInfo(RootMessage.Start(key, ZStream.fromQueue(rootQueue)))
          _ <- leftQueue.offer(ComponentMessage.Value(1))
          _ <- rightQueue.offer(ComponentMessage.Value(2))
          _ <- rootQueue.offer(RootMessage.Value(3))
          leftModel  <- awaitComponent(connection, ids(0))(_.contains(1))
          rightModel <- awaitComponent(connection, ids(1))(_.contains(2))
          roots      <- rootSeen.get.repeatUntil(_.contains(3))
        yield assertTrue(leftModel == Vector(1), rightModel == Vector(2), roots == Vector(3))
      }
    },
    test("a hidden component is revived with the same identity and model and reruns its stored stream") {
      ZIO.scoped {
        for
          oldQueue  <- Queue.unbounded[ComponentMessage]
          freshQueue <- Queue.unbounded[ComponentMessage]
          acquires  <- Ref.make(0)
          releases  <- Ref.make(0)
          stream = ZStream.unwrap(
                     acquires.updateAndGet(_ + 1).map { run =>
                       val queue = if run == 1 then oldQueue else freshQueue
                       ZStream.fromQueue(queue).ensuring(releases.update(_ + 1))
                     }
                   )
          running <- start(Some(Subscription(
                       SubscriptionKey("revive"),
                       SubscriptionDelivery.Lossless,
                       stream
                     )))
          firstId <- componentId(running.connection)
          _       <- oldQueue.offer(ComponentMessage.Value(1))
          _       <- awaitComponent(running.connection, firstId)(_.contains(1))
          _       <- running.connection.submitInfo(RootMessage.Show(false))
          _       <- releases.get.repeatUntil(_ == 1)
          _       <- oldQueue.offer(ComponentMessage.Value(99))
          _       <- running.connection.submitInfo(RootMessage.Show(true))
          _       <- acquires.get.repeatUntil(_ == 2)
          secondId <- componentId(running.connection)
          _        <- freshQueue.offer(ComponentMessage.Value(2))
          model    <- awaitComponent(running.connection, secondId)(_.contains(2))
          mounts   <- running.mounts.get
          updates  <- running.updates.get
          _        <- running.connection.close
          finalized <- releases.get.repeatUntil(_ == 2)
        yield assertTrue(
          firstId == secondId,
          model == Vector(1, 2),
          mounts == 1,
          updates == 1,
          finalized == 2
        )
      }
    },
    test("cancelled, completed, and defective subscriptions stay off after revival") {
      ZIO.scoped {
        for
          cancelledStarts <- Ref.make(0)
          finiteStarts    <- Ref.make(0)
          defectStarts    <- Ref.make(0)
          cancelledStream = ZStream.fromZIO(cancelledStarts.update(_ + 1)) *> ZStream.never
          finiteStream = ZStream.fromZIO(finiteStarts.update(_ + 1)) *>
                           ZStream.succeed(ComponentMessage.Value(1))
          defectDone <- Promise.make[Nothing, Unit]
          defectStream = (ZStream.fromZIO(defectStarts.update(_ + 1)) *> ZStream.dieMessage("broken"))
                           .ensuring(defectDone.succeed(()).unit)
          cancelled <- start(Some(Subscription(
                         SubscriptionKey("cancelled"),
                         SubscriptionDelivery.Lossless,
                         cancelledStream
                       )), id = "cancelled")
          finite <- start(Some(Subscription(
                      SubscriptionKey("finite"),
                      SubscriptionDelivery.Lossless,
                      finiteStream
                    )), id = "finite")
          defective <- start(Some(Subscription(
                         SubscriptionKey("defective"),
                         SubscriptionDelivery.Lossless,
                         defectStream
                       )), id = "defective")
          cancelledId <- componentId(cancelled.connection)
          finiteId    <- componentId(finite.connection)
          _           <- cancelledStarts.get.repeatUntil(_ == 1)
          _           <- awaitComponent(finite.connection, finiteId)(_.contains(1))
          _           <- defectDone.await
          _ <- send(cancelled.connection, cancelledId, ComponentMessage.Cancel(SubscriptionKey("cancelled")))
          all = Vector(cancelled, finite, defective)
          _ <- ZIO.foreachDiscard(all)(_.connection.submitInfo(RootMessage.Show(false)))
          _ <- ZIO.foreachDiscard(all)(running => awaitRootShow(running.connection, false))
          _ <- ZIO.foreachDiscard(all)(_.connection.submitInfo(RootMessage.Show(true)))
          _ <- ZIO.foreachDiscard(all)(running => awaitRootShow(running.connection, true))
          cancelledCount <- cancelledStarts.get
          finiteCount    <- finiteStarts.get
          defectCount    <- defectStarts.get
        yield assertTrue(cancelledCount == 1, finiteCount == 1, defectCount == 1)
      }
    },
    test("confirmed destruction creates a fresh component instance") {
      ZIO.scoped {
        for
          started      <- Promise.make[Nothing, Unit]
          interrupted  <- Promise.make[Nothing, Unit]
          stream = (ZStream.fromZIO(started.succeed(()).unit) *> ZStream.never)
                     .ensuring(interrupted.succeed(()).unit)
          running <- start(Some(Subscription(
                       SubscriptionKey("destroy"),
                       SubscriptionDelivery.Lossless,
                       stream
                     )))
          firstId <- componentId(running.connection)
          token   <- componentToken(running.connection)
          _       <- started.await
          _       <- running.connection.submitInfo(RootMessage.Show(false))
          _       <- interrupted.await
          _       <- running.connection.destroyComponents(Vector(token))
          _       <- running.connection.componentWasClosed(firstId).repeatUntil(identity)
          _       <- running.connection.submitInfo(RootMessage.Show(true))
          secondId <- componentId(running.connection)
          model    <- running.connection.inspectComponentModel[Vector[Int]](secondId)
          mounts   <- running.mounts.get
        yield assertTrue(firstId != secondId, model.contains(Vector.empty), mounts == 2)
      }
    },
    test("component Lossless preserves FIFO while Latest coalesces a blocked handler") {
      ZIO.scoped {
        for
          lossless <- deliveryResult(SubscriptionDelivery.Lossless)
          latest   <- deliveryResult(SubscriptionDelivery.Latest)
        yield assertTrue(lossless == (1 to 8).toVector, latest == Vector(1, 8))
      }
    },
    test("a failed candidate handler does not acquire its journaled subscription") {
      ZIO.scoped {
        for
          acquired <- Ref.make(0)
          running  <- start(None)
          id       <- componentId(running.connection)
          stream    = ZStream.fromZIO(acquired.update(_ + 1)) *> ZStream.never
          command   = CommandId.fresh().toOption.get
          failed <- running.connection.submitComponentMessage(
                      command,
                      id,
                      ComponentMessage.StartThenFail(
                        Subscription(
                          SubscriptionKey("rollback"),
                          SubscriptionDelivery.Lossless,
                          stream
                        )
                      )
                    ).either
          _     <- running.connection.awaitClosed
          count <- acquired.get
        yield assertTrue(failed.isLeft, count == 0)
      }
    }
  ) @@ TestAspect.timeout(10.seconds)

  private def start(
    mountSubscription: Option[Subscription],
    id: String = "subject",
    emitValues: Boolean = false
  ): ZIO[Scope, Nothing, Running] =
    for
      mounts      <- Ref.make(0)
      updates     <- Ref.make(0)
      received    <- Ref.make(Vector.empty[Int])
      order       <- Ref.make(Vector.empty[String])
      parent      <- Ref.make(Vector.empty[Int])
      rootReceived <- Ref.make(Vector.empty[Int])
      definition   = RecordingComponent(mountSubscription, mounts, updates, received, order, emitValues)
      instance     = component(definition, id)
      connection <- RootConnection.start(
                      config,
                      metadata,
                      rootView(Vector(instance), parent, rootReceived, order),
                      _ => ZIO.unit
                    ).orDie
    yield Running(connection, instance, parent, rootReceived, mounts, updates, received, order)

  private def rootView(
    components: Vector[LiveComponentOutputInstance[Props, ComponentMessage, Vector[Int], Int]],
    parentOutput: Ref[Vector[Int]],
    rootReceived: Ref[Vector[Int]],
    order: Ref[Vector[String]]
  ) = new LiveView[RootMessage, RootModel]:
    def mount(ctx: MountContext): Task[RootModel] = ZIO.succeed(RootModel(true, Props()))

    def handleMessage(model: RootModel, ctx: MessageContext): RootMessage => Task[RootModel] =
      case RootMessage.Show(value) => ZIO.succeed(model.copy(show = value))
      case RootMessage.Props(value) => ZIO.succeed(model.copy(props = value))
      case RootMessage.Start(key, stream) =>
        ctx.subscriptions.start(key, SubscriptionDelivery.Lossless)(stream).as(model)
      case RootMessage.Value(value) => rootReceived.update(_ :+ value).as(model)
      case RootMessage.ComponentOutput(value) =>
        order.update(_ :+ "parent") *> parentOutput.update(_ :+ value).as(model)

    def view(model: Signal[RootModel]) =
      div(
        model.map(_.show).when(
          div(components.map(_.render(model.map(_.props), RootMessage.ComponentOutput.apply))*)
        )
      )

  private def send(
    connection: RootConnection[RootMessage, RootModel],
    id: ComponentInstanceId,
    message: ComponentMessage
  ) = connection.submitComponentMessage(CommandId.fresh().toOption.get, id, message)

  private def componentId(
    connection: RootConnection[RootMessage, RootModel]
  ): IO[ConnectionError, ComponentInstanceId] =
    connection.inspectComponentIds.repeatUntil(_.nonEmpty).map(_.head)

  private def awaitRootShow(
    connection: RootConnection[RootMessage, RootModel],
    expected: Boolean
  ): IO[ConnectionError, RootModel] =
    def inspect: IO[ConnectionError, RootModel] =
      connection.inspectModel.catchSome {
        case ConnectionError.KernelRejected(SessionRejection.MailboxSaturated(_)) =>
          ZIO.yieldNow *> inspect
      }
    (ZIO.yieldNow *> inspect).repeatUntil(_.show == expected)

  private def awaitComponent(
    connection: RootConnection[RootMessage, RootModel],
    id: ComponentInstanceId
  )(predicate: Vector[Int] => Boolean): IO[ConnectionError, Vector[Int]] =
    def inspect: IO[ConnectionError, Vector[Int]] =
      connection.inspectComponentModel[Vector[Int]](id).flatMap {
        case Some(model) => ZIO.succeed(model)
        case None        => ZIO.yieldNow *> inspect
      }.catchSome {
        case ConnectionError.KernelRejected(SessionRejection.MailboxSaturated(_)) =>
          ZIO.yieldNow *> inspect
      }
    (ZIO.yieldNow *> inspect).repeatUntil(predicate)

  private def componentToken(
    connection: RootConnection[RootMessage, RootModel]
  ): IO[ConnectionError, Object] =
    connection.inspectTree.map(tree => tokenIn(tree.root).get)

  private def tokenIn(node: EvaluatedNode): Option[Object] = node match
    case value: EvaluatedNode.Component => value.resolution.map(_.instanceToken)
    case value: EvaluatedNode.Element   => value.children.iterator.flatMap(tokenIn).nextOption()
    case value: EvaluatedNode.Choice    => value.child.flatMap(tokenIn)
    case value: EvaluatedNode.Flash     => value.child.flatMap(tokenIn)
    case value: EvaluatedNode.Keyed     =>
      value.rows.iterator.flatMap(row => tokenIn(row.child)).nextOption()
    case _ => None

  private def deliveryResult(delivery: SubscriptionDelivery): ZIO[Scope, Nothing, Vector[Int]] =
    (for
      gate     <- Promise.make[Nothing, Unit]
      entered  <- Promise.make[Nothing, Unit]
      release  <- Promise.make[Nothing, Unit]
      produced <- Promise.make[Nothing, Unit]
      emitted  <- Ref.make(0)
      running  <- start(None, s"delivery-$delivery")
      id       <- componentId(running.connection)
      stream = (ZStream.fromZIO(gate.await) *>
                 ZStream.fromIterable(1 to 8).mapZIO(value =>
                   emitted.update(_ + 1).as(ComponentMessage.Value(value))
                 ))
                 .ensuring(produced.succeed(()).unit)
      _ <- send(running.connection, id, ComponentMessage.Start(
             Subscription(SubscriptionKey("delivery"), delivery, stream)
           ))
      blocked <- send(running.connection, id, ComponentMessage.Block(entered, release)).fork
      _       <- entered.await *> gate.succeed(())
      _ <- if delivery == SubscriptionDelivery.Lossless then emitted.get.repeatUntil(_ >= 2).unit
           else produced.await
      _       <- release.succeed(()) *> blocked.join
      expected = if delivery == SubscriptionDelivery.Lossless then 8 else 2
      model   <- awaitComponent(running.connection, id)(_.size == expected)
    yield model).orDie
end ManagedComponentSubscriptionsSpec
