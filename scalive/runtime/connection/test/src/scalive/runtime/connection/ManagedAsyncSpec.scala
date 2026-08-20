package scalive.runtime.connection

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.*

object ManagedAsyncSpec extends ZIOSpecDefault:
  private val config = ConnectionConfig.make(4, 4, 4, 4, 4).toOption.get
  private val metadata = RootConnectionMetadata(staticChanged = true, connectParams = Map.empty)
  private val lifecycleEvent = ServerToBrowserEvent[String]("managed-async")

  private enum Message:
    case Start(key: AsyncKey[Int], task: Task[Int], label: String)
    case StartTwice(key: AsyncKey[Int], first: Task[Int], second: Task[Int])
    case StartThenCancel(key: AsyncKey[Int], task: Task[Int])
    case Cancel(key: AsyncKey[Int], reason: Option[String])
    case Unrelated
    case Mapped(label: String, status: String)

  private final class Fixture extends LiveView[Message, Vector[String]]:
    def mount(ctx: MountContext): LiveIO[Vector[String]] =
      ctx.hooks.async
        .attach("managed-async") { (model, event, hookContext) =>
          val observed = s"hook:${status(event.result)}"
          hookContext.client.push(lifecycleEvent, observed).as(LiveHookResult.cont(model :+ observed))
        }.as(Vector.empty)

    def handleMessage(
      model: Vector[String],
      ctx: MessageContext
    ): Message => LiveIO[Vector[String]] =
      case Message.Start(key, task, label) =>
        val marker = s"start:$label"
        ctx.async
          .start(key)(task)(result => Message.Mapped(label, status(result))) *>
          ctx.client.push(lifecycleEvent, marker).as(model :+ marker)
      case Message.StartTwice(key, first, second) =>
        ctx.async.start(key)(first)(result => Message.Mapped("first", status(result))) *>
          ctx.async.start(key)(second)(result => Message.Mapped("second", status(result))).as(model)
      case Message.StartThenCancel(key, task) =>
        ctx.async.start(key)(task)(result => Message.Mapped("cancelled", status(result))) *>
          ctx.async.cancel(key, Some("same-turn")).as(model)
      case Message.Cancel(key, reason) =>
        val marker = s"cancel:${reason.getOrElse("")}"
        ctx.async.cancel(key, reason) *>
          ctx.client.push(lifecycleEvent, marker).as(model :+ marker)
      case Message.Unrelated =>
        ctx.client.push(lifecycleEvent, "unrelated").as(model :+ "unrelated")
      case Message.Mapped(label, observedStatus) =>
        val marker = s"mapped:$label:$observedStatus"
        ctx.client.push(lifecycleEvent, marker).as(model :+ marker)

    def view(model: Signal[Vector[String]]) = div(model.map(_.mkString("|")))

  override def spec = suite("ManagedAsyncSpec")(
    test("an immediately completing task publishes its starting turn before its completion") {
      ZIO.scoped {
        for
          outputs    <- Queue.bounded[ConnectionOutput](4)
          connection <- RootConnection.start(config, metadata, Fixture(), outputs.offer(_).unit)
          _          <- outputs.take
          key         = AsyncKey[Int]("immediate")
          _          <- connection.submitInfo(Message.Start(key, ZIO.succeed(1), "immediate"))
          starting   <- outputs.take
          completion <- outputs.take
          model      <- connection.inspectModel
        yield assertTrue(
          markers(starting) == Vector("start:immediate"),
          markers(completion) == Vector("hook:succeeded", "mapped:immediate:succeeded"),
          model == Vector("start:immediate", "hook:succeeded", "mapped:immediate:succeeded")
        )
      }
    },
    test("success and failure run the matching hook before delivering the mapped message") {
      ZIO.scoped {
        for
          outputs    <- Queue.bounded[ConnectionOutput](4)
          connection <- RootConnection.start(config, metadata, Fixture(), outputs.offer(_).unit)
          _          <- outputs.take
          _ <- connection.submitInfo(
                 Message.Start(AsyncKey[Int]("success"), ZIO.succeed(1), "success")
               )
          successStart      <- outputs.take
          successCompletion <- outputs.take
          _ <- connection.submitInfo(
                 Message.Start(AsyncKey[Int]("failure"), ZIO.fail(Exception("boom")), "failure")
               )
          failureStart      <- outputs.take
          failureCompletion <- outputs.take
        yield assertTrue(
          markers(successStart) == Vector("start:success"),
          markers(successCompletion) == Vector("hook:succeeded", "mapped:success:succeeded"),
          markers(failureStart) == Vector("start:failure"),
          markers(failureCompletion) == Vector("hook:failed", "mapped:failure:failed")
        )
      }
    },
    test("same-key replacement suppresses the stale completion") {
      ZIO.scoped {
        for
          first      <- Promise.make[Nothing, Int]
          second     <- Promise.make[Nothing, Int]
          outputs    <- Queue.bounded[ConnectionOutput](4)
          connection <- RootConnection.start(config, metadata, Fixture(), outputs.offer(_).unit)
          _          <- outputs.take
          key         = AsyncKey[Int]("replace")
          _          <- connection.submitInfo(Message.Start(key, first.await, "first"))
          _          <- outputs.take
          _          <- connection.submitInfo(Message.Start(key, second.await, "second"))
          replacement <- outputs.take
          _           <- first.succeed(1)
          _           <- second.succeed(2)
          completion  <- outputs.take
          extra       <- outputs.poll
          model       <- connection.inspectModel
        yield assertTrue(
          markers(replacement) == Vector("start:second"),
          markers(completion) == Vector("hook:succeeded", "mapped:second:succeeded"),
          extra.isEmpty,
          !model.exists(_.contains("first:succeeded"))
        )
      }
    },
    test("same-turn replacement activates only the final task") {
      ZIO.scoped {
        for
          firstStarted  <- Promise.make[Nothing, Unit]
          secondStarted <- Promise.make[Nothing, Unit]
          connection    <- RootConnection.start(config, metadata, Fixture(), _ => ZIO.unit)
          key            = AsyncKey[Int]("same-turn-replace")
          first          = (firstStarted.succeed(()).unit *> ZIO.never).as(1)
          second         = (secondStarted.succeed(()).unit *> ZIO.never).as(2)
          _             <- connection.submitInfo(Message.StartTwice(key, first, second))
          _             <- secondStarted.await
          staleStarted  <- firstStarted.isDone
        yield assertTrue(!staleStarted)
      }
    },
    test("same-turn cancellation never activates the discarded task") {
      ZIO.scoped {
        for
          started    <- Promise.make[Nothing, Unit]
          outputs    <- Queue.bounded[ConnectionOutput](4)
          connection <- RootConnection.start(config, metadata, Fixture(), outputs.offer(_).unit)
          _          <- outputs.take
          key         = AsyncKey[Int]("same-turn-cancel")
          task        = (started.succeed(()).unit *> ZIO.never).as(1)
          _          <- connection.submitInfo(Message.StartThenCancel(key, task))
          _          <- outputs.take
          _          <- outputs.take
          model      <- connection.inspectModel
          wasStarted <- started.isDone
        yield assertTrue(!wasStarted, model.contains("mapped:cancelled:cancelled"))
      }
    },
    test("replacement closes the old task before activating the new task") {
      ZIO.scoped {
        for
          oldStarted       <- Promise.make[Nothing, Unit]
          oldInterrupted   <- Promise.make[Nothing, Unit]
          replacementOrder <- Promise.make[Nothing, Boolean]
          connection       <- RootConnection.start(config, metadata, Fixture(), _ => ZIO.unit)
          key               = AsyncKey[Int]("ordered-replace")
          oldTask           = (oldStarted.succeed(()).unit *> ZIO.never)
                                .onInterrupt(oldInterrupted.succeed(()).unit).as(1)
          newTask = oldInterrupted.isDone.flatMap(replacementOrder.succeed).unit *> ZIO.never
          _      <- connection.submitInfo(Message.Start(key, oldTask, "old"))
          _      <- oldStarted.await
          _      <- connection.submitInfo(Message.Start(key, newTask.as(2), "new"))
          ordered <- replacementOrder.await
        yield assertTrue(ordered)
      }
    },
    test("explicit cancel delivers one mapped cancellation after the cancelling turn commits") {
      ZIO.scoped {
        for
          task        <- Promise.make[Nothing, Int]
          outputs     <- Queue.bounded[ConnectionOutput](4)
          connection  <- RootConnection.start(config, metadata, Fixture(), outputs.offer(_).unit)
          _           <- outputs.take
          key          = AsyncKey[Int]("cancel")
          _           <- connection.submitInfo(Message.Start(key, task.await, "cancelled-task"))
          _           <- outputs.take
          _           <- connection.submitInfo(Message.Cancel(key, Some("requested")))
          cancelling  <- outputs.take
          cancellation <- outputs.take
          extra       <- outputs.poll
        yield assertTrue(
          markers(cancelling) == Vector("cancel:requested"),
          markers(cancellation) == Vector(
            "hook:cancelled",
            "mapped:cancelled-task:cancelled"
          ),
          extra.isEmpty
        )
      }
    },
    test("an unrelated later turn does not retire an active task") {
      ZIO.scoped {
        for
          entered     <- Promise.make[Nothing, Unit]
          task        <- Promise.make[Nothing, Int]
          interrupted <- Ref.make(false)
          outputs     <- Queue.bounded[ConnectionOutput](4)
          connection  <- RootConnection.start(config, metadata, Fixture(), outputs.offer(_).unit)
          _           <- outputs.take
          key          = AsyncKey[Int]("retained")
          runningTask = (entered.succeed(()).unit *> task.await).onInterrupt(interrupted.set(true))
          _          <- connection.submitInfo(Message.Start(key, runningTask, "retained"))
          _          <- outputs.take
          _          <- entered.await
          _          <- connection.submitInfo(Message.Unrelated)
          unrelated  <- outputs.take
          wasStopped <- interrupted.get
          _          <- task.succeed(1)
          completion <- outputs.take
        yield assertTrue(
          markers(unrelated) == Vector("unrelated"),
          !wasStopped,
          markers(completion) == Vector("hook:succeeded", "mapped:retained:succeeded")
        )
      }
    },
    test("removing a component interrupts its task without delivery and reintroduction is fresh") {
      ZIO.scoped {
        for
          firstEntered     <- Promise.make[Nothing, Unit]
          secondEntered    <- Promise.make[Nothing, Unit]
          firstInterrupted <- Promise.make[Nothing, Unit]
          secondInterrupted <- Promise.make[Nothing, Unit]
          mounts           <- Ref.make(0)
          interruptions    <- Ref.make(0)
          deliveries       <- Ref.make(Vector.empty[String])
          entered           = Vector(firstEntered, secondEntered)
          interrupted       = Vector(firstInterrupted, secondInterrupted)
          definition = new LiveComponent[Unit, String, Unit]:
                         def mount(props: Unit, ctx: MountContext) =
                           mounts.getAndUpdate(_ + 1).flatMap { mountIndex =>
                             val task =
                               (entered(mountIndex).succeed(()).unit *> ZIO.never)
                                 .onInterrupt(
                                   interruptions.update(_ + 1) *>
                                     interrupted(mountIndex).succeed(()).unit
                                 ).as(1)
                             ctx.connection match
                               case Connection.Connected(connected) =>
                                 connected.async
                                   .start(AsyncKey[Int]("component-work"))(task)(result =>
                                     s"completion:${status(result)}"
                                   ).as(())
                               case Connection.Disconnected => ZIO.unit
                           }
                         def handleMessage(
                           props: Unit,
                           model: Unit,
                           ctx: MessageContext
                         ): String => LiveIO[Unit] = message => deliveries.update(_ :+ message)
                         def view(
                           props: Signal[Unit],
                           model: Signal[Unit],
                           self: ComponentRef[String]
                         ) = div()
          instance = component(definition, "replaceable-async-owner")
          root = new LiveView[Boolean, Boolean]:
                   def mount(ctx: MountContext) = ZIO.succeed(true)
                   def handleMessage(
                     model: Boolean,
                     ctx: MessageContext
                   ): Boolean => LiveIO[Boolean] = ZIO.succeed(_)
                   def view(model: Signal[Boolean]) =
                     div(model.when(div(instance.render(()))))
          outputs    <- Queue.bounded[ConnectionOutput](4)
          connection <- RootConnection.start(config, metadata, root, outputs.offer(_).unit)
          _          <- outputs.take
          firstId    <- connection.inspectComponentIds.map(_.head)
          _          <- firstEntered.await
          before     <- interruptions.get
          _          <- connection.submitInfo(false)
          _          <- outputs.take
          _          <- firstInterrupted.await
          afterRemoval <- interruptions.get
          removalDelivery <- deliveries.get
          removalOutput   <- outputs.poll
          _               <- connection.submitInfo(true)
          _               <- outputs.take
          secondId        <- connection.inspectComponentIds.map(_.head)
          _               <- secondEntered.await
          mountCount      <- mounts.get
          finalDeliveries <- deliveries.get
          finalInterruptions <- interruptions.get
        yield assertTrue(
          before == 0,
          afterRemoval == 1,
          removalDelivery.isEmpty,
          removalOutput.isEmpty,
          firstId != secondId,
          mountCount == 2,
          finalDeliveries.isEmpty,
          finalInterruptions == 1
        )
      }
    },
    test("closing the session interrupts an active root task exactly once") {
      ZIO.scoped {
        for
          entered       <- Promise.make[Nothing, Unit]
          interrupted   <- Promise.make[Nothing, Unit]
          interruptions <- Ref.make(0)
          outputs       <- Queue.bounded[ConnectionOutput](4)
          connection    <- RootConnection.start(config, metadata, Fixture(), outputs.offer(_).unit)
          _             <- outputs.take
          task = (entered.succeed(()).unit *> ZIO.never)
                   .onInterrupt(interruptions.update(_ + 1) *> interrupted.succeed(()).unit)
                   .as(1)
          _     <- connection.submitInfo(Message.Start(AsyncKey[Int]("close"), task, "close"))
          _     <- outputs.take
          _     <- entered.await
          _     <- connection.close
          _     <- interrupted.await
          count <- interruptions.get
        yield assertTrue(count == 1)
      }
    }
  )

  private def status(result: LiveAsyncResult[?]): String = result match
    case LiveAsyncResult.Succeeded(_)  => "succeeded"
    case LiveAsyncResult.Failed(_)     => "failed"
    case LiveAsyncResult.Cancelled(_)  => "cancelled"

  private def markers(output: ConnectionOutput): Vector[String] =
    val effects = output match
      case ConnectionOutput.Joined(_, effects)                 => effects
      case ConnectionOutput.Reply(_, _, effects)               => effects
      case ConnectionOutput.Diff(_, effects)                    => effects
      case ConnectionOutput.JoinedNavigation(_, _, effects)    => effects
      case ConnectionOutput.ReplyNavigation(_, _, _, effects)  => effects
      case ConnectionOutput.DiffNavigation(_, _, effects)      => effects
      case ConnectionOutput.Rejected(_, _)                     => return Vector.empty
    effects.clientEvents.collect {
      case scalive.runtime.kernel.ClientEffect("managed-async", Json.Str(value)) => value
    }
end ManagedAsyncSpec
