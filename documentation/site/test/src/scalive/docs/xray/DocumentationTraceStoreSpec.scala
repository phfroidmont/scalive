package scalive.docs.xray

import java.nio.charset.StandardCharsets

import zio.*
import zio.http.{Request, URL}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.WebSocketMessage.Payload
import scalive.docs.examples.ExampleRegistry

object DocumentationTraceStoreSpec extends ZIOSpecDefault:
  private val Topic   = "lv:docs-example-counter-L2V4YW1wbGVz"
  private val Session = "01234567-89ab-cdef-0123-456789abcdef"

  private def serverRecord(
    summary: String,
    operation: Long = 1L,
    reference: Int = 2
  ): RuntimeTraceRecord =
    RuntimeTraceRecord(
      RuntimeTraceIdentity(
        traceSession = Session,
        connectionEpoch = 1L,
        socketEpoch = 1L,
        topic = Topic,
        joinReference = Some(1),
        messageReference = Some(reference),
        operationSequence = operation,
        operationKind = RuntimeTraceOperationKind.ClientEvent
      ),
      recordSequence = 1L,
      stage = RuntimeTraceStage.RenderCompleted,
      summary = summary
    )

  override def spec = suite("DocumentationTraceStoreSpec")(
    test("isolates active traces by session and exact topic") {
      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(serverRecord("kept"))
        _       <- store.appendServer(
                     serverRecord("other session").copy(identity =
                       serverRecord("unused").identity.copy(traceSession = "other-session-value")
                     )
                   )
        own   <- store.records(Session, Topic)
        other <- store.records("other-session-value", Topic)
      yield assertTrue(
        store.isActive(Session, Topic),
        !store.isActive(Session, s"$Topic-inspector"),
        own.map(_.summary) == Vector("kept"),
        other.isEmpty
      )
    },
    test("evicts oldest records by count") {
      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 2, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(serverRecord("one", 1L))
        _       <- store.appendServer(serverRecord("two", 2L))
        _       <- store.appendServer(serverRecord("three", 3L))
        records <- store.records(Session, Topic)
      yield assertTrue(records.map(_.summary) == Vector("two", "three"))
    },
    test("evicts complete interactions and rejects their late records") {
      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 2, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(serverRecord("one-a", operation = 1L, reference = 1))
        _       <- store.appendServer(serverRecord("one-b", operation = 1L, reference = 1))
        _       <- store.appendServer(serverRecord("two", operation = 2L, reference = 2))
        _       <- store.appendServer(serverRecord("one-late", operation = 1L, reference = 1))
        records <- store.records(Session, Topic)
      yield assertTrue(
        records.map(_.summary) == Vector("two"),
        records.flatMap(_.interactionOrdinal).distinct == Vector(2L)
      )
    },
    test("keeps interaction ordinals monotonic through eviction and resets them on clear") {
      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 3, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _ <- ZIO.foreachDiscard(1 to 5)(value =>
               store.appendServer(serverRecord(s"interaction $value", value.toLong, value))
             )
        retained <- store.records(Session, Topic)
        _        <- store.reset(Session, Topic)
        _        <- store.appendServer(serverRecord("after clear", operation = 6L, reference = 6))
        reset    <- store.records(Session, Topic)
      yield assertTrue(
        retained.map(_.summary) == Vector("interaction 3", "interaction 4", "interaction 5"),
        retained.flatMap(_.interactionOrdinal) == Vector(3L, 4L, 5L),
        reset.flatMap(_.interactionOrdinal) == Vector(1L)
      )
    },
    test("assigns one interaction ordinal to browser and server records") {
      val browserRecords = Vector(
        BrowserTraceRecord(1L, Topic, Some("1"), Some("7"), 7L, "BrowserEvent", "ignored"),
        BrowserTraceRecord(2L, Topic, Some("1"), Some("7"), 7L, "OutboundFrame", "ignored")
      )

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(serverRecord("server", operation = 7L, reference = 7))
        _       <- store.appendBrowser(Session, Topic, BrowserTraceBatch(browserRecords))
        _       <- store.appendBrowser(Session, Topic, BrowserTraceBatch(browserRecords))
        records <- store.records(Session, Topic)
      yield assertTrue(records.flatMap(_.interactionOrdinal) == Vector(1L, 1L, 1L))
    },
    test("correlates non-event outbound frames with runtime operations") {
      val browserRecord = BrowserTraceRecord(
        1L,
        Topic,
        Some("1"),
        Some("9"),
        9L,
        "OutboundFrame",
        "ignored"
      )
      val livePatch = serverRecord("live patch", operation = 9L, reference = 9).copy(identity =
        serverRecord("unused", operation = 9L, reference = 9).identity.copy(
          operationKind = RuntimeTraceOperationKind.LivePatch
        )
      )

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendBrowser(Session, Topic, BrowserTraceBatch(Vector(browserRecord)))
        _       <- store.appendServer(livePatch)
        records <- store.records(Session, Topic)
      yield assertTrue(
        records.flatMap(_.interactionOrdinal) == Vector(1L, 1L),
        records.last.operationKind == "LivePatch"
      )
    },
    test("assigns new ordinals when references repeat or are absent") {
      val first  = serverRecord("first", operation = 1L, reference = 7)
      val second = serverRecord("second", operation = 2L, reference = 7)
      val unreferenced = serverRecord("unreferenced", operation = 3L).copy(identity =
        serverRecord("unused", operation = 3L).identity.copy(messageReference = None)
      )

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(first)
        _       <- store.appendServer(second)
        _       <- store.appendServer(unreferenced)
        records <- store.records(Session, Topic)
      yield assertTrue(records.flatMap(_.interactionOrdinal) == Vector(1L, 2L, 3L))
    },
    test("keeps interleaved reused references bound to their operation and join") {
      val first = serverRecord("first", operation = 1L, reference = 7)
      val second = serverRecord("second", operation = 2L, reference = 7).copy(identity =
        serverRecord("unused", operation = 2L, reference = 7).identity.copy(
          connectionEpoch = 2L,
          joinReference = Some(2)
        )
      )
      val firstLate = first.copy(recordSequence = 2L, summary = "first late")
      val oldBrowserResponse = BrowserTraceRecord(
        1L,
        Topic,
        Some("1"),
        Some("7"),
        7L,
        "InboundProcessed",
        "ignored"
      )

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(first)
        _       <- store.appendServer(second)
        _       <- store.appendServer(firstLate)
        _ <- store.appendBrowser(
               Session,
               Topic,
               BrowserTraceBatch(Vector(oldBrowserResponse))
             )
        records <- store.records(Session, Topic)
      yield assertTrue(records.flatMap(_.interactionOrdinal) == Vector(1L, 2L, 1L, 1L))
    },
    test("bounds serialized history bytes") {
      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 20, maxBytes = 600))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(serverRecord("a" * 1000))
        _       <- store.appendServer(serverRecord("small", operation = 2L, reference = 3))
        records <- store.records(Session, Topic)
        bytes = records.map(_.toJson.getBytes(StandardCharsets.UTF_8).length).sum
      yield assertTrue(records.nonEmpty, bytes <= 600)
    },
    test("bounds the number of visitor trace histories") {
      val secondSession = "fedcba98-7654-3210-fedc-ba9876543210"
      for
        store <- DocumentationTraceStore.make(
                   TraceLimits(maxRecords = 10, maxBytes = 4096, maxTraces = 1)
                 )
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(serverRecord("first"))
        _       <- store.activate(secondSession, Topic, counter)
        first   <- store.snapshot(Session, Topic)
      yield assertTrue(first.records.isEmpty, !first.active)
    },
    test("releases active capture after its final viewer lease expires") {
      for
        store   <- DocumentationTraceStore.make()
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.attach(Session, Topic, "viewer")
        _       <- store.activate(Session, Topic, counter)
        _       <- store.detach(Session, Topic, "viewer")
        before  <- store.snapshot(Session, Topic)
        _       <- TestClock.adjust(5.seconds)
        _       <- ZIO.yieldNow
        after   <- store.snapshot(Session, Topic)
      yield assertTrue(before.active, !after.active)
    },
    test("reattaching a viewer cancels pending lease cleanup") {
      for
        store   <- DocumentationTraceStore.make()
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.attach(Session, Topic, "first-viewer")
        _       <- store.activate(Session, Topic, counter)
        _       <- store.detach(Session, Topic, "first-viewer")
        _       <- TestClock.adjust(2.seconds)
        _       <- store.attach(Session, Topic, "reconnected-viewer")
        _       <- TestClock.adjust(5.seconds)
        active  <- store.snapshot(Session, Topic)
        _       <- store.detach(Session, Topic, "reconnected-viewer")
        _       <- TestClock.adjust(5.seconds)
        _       <- ZIO.yieldNow
        released <- store.snapshot(Session, Topic)
      yield assertTrue(active.active, !released.active)
    },
    test("pausing discards incomplete interactions and preserves completed ones") {
      val partialBrowser = Vector(
        BrowserTraceRecord(1L, Topic, Some("1"), Some("7"), 7L, "BrowserEvent", "ignored"),
        BrowserTraceRecord(2L, Topic, Some("1"), Some("7"), 7L, "OutboundFrame", "ignored")
      )
      val complete = serverRecord("complete", operation = 8L, reference = 8).copy(
        stage = RuntimeTraceStage.FinalFrame
      )

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendBrowser(Session, Topic, BrowserTraceBatch(partialBrowser))
        _       <- store.appendServer(serverRecord("partial", operation = 7L, reference = 7))
        _       <- store.appendServer(complete)
        _       <- store.deactivate(Session, Topic)
        snapshot <- store.snapshot(Session, Topic)
      yield assertTrue(
        !snapshot.active,
        snapshot.records.map(_.summary) == Vector("complete")
      )
    },
    test("sanitizes secrets and never serializes upload bytes") {
      val join = WebSocketMessage(
        Some(1),
        Some(2),
        Topic,
        WebSocketMessage.Protocol.EventJoin,
        Payload.Join(
          url = Some("https://example.test/examples?token=url-secret"),
          redirect = None,
          session = "signed-session-secret",
          static = None,
          params = Some(
            Map(
              "_csrf_token" -> Json.Str("csrf-secret"),
              "password"    -> Json.Str("password-secret"),
              "safe"        -> Json.Str("unsafe-free-text")
            )
          ),
          flash = Some("flash-secret"),
          sticky = false
        )
      )
      val upload = WebSocketMessage(
        Some(1),
        Some(3),
        "lvu:entry",
        WebSocketMessage.Protocol.BinaryChunkEvent,
        Payload.UploadChunk(Chunk.fromArray("upload-secret".getBytes(StandardCharsets.UTF_8)))
      )

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        trace    = DocumentationRuntimeTrace(store, Session, connectionEpoch = 1L)
        sanitizedJoin   = trace.sanitizeProtocol(join, encoded = None).toJson
        sanitizedUpload = trace.sanitizeProtocol(upload, encoded = None)
        _ <- store.appendServer(
               serverRecord("Upload chunk received").copy(protocol = Some(sanitizedUpload))
             )
        stored <- store.records(Session, Topic)
        storedJson = stored.toJson
      yield assertTrue(
        !sanitizedJoin.contains("signed-session-secret"),
        !sanitizedJoin.contains("csrf-secret"),
        !sanitizedJoin.contains("password-secret"),
        !sanitizedJoin.contains("unsafe-free-text"),
        !sanitizedJoin.contains("url-secret"),
        !storedJson.contains("upload-secret"),
        storedJson.contains("13"),
        storedJson.contains("[redacted]")
      )
    },
    test("unprojected values use type-only redaction without toString") {
      final class Throwing:
        override def toString: String = throw new AssertionError("toString must not run")

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 10, maxBytes = 4096))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        trace    = DocumentationRuntimeTrace(store, Session, connectionEpoch = 1L)
        value    = trace.projectModel(Topic, Throwing())
      yield assertTrue(
        value.typeName.contains("Throwing"),
        value.summary == "Content redacted",
        value.fields.isEmpty
      )
    },
    test("bounds and sanitizes browser-submitted records") {
      val records = Vector.tabulate(65) { index =>
        BrowserTraceRecord(
          sequence = index.toLong + 1L,
          topic = Topic,
          joinReference = Some("forged-reference"),
          messageReference = Some("2"),
          operationSequence = index.toLong + 1L,
          stage = "OutboundFrame",
          summary = "untrusted summary",
          protocol = Some(
            Json.Obj(
              "event" -> Json.Str("click"),
              "name"  -> Json.Str("credential contents"),
              "topic" -> Json.Str("untrusted-topic-value")
            )
          )
        )
      }

      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 100, maxBytes = 65536))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendBrowser(Session, Topic, BrowserTraceBatch(records))
        stored  <- store.records(Session, Topic)
        encoded  = stored.toJson
      yield assertTrue(
        stored.length == 64,
        stored.map(_.producerSequence) == (1L to 64L).toVector,
        stored.forall(_.joinReference.isEmpty),
        stored.forall(_.messageReference.contains("2")),
        !encoded.contains("forged-reference"),
        !encoded.contains("untrusted summary"),
        encoded.contains("untrusted-topic-value"),
        encoded.contains("click"),
        !encoded.contains("credential contents")
      )
    },
    test("validates page trace sessions and increments connection epochs") {
      for
        store <- DocumentationTraceStore.make()
        factory = DocumentationRuntimeTraceFactory(store)
        validUrl <- ZIO.fromEither(
                      URL.decode(
                        s"https://example.test/live/websocket?${DocumentationRuntimeTraceFactory.TraceSessionParameter}=$Session"
                      )
                    )
        invalidUrl <- ZIO.fromEither(
                        URL.decode(
                          s"https://example.test/live/websocket?${DocumentationRuntimeTraceFactory.TraceSessionParameter}=bad"
                        )
                      )
        first   = factory.connect(Request.get(validUrl))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        second  = factory.connect(Request.get(validUrl))
        invalid = factory.connect(Request.get(invalidUrl))
      yield assertTrue(
        first.asInstanceOf[RuntimeTrace.Enabled].connectionEpoch == 1L,
        second.asInstanceOf[RuntimeTrace.Enabled].connectionEpoch == 2L,
        invalid == RuntimeTrace.Disabled
      )
    }
  )
end DocumentationTraceStoreSpec
