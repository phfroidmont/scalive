package scalive.runtime.kernel

import zio.*
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*

object SessionKernelSpec extends ZIOSpecDefault:
  private val config = SessionConfig.make(4, 8).toOption.get

  private final class ProbeReservation(
    publishEffect: OutboundBatch[SessionOutput] => UIO[Unit],
    releaseEffect: UIO[Unit] = ZIO.unit)
      extends OutboundReservation[SessionOutput]:
    override def publish(batch: OutboundBatch[SessionOutput]): UIO[Unit] = publishEffect(batch)
    override def release: UIO[Unit] = releaseEffect

  private final class ProbeOutbound(
    reserveEffect: ZIO[Any, OutboundReservationError, OutboundReservation[SessionOutput]])
      extends OutboundReservations[SessionOutput]:
    override def reserve = reserveEffect
    override def take = ZIO.fail(OutboundReservationError.Shutdown)
    override def shutdown = ZIO.unit

  private def recordingOutbound
    : UIO[(OutboundReservations[SessionOutput], Ref[Vector[OutboundBatch[SessionOutput]]])] =
    Ref.make(Vector.empty[OutboundBatch[SessionOutput]]).map { batches =>
      val outbound = ProbeOutbound(
        ZIO.succeed(ProbeReservation(batch => batches.update(_ :+ batch)))
      )
      outbound -> batches
    }

  private def textProgram: IO[RenderError, RenderProgram[Int, Int]] =
    ZIO.fromEither(RenderProgram.compile[Int, Int](model => div(model.map(_.toString))))

  private def standardLogic(
    bootstrapModel: Int = 0
  ): SessionLogic[Int, Int] =
    SessionLogic(
      bootstrap = ZIO.succeed(TurnDraft(bootstrapModel)),
      handle = (model, message) => ZIO.succeed(TurnDraft(model + message))
    )

  override def spec = suite("SessionKernelSpec")(
    test("bootstrap is uncorrelated and a typed message publishes its exact command id") {
      ZIO.scoped {
        for
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          kernel              <- SessionKernel.start(config, standardLogic(1), program, outbound)
          boot                <- kernel.inspect
          commandId = CommandId.fresh().toOption.get
          result              <- kernel.submit(commandId, SessionCommand.Message(kernel.epoch, 2))
          committed           <- kernel.inspect
          published           <- batches.get
          bootOutput = published.head.items.head
          bootDelta = bootOutput.delta
          slot = bootDelta match
            case RenderDelta.Replace(tree) =>
              tree.root.children.head.asInstanceOf[EvaluatedNode.Text].slot.get
            case _ => throw AssertionError("bootstrap did not publish a replacement")
          expected = RenderDelta.Update(
            committed.render.tree.revision,
            Vector(RenderChange.Text(slot, "3", raw = false))
          )
        yield assertTrue(
          bootOutput.command.isEmpty,
          boot.model == 1,
          committed.model == 3,
          committed.revision == result.revision,
          result.command == commandId,
          result.delta == expected,
          published.map(_.items) == Vector(
            Vector(SessionOutput(None, bootDelta)),
            Vector(SessionOutput(Some(commandId), expected))
          )
        )
      }
    },
    test("client events resolve from committed bindings and rejections preserve state") {
      ZIO.scoped {
        val compiled = RenderProgram.compile[Int, Int] { model =>
          button(
            on.click((params: Map[String, String]) => params("amount").toInt),
            model.map(_.toString)
          )
        }
        for
          program             <- ZIO.fromEither(compiled)
          (outbound, batches) <- recordingOutbound
          kernel              <- SessionKernel.start(config, standardLogic(10), program, outbound)
          before              <- kernel.inspect
          binding = before.render.bindings.ids.head
          stale <- kernel
                     .submit(
                       SessionCommand.ClientEvent(
                         Epoch(kernel.epoch.value + 1),
                         binding,
                         BindingPayload.Params(Map("amount" -> "1"))
                       )
                     ).either
          unknownId = BindingId.fromEncoded("unknown")
          unknown <- kernel
                       .submit(
                         SessionCommand.ClientEvent(
                           kernel.epoch,
                           unknownId,
                           BindingPayload.Params(Map.empty)
                         )
                       ).either
          malformed <- kernel
                         .submit(
                           SessionCommand.ClientEvent(
                             kernel.epoch,
                             binding,
                             BindingPayload.Params(Map("amount" -> "not-an-int"))
                           )
                         ).either
          afterRejections <- kernel.inspect
          accepted <- kernel.submit(
                        SessionCommand.ClientEvent(
                          kernel.epoch,
                          binding,
                          BindingPayload.Params(Map("amount" -> "5"))
                        )
                      )
          after <- kernel.inspect
          output <- batches.get
        yield assertTrue(
          stale == Left(SessionRejection.InvalidEpoch(kernel.epoch, Epoch(kernel.epoch.value + 1))),
          unknown == Left(SessionRejection.UnknownBinding(unknownId)),
          malformed.left.exists {
            case SessionRejection.BindingFailed(`binding`, _: NumberFormatException) => true
            case _                                                                  => false
          },
          afterRejections.model == before.model,
          afterRejections.revision == before.revision,
          after.model == 15,
          accepted.delta != RenderDelta.Empty,
          output.size == 2,
          output.last.items.map(_.command) == Vector(Some(accepted.command))
        )
      }
    },
    test("new resources activate before publication and old resources retire afterward") {
      ZIO.scoped {
        for
          resources    <- Ref.make(Vector.empty[PreparedResource])
          finalized    <- Ref.make(0)
          observations <- Ref.make(Vector.empty[(PreparedResource.State, Option[PreparedResource.State], Int)])
          program      <- textProgram
          outbound = ProbeOutbound(
            ZIO.succeed(
              ProbeReservation { _ =>
                for
                  prepared <- resources.get
                  newest   <- prepared.last.state
                  previous <- ZIO.foreach(prepared.dropRight(1).lastOption)(_.state)
                  closes   <- finalized.get
                  _        <- observations.update(_ :+ (newest, previous, closes))
                yield ()
              }
            )
          )
          logic = standardLogic().copy(prepare = (_, registry) =>
            registry.prepare(finalized.update(_ + 1)).flatMap { resource =>
              resources.update(_ :+ resource)
            }
          )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          seen   <- observations.get
          all    <- resources.get
          old    <- all.head.state
          newest <- all.last.state
        yield assertTrue(
          seen == Vector(
            (PreparedResource.State.Active, None, 0),
            (PreparedResource.State.Active, Some(PreparedResource.State.Stale), 0)
          ),
          old == PreparedResource.State.Closed,
          newest == PreparedResource.State.Active
        )
      }
    },
    test("pre-commit stage failures publish no update and close candidate resources") {
      enum FailureCase:
        case Render, Reservation, AfterRender, Validation

      ZIO.foreach(FailureCase.values.toVector) { failureCase =>
        ZIO.scoped {
          for
            candidateResource <- Ref.make(Option.empty[PreparedResource])
            releases          <- Ref.make(0)
            publications      <- Ref.make(0)
            program <- ZIO.fromEither(
                         RenderProgram.compile[Int, Int](model =>
                           div(model.map(value => if value < 0 then throw IllegalStateException("render") else value.toString))
                         )
                       )
            reserveCount <- Ref.make(0)
            outbound = ProbeOutbound(
              reserveCount.getAndUpdate(_ + 1).flatMap { index =>
                if failureCase == FailureCase.Reservation && index == 1 then
                  ZIO.fail(OutboundReservationError.Saturated(1))
                else
                  ZIO.succeed(
                    ProbeReservation(
                      _ => publications.update(_ + 1),
                      releases.update(_ + 1)
                    )
                  )
              }
            )
            logic = SessionLogic[Int, Int](
              bootstrap = ZIO.succeed(TurnDraft(0)),
              handle = (_, _) =>
                ZIO.succeed(
                  TurnDraft(
                    if failureCase == FailureCase.Render then -1 else 1,
                    if failureCase == FailureCase.Validation then Vector.fill(9)(1) else Vector.empty
                  )
                ),
              prepare = (draft, registry) =>
                if draft.model == 0 then ZIO.unit
                else
                  registry.prepare(ZIO.unit).flatMap { resource =>
                    candidateResource.set(Some(resource))
                  },
              afterRender = draft =>
                if draft.model != 0 && failureCase == FailureCase.AfterRender then
                  ZIO.fail(IllegalStateException("after-render"))
                else ZIO.unit
            )
            kernel <- SessionKernel.start(config, logic, program, outbound)
            failed <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
            state  <- kernel.awaitTermination
            resource <- candidateResource.get.flatMap {
                          case Some(value) => value.state
                          case None        => ZIO.succeed(PreparedResource.State.Closed)
                        }
            published <- publications.get
          yield assertTrue(
            failed.left.exists(_.isInstanceOf[SessionRejection.SessionFailed]),
            state.isInstanceOf[SessionState.Crashed[?, ?]],
            published == 1,
            resource == PreparedResource.State.Closed
          )
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    },
    test("handler and resource-preparation failures are terminal without an update") {
      enum FailureCase:
        case Handler, Preparation

      ZIO.foreach(FailureCase.values.toVector) { failureCase =>
        ZIO.scoped {
          for
            program             <- textProgram
            (outbound, batches) <- recordingOutbound
            logic = SessionLogic[Int, Int](
              bootstrap = ZIO.succeed(TurnDraft(0)),
              handle = (_, _) =>
                if failureCase == FailureCase.Handler then ZIO.fail(IllegalStateException("handler"))
                else ZIO.succeed(TurnDraft(1)),
              prepare = (draft, _) =>
                if draft.model != 0 && failureCase == FailureCase.Preparation then
                  ZIO.fail(IllegalStateException("prepare"))
                else ZIO.unit
            )
            kernel <- SessionKernel.start(config, logic, program, outbound)
            result <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
            output <- batches.get
          yield assertTrue(
            result.left.exists(_.isInstanceOf[SessionRejection.SessionFailed]),
            output.size == 1,
            output.flatMap(_.items).map(_.command) == Vector(None)
          )
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    },
    test("partial preparation failure closes every resource registered before the failure") {
      ZIO.scoped {
        for
          finalized <- Ref.make(false)
          prepared  <- Ref.make(Option.empty[PreparedResource])
          program   <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(prepare = (draft, registry) =>
            if draft.model == 0 then ZIO.unit
            else
              registry.prepare(finalized.set(true)).flatMap { resource =>
                prepared.set(Some(resource)) *>
                  ZIO.fail(IllegalStateException("partial preparation"))
              }
          )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          result <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          terminal <- kernel.awaitTermination
          didFinalize <- finalized.get
          state <- prepared.get.flatMap(_.fold(ZIO.dieMessage("resource missing"))(_.state))
          output <- batches.get
        yield assertTrue(
          result.left.exists(_.isInstanceOf[SessionRejection.SessionFailed]),
          terminal.isInstanceOf[SessionState.Crashed[?, ?]],
          didFinalize,
          state == PreparedResource.State.Closed,
          output.size == 1
        )
      }
    },
    test("cleanup defects cannot suppress terminal state or later finalizers") {
      ZIO.scoped {
        for
          laterFinalized <- Ref.make(false)
          program        <- textProgram
          (outbound, _)  <- recordingOutbound
          logic = SessionLogic[Int, Int](
            bootstrap = ZIO.succeed(TurnDraft(0)),
            handle = (_, _) => ZIO.fail(IllegalStateException("handler")),
            prepare = (_, registry) =>
              registry.prepare(ZIO.dieMessage("cleanup defect")) *>
                registry.prepare(laterFinalized.set(true)).unit
          )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          result <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          terminal <- kernel.awaitTermination
          laterRan <- laterFinalized.get
          rejected <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          closed   <- kernel.close.exit.timeout(1.second)
        yield assertTrue(
          result.left.exists(_.isInstanceOf[SessionRejection.SessionFailed]),
          terminal.isInstanceOf[SessionState.Crashed[?, ?]],
          laterRan,
          rejected == Left(SessionRejection.Terminal("crashed")),
          closed.nonEmpty
        )
      }
    },
    test("private continuations run FIFO independently of mailbox capacity") {
      ZIO.scoped {
        for
          handled <- Ref.make(Vector.empty[Int])
          program <- textProgram
          (outbound, batches) <- recordingOutbound
          tiny = SessionConfig.make(1, 4).toOption.get
          logic = SessionLogic[Int, Int](
            bootstrap = ZIO.succeed(TurnDraft(0, Vector(1, 2, 3))),
            handle = (model, message) => handled.update(_ :+ message).as(TurnDraft(model + message))
          )
          kernel <- SessionKernel.start(tiny, logic, program, outbound)
          finalState <- kernel.inspect
          order      <- handled.get
          output     <- batches.get
        yield assertTrue(
          order == Vector(1, 2, 3),
          finalState.model == 6,
          output.size == 4,
          output.flatMap(_.items).forall(_.command.isEmpty)
        )
      }
    },
    test("mailbox saturation is explicit") {
      ZIO.scoped {
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          program <- textProgram
          (outbound, _) <- recordingOutbound
          tiny = SessionConfig.make(1, 4).toOption.get
          logic = standardLogic().copy(handle = (model, message) =>
            entered.succeed(()).unit *> release.await.as(TurnDraft(model + message))
          )
          kernel <- SessionKernel.start(tiny, logic, program, outbound)
          first  <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).fork
          _      <- entered.await
          queued <- kernel.submit(SessionCommand.Message(kernel.epoch, 2)).fork
          _      <- ZIO.yieldNow
          saturated <- kernel.submit(SessionCommand.Message(kernel.epoch, 3)).either
          _         <- release.succeed(())
          _         <- first.join
          _         <- queued.join
        yield assertTrue(saturated == Left(SessionRejection.MailboxSaturated(1)))
      }
    },
    test("close while blocked before commit publishes nothing and closes the candidate") {
      ZIO.scoped {
        for
          entered   <- Promise.make[Nothing, Unit]
          never     <- Promise.make[Nothing, Unit]
          interrupted <- Promise.make[Nothing, Unit]
          finalized <- Promise.make[Nothing, Unit]
          candidate <- Ref.make(Option.empty[PreparedResource])
          program   <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(
            prepare = (draft, registry) =>
              if draft.model == 0 then ZIO.unit
              else
                registry.prepare(finalized.succeed(()).unit).flatMap { resource =>
                  candidate.set(Some(resource))
                },
            afterRender = draft =>
              if draft.model == 0 then ZIO.unit
              else
                ZIO.uninterruptibleMask { restore =>
                  entered.succeed(()).unit *>
                    restore(never.await).onInterrupt(interrupted.succeed(()).unit)
                }
          )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          turn   <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).fork
          _      <- entered.await
          closing <- kernel.close.fork
          _      <- interrupted.await
          _      <- finalized.await
          _      <- closing.join
          result <- turn.await
          output <- batches.get
          state <- candidate.get.flatMap(_.fold(ZIO.dieMessage("candidate missing"))(_.state))
        yield assertTrue(
          result == Exit.fail(SessionRejection.Terminal("closed")),
          output.size == 1,
          state == PreparedResource.State.Closed
        )
      }
    },
    test("a publication defect is terminal and closes the unpublished candidate") {
      ZIO.scoped {
        for
          publishCount <- Ref.make(0)
          reserveCount <- Ref.make(0)
          candidate    <- Ref.make(Option.empty[PreparedResource])
          program      <- textProgram
          outbound = ProbeOutbound(
            reserveCount.getAndUpdate(_ + 1).map { index =>
              ProbeReservation { _ =>
                if index == 1 then ZIO.dieMessage("publication defect")
                else publishCount.update(_ + 1)
              }
            }
          )
          logic = standardLogic().copy(prepare = (draft, registry) =>
            if draft.model == 0 then ZIO.unit
            else
              registry.prepare(ZIO.unit).flatMap { resource =>
                candidate.set(Some(resource))
              }
          )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          failed <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          terminal <- kernel.awaitTermination
          state <- candidate.get.flatMap(_.fold(ZIO.dieMessage("candidate missing"))(_.state))
          count <- publishCount.get
          later <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
        yield assertTrue(
          failed.left.exists {
            case SessionRejection.SessionFailed(_: SessionFailure.CommitDefect) => true
            case _                                                             => false
          },
          terminal.isInstanceOf[SessionState.Crashed[?, ?]],
          state == PreparedResource.State.Closed,
          count == 1,
          later == Left(SessionRejection.Terminal("crashed"))
        )
      }
    },
    test("close cannot interrupt the masked commit tail before publication") {
      ZIO.scoped {
        for
          secondPublishing <- Promise.make[Nothing, Unit]
          allowPublication <- Promise.make[Nothing, Unit]
          published        <- Ref.make(Vector.empty[OutboundBatch[SessionOutput]])
          reserveCount     <- Ref.make(0)
          candidate        <- Ref.make(Option.empty[PreparedResource])
          program          <- textProgram
          outbound = ProbeOutbound(
            reserveCount.getAndUpdate(_ + 1).map { index =>
              ProbeReservation { batch =>
                if index == 0 then published.update(_ :+ batch)
                else
                  secondPublishing.succeed(()).unit *>
                    allowPublication.await *>
                    published.update(_ :+ batch)
              }
            }
          )
          logic = standardLogic().copy(prepare = (draft, registry) =>
            if draft.model == 0 then ZIO.unit
            else
              registry.prepare(ZIO.unit).flatMap { resource =>
                candidate.set(Some(resource))
              }
          )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          turn   <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).fork
          _      <- secondPublishing.await
          closing <- kernel.close.fork
          _       <- ZIO.yieldNow
          pending <- closing.poll
          active <- candidate.get.flatMap(_.fold(ZIO.dieMessage("candidate missing"))(_.state))
          _      <- allowPublication.succeed(())
          _      <- closing.join
          _      <- turn.await
          output <- published.get
        yield assertTrue(
          pending.isEmpty,
          active == PreparedResource.State.Active,
          output.size == 2
        )
      }
    },
    test("startup interruption closes the owner before it can publish") {
      ZIO.foreach(1 to 50) { _ =>
        ZIO.scoped {
          for
            entered     <- Promise.make[Nothing, Unit]
            interrupted <- Promise.make[Nothing, Unit]
            program     <- textProgram
            (outbound, batches) <- recordingOutbound
            logic = standardLogic().copy(bootstrap =
              ZIO.uninterruptibleMask { restore =>
                entered.succeed(()).unit *>
                  restore(ZIO.never).onInterrupt(interrupted.succeed(()).unit)
              }
            )
            starting <- SessionKernel.start(config, logic, program, outbound).fork
            _        <- entered.await
            result   <- starting.interrupt
            _        <- interrupted.await
            output   <- batches.get
          yield assertTrue(result.isInterrupted, output.isEmpty)
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    },
    test("submission and inspection racing close always complete") {
      ZIO.scoped {
        for
          entered <- Promise.make[Nothing, Unit]
          never   <- Promise.make[Nothing, Unit]
          program <- textProgram
          (outbound, _) <- recordingOutbound
          kernel <- SessionKernel.start(
                      config,
                      standardLogic().copy(handle = (model, message) =>
                        entered.succeed(()).unit *> never.await.as(TurnDraft(model + message))
                      ),
                      program,
                      outbound
                    )
          first <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either.fork
          _     <- entered.await
          commands <- ZIO.foreach(1 to 20)(_ =>
                        kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either.fork
                      )
          inspections <- ZIO.foreach(1 to 20)(_ => kernel.inspect.either.fork)
          closing     <- kernel.close.fork
          all = first +: (commands ++ inspections)
          completed <- ZIO.foreach(all)(_.join).timeout(1.second)
          _         <- closing.join
        yield assertTrue(completed.exists(_.size == all.size))
      }
    },
    test("retired candidate ownership stays bounded across many turns") {
      ZIO.scoped {
        for
          finalized <- Ref.make(0)
          program   <- textProgram
          (outbound, _) <- recordingOutbound
          logic = standardLogic().copy(prepare = (_, registry) =>
            registry.prepare(finalized.update(_ + 1)).unit
          )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          _ <- ZIO.foreachDiscard(1 to 100)(_ =>
                 kernel.submit(SessionCommand.Message(kernel.epoch, 1))
               )
          beforeClose <- finalized.get
          _           <- kernel.close
          afterClose  <- finalized.get
        yield assertTrue(beforeClose == 100, afterClose == 101)
      }
    },
    test("configuration requires positive mailbox and continuation capacities") {
      assertTrue(
        SessionConfig.make(0, 1) == Left(SessionConfig.Error.InvalidMailboxCapacity(0)),
        SessionConfig.make(1, 0) == Left(SessionConfig.Error.InvalidContinuationCapacity(0)),
        SessionConfig.make(1, 1).isRight
      )
    },
    test("closed sessions reject later commands") {
      ZIO.scoped {
        for
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          kernel              <- SessionKernel.start(config, standardLogic(), program, outbound)
          _                   <- kernel.close
          command <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          inspect <- kernel.inspect.either
          output  <- batches.get
        yield assertTrue(
          command == Left(SessionRejection.Terminal("closed")),
          inspect == Left(SessionRejection.Terminal("closed")),
          output.size == 1
        )
      }
    }
  ) @@ TestAspect.sequential
