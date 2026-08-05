package scalive.docs.xray

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.*
import scalive.docs.examples.RegisteredExample

final private[docs] case class TraceLimits(
  maxRecords: Int = 200,
  maxBytes: Int = 256 * 1024,
  maxTraces: Int = 128):
  require(maxRecords > 0, "Trace record limit must be positive")
  require(maxBytes > 0, "Trace byte limit must be positive")
  require(maxTraces > 0, "Trace history limit must be positive")

private[docs] enum TraceProducer derives JsonCodec:
  case Server, Browser

final private[docs] case class DocumentationTraceValue(
  typeName: String,
  summary: String,
  fields: Vector[(String, String)])
    derives JsonCodec

final private[docs] case class DocumentationTraceRecord(
  producer: TraceProducer,
  producerSequence: Long,
  traceSession: String,
  connectionEpoch: Option[Long],
  socketEpoch: Option[Long],
  topic: String,
  joinReference: Option[String],
  messageReference: Option[String],
  operationSequence: Long,
  operationKind: String,
  operationRecordSequence: Long,
  stage: String,
  summary: String,
  value: Option[DocumentationTraceValue],
  protocol: Option[Json],
  byteSize: Option[Int])
    derives JsonCodec

final private[docs] case class BrowserTraceRecord(
  sequence: Long,
  topic: String,
  joinReference: Option[String],
  messageReference: Option[String],
  operationSequence: Long,
  stage: String,
  summary: String,
  protocol: Option[Json] = None)
    derives JsonCodec

final private[docs] case class BrowserTraceBatch(records: Vector[BrowserTraceRecord])
    derives JsonCodec

final private[docs] case class TraceKey(traceSession: String, topic: String)

