package scalive.docs.trace

import scala.collection.mutable

import zio.json.ast.Json

import scalive.docs.xray.*

private[docs] enum CapturedInteractionState:
  case InProgress, Complete, Failed

final private[docs] case class CapturedInteractionAnchor(
  browserSequence: Option[Long],
  serverSequence: Option[Long])

final private[docs] case class CapturedInteraction(
  id: String,
  reference: Option[String],
  records: Vector[DocumentationTraceRecord],
  orderingAnchor: CapturedInteractionAnchor,
  state: CapturedInteractionState,
  label: String,
  summary: String)

private[docs] object CapturedInteractionGrouper:
  final private case class ServerKey(
    traceSession: String,
    connectionEpoch: Option[Long],
    socketEpoch: Option[Long],
    topic: String,
    operationSequence: Long)

  final private case class BrowserGroup(
    reference: String,
    occurrence: Int,
    anchor: Long,
    records: Vector[DocumentationTraceRecord])

  final private case class ServerGroup(
    reference: Option[String],
    occurrence: Option[Int],
    anchor: Long,
    key: ServerKey,
    records: Vector[DocumentationTraceRecord])

  def group(records: Vector[DocumentationTraceRecord]): Vector[CapturedInteraction] =
    val browserGroups       = groupBrowserRecords(records)
    val serverGroups        = groupServerRecords(records)
    val browserByOccurrence =
      browserGroups.map(group => (group.reference, group.occurrence) -> group).toMap
    val serverByOccurrence =
      serverGroups.flatMap(group => group.reference.zip(group.occurrence).map(_ -> group)).toMap
    val pairedKeys = (browserByOccurrence.keySet ++ serverByOccurrence.keySet).toVector

    val paired = pairedKeys.map { key =>
      captured(browserByOccurrence.get(key), serverByOccurrence.get(key), Some(key._1), key._2)
    }
    val unreferencedServer = serverGroups
      .filter(_.reference.isEmpty)
      .map(group => captured(None, Some(group), None, occurrence = 1))

    (paired ++ unreferencedServer).sortBy { interaction =>
      val primary = interaction.orderingAnchor.browserSequence
        .orElse(interaction.orderingAnchor.serverSequence)
        .getOrElse(0L)
      val server = interaction.orderingAnchor.serverSequence.getOrElse(0L)
      (-primary, -server)
    }

  private def groupBrowserRecords(records: Vector[DocumentationTraceRecord]): Vector[BrowserGroup] =
    final class Pending(val reference: String, val operation: Long, val anchor: Long):
      val records = mutable.ArrayBuffer.empty[DocumentationTraceRecord]

    val pending = mutable.ArrayBuffer.empty[Pending]
    records
      .filter(_.producer == TraceProducer.Browser)
      .sortBy(_.producerSequence)
      .foreach { record =>
        record.messageReference.foreach { reference =>
          val matching = pending.reverseIterator.find(group =>
            group.reference == reference && group.operation == record.operationSequence
          )
          if record.stage == "BrowserEvent" then
            val created = Pending(reference, record.operationSequence, record.producerSequence)
            created.records += record
            pending += created
          else
            matching match
              case Some(group)                      => group.records += record
              case None if isClientOutbound(record) =>
                val created = Pending(reference, record.operationSequence, record.producerSequence)
                created.records += record
                pending += created
              case None => ()
        }
      }

    val occurrences = mutable.Map.empty[String, Int].withDefaultValue(0)
    pending.toVector.map { group =>
      val occurrence = occurrences(group.reference) + 1
      occurrences.update(group.reference, occurrence)
      BrowserGroup(group.reference, occurrence, group.anchor, group.records.toVector)
    }
  end groupBrowserRecords

  private def groupServerRecords(records: Vector[DocumentationTraceRecord]): Vector[ServerGroup] =
    val grouped = records
      .filter(record =>
        record.producer == TraceProducer.Server && record.operationKind == "ClientEvent"
      )
      .groupBy(record =>
        ServerKey(
          record.traceSession,
          record.connectionEpoch,
          record.socketEpoch,
          record.topic,
          record.operationSequence
        )
      )
      .toVector
      .map { case (key, values) => key -> values.sortBy(_.operationRecordSequence) }
      .sortBy { case (_, values) => values.map(_.producerSequence).min }

    val occurrences = mutable.Map.empty[String, Int].withDefaultValue(0)
    grouped.map { case (key, values) =>
      val reference  = values.flatMap(_.messageReference).headOption
      val occurrence = reference.map { value =>
        val next = occurrences(value) + 1
        occurrences.update(value, next)
        next
      }
      ServerGroup(reference, occurrence, values.map(_.producerSequence).min, key, values)
    }

  private def captured(
    browser: Option[BrowserGroup],
    server: Option[ServerGroup],
    reference: Option[String],
    occurrence: Int
  ): CapturedInteraction =
    val records =
      causalRecords(browser.toVector.flatMap(_.records), server.toVector.flatMap(_.records))
    val typed            = records.find(_.stage == "TypedMessage").flatMap(_.value)
    val (label, summary) = typed.fold("Counter interaction" -> "A captured counter client event.")(
      messageLabel
    )
    val id = reference match
      case Some(value) => s"counter-interaction-ref-$value-$occurrence"
      case None        =>
        val key = server.get.key
        s"counter-interaction-server-${key.connectionEpoch.getOrElse(0L)}-${key.socketEpoch.getOrElse(0L)}-${key.operationSequence}"
    val state =
      if records.exists(_.stage == "Crash") then CapturedInteractionState.Failed
      else if records.exists(record =>
          record.stage == "InboundProcessed" || record.stage == "DomDiff"
        ) ||
        (browser.isEmpty && records.exists(_.stage == "FinalFrame"))
      then CapturedInteractionState.Complete
      else CapturedInteractionState.InProgress

    CapturedInteraction(
      id,
      reference,
      records,
      CapturedInteractionAnchor(browser.map(_.anchor), server.map(_.anchor)),
      state,
      label,
      summary
    )
  end captured

  private def causalRecords(
    browser: Vector[DocumentationTraceRecord],
    server: Vector[DocumentationTraceRecord]
  ): Vector[DocumentationTraceRecord] =
    val orderedBrowser = browser.sortBy(_.producerSequence)
    val inboundIndex   = orderedBrowser.indexWhere(_.stage == "InboundFrame")
    if inboundIndex < 0 then orderedBrowser ++ server
    else orderedBrowser.take(inboundIndex) ++ server ++ orderedBrowser.drop(inboundIndex)

  private def messageLabel(value: DocumentationTraceValue): (String, String) =
    val name  = value.typeName.split("[.$]").lastOption.getOrElse(value.typeName)
    val label = name match
      case "Increment" => "Increase counter"
      case "Decrement" => "Decrease counter"
      case "Reset"     => "Reset counter"
      case _           => name
    val summary =
      Option(value.summary).filter(_.trim.nonEmpty).getOrElse(s"Projected message: $name")
    label -> summary

  private def isClientOutbound(record: DocumentationTraceRecord): Boolean =
    record.stage == "OutboundFrame" && record.protocol.exists {
      case Json.Obj(fields) =>
        fields.exists { case (name, value) =>
          name == "event" && value == Json.Str("event")
        }
      case _ => false
    }
end CapturedInteractionGrouper
