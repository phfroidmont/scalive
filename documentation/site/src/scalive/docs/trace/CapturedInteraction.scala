package scalive.docs.trace

import scalive.docs.xray.*

private[docs] enum CapturedInteractionState:
  case InProgress, Complete, Failed

final private[docs] case class CapturedInteractionAnchor(
  browserSequence: Option[Long],
  serverSequence: Option[Long])

final private[docs] case class CapturedInteraction(
  id: String,
  ordinal: Long,
  operationKind: String,
  reference: Option[String],
  records: Vector[DocumentationTraceRecord],
  orderingAnchor: CapturedInteractionAnchor,
  state: CapturedInteractionState,
  label: String,
  summary: String)

private[docs] object CapturedInteractionGrouper:
  def group(records: Vector[DocumentationTraceRecord]): Vector[CapturedInteraction] =
    records
      .flatMap(record => record.interactionOrdinal.map(_ -> record))
      .groupMap(_._1)(_._2)
      .toVector
      .map(captured)
      .sortBy(interaction =>
        val primary = interaction.orderingAnchor.browserSequence
          .orElse(interaction.orderingAnchor.serverSequence)
          .getOrElse(interaction.ordinal)
        (-primary, -interaction.ordinal)
      )

  private def captured(
    ordinal: Long,
    unordered: Vector[DocumentationTraceRecord]
  ): CapturedInteraction =
    val browser = unordered
      .filter(_.producer == TraceProducer.Browser).sortBy(_.producerSequence)
    val server = unordered
      .filter(_.producer == TraceProducer.Server).sortBy(_.operationRecordSequence)
    val records       = causalRecords(browser, server)
    val operationKind = server.headOption.map(_.operationKind).getOrElse("Browser")
    val typed         = records.find(_.stage == "TypedMessage").flatMap(_.value)
    val label         = typed
      .map { value =>
        val projectedType = typeName(value.typeName)
        if projectedType == "Msg" || projectedType == "Message" then value.summary
        else projectedType
      }.filter(_.trim.nonEmpty)
      .getOrElse(operationLabel(operationKind))
    val summary = typed
      .map(_.summary).filter(_.trim.nonEmpty)
      .getOrElse(s"Captured ${operationLabel(operationKind).toLowerCase} operation.")
    val hasBrowserRequest =
      browser.exists(record => record.stage == "BrowserEvent" || record.stage == "OutboundFrame")
    val browserComplete =
      browser.exists(record => record.stage == "InboundProcessed" || record.stage == "DomDiff")
    val serverComplete = server.exists(_.stage == "FinalFrame")
    val state          =
      if records.exists(_.stage == "Crash") then CapturedInteractionState.Failed
      else if browserComplete || (!hasBrowserRequest && serverComplete) then
        CapturedInteractionState.Complete
      else CapturedInteractionState.InProgress

    CapturedInteraction(
      id = s"captured-operation-$ordinal",
      ordinal = ordinal,
      operationKind = operationKind,
      reference = records.flatMap(_.messageReference).headOption,
      records = records,
      orderingAnchor = CapturedInteractionAnchor(
        browser.map(_.producerSequence).minOption,
        server.map(_.producerSequence).minOption
      ),
      state = state,
      label = label,
      summary = summary
    )
  end captured

  private def causalRecords(
    browser: Vector[DocumentationTraceRecord],
    server: Vector[DocumentationTraceRecord]
  ): Vector[DocumentationTraceRecord] =
    val inboundIndex = browser.indexWhere(_.stage == "InboundFrame")
    if inboundIndex < 0 then browser ++ server
    else browser.take(inboundIndex) ++ server ++ browser.drop(inboundIndex)

  private def typeName(qualifiedName: String): String =
    qualifiedName.split("[.$]").lastOption.filter(_.nonEmpty).getOrElse(qualifiedName)

  private def operationLabel(kind: String): String = kind match
    case "Join"            => "Socket join"
    case "ClientEvent"     => "Client event"
    case "ServerMessage"   => "Server message"
    case "AsyncCompletion" => "Async completion"
    case "LivePatch"       => "Live patch"
    case "Upload"          => "Upload"
    case "Leave"           => "Socket leave"
    case "Browser"         => "Browser event"
    case _                 => "Runtime operation"
end CapturedInteractionGrouper
