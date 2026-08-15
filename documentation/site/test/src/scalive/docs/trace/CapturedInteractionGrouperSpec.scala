package scalive.docs.trace

import zio.test.*

import scalive.docs.xray.*

object CapturedInteractionGrouperSpec extends ZIOSpecDefault:
  private val Session = "trace-session"
  private val Topic   = "lv:counter"

  override def spec = suite("CapturedInteractionGrouperSpec")(
    test("groups repeated references by occurrence without crossing socket epochs") {
      val records = Vector(
        browser(1, 2, 2, "BrowserEvent"),
        browser(2, 2, 2, "OutboundFrame"),
        server(1, 1, 1, 2, 2, "TypedMessage", Some(message("Increment"))),
        server(2, 1, 1, 2, 2, "FinalFrame"),
        browser(3, 2, 2, "InboundFrame"),
        browser(4, 2, 2, "DomDiff"),
        browser(5, 2, 2, "BrowserEvent"),
        server(3, 2, 1, 2, 2, "TypedMessage", Some(message("Reset")))
      )

      val interactions = CapturedInteractionGrouper.group(records)

      assertTrue(
        interactions.map(_.id) == Vector(
          "counter-interaction-ref-2-2",
          "counter-interaction-ref-2-1"
        ),
        interactions.map(_.label) == Vector("Reset counter", "Increase counter"),
        interactions.map(_.state) == Vector(
          CapturedInteractionState.InProgress,
          CapturedInteractionState.Complete
        ),
        interactions.head.records.filter(_.producer == TraceProducer.Server).forall(
          _.connectionEpoch.contains(2L)
        ),
        interactions(1).records.filter(_.producer == TraceProducer.Server).forall(
          _.connectionEpoch.contains(1L)
        )
      )
    },
    test("keeps IDs and ordering anchors stable as an interaction is appended") {
      val initial = Vector(browser(10, 7, 7, "BrowserEvent"))
      val before  = CapturedInteractionGrouper.group(initial).head
      val after = CapturedInteractionGrouper.group(
        initial ++ Vector(
          browser(11, 7, 7, "OutboundFrame"),
          server(20, 1, 3, 8, 7, "TypedMessage", Some(message("Decrement"))),
          server(21, 1, 3, 8, 7, "FinalFrame"),
          browser(12, 7, 7, "DomDiff")
        )
      ).head

      assertTrue(
        before.id == after.id,
        before.orderingAnchor.browserSequence == after.orderingAnchor.browserSequence,
        before.state == CapturedInteractionState.InProgress,
        after.state == CapturedInteractionState.Complete,
        after.label == "Decrease counter"
      )
    },
    test("completes when the browser processes a response without a DOM patch") {
      val received = Vector(
        browser(1, 3, 3, "BrowserEvent"),
        browser(2, 3, 3, "OutboundFrame"),
        server(1, 1, 1, 3, 3, "TypedMessage", Some(message("Reset"))),
        server(2, 1, 1, 3, 3, "TreeDiff"),
        server(3, 1, 1, 3, 3, "FinalFrame"),
        browser(3, 3, 3, "InboundFrame")
      )
      val before    = CapturedInteractionGrouper.group(received).head
      val completed = CapturedInteractionGrouper.group(received :+ browser(4, 3, 3, "InboundProcessed")).head
      val failed = CapturedInteractionGrouper
        .group(received ++ Vector(browser(4, 3, 3, "InboundProcessed"), server(4, 1, 1, 3, 3, "Crash")))
        .head

      assertTrue(
        before.state == CapturedInteractionState.InProgress,
        completed.state == CapturedInteractionState.Complete,
        !completed.records.exists(_.stage == "DomDiff"),
        failed.state == CapturedInteractionState.Failed
      )
    },
    test("preserves server-only failures and gives failure precedence") {
      val interaction = CapturedInteractionGrouper.group(
        Vector(
          server(1, 4, 2, 9, 12, "DecodedEvent"),
          server(2, 4, 2, 9, 12, "FinalFrame"),
          server(3, 4, 2, 9, 12, "Crash")
        )
      ).head

      assertTrue(
        interaction.reference.contains("12"),
        interaction.records.size == 3,
        interaction.state == CapturedInteractionState.Failed,
        interaction.orderingAnchor.browserSequence.isEmpty,
        interaction.orderingAnchor.serverSequence.contains(1L)
      )
    }
  )

  private def browser(
    sequence: Long,
    operation: Long,
    reference: Long,
    stage: String
  ): DocumentationTraceRecord =
    DocumentationTraceRecord(
      TraceProducer.Browser,
      sequence,
      Session,
      None,
      None,
      Topic,
      Some("1"),
      Some(reference.toString),
      operation,
      "Browser",
      sequence,
      stage,
      stage,
      None,
      None,
      None
    )

  private def server(
    producerSequence: Long,
    connectionEpoch: Long,
    socketEpoch: Long,
    operation: Long,
    reference: Long,
    stage: String,
    value: Option[DocumentationTraceValue] = None
  ): DocumentationTraceRecord =
    DocumentationTraceRecord(
      TraceProducer.Server,
      producerSequence,
      Session,
      Some(connectionEpoch),
      Some(socketEpoch),
      Topic,
      Some("1"),
      Some(reference.toString),
      operation,
      "ClientEvent",
      producerSequence,
      stage,
      stage,
      value,
      None,
      None
    )

  private def message(name: String): DocumentationTraceValue =
    DocumentationTraceValue(s"scalive.docs.examples.CounterExample.Msg.$name", s"$name message", Vector.empty)
end CapturedInteractionGrouperSpec
