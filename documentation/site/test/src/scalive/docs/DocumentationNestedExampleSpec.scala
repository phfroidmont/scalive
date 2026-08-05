package scalive.docs

import zio.*
import zio.test.*

import scalive.docs.examples.ExampleRegistry
import scalive.docs.xray.*

object DocumentationNestedExampleSpec extends ZIOSpecDefault:
  private val Count = "[role=status] strong"

  override def spec = suite("DocumentationNestedExampleSpec")(
    test("joins the counter and terminates it when the documentation page leaves") {
      ZIO.scoped {
        for
          bundle <- ZIO
                      .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                      .mapError(new IllegalArgumentException(_))
          application <- ZIO
                           .fromEither(DocumentationApplication.from(bundle))
                           .mapError(new IllegalArgumentException(_))
          page <- ZIO
                    .fromOption(application.page("/examples"))
                    .orElseFail(new NoSuchElementException("/examples"))
          renderer = DocumentationRenderer(application)
          parent   <- SiteLiveViewHarness.join(DocumentationPageLiveView(page, renderer))
          childId   = ExampleRegistry.instanceId(page.route, "counter")
          child    <- parent.joinNested(childId)
          initial  <- child.text(Count)
          _        <- child.clickButton("Increase")
          changed  <- child.text(Count)
          _        <- child.clickButton("Reset")
          reset    <- child.text(Count)
          joined   <- parent.socketExists(child.topic)
          _        <- parent.leave
          removed  <- parent.socketExists(child.topic)
        yield assertTrue(
          initial == "0",
          changed == "1",
          reset == "0",
          joined,
          !removed
        )
      }
    },
    test("captures a counter operation on a separate inspector topic") {
      ZIO.scoped {
        val session = "01234567-89ab-cdef-0123-456789abcdef"
        for
          bundle <- ZIO
                      .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                      .mapError(new IllegalArgumentException(_))
          application <- ZIO
                           .fromEither(DocumentationApplication.from(bundle))
                           .mapError(new IllegalArgumentException(_))
          page <- ZIO
                    .fromOption(application.page("/examples"))
                    .orElseFail(new NoSuchElementException("/examples"))
          store    <- DocumentationTraceStore.make()
          renderer  = DocumentationRenderer(application, Some(store))
          trace     = DocumentationRuntimeTrace(store, session, connectionEpoch = 1L)
          parent   <- SiteLiveViewHarness.join(DocumentationPageLiveView(page, renderer), trace)
          childId   = ExampleRegistry.instanceId(page.route, "counter")
          inspectorId = ExampleRegistry.inspectorInstanceId(page.route, "counter")
          child     <- parent.joinNested(childId)
          inspector <- parent.joinNested(inspectorId)
          _         <- inspector.clickButton("Enable X-ray")
          _         <- child.clickButton("Increase")
          records   <- store.records(session, child.topic)
          inspectorText <- (ZIO.yieldNow *> inspector.html)
                             .repeatUntil(_.contains("Committed model"))
          stages = records.filter(_.producer == TraceProducer.Server).map(_.stage)
          inspectorObserved <- store.records(session, inspector.topic)
          _                  <- parent.leave
          childRemoved       <- parent.socketExists(child.topic)
          inspectorRemoved   <- parent.socketExists(inspector.topic)
        yield assertTrue(
          child.topic != inspector.topic,
          stages.contains("DecodedEvent"),
          stages.contains("BindingResolution"),
          stages.contains("TypedMessage"),
          stages.contains("ModelProposed"),
          stages.contains("ModelRendered"),
          stages.contains("TreeDiff"),
          stages.contains("ModelCommitted"),
          inspectorText.contains("Committed model"),
          inspectorObserved.isEmpty,
          !childRemoved,
          !inspectorRemoved
        )
      }
    }
  )
end DocumentationNestedExampleSpec
