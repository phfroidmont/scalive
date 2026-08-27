package scalive.docs.xray

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.docs.examples.ExampleRegistry
import scalive.runtime.kernel.*

object DocumentationTraceStoreSpec extends ZIOSpecDefault:
  private val Session = "12345678-1234-1234-1234-123456789012"
  private val Topic   = "lv:example:counter"

  private def serverRecord(
    summary: String,
    operation: Long = 1L,
    reference: String = "7"
  ): RuntimeTraceRecord =
    RuntimeTraceRecord(
      RuntimeTraceIdentity(
        traceSession = Session,
        connectionEpoch = 2L,
        socketEpoch = 2L,
        topic = Topic,
        joinReference = Some("1"),
        messageReference = Some(reference),
        operationSequence = operation,
        operationKind = RuntimeTraceOperationKind.ClientEvent,
        initiator = RuntimeTraceInitiator.Browser
      ),
      recordSequence = 1L,
      stage = RuntimeTraceStage.TypedMessage,
      summary = summary
    )

  override def spec = suite("DocumentationTraceStoreSpec")(
    test("stores bounded server records only for registered examples") {
      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 2, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(serverRecord("one", operation = 1L, reference = "1"))
        _       <- store.appendServer(serverRecord("two", operation = 2L, reference = "2"))
        _       <- store.appendServer(serverRecord("three", operation = 3L, reference = "3"))
        records <- store.records(Session, Topic)
      yield assertTrue(
        records.map(_.summary) == Vector("two", "three"),
        records.forall(_.producer == TraceProducer.Server),
        records.forall(_.operationKind == "ClientEvent")
      )
    },
    test("correlates browser and server records by string protocol references") {
      val browser = BrowserTraceRecord(
        sequence = 1L,
        topic = Topic,
        joinReference = Some("1"),
        messageReference = Some("7"),
        operationSequence = 1L,
        stage = "OutboundFrame",
        summary = "untrusted",
        protocol = Some(Json.Obj("token" -> Json.Str("secret")))
      )

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendBrowser(Session, Topic, BrowserTraceBatch(Vector(browser)))
        _       <- store.appendServer(serverRecord("server"))
        records <- store.records(Session, Topic)
        encoded  = records.head.protocol.map(_.toJson).getOrElse("")
      yield assertTrue(
        records.flatMap(_.interactionOrdinal) == Vector(1L, 1L),
        encoded.contains("[redacted]"),
        !encoded.contains("secret")
      )
    },
    test("increments connection epochs and projects unknown values without toString") {
      final class Throwing:
        override def toString: String = throw new AssertionError("toString must not run")

      for
        store   <- DocumentationTraceStore.make()
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        first    = store.nextConnectionEpoch(Session)
        second   = store.nextConnectionEpoch(Session)
        diagnostic = DocumentationRuntimeDiagnostic(store, Session, first)
        value      = diagnostic.projectModel(Topic, Throwing())
      yield assertTrue(
        first == 2L,
        second == 3L,
        value.typeName.contains("Throwing"),
        value.summary == "Content redacted",
        value.fields.isEmpty,
        value.scalaValue.isEmpty
      )
    },
    test("preserves a pinned inspector selection until trace history is reset") {
      for
        store   <- DocumentationTraceStore.make()
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _ <- store.selectInteraction(
               Session,
               Topic,
               Some("captured-operation-1"),
               followLatest = false
             )
        pinned <- store.snapshot(Session, Topic)
        _      <- store.reset(Session, Topic)
        reset  <- store.snapshot(Session, Topic)
      yield assertTrue(
        pinned.selectedInteraction.contains("captured-operation-1"),
        !pinned.followLatest,
        reset.selectedInteraction.isEmpty,
        reset.followLatest
      )
    }
  )
end DocumentationTraceStoreSpec
