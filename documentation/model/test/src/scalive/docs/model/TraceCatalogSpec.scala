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
    }
  )