final private[docs] class DocumentationTraceStore private (
  limits: TraceLimits,
  updatesHub: Hub[TraceKey]):

  private val MaxBrowserBatchRecords = 64

  final private class History:
    private var values             = Vector.empty[DocumentationTraceRecord]
    private var bytes              = 0
    private val nextServerSequence = AtomicLong(0L)
    private val accessSequence     = AtomicLong(0L)

    def lastAccess: Long = accessSequence.get()

    private def touch(): Unit = accessSequence.set(nextStoreAccess.incrementAndGet())

    def appendServer(record: RuntimeTraceRecord): Boolean = synchronized {
      touch()
      val value = DocumentationTraceRecord(
        producer = TraceProducer.Server,
        producerSequence = nextServerSequence.incrementAndGet(),
        traceSession = record.identity.traceSession,
        connectionEpoch = Some(record.identity.connectionEpoch),
        socketEpoch = Some(record.identity.socketEpoch),
        topic = record.identity.topic,
        joinReference = record.identity.joinReference.map(_.toString),
        messageReference = record.identity.messageReference.map(_.toString),
        operationSequence = record.identity.operationSequence,
        operationKind = record.identity.operationKind.toString,
        operationRecordSequence = record.recordSequence,
        stage = record.stage.toString,
        summary = record.summary,
        value = record.value.map(value =>
          DocumentationTraceValue(value.typeName, value.summary, value.fields)
        ),
        protocol = record.protocol,
        byteSize = record.byteSize
      )
      append(value)
    }

    def appendBrowser(session: String, record: BrowserTraceRecord): Boolean = synchronized {
      touch()
      val (stage, summary) = DocumentationTraceSanitizer.browserLabel(record.stage)
      append(
        DocumentationTraceRecord(
          producer = TraceProducer.Browser,
          producerSequence = record.sequence,
          traceSession = session,
          connectionEpoch = None,
          socketEpoch = None,
          topic = record.topic,
          joinReference = safeReference(record.joinReference),
          messageReference = safeReference(record.messageReference),
          operationSequence = record.operationSequence,
          operationKind = "Browser",
          operationRecordSequence = record.sequence,
          stage = stage,
          summary = summary,
          value = None,
          protocol = record.protocol.map(DocumentationTraceSanitizer.structure),
          byteSize = None
        )
      )
    }

    private def append(record: DocumentationTraceRecord): Boolean =
      val recordBytes = encodedBytes(record)
      if recordBytes > limits.maxBytes then false
      else
        values = values :+ record
        bytes += recordBytes
        while values.size > limits.maxRecords || bytes > limits.maxBytes do
          val removed = values.head
          values = values.tail
          bytes -= encodedBytes(removed)
        true

    def snapshot: Vector[DocumentationTraceRecord] = synchronized {
      touch()
      values
    }

    def clear(): Unit = synchronized {
      touch()
      values = Vector.empty
      bytes = 0
    }

    private def encodedBytes(record: DocumentationTraceRecord): Int =
      record.toJson.getBytes(StandardCharsets.UTF_8).length
  end History

  private val active           = ConcurrentHashMap[TraceKey, RegisteredExample]()
  private val histories        = ConcurrentHashMap[TraceKey, History]()
  private val connectionEpochs = ConcurrentHashMap[String, AtomicLong]()
  private val nextStoreAccess  = AtomicLong(0L)

  private def history(key: TraceKey): History = synchronized {
    Option(histories.get(key)).getOrElse {
      while histories.size() >= limits.maxTraces do
        histories.entrySet().iterator().asScala.minByOption(_.getValue.lastAccess).foreach {
          entry =>
            histories.remove(entry.getKey)
            active.remove(entry.getKey)
            val sessionStillStored = histories
              .keySet().asScala.exists(
                _.traceSession == entry.getKey.traceSession
              )
            if !sessionStillStored then
              val _ = connectionEpochs.remove(entry.getKey.traceSession)
        }
      val created = History()
      histories.put(key, created)
      created
    }
  }

  def activate(session: String, topic: String, example: RegisteredExample): UIO[Unit] =
    ZIO.succeed {
      val key = TraceKey(session, topic)
      val _   = history(key)
      connectionEpochs.putIfAbsent(session, AtomicLong(1L))
      active.put(key, example)
    }.unit

  def deactivate(session: String, topic: String): UIO[Unit] =
    ZIO.succeed(active.remove(TraceKey(session, topic))).unit

  def isActive(session: String, topic: String): Boolean =
    active.containsKey(TraceKey(session, topic))

  def registered(session: String, topic: String): Option[RegisteredExample] =
    Option(active.get(TraceKey(session, topic)))

  def nextConnectionEpoch(session: String): Long =
    Option(connectionEpochs.get(session)).fold(1L)(_.incrementAndGet())

  def appendServer(record: RuntimeTraceRecord): UIO[Unit] =
    val key = TraceKey(record.identity.traceSession, record.identity.topic)
    if !active.containsKey(key) then ZIO.unit
    else
      val appended = history(key).appendServer(record)
      ZIO.when(appended)(updatesHub.publish(key).unit).unit

  def appendBrowser(session: String, topic: String, batch: BrowserTraceBatch): UIO[Unit] =
    val key = TraceKey(session, topic)
    if !active.containsKey(key) then ZIO.unit
    else
      val history  = this.history(key)
      val appended = batch.records
        .take(MaxBrowserBatchRecords)
        .filter(_.topic == topic)
        .foldLeft(false)((stored, record) => history.appendBrowser(session, record) || stored)
      ZIO.when(appended)(updatesHub.publish(key).unit).unit

  def records(session: String, topic: String): UIO[Vector[DocumentationTraceRecord]] =
    ZIO.succeed(Option(histories.get(TraceKey(session, topic))).fold(Vector.empty)(_.snapshot))

  def reset(session: String, topic: String): UIO[Unit] =
    val key = TraceKey(session, topic)
    ZIO.succeed(Option(histories.get(key)).foreach(_.clear())) *>
      updatesHub.publish(key).unit

  def updates(session: String, topic: String): ZStream[Any, Nothing, Unit] =
    val key = TraceKey(session, topic)
    ZStream.unwrapScoped(
      ZStream.fromHubScoped(updatesHub).map(_.filter(_ == key).map(_ => ()))
    )

  private def safeReference(value: Option[String]): Option[String] =
    value.filter(reference =>
      reference.nonEmpty && reference.length <= 20 && reference.forall(_.isDigit)
    )

end DocumentationTraceStore

private[docs] object DocumentationTraceStore:
  def make(limits: TraceLimits = TraceLimits()): UIO[DocumentationTraceStore] =
    Hub.sliding[TraceKey](1024).map(DocumentationTraceStore(limits, _))
