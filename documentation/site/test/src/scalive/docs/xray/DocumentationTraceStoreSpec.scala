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

  private def serverRecord(summary: String, operation: Long = 1L): RuntimeTraceRecord =
    RuntimeTraceRecord(
      RuntimeTraceIdentity(
        traceSession = Session,
        connectionEpoch = 1L,
        socketEpoch = 1L,
        topic = Topic,
        joinReference = Some(1),
        messageReference = Some(2),
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
    test("bounds serialized history bytes") {
      for
        store   <- DocumentationTraceStore.make(TraceLimits(maxRecords = 20, maxBytes = 600))
        counter <- ZIO.fromOption(ExampleRegistry.get("counter"))
        _       <- store.activate(Session, Topic, counter)
        _       <- store.appendServer(serverRecord("a" * 1000))
        _       <- store.appendServer(serverRecord("small"))
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
        first   <- store.records(Session, Topic)
      yield assertTrue(first.isEmpty, !store.isActive(Session, Topic))
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
        !encoded.contains("untrusted-topic-value"),
        encoded.contains("click")
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
