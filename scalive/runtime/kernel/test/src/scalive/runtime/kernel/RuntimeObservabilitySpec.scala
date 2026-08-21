package scalive.runtime.kernel

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import zio.*
import zio.json.ast.Json
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
    },
    test("diagnostic tracing projects values only for observed operations") {
      val projections = AtomicInteger(0)
      val projectedModel = AtomicReference[Any]()
      for
        records <- Ref.make(Vector.empty[RuntimeTraceRecord])
        diagnostic = new RuntimeDiagnostic.Enabled("trace-session-1234", 2L):
                       def isObserved(topic: String) = topic == "lv:observed"
                       def projectMessage(topic: String, value: Any) =
                         projections.incrementAndGet()
                         RuntimeTraceValue("Message", "Projected message")
                       def projectModel(topic: String, value: Any) =
                         projections.incrementAndGet()
                         projectedModel.set(value)
                         RuntimeTraceValue("Model", "Projected model")
                       def publish(record: RuntimeTraceRecord) = records.update(_ :+ record)
        observer = RuntimeObserver.withDiagnostic(_ => ZIO.unit, diagnostic)
        _ = observer.registerLifecycle(
              correlation.lifecycle,
              "lv:observed",
              value => s"application:$value"
            )
        _ <- observer.correlate(
               CommandId(4L),
               correlation.lifecycle,
               "lv:observed",
               Some("1"),
               Some("7")
             )
        _ <- observer.emit(
               RuntimeEvent.CommandAccepted(
                 correlation,
                 RuntimeCommandKind.ClientEvent,
                 RuntimeInitiator.Browser,
                 queueDepth = 0
               )
             )
        _ <- observer.emit(
               RuntimeEvent.TurnStarted(
                 correlation,
                 RuntimeCommandKind.ClientEvent,
                 RuntimeInitiator.Browser
               )
             )
        _      <- observer.message(correlation, "message-secret")
        _      <- observer.model(correlation, "model-secret")
        _      <- observer.diff(correlation, changed = true)
        output = Object()
        frame  = Object()
        _      <- observer.prepareOutput(correlation, output)
        _      <- observer.emit(RuntimeEvent.OutputPublished(correlation))
        beforeFrame <- records.get
        _ = observer.bindOutput(output, frame)
        trace = observer.takeOutput(frame)
        _ <- ZIO.foreachDiscard(trace)(
               observer.frame(_, Json.Obj("status" -> Json.Str("ok")), 42)
             )
        values <- records.get
      yield assertTrue(
        trace.nonEmpty,
        projections.get() == 2,
        projectedModel.get() == "application:model-secret",
        !beforeFrame.exists(_.stage == RuntimeTraceStage.FinalFrame),
        values.map(_.stage) == Vector(
          RuntimeTraceStage.LifecycleStarted,
          RuntimeTraceStage.TypedMessage,
          RuntimeTraceStage.ModelProposed,
          RuntimeTraceStage.TreeDiff,
          RuntimeTraceStage.FinalPayload,
          RuntimeTraceStage.FinalFrame
        ),
        values.forall(_.identity.messageReference.contains("7")),
        values.last.byteSize.contains(42),
        !values.mkString.contains("message-secret"),
        !values.mkString.contains("model-secret")
      )
    },
    test("internal operations retain their kind and wait for transport frames") {
      val joinCorrelation = correlation.copy(command = None, turn = Some(TurnId(10L)))
      val componentCorrelation = correlation.copy(command = None, turn = Some(TurnId(11L)))
      for
        records <- Ref.make(Vector.empty[RuntimeTraceRecord])
        diagnostic = new RuntimeDiagnostic.Enabled("trace-session-1234", 2L):
                       def isObserved(topic: String) = true
                       def projectMessage(topic: String, value: Any) =
                         RuntimeTraceValue("Message", "Projected")
                       def projectModel(topic: String, value: Any) =
                         RuntimeTraceValue("Model", "Projected")
                       def publish(record: RuntimeTraceRecord) = records.update(_ :+ record)
        observer = RuntimeObserver.withDiagnostic(_ => ZIO.unit, diagnostic)
        _ = observer.registerLifecycle(correlation.lifecycle, "lv:observed", identity)
        _ <- observer.beginInternal(
               correlation.lifecycle,
               RuntimeTraceOperationKind.Join,
               RuntimeTraceInitiator.Browser,
               None
             )
        _ <- observer.emit(
               RuntimeEvent.TurnStarted(
                 joinCorrelation,
                 RuntimeCommandKind.Internal,
                 RuntimeInitiator.Runtime
               )
             )
        joinOutput = Object()
        joinFrame  = Object()
        _ <- observer.prepareOutput(joinCorrelation, joinOutput)
        _ <- observer.emit(RuntimeEvent.OutputPublished(joinCorrelation))
        beforeJoinFrame <- records.get
        _ = observer.bindOutput(joinOutput, joinFrame)
        joinTrace = observer.takeOutput(joinFrame)
        _ <- ZIO.foreachDiscard(joinTrace)(
               observer.frame(_, Json.Obj("status" -> Json.Str("ok")), 20)
             )
        _ <- observer.beginInternal(
               correlation.lifecycle,
               RuntimeTraceOperationKind.ServerMessage,
               RuntimeTraceInitiator.Component("VoteComponent", "scala-vote"),
               Some("component-message")
             )
        _ <- observer.emit(
               RuntimeEvent.TurnStarted(
                 componentCorrelation,
                 RuntimeCommandKind.Internal,
                 RuntimeInitiator.Runtime
               )
             )
        componentOutput = Object()
        componentFrame  = Object()
        _ <- observer.prepareOutput(componentCorrelation, componentOutput)
        _ <- observer.emit(RuntimeEvent.OutputPublished(componentCorrelation))
        _ = observer.bindOutput(componentOutput, componentFrame)
        componentTrace = observer.takeOutput(componentFrame)
        _ <- ZIO.foreachDiscard(componentTrace)(
               observer.frame(_, Json.Obj("diff" -> Json.Obj.empty), 21)
             )
        values <- records.get
        operations = values.groupBy(_.identity.operationSequence).toVector.sortBy(_._1)
      yield assertTrue(
        joinTrace.nonEmpty,
        componentTrace.nonEmpty,
        !beforeJoinFrame.exists(_.stage == RuntimeTraceStage.FinalFrame),
        operations.map(_._2.head.identity.operationKind) == Vector(
          RuntimeTraceOperationKind.Join,
          RuntimeTraceOperationKind.ServerMessage
        ),
        operations.flatMap(_._2.map(_.identity.socketEpoch)).distinct.size == 1,
        operations(1)._2.head.identity.initiator ==
          RuntimeTraceInitiator.Component("VoteComponent", "scala-vote"),
        values.count(_.stage == RuntimeTraceStage.FinalFrame) == 2
      )
    },
    test("cancellation discards a prepared output before transport binding") {
      val diagnostic = new RuntimeDiagnostic.Enabled("trace-session-1234", 2L):
        def isObserved(topic: String) = true
        def projectMessage(topic: String, value: Any) = RuntimeTraceValue.redacted(value)
        def projectModel(topic: String, value: Any)   = RuntimeTraceValue.redacted(value)
        def publish(record: RuntimeTraceRecord)       = ZIO.unit

      val observer = RuntimeObserver.withDiagnostic(_ => ZIO.unit, diagnostic)
      val output   = Object()
      val frame    = Object()
      for
        _ <- ZIO.succeed(
               observer.registerLifecycle(correlation.lifecycle, "lv:observed", identity)
             )
        _ <- observer.emit(
               RuntimeEvent.CommandAccepted(
                 correlation,
                 RuntimeCommandKind.ClientEvent,
                 RuntimeInitiator.Browser,
                 queueDepth = 0
               )
             )
        _ <- observer.prepareOutput(correlation, output)
        _  = observer.cancel(correlation.command.get)
        _  = observer.bindOutput(output, frame)
      yield assertTrue(observer.takeOutput(frame).isEmpty)
    },
    test("transport shutdown fails frames already bound for physical writing") {
      for
        records <- Ref.make(Vector.empty[RuntimeTraceRecord])
        diagnostic = new RuntimeDiagnostic.Enabled("trace-session-1234", 2L):
                       def isObserved(topic: String) = true
                       def projectMessage(topic: String, value: Any) =
                         RuntimeTraceValue.redacted(value)
                       def projectModel(topic: String, value: Any) =
                         RuntimeTraceValue.redacted(value)
                       def publish(record: RuntimeTraceRecord) = records.update(_ :+ record)
        observer = RuntimeObserver.withDiagnostic(_ => ZIO.unit, diagnostic)
        output   = Object()
        connectionOutput = Object()
        frame            = Object()
        _ = observer.registerLifecycle(correlation.lifecycle, "lv:observed", identity)
        _ <- observer.emit(
               RuntimeEvent.CommandAccepted(
                 correlation,
                 RuntimeCommandKind.ClientEvent,
                 RuntimeInitiator.Browser,
                 queueDepth = 0
               )
             )
        _ <- observer.prepareOutput(correlation, output)
        _  = observer.bindOutput(output, connectionOutput)
        _  = observer.bindFrame(connectionOutput, frame)
        _  = observer.unregisterLifecycle(correlation.lifecycle)
        _ <- observer.failTransportFrames
        values <- records.get
      yield assertTrue(
        values.lastOption.exists(_.stage == RuntimeTraceStage.Crash),
        observer.takeOutput(frame).isEmpty
      )
    },
    test("bootstrap failure crashes the pending join instead of synthesizing a leave") {
      for
        records <- Ref.make(Vector.empty[RuntimeTraceRecord])
        diagnostic = new RuntimeDiagnostic.Enabled("trace-session-1234", 2L):
                       def isObserved(topic: String) = true
                       def projectMessage(topic: String, value: Any) =
                         RuntimeTraceValue.redacted(value)
                       def projectModel(topic: String, value: Any) =
                         RuntimeTraceValue.redacted(value)
                       def publish(record: RuntimeTraceRecord) = records.update(_ :+ record)
        observer = RuntimeObserver.withDiagnostic(_ => ZIO.unit, diagnostic)
        _ = observer.registerLifecycle(correlation.lifecycle, "lv:observed", identity)
        _ <- observer.beginInternal(
               correlation.lifecycle,
               RuntimeTraceOperationKind.Join,
               RuntimeTraceInitiator.Browser,
               None
             )
        _ <- observer.emit(
               RuntimeEvent.SessionTerminated(
                 correlation.copy(command = None, turn = None),
                 RuntimeTerminal.Crashed
               )
             )
        values <- records.get
      yield assertTrue(
        values.map(_.stage) == Vector(RuntimeTraceStage.Crash),
        values.headOption.exists(_.identity.operationKind == RuntimeTraceOperationKind.Join)
      )
    },
    test("unobserved diagnostics never invoke projectors") {
      val projections = AtomicInteger(0)
      val diagnostic = new RuntimeDiagnostic.Enabled("trace-session-1234", 2L):
        def isObserved(topic: String) = false
        def projectMessage(topic: String, value: Any) =
          projections.incrementAndGet()
          RuntimeTraceValue.redacted(value)
        def projectModel(topic: String, value: Any) =
          projections.incrementAndGet()
          RuntimeTraceValue.redacted(value)
        def publish(record: RuntimeTraceRecord) = ZIO.unit
      val observer = RuntimeObserver.withDiagnostic(_ => ZIO.unit, diagnostic)

      observer.registerLifecycle(correlation.lifecycle, "lv:unobserved", identity)
      for
        _ <- observer.correlate(
               CommandId(4L),
               correlation.lifecycle,
               "lv:unobserved",
               None,
               None
             )
        _ <- observer.emit(
               RuntimeEvent.CommandAccepted(
                 correlation,
                 RuntimeCommandKind.ClientEvent,
                 RuntimeInitiator.Browser,
                 queueDepth = 0
               )
             )
        _ <- observer.message(correlation, "secret")
        _ <- observer.model(correlation, "secret")
      yield assertTrue(projections.get() == 0)
    }
  )
