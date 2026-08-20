package scalive.runtime.kernel

import zio.*
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*

object RuntimeObservabilitySpec extends ZIOSpecDefault:
  private val correlation = RuntimeCorrelation(
    ConnectionId(1L),
    LifecycleId(2L),
    Epoch(3L),
    command = Some(CommandId(4L)),
    turn = Some(TurnId(5L)),
    revision = Some(TurnRevision(6L))
  )

  override def spec = suite("RuntimeObservabilitySpec")(
    test("preserves typed correlations without payload fields") {
      val event = RuntimeEvent.StateCommitted(correlation)
      assertTrue(
        event.context == correlation,
        event.name == "state_committed",
        !event.toString.contains("Json"),
        !event.toString.contains("Throwable")
      )
    },
    test("sink defects cannot affect runtime transitions") {
      val observer = RuntimeObserver.fromFunction(_ => ZIO.dieMessage("observer defect"))
      observer.emit(RuntimeEvent.OutputPublished(correlation)).exit.map(result =>
        assertTrue(result.isSuccess)
      )
    },
    test("emits events in transition order") {
      for
        events <- Ref.make(Vector.empty[String])
        observer = RuntimeObserver.fromFunction(event => events.update(_ :+ event.name))
        _ <- observer.emit(RuntimeEvent.TurnStarted(
               correlation,
               RuntimeCommandKind.Message,
               RuntimeInitiator.Application
             ))
        _      <- observer.emit(RuntimeEvent.StateCommitted(correlation))
        _      <- observer.emit(RuntimeEvent.OutputPublished(correlation))
        values <- events.get
      yield assertTrue(
        values == Vector("turn_started", "state_committed", "output_published")
      )
    },
    test("kernel boundaries are ordered and redact application values") {
      ZIO.scoped {
        for
          events <- Ref.make(Vector.empty[RuntimeEvent])
          observer = RuntimeObserver.fromFunction(event => events.update(_ :+ event))
          program <- ZIO.fromEither(
                       RenderProgram.compile[String, String](model => div(model))
                     )
          outbound <- InMemoryOutboundReservations
                        .make[SessionOutput](8).orDieWith(error =>
                          IllegalStateException(error.toString)
                        )
          kernel <- SessionKernel.start(
                      SessionConfig.make(4, 4).toOption.get,
                      SessionLogic[String, String](
                        bootstrap = ZIO.succeed(TurnDraft("bootstrap-secret")),
                        handle = (_, message) => ZIO.succeed(TurnDraft(message))
                      ),
                      program,
                      outbound,
                      providedConnection = Some(ConnectionId(11L)),
                      observer = observer
                    )
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, "message-secret"))
          values <- events.get
          commandEvents = values.filter(_.context.command.nonEmpty)
          names         = commandEvents.map(_.name)
          descriptors = commandEvents.collect {
                          case RuntimeEvent.CommandAccepted(_, kind, initiator, _) =>
                            kind -> initiator
                          case RuntimeEvent.TurnStarted(_, kind, initiator) => kind -> initiator
                        }
          rendered      = commandEvents.mkString(" ")
        yield assertTrue(
          names == Vector(
            "command_accepted",
            "turn_started",
            "handler_completed",
            "candidate_render_started",
            "candidate_validated",
            "diff_completed",
            "state_committed",
            "output_published"
          ),
          descriptors == Vector.fill(2)(
            RuntimeCommandKind.Message -> RuntimeInitiator.Application
          ),
          commandEvents.forall(_.context.connection == ConnectionId(11L)),
          !rendered.contains("bootstrap-secret"),
          !rendered.contains("message-secret")
        )
      }
    },
    test("resource activation and retirement share an opaque correlation") {
      ZIO.scoped {
        val lifecycle = LifecycleId(41L)
        val owner     = OwnerId.Root(lifecycle)
        val key       = AsyncKey[Int]("resource-secret")
        for
          events <- Ref.make(Vector.empty[RuntimeEvent])
          observer = RuntimeObserver.fromFunction(event => events.update(_ :+ event))
          program <- ZIO.fromEither(RenderProgram.compile[String, String](model => div(model)))
          outbound <- InMemoryOutboundReservations
                        .make[SessionOutput](8).orDieWith(error =>
                          IllegalStateException(error.toString)
                        )
          logic = SessionLogic[String, String](
                    bootstrap = ZIO.succeed(TurnDraft("ready")),
                    handle = (model, message) =>
                      message match
                        case "start" =>
                          ZIO.succeed(
                            TurnDraft(
                              model,
                              resourceOperations = Vector(
                                ResourceOperation.StartAsync(
                                  owner,
                                  key,
                                  ZIO.never,
                                  _ => "completion"
                                )
                              )
                            )
                          )
                        case "cancel" =>
                          ZIO.succeed(
                            TurnDraft(
                              model,
                              resourceOperations = Vector(
                                ResourceOperation.CancelAsync(
                                  owner,
                                  key.asInstanceOf[AsyncKey[Any]],
                                  None
                                )
                              )
                            )
                          )
                        case _ => ZIO.succeed(TurnDraft(model))
                  )
          kernel <- SessionKernel.start(
                      SessionConfig.make(4, 4).toOption.get,
                      logic,
                      program,
                      outbound,
                      providedLifecycle = Some(lifecycle),
                      providedConnection = Some(ConnectionId(40L)),
                      observer = observer
                    )
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, "start"))
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, "cancel"))
          values <- events.get
          activated = values.collect { case RuntimeEvent.ResourceActivated(value) => value.resource }
          retired   = values.collect { case RuntimeEvent.ResourceRetired(value) => value.resource }
        yield assertTrue(
          activated.size == 1,
          retired == activated,
          !values.mkString(" ").contains("resource-secret")
        )
      }
    }
  )
