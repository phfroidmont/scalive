package scalive.runtime.kernel

import java.time.Duration as JavaDuration

import zio.*
import zio.http.URL
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*

object SessionKernelSpec extends ZIOSpecDefault:
  private val config = SessionConfig.make(4, 8).toOption.get

  final private class ProbeReservation(
    publishEffect: OutboundBatch[SessionOutput] => UIO[Unit],
    releaseEffect: UIO[Unit] = ZIO.unit)
      extends OutboundReservation[SessionOutput]:
    override def publish(batch: OutboundBatch[SessionOutput]): UIO[Unit] = publishEffect(batch)
    override def release: UIO[Unit]                                      = releaseEffect

  final private class ProbeOutbound(
    reserveEffect: ZIO[Any, OutboundReservationError, OutboundReservation[SessionOutput]])
      extends OutboundReservations[SessionOutput]:
    override def reserve  = reserveEffect
    override def take     = ZIO.fail(OutboundReservationError.Shutdown)
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

  private def uploadPlan(effect: Task[Unit]): UploadRetirementPlan =
    UploadRetirementPlan(
      Vector(UploadRetirementInstruction.Cleanup(UploadOperation(effect)))
    )

  private val retainedComponent = new LiveComponent[Int, Int, Int]:
    def mount(props: Int, ctx: MountContext)                       = ZIO.succeed(props)
    def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
      ZIO.succeed(model + message)
    def view(props: Signal[Int], model: Signal[Int], self: ComponentRef[Int]) =
      div(model.map(_.toString))

  private val componentEnvironment = new ComponentEnvironment[Int, Int]:
    def mount[P, M, A](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      draft: TurnDraft[Int, Int]
    ) =
      ZIO.succeed(
        ComponentCallbackResult(
          props.asInstanceOf[A],
          draft,
          ComponentEnvironmentState(new Object())
        )
      )
    def update[P, M, A](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      state: ComponentEnvironmentState,
      draft: TurnDraft[Int, Int]
    ) =
      ZIO.succeed(ComponentCallbackResult(model, draft, state))
    def message[P, M, A, O](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      value: M,
      emit: O => Task[Unit],
      state: ComponentEnvironmentState,
      draft: TurnDraft[Int, Int]
    ) =
      ZIO.succeed(ComponentCallbackResult(model, draft, state))
    def async[P, M, A, O](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      event: LiveAsyncEvent[M],
      emit: O => Task[Unit],
      state: ComponentEnvironmentState,
      draft: TurnDraft[Int, Int]
    ) =
      ZIO.succeed(ComponentCallbackResult(model, draft, state))
    def browserEvent[P, M, A, O](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      command: SessionCommand.ComponentClientEvent,
      emit: O => Task[Unit],
      state: ComponentEnvironmentState,
      draft: TurnDraft[Int, Int]
    ) =
      ZIO.none
    def afterRender[P, M, A](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      state: ComponentEnvironmentState,
      draft: TurnDraft[Int, Int]
    ) =
      ZIO.succeed(ComponentAfterRenderResult(draft, state))
    def discard(id: ComponentInstanceId, state: ComponentEnvironmentState) = ZIO.unit
    def close(id: ComponentInstanceId, state: ComponentEnvironmentState)   = ZIO.unit

  private val firstUrl  = URL.decode("/first").toOption.get
  private val secondUrl = URL.decode("/second").toOption.get

  private def patchDraft(model: Int, destination: URL = firstUrl): TurnDraft[Int, Int] =
    TurnDraft(model, navigation = Some(NavigationRequest(destination, NavigationKind.PushPatch)))

  private def navigationDraft(
    model: Int,
    kind: NavigationKind,
    destination: URL = firstUrl
  ): TurnDraft[Int, Int] =
    TurnDraft(model, navigation = Some(NavigationRequest(destination, kind)))

  override def spec = suite("SessionKernelSpec")(
    test("bootstrap is uncorrelated and a typed message publishes its exact command id") {
      ZIO.scoped {
        for
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          kernel              <- SessionKernel.start(config, standardLogic(1), program, outbound)
          boot                <- kernel.inspect
          commandId = CommandId.fresh().toOption.get
          result    <- kernel.submit(commandId, SessionCommand.Message(kernel.epoch, 2))
          committed <- kernel.inspect
          published <- batches.get
          bootOutput = published.head.items.head
          bootDelta  = bootOutput.delta
          slot       = bootDelta match
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
          accepted        <- kernel.submit(
                        SessionCommand.ClientEvent(
                          kernel.epoch,
                          binding,
                          BindingPayload.Params(Map("amount" -> "5"))
                        )
                      )
          after  <- kernel.inspect
          output <- batches.get
        yield assertTrue(
          stale == Left(SessionRejection.InvalidEpoch(kernel.epoch, Epoch(kernel.epoch.value + 1))),
          unknown == Left(SessionRejection.UnknownBinding(unknownId)),
          malformed.left.exists {
            case SessionRejection.BindingFailed(`binding`, _: NumberFormatException) => true
            case _                                                                   => false
          },
          afterRejections.model == before.model,
          afterRejections.revision == before.revision,
          after.model == 15,
          accepted.delta != RenderDelta.Empty,
          output.size == 2,
          output.last.items.map(_.command) == Vector(Some(accepted.command))
        )
        end for
      }
    },
    test("new resources activate before publication and old resources retire afterward") {
      ZIO.scoped {
        for
          resources    <- Ref.make(Vector.empty[PreparedResource])
          finalized    <- Ref.make(0)
          observations <-
            Ref.make(Vector.empty[(PreparedResource.State, Option[PreparedResource.State], Int)])
          program <- textProgram
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
          logic = standardLogic().copy(prepare =
                    (_, registry) =>
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

      ZIO
        .foreach(FailureCase.values.toVector) { failureCase =>
          ZIO.scoped {
            for
              candidateResource <- Ref.make(Option.empty[PreparedResource])
              releases          <- Ref.make(0)
              publications      <- Ref.make(0)
              program           <- ZIO.fromEither(
                           RenderProgram.compile[Int, Int](model =>
                             div(
                               model.map(value =>
                                 if value < 0 then throw IllegalStateException("render")
                                 else value.toString
                               )
                             )
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
                              if failureCase == FailureCase.Validation then Vector.fill(9)(1)
                              else Vector.empty
                            )
                          ),
                        prepare = (draft, registry) =>
                          if draft.model == 0 then ZIO.unit
                          else
                            registry.prepare(ZIO.unit).flatMap { resource =>
                              candidateResource.set(Some(resource))
                            }
                        ,
                        afterRender = draft =>
                          if draft.model != 0 && failureCase == FailureCase.AfterRender then
                            ZIO.fail(IllegalStateException("after-render"))
                          else ZIO.succeed(draft)
                      )
              kernel   <- SessionKernel.start(config, logic, program, outbound)
              failed   <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
              state    <- kernel.awaitTermination
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

      ZIO
        .foreach(FailureCase.values.toVector) { failureCase =>
          ZIO.scoped {
            for
              program             <- textProgram
              (outbound, batches) <- recordingOutbound
              logic = SessionLogic[Int, Int](
                        bootstrap = ZIO.succeed(TurnDraft(0)),
                        handle = (_, _) =>
                          if failureCase == FailureCase.Handler then
                            ZIO.fail(IllegalStateException("handler"))
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
          finalized           <- Ref.make(false)
          prepared            <- Ref.make(Option.empty[PreparedResource])
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(prepare =
                    (draft, registry) =>
                      if draft.model == 0 then ZIO.unit
                      else
                        registry.prepare(finalized.set(true)).flatMap { resource =>
                          prepared.set(Some(resource)) *>
                            ZIO.fail(IllegalStateException("partial preparation"))
                        }
                  )
          kernel      <- SessionKernel.start(config, logic, program, outbound)
          result      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          terminal    <- kernel.awaitTermination
          didFinalize <- finalized.get
          state       <- prepared.get.flatMap(_.fold(ZIO.dieMessage("resource missing"))(_.state))
          output      <- batches.get
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
          kernel   <- SessionKernel.start(config, logic, program, outbound)
          result   <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
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
          handled             <- Ref.make(Vector.empty[Int])
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          tiny  = SessionConfig.make(1, 4).toOption.get
          logic =
            SessionLogic[Int, Int](
              bootstrap = ZIO.succeed(TurnDraft(0, Vector(1, 2, 3))),
              handle =
                (model, message) => handled.update(_ :+ message).as(TurnDraft(model + message))
            )
          kernel     <- SessionKernel.start(tiny, logic, program, outbound)
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
    test("a matching patch consumes the staged model and commits its destination") {
      ZIO.scoped {
        for
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic(1).copy(
                    handle = (model, message) => ZIO.succeed(patchDraft(model + message)),
                    handleParams =
                      (model, url) => ZIO.succeed(TurnDraft(model * 10, url = Some(url)))
                  )
          kernel     <- SessionKernel.start(config, logic, program, outbound)
          initiating <- kernel.submit(SessionCommand.Message(kernel.epoch, 2))
          visible    <- kernel.inspect
          patchId = CommandId.fresh().toOption.get
          patched   <- kernel.submit(patchId, SessionCommand.ParamsPatch(kernel.epoch, firstUrl))
          committed <- kernel.inspect
          output    <- batches.get
        yield assertTrue(
          visible.model == 1,
          committed.model == 30,
          committed.url == firstUrl,
          patched.command == patchId,
          output(1).items.head.navigation.exists(_.destination == firstUrl),
          initiating.delta == RenderDelta.Empty
        )
      }
    },
    test("a matching internal patch remains admissible when the regular mailbox is full") {
      ZIO.scoped {
        for
          entered             <- Promise.make[Nothing, Unit]
          release             <- Promise.make[Nothing, Unit]
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          tiny  = SessionConfig.make(1, 4).toOption.get
          logic = standardLogic().copy(
                    handle = (model, message) =>
                      if message == 1 then
                        entered.succeed(()).unit *> release.await.as(patchDraft(model + message))
                      else ZIO.succeed(TurnDraft(model + message)),
                    handleParams =
                      (model, url) => ZIO.succeed(TurnDraft(model * 10, url = Some(url)))
                  )
          kernel <- SessionKernel.start(tiny, logic, program, outbound)
          first  <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).fork
          _      <- entered.await
          queuedId = CommandId.fresh().toOption.get
          queued <- kernel.submit(queuedId, SessionCommand.Message(kernel.epoch, 2)).fork
          _      <- ZIO.yieldNow
          patchId = CommandId.fresh().toOption.get
          patch <- kernel
                     .enqueuePatchAcknowledgement(patchId, kernel.epoch, firstUrl).flatten.fork
          saturated <- kernel.submit(SessionCommand.Message(kernel.epoch, 3)).either
          _         <- release.succeed(())
          _         <- first.join
          patched   <- patch.join
          replayed  <- queued.join
          committed <- kernel.inspect
          output    <- batches.get
        yield assertTrue(
          saturated == Left(SessionRejection.MailboxSaturated(1)),
          patched.command == patchId,
          replayed.command == queuedId,
          committed.model == 12,
          committed.url == firstUrl,
          output.flatMap(_.items).flatMap(_.command).takeRight(2) == Vector(patchId, queuedId)
        )
      }
    },
    test("a delayed internal patch cannot commit matching params twice") {
      ZIO.scoped {
        for
          paramsCalls         <- Ref.make(0)
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(
                    handle = (model, message) => ZIO.succeed(patchDraft(model + message)),
                    handleParams = (model, url) =>
                      paramsCalls.update(_ + 1).as(TurnDraft(model * 10, url = Some(url)))
                  )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          patchId = CommandId.fresh().toOption.get
          patch <- kernel.enqueue(patchId, SessionCommand.ParamsPatch(kernel.epoch, firstUrl))
          internalId = CommandId.fresh().toOption.get
          internal  <- kernel.enqueuePatchAcknowledgement(internalId, kernel.epoch, firstUrl)
          patched   <- patch
          duplicate <- internal.either
          committed <- kernel.inspect
          calls     <- paramsCalls.get
          output    <- batches.get
        yield assertTrue(
          patched.command == patchId,
          duplicate == Left(SessionRejection.UnexpectedPatch),
          committed.model == 10,
          calls == 1,
          output.flatMap(_.items).flatMap(_.command).takeRight(1) == Vector(patchId)
        )
      }
    },
    test("a mismatched patch preserves pending navigation") {
      ZIO.scoped {
        for
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          logic = standardLogic(4).copy(handle = (model, _) => ZIO.succeed(patchDraft(model + 1)))
          kernel  <- SessionKernel.start(config, logic, program, outbound)
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          wrong   <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, secondUrl)).either
          visible <- kernel.inspect
          _       <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, firstUrl))
          after   <- kernel.inspect
        yield assertTrue(
          wrong == Left(SessionRejection.MismatchedPatch(firstUrl, secondUrl)),
          visible.model == 4,
          after.model == 5
        )
      }
    },
    test("deferred commands remain pending and replay FIFO with exact command ids") {
      ZIO.scoped {
        for
          seen                <- Ref.make(Vector.empty[Int])
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(handle =
                    (model, message) =>
                      seen.update(_ :+ message) *>
                        ZIO.succeed(
                          if message == 9 then patchDraft(model + message)
                          else TurnDraft(model + message)
                        )
                  )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 9))
          firstId  = CommandId.fresh().toOption.get
          secondId = CommandId.fresh().toOption.get
          first         <- kernel.submit(firstId, SessionCommand.Message(kernel.epoch, 1)).fork
          second        <- kernel.submit(secondId, SessionCommand.Message(kernel.epoch, 2)).fork
          _             <- kernel.inspect
          pendingFirst  <- first.poll
          pendingSecond <- second.poll
          _             <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, firstUrl))
          firstResult   <- first.join
          secondResult  <- second.join
          order         <- seen.get
          output        <- batches.get
        yield assertTrue(
          pendingFirst.isEmpty,
          pendingSecond.isEmpty,
          firstResult.command == firstId,
          secondResult.command == secondId,
          order == Vector(9, 1, 2),
          output.flatMap(_.items).flatMap(_.command).takeRight(2) == Vector(firstId, secondId)
        )
      }
    },
    test("navigation timeout is terminal") {
      ZIO.scoped {
        for
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          short = SessionConfig.make(4, 8, 8, JavaDuration.ofSeconds(1), 2).toOption.get
          logic = standardLogic().copy(handle = (model, _) => ZIO.succeed(patchDraft(model + 1)))
          kernel <- SessionKernel.start(short, logic, program, outbound)
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _      <- TestClock.adjust(2.seconds)
          state  <- kernel.awaitTermination
        yield assertTrue(state match
          case SessionState.Crashed(_, _: SessionFailure.NavigationTimedOut) => true
          case _                                                             => false)
      }
    },
    test("deferred overflow is terminal without dropping promises") {
      ZIO.scoped {
        for
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          tiny  = SessionConfig.make(4, 8, 1, JavaDuration.ofSeconds(5), 2).toOption.get
          logic =
            standardLogic().copy(handle =
              (model, message) =>
                ZIO.succeed(if message == 9 then patchDraft(model) else TurnDraft(model + message))
            )
          kernel      <- SessionKernel.start(tiny, logic, program, outbound)
          _           <- kernel.submit(SessionCommand.Message(kernel.epoch, 9))
          first       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either.fork
          _           <- kernel.inspect
          second      <- kernel.submit(SessionCommand.Message(kernel.epoch, 2)).either
          firstResult <- first.join
          state       <- kernel.awaitTermination
        yield assertTrue(
          firstResult.left.exists {
            case SessionRejection.SessionFailed(_: SessionFailure.NavigationDeferredOverflow) =>
              true
            case _ => false
          },
          second.left.exists {
            case SessionRejection.SessionFailed(_: SessionFailure.NavigationDeferredOverflow) =>
              true
            case _ => false
          },
          state.isInstanceOf[SessionState.Crashed[?, ?]]
        )
      }
    },
    test("chained patches allocate fresh ids and enforce the redirect limit") {
      ZIO.scoped {
        for
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          limited = SessionConfig.make(4, 8, 8, JavaDuration.ofSeconds(5), 1).toOption.get
          calls <- Ref.make(0)
          logic = standardLogic().copy(
                    handle = (model, _) => ZIO.succeed(patchDraft(model + 1)),
                    handleParams = (model, _) =>
                      calls.getAndUpdate(_ + 1).map { index =>
                        if index == 0 then patchDraft(model + 1, secondUrl)
                        else patchDraft(model + 1, firstUrl)
                      }
                  )
          kernel    <- SessionKernel.start(limited, logic, program, outbound)
          _         <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _         <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, firstUrl))
          published <- batches.get
          ids = published.flatMap(_.items).flatMap(_.navigation).map(_.id)
          overflow <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, secondUrl)).either
          state    <- kernel.awaitTermination
        yield assertTrue(
          ids.size == 2,
          ids.distinct.size == 2,
          overflow.left.exists {
            case SessionRejection.SessionFailed(_: SessionFailure.NavigationRedirectOverflow) =>
              true
            case _ => false
          },
          state.isInstanceOf[SessionState.Crashed[?, ?]]
        )
      }
    },
    test("live navigation and redirects publish once and terminate without pending patch state") {
      ZIO
        .foreach(
          Vector(
            NavigationKind.PushNavigate,
            NavigationKind.ReplaceNavigate,
            NavigationKind.Redirect
          )
        ) { kind =>
          ZIO.scoped {
            for
              program             <- textProgram
              (outbound, batches) <- recordingOutbound
              logic = standardLogic(4).copy(handle =
                        (model, message) => ZIO.succeed(navigationDraft(model + message, kind))
                      )
              kernel <- SessionKernel.start(config, logic, program, outbound)
              command = CommandId.fresh().toOption.get
              result    <- kernel.submit(command, SessionCommand.Message(kernel.epoch, 2))
              terminal  <- kernel.awaitTermination
              rejected  <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
              published <- batches.get
              navigations = published.flatMap(_.items).flatMap(_.navigation)
            yield assertTrue(
              result.command == command,
              result.delta == RenderDelta.Empty,
              navigations.map(_.kind) == Vector(kind),
              navigations.map(_.destination) == Vector(firstUrl),
              terminal match
                case SessionState.Redirected(_, output) => output == navigations.head
                case _                                  => false,
              rejected == Left(SessionRejection.Terminal("redirected"))
            )
          }
        }.map(_.reduce(_ && _))
    },
    test("connected bootstrap navigation publishes no render and terminates") {
      ZIO.scoped {
        for
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(
                    bootstrap = ZIO.succeed(
                      navigationDraft(7, NavigationKind.PushNavigate, secondUrl)
                    )
                  )
          kernel    <- SessionKernel.start(config, logic, program, outbound)
          terminal  <- kernel.awaitTermination
          published <- batches.get
          output = published.flatMap(_.items).head
        yield assertTrue(
          output.command.isEmpty,
          output.delta == RenderDelta.Empty,
          output.navigation.exists(navigation =>
            navigation.kind == NavigationKind.PushNavigate &&
              navigation.destination == secondUrl
          ),
          terminal match
            case SessionState.Redirected(_, navigation) => output.navigation.contains(navigation)
            case _                                      => false
        )
      }
    },
    test("terminal navigation during patch acknowledgement rejects deferred commands exactly") {
      ZIO.scoped {
        for
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(
                    handle = (model, message) => ZIO.succeed(patchDraft(model + message)),
                    handleParams = (model, _) =>
                      ZIO.succeed(navigationDraft(model + 1, NavigationKind.Redirect, secondUrl))
                  )
          kernel   <- SessionKernel.start(config, logic, program, outbound)
          _        <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          deferred <- kernel.submit(SessionCommand.Message(kernel.epoch, 2)).either.fork
          _        <- kernel.inspect
          patch    <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, firstUrl))
          result   <- deferred.join
          terminal <- kernel.awaitTermination
          output   <- batches.get
        yield assertTrue(
          patch.delta == RenderDelta.Empty,
          result == Left(SessionRejection.Terminal("redirected")),
          output.flatMap(_.items).flatMap(_.navigation).map(_.kind) ==
            Vector(NavigationKind.PushPatch, NavigationKind.Redirect),
          terminal.isInstanceOf[SessionState.Redirected[?, ?]]
        )
      }
    },
    test("mailbox saturation is explicit") {
      ZIO.scoped {
        for
          entered       <- Promise.make[Nothing, Unit]
          release       <- Promise.make[Nothing, Unit]
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          tiny  = SessionConfig.make(1, 4).toOption.get
          logic = standardLogic().copy(handle =
                    (model, message) =>
                      entered.succeed(()).unit *> release.await.as(TurnDraft(model + message))
                  )
          kernel    <- SessionKernel.start(tiny, logic, program, outbound)
          first     <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).fork
          _         <- entered.await
          queued    <- kernel.submit(SessionCommand.Message(kernel.epoch, 2)).fork
          _         <- ZIO.yieldNow
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
          entered             <- Promise.make[Nothing, Unit]
          never               <- Promise.make[Nothing, Unit]
          interrupted         <- Promise.make[Nothing, Unit]
          finalized           <- Promise.make[Nothing, Unit]
          candidate           <- Ref.make(Option.empty[PreparedResource])
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(
                    prepare = (draft, registry) =>
                      if draft.model == 0 then ZIO.unit
                      else
                        registry.prepare(finalized.succeed(()).unit).flatMap { resource =>
                          candidate.set(Some(resource))
                        }
                    ,
                    afterRender = draft =>
                      if draft.model == 0 then ZIO.succeed(draft)
                      else
                        ZIO.uninterruptibleMask { restore =>
                          entered.succeed(()).unit *>
                            restore(never.await).onInterrupt(interrupted.succeed(()).unit).as(draft)
                        }
                  )
          kernel  <- SessionKernel.start(config, logic, program, outbound)
          turn    <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).fork
          _       <- entered.await
          closing <- kernel.close.fork
          _       <- interrupted.await
          _       <- finalized.await
          _       <- closing.join
          result  <- turn.await
          output  <- batches.get
          state   <- candidate.get.flatMap(_.fold(ZIO.dieMessage("candidate missing"))(_.state))
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
          logic = standardLogic().copy(prepare =
                    (draft, registry) =>
                      if draft.model == 0 then ZIO.unit
                      else
                        registry.prepare(ZIO.unit).flatMap { resource =>
                          candidate.set(Some(resource))
                        }
                  )
          kernel   <- SessionKernel.start(config, logic, program, outbound)
          failed   <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          terminal <- kernel.awaitTermination
          state    <- candidate.get.flatMap(_.fold(ZIO.dieMessage("candidate missing"))(_.state))
          count    <- publishCount.get
          later    <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
        yield assertTrue(
          failed.left.exists {
            case SessionRejection.SessionFailed(_: SessionFailure.CommitDefect) => true
            case _                                                              => false
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
          logic = standardLogic().copy(prepare =
                    (draft, registry) =>
                      if draft.model == 0 then ZIO.unit
                      else
                        registry.prepare(ZIO.unit).flatMap { resource =>
                          candidate.set(Some(resource))
                        }
                  )
          kernel  <- SessionKernel.start(config, logic, program, outbound)
          turn    <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).fork
          _       <- secondPublishing.await
          closing <- kernel.close.fork
          _       <- ZIO.yieldNow
          pending <- closing.poll
          active  <- candidate.get.flatMap(_.fold(ZIO.dieMessage("candidate missing"))(_.state))
          _       <- allowPublication.succeed(())
          _       <- closing.join
          _       <- turn.await
          output  <- published.get
        yield assertTrue(
          pending.isEmpty,
          active == PreparedResource.State.Active,
          output.size == 2
        )
      }
    },
    test("startup interruption closes the owner before it can publish") {
      ZIO
        .foreach(1 to 50) { _ =>
          ZIO.scoped {
            for
              entered             <- Promise.make[Nothing, Unit]
              interrupted         <- Promise.make[Nothing, Unit]
              program             <- textProgram
              (outbound, batches) <- recordingOutbound
              logic = standardLogic().copy(bootstrap = ZIO.uninterruptibleMask { restore =>
                        entered.succeed(()).unit *>
                          restore(ZIO.never).onInterrupt(interrupted.succeed(()).unit)
                      })
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
          entered       <- Promise.make[Nothing, Unit]
          never         <- Promise.make[Nothing, Unit]
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          kernel        <- SessionKernel.start(
                      config,
                      standardLogic().copy(handle =
                        (model, message) =>
                          entered.succeed(()).unit *> never.await.as(TurnDraft(model + message))
                      ),
                      program,
                      outbound
                    )
          first    <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either.fork
          _        <- entered.await
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
          finalized     <- Ref.make(0)
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          logic = standardLogic().copy(prepare =
                    (_, registry) => registry.prepare(finalized.update(_ + 1)).unit
                  )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          _      <- ZIO.foreachDiscard(1 to 100)(_ =>
                 kernel.submit(SessionCommand.Message(kernel.epoch, 1))
               )
          beforeClose <- finalized.get
          _           <- kernel.close
          afterClose  <- finalized.get
        yield assertTrue(beforeClose == 100, afterClose == 101)
      }
    },
    test("upload commit runs after installation and before publication without rollback") {
      ZIO.scoped {
        for
          events  <- Ref.make(Vector.empty[String])
          program <- textProgram
          reserve <- Ref.make(0)
          outbound = ProbeOutbound(
                       reserve.getAndUpdate(_ + 1).map { index =>
                         ProbeReservation(_ =>
                           if index == 0 then ZIO.unit else events.update(_ :+ "publish")
                         )
                       }
                     )
          logic = standardLogic().copy(handle =
                    (model, message) =>
                      ZIO.succeed(
                        TurnDraft(
                          model + message,
                          uploadCommit = uploadPlan(events.update(_ :+ "commit")),
                          uploadRollback = uploadPlan(events.update(_ :+ "rollback"))
                        )
                      )
                  )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          state  <- kernel.inspect
          seen   <- events.get
        yield assertTrue(state.model == 1, seen == Vector("commit", "publish"))
      }
    },
    test("failed upload candidate runs rollback without commit") {
      ZIO.scoped {
        for
          events              <- Ref.make(Vector.empty[String])
          program             <- textProgram
          (outbound, batches) <- recordingOutbound
          logic = standardLogic().copy(
                    handle = (model, message) =>
                      ZIO.succeed(
                        TurnDraft(
                          model + message,
                          uploadCommit = uploadPlan(events.update(_ :+ "commit")),
                          uploadRollback = uploadPlan(events.update(_ :+ "rollback"))
                        )
                      ),
                    afterRender = draft =>
                      if draft.model == 0 then ZIO.succeed(draft)
                      else ZIO.fail(IllegalStateException("candidate failure"))
                  )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          failed <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          seen   <- events.get
          output <- batches.get
        yield assertTrue(
          failed.left.exists(_.isInstanceOf[SessionRejection.SessionFailed]),
          seen == Vector("rollback"),
          output.size == 1
        )
      }
    },
    test("commit-tail failure runs upload rollback only once") {
      ZIO.scoped {
        for
          events  <- Ref.make(Vector.empty[String])
          program <- textProgram
          reserve <- Ref.make(0)
          outbound = ProbeOutbound(
                       reserve.getAndUpdate(_ + 1).map { index =>
                         ProbeReservation(_ =>
                           if index == 0 then ZIO.unit else ZIO.dieMessage("publish failed")
                         )
                       }
                     )
          logic = standardLogic().copy(handle =
                    (model, message) =>
                      ZIO.succeed(
                        TurnDraft(
                          model + message,
                          uploadCommit = uploadPlan(events.update(_ :+ "commit")),
                          uploadRollback = uploadPlan(events.update(_ :+ "rollback"))
                        )
                      )
                  )
          kernel <- SessionKernel.start(config, logic, program, outbound)
          failed <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          seen   <- events.get
        yield assertTrue(
          failed.left.exists {
            case SessionRejection.SessionFailed(_: SessionFailure.CommitDefect) => true
            case _                                                              => false
          },
          seen == Vector("commit", "rollback")
        )
      }
    },
    test("discarded navigation candidate runs upload rollback once") {
      ZIO.scoped {
        for
          rollbacks     <- Ref.make(0)
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          logic = standardLogic().copy(
                    handle = (model, message) =>
                      ZIO.succeed(
                        TurnDraft(
                          model + message,
                          uploadRollback = uploadPlan(rollbacks.update(_ + 1))
                        )
                      ),
                    afterRender = draft =>
                      ZIO.succeed(
                        if draft.model == 0 then draft
                        else
                          draft.copy(navigation =
                            Some(
                              NavigationRequest(firstUrl, NavigationKind.Redirect)
                            )
                          )
                      )
                  )
          kernel   <- SessionKernel.start(config, logic, program, outbound)
          _        <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          terminal <- kernel.awaitTermination
          count    <- rollbacks.get
        yield assertTrue(terminal.isInstanceOf[SessionState.Redirected[?, ?]], count == 1)
      }
    },
    test("ordinary turns retain uploads until session close") {
      ZIO.scoped {
        for
          closes        <- Ref.make(Vector.empty[Int])
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          logic = standardLogic().copy(closeUploads = model => closes.update(_ :+ model))
          kernel    <- SessionKernel.start(config, logic, program, outbound)
          _         <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _         <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          before    <- closes.get
          committed <- kernel.inspect
          _         <- kernel.close
          after     <- closes.get
        yield assertTrue(before.isEmpty, committed.model == 2, after == Vector(2))
      }
    },
    test("session close invokes committed model upload cleanup once") {
      ZIO.scoped {
        for
          closes        <- Ref.make(0)
          program       <- textProgram
          (outbound, _) <- recordingOutbound
          logic = standardLogic(7).copy(closeUploads = _ => closes.update(_ + 1))
          kernel <- SessionKernel.start(config, logic, program, outbound)
          _      <- kernel.close
          _      <- kernel.close
          count  <- closes.get
        yield assertTrue(count == 1)
      }
    },
    test("upload reconciliation receives the exact retained component ids") {
      ZIO.scoped {
        val instance = component(retainedComponent, "retained-upload-owner")
        for
          seen    <- Ref.make(Vector.empty[Set[ComponentInstanceId]])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          (outbound, _) <- recordingOutbound
          logic = standardLogic().copy(reconcileUploads =
                    (draft, activeIds) => seen.update(_ :+ activeIds).as(draft)
                  )
          kernel    <- SessionKernel.start(config, logic, program, outbound, componentEnvironment)
          committed <- kernel.inspect
          observed  <- seen.get
          expected = committed.components.values.map(_.id).toSet
        yield assertTrue(expected.nonEmpty, observed == Vector(expected))
      }
    },
    test("configuration validates mailbox, continuation, and navigation bounds") {
      assertTrue(
        SessionConfig.make(0, 1) == Left(SessionConfig.Error.InvalidMailboxCapacity(0)),
        SessionConfig.make(1, 0) == Left(SessionConfig.Error.InvalidContinuationCapacity(0)),
        SessionConfig.make(1, 1, 0, JavaDuration.ofSeconds(1), 1) ==
          Left(SessionConfig.Error.InvalidNavigationDeferredCapacity(0)),
        SessionConfig.make(1, 1, 1, JavaDuration.ZERO, 1) ==
          Left(SessionConfig.Error.NonPositiveNavigationTimeout),
        SessionConfig.make(1, 1, 1, JavaDuration.ofSeconds(1), 0) ==
          Left(SessionConfig.Error.InvalidNavigationRedirectLimit(0)),
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
          command             <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
          inspect             <- kernel.inspect.either
          output              <- batches.get
        yield assertTrue(
          command == Left(SessionRejection.Terminal("closed")),
          inspect == Left(SessionRejection.Terminal("closed")),
          output.size == 1
        )
      }
    }
  ) @@ TestAspect.sequential
end SessionKernelSpec
