package scalive

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.reflect.ClassTag
import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.Payload

object RuntimeTraceSpec extends ZIOSpecDefault:
  private enum Msg:
    case Increment

  private object Marker

  private final case class Model(count: Int):
    override def toString: String = throw new AssertionError("model.toString must not be called")

  private val Topic = "lv:runtime-trace"
  private val JoinMeta = WebSocketMessage.Meta(
    joinRef = Some(1),
    messageRef = Some(1),
    topic = Topic,
    eventType = WebSocketMessage.Protocol.EventJoin
  )

  private val liveView = new LiveView[Msg, Model]:
    def mount(ctx: MountContext) = ZIO.succeed(Model(0))

    def handleMessage(model: Model, ctx: MessageContext) =
      case Msg.Increment => ZIO.succeed(model.copy(count = model.count + 1))

    override def view(model: Signal[Model]): HtmlElement[Msg] =
      button(on.click(Msg.Increment), model.map(_.count.toString))

  private final case class Observation(initialHtml: Array[Byte], finalHtml: Array[Byte], frames: Vector[Array[Byte]])

  private def observe(trace: RuntimeTrace): RIO[Scope, Observation] =
    for
      socket <- Socket.start(
                  Topic,
                  "token",
                  liveView,
                  LiveContext(staticChanged = false),
                  JoinMeta,
                  initialUrl = URL.root,
                  runtimeTrace = trace
                )
      queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
      _     <- ZIO.addFinalizer(queue.shutdown)
      _     <- socket.outbox.runForeach(queue.offer).forkScoped
      init  <- queue.take
      initialHtml <- socket.renderedHtml.map(_.getBytes(StandardCharsets.UTF_8))
      eventMeta = JoinMeta.copy(
                    messageRef = Some(2),
                    eventType = WebSocketMessage.Protocol.EventEvent
                  )
      _ <- socket.inbox.offer(
             Payload.Event(
               `type` = "click",
               event = BindingId.attrBindingId(Vector("root:button"), 0),
               value = Json.Obj.empty
             ) -> eventMeta
           )
      reply <- queue.take
      finalHtml <- socket.renderedHtml.map(_.getBytes(StandardCharsets.UTF_8))
      frames <- ZIO.foreach(Vector(init, reply)) { case (payload, meta) =>
                  val message = WebSocketMessage(
                    joinRef = meta.joinRef,
                    messageRef = meta.messageRef,
                    topic = meta.topic,
                    eventType = payload match
                      case Payload.Diff(_) => WebSocketMessage.Protocol.EventDiff
                      case _               => WebSocketMessage.Protocol.EventReply,
                    payload = payload,
                    traceOperation = meta.traceOperation
                  )
                  RuntimeTraceFrame
                    .encode(message)
                    .map(_.getBytes(StandardCharsets.UTF_8))
                }
    yield Observation(initialHtml, finalHtml, frames)

  private final class InactiveTrace(
    projections: AtomicInteger,
    sanitizations: AtomicInteger,
    publications: AtomicInteger)
      extends RuntimeTrace.Enabled("inactive-session", connectionEpoch = 1L):

    def isObserved(topic: String): Boolean = false

    def projectMessage(topic: String, value: Any): RuntimeTraceValue =
      projections.incrementAndGet()
      throw new AssertionError("inactive tracing projected a message")

    def projectModel(topic: String, value: Any): RuntimeTraceValue =
      projections.incrementAndGet()
      throw new AssertionError("inactive tracing projected a model")

    def sanitizeProtocol(message: WebSocketMessage, encoded: Option[String]): Json =
      sanitizations.incrementAndGet()
      throw new AssertionError("inactive tracing sanitized protocol data")

    def publish(record: RuntimeTraceRecord): UIO[Unit] =
      ZIO.succeed(publications.incrementAndGet()).unit

  private final class CollectingTrace(records: ConcurrentLinkedQueue[RuntimeTraceRecord])
      extends RuntimeTrace.Enabled("active-session", connectionEpoch = 3L):

    def isObserved(topic: String): Boolean = topic == Topic

    def projectMessage(topic: String, value: Any): RuntimeTraceValue =
      RuntimeTraceValue(value.getClass.getName, "Explicit message projection")

    def projectModel(topic: String, value: Any): RuntimeTraceValue =
      RuntimeTraceValue(value.getClass.getName, "Explicit model projection")

    def sanitizeProtocol(message: WebSocketMessage, encoded: Option[String]): Json =
      Json.Obj("event" -> Json.Str(message.eventType))

    def publish(record: RuntimeTraceRecord): UIO[Unit] = ZIO.succeed(records.add(record)).unit

  private object FailingTrace extends RuntimeTrace.Enabled("failing-session", connectionEpoch = 1L):
    def isObserved(topic: String): Boolean = topic == Topic
    def projectMessage(topic: String, value: Any): RuntimeTraceValue =
      throw new IllegalStateException("message projector failed")
    def projectModel(topic: String, value: Any): RuntimeTraceValue =
      throw new IllegalStateException("model projector failed")
    def sanitizeProtocol(message: WebSocketMessage, encoded: Option[String]): Json =
      throw new IllegalStateException("sanitizer failed")
    def publish(record: RuntimeTraceRecord): UIO[Unit] = ZIO.dieMessage("sink failed")

  private def startTraced[Msg: ClassTag, Model](
    liveView: LiveView[Msg, Model],
    records: ConcurrentLinkedQueue[RuntimeTraceRecord]
  ): RIO[Scope, Socket[Msg, Model]] =
    Socket.start(
      Topic,
      "token",
      liveView,
      LiveContext(staticChanged = false),
      JoinMeta,
      runtimeTrace = CollectingTrace(records)
    )

  private def subscribe(socket: Socket[?, ?]): RIO[Scope, Queue[(Payload, WebSocketMessage.Meta)]] =
    for
      queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
      _     <- ZIO.addFinalizer(queue.shutdown)
      _     <- socket.outbox.runForeach(queue.offer).forkScoped
      _     <- queue.take
    yield queue

  private def event(
    messageRef: Int,
    path: Vector[String],
    attrIndex: Int = 0,
    cid: Option[Int] = None
  ): (Payload.Event, WebSocketMessage.Meta) =
    Payload.Event(
      `type` = "click",
      event = BindingId.attrBindingId(path, attrIndex),
      value = Json.Obj.empty,
      cid = cid
    ) -> JoinMeta.copy(
      messageRef = Some(messageRef),
      eventType = WebSocketMessage.Protocol.EventEvent,
      traceOperation = RuntimeTraceOperation.Disabled
    )

  private def componentEvent(
    socket: Socket[?, ?],
    messageRef: Int,
    cid: Int
  ): Task[(Payload.Event, WebSocketMessage.Meta)] =
    socket.renderedHtml.flatMap { html =>
      ZIO
        .fromOption("phx-click=\"([^\"]+)\"".r.findFirstMatchIn(html).map(_.group(1)))
        .orElseFail(new RuntimeException("Missing component click binding"))
        .map { binding =>
          Payload.Event(
            `type` = "click",
            event = binding,
            value = Json.Obj.empty,
            cid = Some(cid)
          ) -> JoinMeta.copy(
            messageRef = Some(messageRef),
            eventType = WebSocketMessage.Protocol.EventEvent,
            traceOperation = RuntimeTraceOperation.Disabled
          )
        }
    }

  private def recordIndex(
    records: Vector[RuntimeTraceRecord],
    stage: RuntimeTraceStage
  ): Int =
    records.indexWhere(_.stage == stage)

  private def isStrictlyOrdered(indices: Int*): Boolean =
    indices.forall(_ >= 0) && indices.sliding(2).forall {
      case Seq(left, right) => left < right
      case _                => true
    }

  override def spec = suite("RuntimeTraceSpec")(
    test("inactive tracing does not project, sanitize, collect, or change output bytes") {
      ZIO.scoped {
        for
          projections   <- ZIO.succeed(AtomicInteger(0))
          sanitizations <- ZIO.succeed(AtomicInteger(0))
          publications  <- ZIO.succeed(AtomicInteger(0))
          baseline      <- observe(RuntimeTrace.Disabled)
          traced        <- observe(InactiveTrace(projections, sanitizations, publications))
        yield assertTrue(
          baseline.initialHtml.sameElements(traced.initialHtml),
          baseline.finalHtml.sameElements(traced.finalHtml),
          baseline.frames.zip(traced.frames).forall((left, right) => left.sameElements(right)),
          projections.get() == 0,
          sanitizations.get() == 0,
          publications.get() == 0
        )
      }
    },
    test("type-only redaction always emits valid concise Scala patterns") {
      val anonymous = new Object {}
      val lambda    = () => ()

      assertTrue(
        RuntimeTraceValue.redacted(null).scalaValue.contains("null"),
        RuntimeTraceValue.redacted(Array(1, 2)).scalaValue.contains("_: Array[Int]"),
        RuntimeTraceValue.redacted(Marker).scalaValue.exists(
          value => value.startsWith("_: ") && value.endsWith("RuntimeTraceSpec.Marker.type")
        ),
        RuntimeTraceValue.redacted(Msg.Increment).scalaValue.contains("_: Any"),
        RuntimeTraceValue.redacted(anonymous).scalaValue.contains("_: Any"),
        RuntimeTraceValue.redacted(lambda).scalaValue.contains("_: Any")
      )
    },
    test("active tracing distinguishes lifecycle edges and preserves causal render ordering") {
      ZIO.scoped {
        for
          records <- ZIO.succeed(ConcurrentLinkedQueue[RuntimeTraceRecord]())
          _       <- observe(CollectingTrace(records))
          captured = records.iterator().asScala.toVector
          joinRecords = captured.filter(_.identity.messageReference.contains(1))
          eventRecords = captured.filter(_.identity.messageReference.contains(2))
          modelStages = captured.collect {
                          case record
                              if record.stage == RuntimeTraceStage.ModelProposed ||
                                record.stage == RuntimeTraceStage.ModelRendered ||
                                record.stage == RuntimeTraceStage.ModelCommitted =>
                            record.stage
                        }
          eventOperation = captured.find(_.stage == RuntimeTraceStage.TypedMessage).map(_.identity)
        yield assertTrue(
          modelStages.contains(RuntimeTraceStage.ModelProposed),
          modelStages.contains(RuntimeTraceStage.ModelRendered),
          modelStages.contains(RuntimeTraceStage.ModelCommitted),
          eventOperation.exists(_.traceSession == "active-session"),
          eventOperation.exists(_.connectionEpoch == 3L),
           eventOperation.exists(_.socketEpoch == 1L),
           eventOperation.exists(_.messageReference.contains(2)),
           eventOperation.exists(_.initiator == RuntimeTraceInitiator.Browser),
          isStrictlyOrdered(
            recordIndex(joinRecords, RuntimeTraceStage.RenderStarted),
            recordIndex(joinRecords, RuntimeTraceStage.ModelRendered),
            recordIndex(joinRecords, RuntimeTraceStage.RenderCompleted),
            recordIndex(joinRecords, RuntimeTraceStage.TreeDiff)
          ),
          isStrictlyOrdered(
            recordIndex(eventRecords, RuntimeTraceStage.LifecycleStarted),
            recordIndex(eventRecords, RuntimeTraceStage.LifecycleCompleted),
            recordIndex(eventRecords, RuntimeTraceStage.ModelProposed),
            recordIndex(eventRecords, RuntimeTraceStage.RenderStarted),
            recordIndex(eventRecords, RuntimeTraceStage.ModelRendered),
            recordIndex(eventRecords, RuntimeTraceStage.RenderCompleted),
            recordIndex(eventRecords, RuntimeTraceStage.TreeDiff)
          )
        )
      }
    },
    test("trace projector, sanitizer, and sink failures do not alter socket output") {
      ZIO.scoped {
        for
          baseline <- observe(RuntimeTrace.Disabled)
          traced   <- observe(FailingTrace)
        yield assertTrue(
          baseline.initialHtml.sameElements(traced.initialHtml),
          baseline.finalHtml.sameElements(traced.finalHtml),
          baseline.frames.zip(traced.frames).forall((left, right) => left.sameElements(right))
        )
      }
    },
    test("records empty diffs and failed renders without committing the failed model") {
      enum EdgeMsg:
        case NoChange, FailRender
      final case class EdgeModel(failRender: Boolean)
      val edgeView = new LiveView[EdgeMsg, EdgeModel]:
        def mount(ctx: MountContext) = ZIO.succeed(EdgeModel(false))
        def handleMessage(model: EdgeModel, ctx: MessageContext) =
          case EdgeMsg.NoChange   => ZIO.succeed(model)
          case EdgeMsg.FailRender => ZIO.succeed(model.copy(failRender = true))
        override def view(model: Signal[EdgeModel]) =
          div(
            model.map { model =>
              if model.failRender then throw new IllegalStateException("render failed")
              else ""
            },
            button(on.click(EdgeMsg.NoChange), "No change"),
            button(on.click(EdgeMsg.FailRender), "Fail")
          )

      ZIO.scoped {
        for
          records <- ZIO.succeed(ConcurrentLinkedQueue[RuntimeTraceRecord]())
          socket  <- startTraced(edgeView, records)
          output  <- subscribe(socket)
          _       <- socket.inbox.offer(event(2, Vector("root:div", "tag:0:button")))
          _       <- output.take
          _       <- socket.inbox.offer(event(3, Vector("root:div", "tag:1:button")))
          _       <- output.take
          captured = records.iterator().asScala.toVector
          unchanged = captured.filter(_.identity.messageReference.contains(2))
          failed    = captured.filter(_.identity.messageReference.contains(3))
        yield assertTrue(
          unchanged.exists(record =>
            record.stage == RuntimeTraceStage.TreeDiff && record.summary == "Tree diff is empty"
          ),
          unchanged.exists(_.stage == RuntimeTraceStage.ModelCommitted),
          failed.exists(_.stage == RuntimeTraceStage.ModelProposed),
          failed.exists(_.stage == RuntimeTraceStage.RenderStarted),
          !failed.exists(_.stage == RuntimeTraceStage.RenderCompleted),
          !failed.exists(_.stage == RuntimeTraceStage.ModelRendered),
          failed.exists(_.stage == RuntimeTraceStage.Crash),
          !failed.exists(_.stage == RuntimeTraceStage.ModelCommitted)
        )
      }
    },
    test("correlates async completion as an independent server operation") {
      enum AsyncMsg:
        case Start
        case Done(result: LiveAsyncResult[Int])
      final case class AsyncModel(value: Int)
      val task = AsyncKey[Int]("trace-async")
      val asyncView = new LiveView[AsyncMsg, AsyncModel]:
        def mount(ctx: MountContext) = ZIO.succeed(AsyncModel(0))
        def handleMessage(model: AsyncModel, ctx: MessageContext) =
          case AsyncMsg.Start =>
            ctx.async.start(task)(ZIO.succeed(7))(AsyncMsg.Done.apply).as(model)
          case AsyncMsg.Done(LiveAsyncResult.Succeeded(value)) =>
            ZIO.succeed(model.copy(value = value))
          case AsyncMsg.Done(_) => ZIO.succeed(model)
        override def view(model: Signal[AsyncModel]) =
          div(button(on.click(AsyncMsg.Start), "Start"), span(model.map(_.value.toString)))

      ZIO.scoped {
        for
          records <- ZIO.succeed(ConcurrentLinkedQueue[RuntimeTraceRecord]())
          socket  <- startTraced(asyncView, records)
          output  <- subscribe(socket)
          _       <- socket.inbox.offer(event(2, Vector("root:div", "tag:0:button")))
          _       <- output.take
          _       <- output.take
          captured = records.iterator().asScala.toVector
          asyncRecords = captured.filter(
                           _.identity.operationKind == RuntimeTraceOperationKind.AsyncCompletion
                         )
        yield assertTrue(
           asyncRecords.exists(_.stage == RuntimeTraceStage.TypedMessage),
           asyncRecords.exists(_.stage == RuntimeTraceStage.ModelCommitted),
           asyncRecords.forall(_.identity.messageReference.isEmpty),
           asyncRecords.forall(_.identity.initiator == RuntimeTraceInitiator.Runtime)
        )
      }
    },
    test("records component model commits") {
      object CounterComponent
          extends LiveComponent[Unit, CounterComponent.Msg.type, Int]:
        object Msg
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          (_: Msg.type) => ZIO.succeed(model + 1)
        override def view(props: Signal[Unit], model: Signal[Int], self: ComponentRef[Msg.type]) =
          button(on.click(Msg), phx.target(self), model.map(_.toString))
      val componentView = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.succeed(model)
        override def view(model: Signal[Unit]) =
          div(liveComponent(CounterComponent, id = "trace-counter", props = ()))

      ZIO.scoped {
        for
          records <- ZIO.succeed(ConcurrentLinkedQueue[RuntimeTraceRecord]())
          socket  <- startTraced(componentView, records)
          output  <- subscribe(socket)
          click   <- componentEvent(socket, messageRef = 2, cid = 1)
          _       <- socket.inbox.offer(click)
          _        <- output.take
          captured  = records.iterator().asScala.toVector
          componentRecords = captured.filter(_.identity.messageReference.contains(2))
        yield assertTrue(
          captured.exists(_.summary == "Component proposed a model"),
          captured.exists(_.summary == "Component model committed"),
          captured.exists(_.stage == RuntimeTraceStage.TreeDiff),
          isStrictlyOrdered(
            recordIndex(componentRecords, RuntimeTraceStage.LifecycleStarted),
            recordIndex(componentRecords, RuntimeTraceStage.LifecycleCompleted)
          )
        )
      }
    },
    test("records component output delivery as a separate server operation") {
      enum ParentMsg:
        case Changed(value: Int)

      object OutputComponent
          extends LiveComponent.WithOutput[Unit, Unit, Int, Int]:
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          (_: Unit) => ctx.emit(model + 1).as(model + 1)
        override def view(props: Signal[Unit], model: Signal[Int], self: ComponentRef[Unit]) =
          button(on.click.to(self)(()), model.map(_.toString))

      val instance = component(OutputComponent, "output-trace")
      val componentView = new LiveView[ParentMsg, Int]:
        def mount(ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(model: Int, ctx: MessageContext) =
          case ParentMsg.Changed(value) => ZIO.succeed(value)
        override def view(model: Signal[Int]): HtmlElement[ParentMsg] =
          div(instance.render((), ParentMsg.Changed.apply), p(model.map(_.toString)))

      ZIO.scoped {
        for
          records <- ZIO.succeed(ConcurrentLinkedQueue[RuntimeTraceRecord]())
          socket  <- startTraced(componentView, records)
          queue   <- subscribe(socket)
          click   <- componentEvent(socket, messageRef = 2, cid = 1)
          _       <- socket.inbox.offer(click)
          _        <- queue.take
          _        <- queue.take
          captured  = records.iterator().asScala.toVector
          operations = captured.groupBy(_.identity.operationSequence)
          client = operations.values.find(_.exists(_.identity.operationKind == RuntimeTraceOperationKind.ClientEvent))
          server = operations.values.find(_.exists(_.identity.operationKind == RuntimeTraceOperationKind.ServerMessage))
        yield assertTrue(
          client.exists(_.exists(_.stage == RuntimeTraceStage.TypedMessage)),
           server.exists(_.exists(_.stage == RuntimeTraceStage.TypedMessage)),
           server.exists(_.forall(_.identity.messageReference.isEmpty)),
           server.exists(_.forall(
             _.identity.initiator == RuntimeTraceInitiator.Component(
               OutputComponent.getClass.getName,
               "output-trace"
             )
           )),
           server.exists(records =>
            isStrictlyOrdered(
              recordIndex(records, RuntimeTraceStage.LifecycleStarted),
              recordIndex(records, RuntimeTraceStage.LifecycleCompleted)
            )
          )
        )
      }
    },
    test("records stream and title-only updates") {
      final case class User(id: Int, name: String)
      val users = LiveStreamDef.byId[User, Int]("trace-users")(_.id)
      enum StreamMsg:
        case Add, SetTitle
      final case class StreamModel(items: LiveStream[User], title: String)
      val streamView = new LiveView[StreamMsg, StreamModel]:
        override def pageTitle(model: StreamModel) = Some(model.title)
        def mount(ctx: MountContext) =
          ctx.streams.create(users, List(User(1, "one"))).map(StreamModel(_, "Initial"))
        def handleMessage(model: StreamModel, ctx: MessageContext) =
          case StreamMsg.Add =>
            ctx.streams.insert(users, User(2, "two")).map(items => model.copy(items = items))
          case StreamMsg.SetTitle => ZIO.succeed(model.copy(title = "Updated"))
        override def view(model: Signal[StreamModel]) =
          div(
            button(on.click(StreamMsg.Add), "Add"),
            button(on.click(StreamMsg.SetTitle), "Title"),
            model.map(_.items).renderIn(ul)(user => li(user.map(_.name)))
          )

      ZIO.scoped {
        for
          records <- ZIO.succeed(ConcurrentLinkedQueue[RuntimeTraceRecord]())
          socket  <- startTraced(streamView, records)
          output  <- subscribe(socket)
          _       <- socket.inbox.offer(event(2, Vector("root:div", "tag:0:button")))
          _       <- output.take
          _       <- socket.inbox.offer(event(3, Vector("root:div", "tag:1:button")))
          _       <- output.take
          captured = records.iterator().asScala.toVector
          streamRecords = captured.filter(_.identity.messageReference.contains(2))
          titleRecords  = captured.filter(_.identity.messageReference.contains(3))
        yield assertTrue(
          streamRecords.exists(record =>
            record.stage == RuntimeTraceStage.TreeDiff && record.summary == "Tree diff contains changes"
          ),
          streamRecords.exists(_.stage == RuntimeTraceStage.ModelCommitted),
          titleRecords.exists(_.stage == RuntimeTraceStage.TreeDiff),
          titleRecords.exists(_.stage == RuntimeTraceStage.FinalPayload)
        )
      }
    }
  )
end RuntimeTraceSpec
