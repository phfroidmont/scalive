package scalive.docs.model

import zio.test.*

object TraceCatalogSpec extends ZIOSpecDefault:
  override def spec = suite("TraceCatalogSpec")(
    test("defines valid lifecycle traces") {
      assertTrue(
        TraceCatalog.get("http-get").contains(TraceCatalog.HttpGet),
        TraceCatalog.get("live-socket-join").contains(TraceCatalog.LiveSocketJoin),
        TraceCatalog.validate().isEmpty,
        TraceCatalog.prose(TraceCatalog.HttpGet).contains("Disconnected HTTP render"),
        TraceCatalog.prose(TraceCatalog.LiveSocketJoin).contains("Connected LiveSocket mount")
      )
    },
    test("reports invalid participant references") {
      val invalid = TraceCatalog.HttpGet.copy(
        phases = Vector(
          TracePhase("request", "Request", Vector(TraceStep.Operation("missing", "Start", "Starts.")))
        )
      )
      assertTrue(
        TraceCatalog.validate(Vector(invalid)) ==
          Vector("trace 'http-get' operation references unknown participant 'missing'.")
      )
    },
    test("accepts semantic facts and rejects contentless evidence") {
      def trace(evidence: TraceEvidence) = TraceCatalog.HttpGet.copy(
        phases = Vector(
          TracePhase(
            "request",
            "Request",
            Vector(TraceStep.Operation("runtime", "Start", "Starts.", Some(evidence)))
          )
        )
      )

      val facts       = trace(TraceEvidence("Updated model", facts = Vector("count" -> "1")))
      val scalaValue  = trace(TraceEvidence("Updated model", scalaValue = Some("Model(count = 1)")))
      val mixedValue = trace(
        TraceEvidence(
          "Updated model",
          summary = Some("Current model"),
          facts = Vector("count" -> "1"),
          scalaValue = Some("Model(count = 1)"),
          code = Some("protocol")
        )
      )
      val contentless = trace(TraceEvidence("Measurement"))
      val blankValue  = trace(TraceEvidence("Updated model", scalaValue = Some("  ")))
      val blankCode   = trace(TraceEvidence("Protocol frame", code = Some("  ")))

      assertTrue(
        TraceCatalog.validate(Vector(facts)).isEmpty,
        TraceCatalog.validate(Vector(scalaValue)).isEmpty,
        TraceCatalog.validate(Vector(mixedValue)) ==
          Vector("trace 'http-get' Scala value evidence must not include other content."),
        TraceCatalog.validate(Vector(contentless)) ==
          Vector("trace 'http-get' evidence must have content."),
        TraceCatalog.validate(Vector(blankValue)) ==
          Vector("trace 'http-get' evidence content must not be blank."),
        TraceCatalog.validate(Vector(blankCode)) ==
          Vector("trace 'http-get' evidence content must not be blank.")
      )
    }
  )
