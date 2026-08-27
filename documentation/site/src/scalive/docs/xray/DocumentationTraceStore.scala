package scalive.docs.xray

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.*
import scalive.docs.examples.RegisteredExample
import scalive.runtime.kernel.*

final private[docs] case class TraceLimits(
  maxRecords: Int = 200,
  maxBytes: Int = 256 * 1024,
  maxTraces: Int = 128):
  require(maxRecords > 0, "Trace record limit must be positive")
  require(maxBytes > 0, "Trace byte limit must be positive")
  require(maxTraces > 0, "Trace history limit must be positive")

private[docs] enum TraceProducer derives JsonCodec:
  case Server, Browser

private[docs] enum DocumentationTraceInitiator derives JsonCodec:
  case Browser
  case Runtime
  case Component(typeName: String, id: String)

final private[docs] case class DocumentationTraceValue(
  typeName: String,
  summary: String,
  fields: Vector[(String, String)],
  scalaValue: Option[String] = None)
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
  byteSize: Option[Int],
  interactionOrdinal: Option[Long],
  initiator: DocumentationTraceInitiator)
    derives JsonCodec

final private[docs] case class BrowserTraceRecord(
  sequence: Long,
  topic: String,
  joinReference: Option[String],
  messageReference: Option[String],
  operationSequence: Long,
  stage: String,
  summary: String,
  protocol: Option[Json] = None,
  byteSize: Option[Int] = None)
    derives JsonCodec

final private[docs] case class BrowserTraceBatch(records: Vector[BrowserTraceRecord])
    derives JsonCodec

final private[docs] case class TraceKey(traceSession: String, topic: String)

final private[docs] case class DocumentationTraceSnapshot(
  active: Boolean,
  records: Vector[DocumentationTraceRecord],
  selectedInteraction: Option[String],
  followLatest: Boolean)

final private[docs] class DocumentationTraceStore private (
  limits: TraceLimits,
  updatesHub: Hub[TraceKey]):

  private val MaxBrowserBatchRecords = 64

  final private class History:
    final private case class ServerOperation(
      connectionEpoch: Long,
      socketEpoch: Long,
      sequence: Long)
    final private case class InteractionOrdinal(
      value: Long,
      var browserStarted: Boolean,
      var expectingOutbound: Boolean,
      var serverOperation: Option[ServerOperation])

    private var values                 = Vector.empty[DocumentationTraceRecord]
    private var bytes                  = 0
    private var lastBrowserSequence    = 0L
    private val nextServerSequence     = AtomicLong(0L)
    private val accessSequence         = AtomicLong(0L)
    private val interactionOrdinals    = mutable.LinkedHashMap.empty[String, InteractionOrdinal]
    private val serverOrdinals         = mutable.LinkedHashMap.empty[ServerOperation, Long]
    private val discardedInteractions  = mutable.LinkedHashSet.empty[Long]
    private var nextInteractionOrdinal = 0L
    private var selectedInteraction    = Option.empty[String]
    private var followLatest           = true

    def lastAccess: Long = accessSequence.get()

    private def touch(): Unit = accessSequence.set(nextStoreAccess.incrementAndGet())

    def appendServer(record: RuntimeTraceRecord): Boolean = synchronized {
      touch()
      val identity         = record.identity
      val joinReference    = safeReference(identity.joinReference)
      val messageReference = safeReference(identity.messageReference)
      val value            = DocumentationTraceRecord(
        producer = TraceProducer.Server,
        producerSequence = nextServerSequence.incrementAndGet(),
        traceSession = identity.traceSession,
        connectionEpoch = Some(identity.connectionEpoch),
        socketEpoch = Some(identity.socketEpoch),
        topic = identity.topic,
        joinReference = joinReference,
        messageReference = messageReference,
        operationSequence = identity.operationSequence,
        operationKind = operationKind(identity.operationKind),
        operationRecordSequence = record.recordSequence,
        stage = stageName(record.stage),
        summary = record.summary,
        value = record.value.map(value =>
          DocumentationTraceValue(value.typeName, value.summary, value.fields, value.scalaValue)
        ),
        protocol = record.protocol.map(DocumentationTraceSanitizer.structure),
        byteSize = record.byteSize,
        initiator = identity.initiator match
          case RuntimeTraceInitiator.Browser                 => DocumentationTraceInitiator.Browser
          case RuntimeTraceInitiator.Runtime                 => DocumentationTraceInitiator.Runtime
          case RuntimeTraceInitiator.Component(typeName, id) =>
            DocumentationTraceInitiator.Component(typeName, id),
        interactionOrdinal = interactionOrdinal(
          correlationReference(joinReference, messageReference),
          browserStart = false,
          serverOperation = Some(
            ServerOperation(
              identity.connectionEpoch,
              identity.socketEpoch,
              identity.operationSequence
            )
          ),
          browserFallbackStart = false
        )
      )
      append(value)
    }

    def appendBrowser(session: String, record: BrowserTraceRecord): Boolean = synchronized {
      if record.sequence <= lastBrowserSequence then false
      else
        touch()
        lastBrowserSequence = record.sequence
        val (stage, summary) = DocumentationTraceSanitizer.browserLabel(record.stage)
        val messageReference = safeReference(record.messageReference)
        append(
          DocumentationTraceRecord(
            producer = TraceProducer.Browser,
            producerSequence = record.sequence,
            traceSession = session,
            connectionEpoch = None,
            socketEpoch = None,
            topic = record.topic,
            joinReference = safeReference(record.joinReference),
            messageReference = messageReference,
            operationSequence = record.operationSequence,
            operationKind = "Browser",
            operationRecordSequence = record.sequence,
            stage = stage,
            summary = summary,
            value = None,
            protocol = record.protocol.map(DocumentationTraceSanitizer.structure),
            byteSize = record.byteSize.filter(value => value >= 0 && value <= limits.maxBytes),
            initiator = DocumentationTraceInitiator.Browser,
            interactionOrdinal = interactionOrdinal(
              correlationReference(safeReference(record.joinReference), messageReference),
              browserStart = record.stage == "BrowserEvent",
              serverOperation = None,
              browserFallbackStart = record.stage == "OutboundFrame"
            )
          )
        )
    }

    private def append(record: DocumentationTraceRecord): Boolean =
      val recordBytes = encodedBytes(record)
      if recordBytes > limits.maxBytes then
        record.interactionOrdinal.foreach(discardInteraction)
        false
      else if record.interactionOrdinal.exists(discardedInteractions.contains) then false
      else
        values = values :+ record
        bytes += recordBytes
        while values.size > limits.maxRecords || bytes > limits.maxBytes do
          values.head.interactionOrdinal match
            case Some(ordinal) =>
              discardInteraction(ordinal)
            case None =>
              val removed = values.head
              values = values.tail
              bytes -= encodedBytes(removed)
        !record.interactionOrdinal.exists(discardedInteractions.contains) && values.contains(record)

    private def discardInteraction(ordinal: Long): Unit =
      val (removed, retained) = values.partition(_.interactionOrdinal.contains(ordinal))
      values = retained
      bytes -= removed.map(encodedBytes).sum
      discardedInteractions += ordinal
      while discardedInteractions.size > limits.maxRecords do
        val _ = discardedInteractions.remove(discardedInteractions.head)

    private def interactionOrdinal(
      reference: Option[String],
      browserStart: Boolean,
      serverOperation: Option[ServerOperation],
      browserFallbackStart: Boolean
    ): Option[Long] =
      serverOperation.flatMap(serverOrdinals.get).orElse {
        val key = reference.orElse(
          serverOperation.map(operation =>
            s"server:${operation.connectionEpoch}:${operation.socketEpoch}:${operation.sequence}"
          )
        )
        key.flatMap { value =>
          interactionOrdinals.get(value) match
            case Some(existing)
                if (browserStart && existing.browserStarted) ||
                  (browserFallbackStart && existing.browserStarted && !existing.expectingOutbound &&
                    existing.serverOperation.nonEmpty) ||
                  serverOperation
                    .exists(current => existing.serverOperation.exists(_ != current)) =>
              Some(
                allocateInteraction(
                  value,
                  browserStart || browserFallbackStart,
                  expectingOutbound = browserStart,
                  serverOperation
                )
              )
            case Some(existing) =>
              if browserStart then
                existing.browserStarted = true
                existing.expectingOutbound = true
              else if browserFallbackStart then
                existing.browserStarted = true
                existing.expectingOutbound = false
              if existing.serverOperation.isEmpty then existing.serverOperation = serverOperation
              Some(existing.value)
            case None if browserStart || browserFallbackStart || serverOperation.nonEmpty =>
              Some(
                allocateInteraction(
                  value,
                  browserStart || browserFallbackStart,
                  expectingOutbound = browserStart,
                  serverOperation
                )
              )
            case None => None
        }
      }
    end interactionOrdinal

    private def allocateInteraction(
      key: String,
      browserStarted: Boolean,
      expectingOutbound: Boolean,
      serverOperation: Option[ServerOperation]
    ): Long =
      nextInteractionOrdinal += 1
      val _ = interactionOrdinals.remove(key)
      interactionOrdinals.update(
        key,
        InteractionOrdinal(
          nextInteractionOrdinal,
          browserStarted,
          expectingOutbound,
          serverOperation
        )
      )
      serverOperation.foreach { operation =>
        serverOrdinals.update(operation, nextInteractionOrdinal)
        while serverOrdinals.size > limits.maxRecords do
          val _ = serverOrdinals.remove(serverOrdinals.head._1)
      }
      while interactionOrdinals.size > limits.maxRecords do
        val _ = interactionOrdinals.remove(interactionOrdinals.head._1)
      nextInteractionOrdinal

    def snapshot: Vector[DocumentationTraceRecord] = synchronized {
      touch()
      values
    }

    def inspectionSelection: (Option[String], Boolean) = synchronized {
      selectedInteraction -> followLatest
    }

    def selectInteraction(id: Option[String], follow: Boolean): Unit = synchronized {
      touch()
      selectedInteraction = id
      followLatest = follow
    }

    def clear(): Unit = synchronized {
      touch()
      values = Vector.empty
      bytes = 0
      interactionOrdinals.clear()
      serverOrdinals.clear()
      discardedInteractions.clear()
      nextInteractionOrdinal = 0L
      selectedInteraction = None
      followLatest = true
    }

    def discardIncomplete(): Unit = synchronized {
      values
        .flatMap(_.interactionOrdinal)
        .distinct
        .filter { ordinal =>
          val records           = values.filter(_.interactionOrdinal.contains(ordinal))
          val hasBrowserRequest = records.exists(record =>
            record.producer == TraceProducer.Browser &&
              (record.stage == "BrowserEvent" || record.stage == "OutboundFrame")
          )
          val browserComplete = records.exists(record =>
            record.producer == TraceProducer.Browser &&
              (record.stage == "InboundProcessed" || record.stage == "DomDiff")
          )
          val serverComplete = records.exists(record =>
            record.producer == TraceProducer.Server && record.stage == "FinalFrame"
          )
          val failed = records.exists(_.stage == "Crash")
          !(failed || browserComplete || (!hasBrowserRequest && serverComplete))
        }.foreach(discardInteraction)
    }

    private def encodedBytes(record: DocumentationTraceRecord): Int =
      record.toJson.getBytes(StandardCharsets.UTF_8).length
  end History

  private val active           = ConcurrentHashMap[TraceKey, RegisteredExample]()
  private val histories        = ConcurrentHashMap[TraceKey, History]()
  private val connectionEpochs = ConcurrentHashMap[String, AtomicLong]()
  private val owners           = ConcurrentHashMap[TraceKey, java.util.Set[String]]()
  private val leaseGenerations = ConcurrentHashMap[TraceKey, AtomicLong]()
  private val nextStoreAccess  = AtomicLong(0L)

  private val LeaseReleaseDelay = 5.seconds

  private def history(key: TraceKey): (History, Vector[TraceKey]) = synchronized {
    Option(histories.get(key)) match
      case Some(existing) => existing -> Vector.empty
      case None           =>
        val evicted = Vector.newBuilder[TraceKey]
        while histories.size() >= limits.maxTraces do
          histories.entrySet().iterator().asScala.minByOption(_.getValue.lastAccess).foreach {
            entry =>
              evicted += entry.getKey
              histories.remove(entry.getKey)
              active.remove(entry.getKey)
              val sessionStillStored = histories
                .keySet().asScala.exists(_.traceSession == entry.getKey.traceSession)
              if !sessionStillStored then
                val _ = connectionEpochs.remove(entry.getKey.traceSession)
          }
        val created = History()
        histories.put(key, created)
        created -> evicted.result()
  }

  def activate(session: String, topic: String, example: RegisteredExample): UIO[Unit] =
    val key = TraceKey(session, topic)
    for
      evicted <- ZIO.succeed {
                   val (_, removed) = this.synchronized {
                     val result = history(key)
                     connectionEpochs.putIfAbsent(session, AtomicLong(1L))
                     active.put(key, example)
                     result
                   }
                   removed
                 }
      _ <- ZIO.foreachDiscard(evicted)(updatesHub.publish(_).unit)
      _ <- updatesHub.publish(key).unit
    yield ()

  def deactivate(session: String, topic: String): UIO[Unit] =
    val key = TraceKey(session, topic)
    ZIO.succeed {
      this.synchronized {
        Option(histories.get(key)).foreach(_.discardIncomplete())
        active.remove(key)
      }
    }.unit *> updatesHub.publish(key).unit

  def isActive(session: String, topic: String): Boolean =
    active.containsKey(TraceKey(session, topic))

  def registered(session: String, topic: String): Option[RegisteredExample] =
    Option(active.get(TraceKey(session, topic)))

  def nextConnectionEpoch(session: String): Long =
    Option(connectionEpochs.get(session)).fold(1L)(_.incrementAndGet())

  def attach(session: String, topic: String, owner: String): UIO[Unit] =
    ZIO.succeed {
      this.synchronized {
        val key = TraceKey(session, topic)
        leaseGenerations.computeIfAbsent(key, _ => AtomicLong(0L)).incrementAndGet()
        val _ = owners.computeIfAbsent(key, _ => ConcurrentHashMap.newKeySet[String]()).add(owner)
      }
    }.unit

  def detach(session: String, topic: String, owner: String): UIO[Unit] =
    val key = TraceKey(session, topic)
    for
      generation <- ZIO.succeed {
                      this.synchronized {
                        Option(owners.get(key)).foreach { current =>
                          val _ = current.remove(owner)
                          if current.isEmpty then
                            val _ = owners.remove(key, current)
                        }
                        leaseGenerations
                          .computeIfAbsent(key, _ => AtomicLong(0L)).incrementAndGet()
                      }
                    }
      _ <- (ZIO.sleep(LeaseReleaseDelay) *>
             ZIO
               .succeed {
                 this.synchronized {
                   val lease     = Option(leaseGenerations.get(key))
                   val unchanged = lease.exists(_.get() == generation)
                   if unchanged && !owners.containsKey(key) then
                     lease.foreach(current => leaseGenerations.remove(key, current))
                     Option(histories.get(key)).foreach(_.discardIncomplete())
                     Option(active.remove(key)).nonEmpty
                   else false
                 }
               }.flatMap(removed => ZIO.when(removed)(updatesHub.publish(key).unit))).forkDaemon
    yield ()

  def appendBrowser(session: String, topic: String, batch: BrowserTraceBatch): UIO[Unit] =
    val key = TraceKey(session, topic)
    ZIO
      .succeed {
        this.synchronized {
          Option(active.get(key)).nonEmpty && Option(histories.get(key)).exists { history =>
            batch.records
              .take(MaxBrowserBatchRecords)
              .filter(_.topic == topic)
              .foldLeft(false)((stored, record) => history.appendBrowser(session, record) || stored)
          }
        }
      }.flatMap(appended => ZIO.when(appended)(updatesHub.publish(key).unit)).unit

  def appendServer(record: RuntimeTraceRecord): UIO[Unit] =
    val key = TraceKey(record.identity.traceSession, record.identity.topic)
    ZIO
      .succeed {
        this.synchronized {
          Option(active.get(key)).nonEmpty && Option(histories.get(key)).exists(
            _.appendServer(record)
          )
        }
      }.flatMap(appended => ZIO.when(appended)(updatesHub.publish(key).unit)).unit

  def records(session: String, topic: String): UIO[Vector[DocumentationTraceRecord]] =
    ZIO.succeed(Option(histories.get(TraceKey(session, topic))).fold(Vector.empty)(_.snapshot))

  def snapshot(session: String, topic: String): UIO[DocumentationTraceSnapshot] =
    ZIO.succeed {
      this.synchronized {
        val key                                 = TraceKey(session, topic)
        val history                             = Option(histories.get(key))
        val (selectedInteraction, followLatest) = history
          .map(_.inspectionSelection).getOrElse(None -> true)
        DocumentationTraceSnapshot(
          active.containsKey(key),
          history.fold(Vector.empty)(_.snapshot),
          selectedInteraction,
          followLatest
        )
      }
    }

  def selectInteraction(
    session: String,
    topic: String,
    id: Option[String],
    followLatest: Boolean
  ): UIO[Unit] =
    ZIO.succeed {
      this.synchronized {
        Option(histories.get(TraceKey(session, topic))).foreach(
          _.selectInteraction(id, followLatest)
        )
      }
    }

  def reset(session: String, topic: String): UIO[Unit] =
    val key = TraceKey(session, topic)
    ZIO.succeed(Option(histories.get(key)).foreach(_.clear())) *>
      updatesHub.publish(key).unit

  def updates(session: String, topic: String): ZStream[Any, Nothing, Unit] =
    val key = TraceKey(session, topic)
    ZStream.succeed(()) ++ ZStream.unwrapScoped(
      ZStream.fromHubScoped(updatesHub).map(_.filter(_ == key).map(_ => ()))
    )

  private def safeReference(value: Option[String]): Option[String] =
    value.filter(reference =>
      reference.nonEmpty && reference.length <= 20 && reference.forall(_.isDigit)
    )

  private def correlationReference(
    joinReference: Option[String],
    messageReference: Option[String]
  ): Option[String] =
    messageReference.map(reference =>
      s"join:${joinReference.getOrElse("none")}:reference:$reference"
    )

  private def operationKind(value: RuntimeTraceOperationKind): String = value match
    case RuntimeTraceOperationKind.Join            => "Join"
    case RuntimeTraceOperationKind.ClientEvent     => "ClientEvent"
    case RuntimeTraceOperationKind.ServerMessage   => "ServerMessage"
    case RuntimeTraceOperationKind.AsyncCompletion => "AsyncCompletion"
    case RuntimeTraceOperationKind.LivePatch       => "LivePatch"
    case RuntimeTraceOperationKind.Upload          => "Upload"
    case RuntimeTraceOperationKind.Leave           => "Leave"
    case RuntimeTraceOperationKind.Other           => "Other"

  private def stageName(value: RuntimeTraceStage): String = value match
    case RuntimeTraceStage.SocketJoin         => "SocketJoin"
    case RuntimeTraceStage.DecodedEvent       => "DecodedEvent"
    case RuntimeTraceStage.BindingResolution  => "BindingResolution"
    case RuntimeTraceStage.TypedMessage       => "TypedMessage"
    case RuntimeTraceStage.LifecycleStarted   => "LifecycleStarted"
    case RuntimeTraceStage.LifecycleCompleted => "LifecycleCompleted"
    case RuntimeTraceStage.ModelProposed      => "ModelProposed"
    case RuntimeTraceStage.RenderStarted      => "RenderStarted"
    case RuntimeTraceStage.ModelRendered      => "ModelRendered"
    case RuntimeTraceStage.RenderCompleted    => "RenderCompleted"
    case RuntimeTraceStage.TreeDiff           => "TreeDiff"
    case RuntimeTraceStage.ModelCommitted     => "ModelCommitted"
    case RuntimeTraceStage.FinalPayload       => "FinalPayload"
    case RuntimeTraceStage.FinalFrame         => "FinalFrame"
    case RuntimeTraceStage.Crash              => "Crash"
    case RuntimeTraceStage.Upload             => "Upload"

end DocumentationTraceStore

private[docs] object DocumentationTraceStore:
  def make(limits: TraceLimits = TraceLimits()): UIO[DocumentationTraceStore] =
    Hub.sliding[TraceKey](1024).map(DocumentationTraceStore(limits, _))
