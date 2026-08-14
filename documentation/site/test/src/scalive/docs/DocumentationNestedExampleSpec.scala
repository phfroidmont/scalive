package scalive.docs

import zio.*
import zio.test.*

import scalive.docs.examples.ExampleRegistry
import scalive.docs.xray.*

object DocumentationNestedExampleSpec extends ZIOSpecDefault:
  override def spec = suite("DocumentationNestedExampleSpec")(
    test("mounts and terminates every canonical nested example") {
      for
        bundle <- ZIO
                    .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                    .mapError(new IllegalArgumentException(_))
        application <- ZIO
                         .fromEither(DocumentationApplication.from(bundle))
                         .mapError(new IllegalArgumentException(_))
        results <- ZIO.foreach(ExampleRegistry.entries) { entry =>
                     ZIO.scoped {
                       val route = s"/examples/${entry.descriptor.id}"
                       for
                         page <- ZIO
                                   .fromOption(application.page(route))
                                   .orElseFail(new NoSuchElementException(route))
                         renderer = DocumentationRenderer(application)
                         parent <- SiteLiveViewHarness.join(
                                     DocumentationPageLiveView(page, renderer)
                                   )
                         childId = ExampleRegistry.instanceId(route, entry.descriptor.id)
                         child   <- parent.joinNested(childId)
                         joined  <- parent.socketExists(child.topic)
                         _       <- parent.leave
                         removed <- parent.socketExists(child.topic)
                       yield joined && !removed
                     }
                   }
      yield assertTrue(results.forall(identity))
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
                    .fromOption(application.page("/examples/counter"))
                    .orElseFail(new NoSuchElementException("/examples/counter"))
          store    <- DocumentationTraceStore.make()
          renderer  = DocumentationRenderer(application, Some(store))
          trace     = DocumentationRuntimeTrace(store, session, connectionEpoch = 1L)
          parent   <- SiteLiveViewHarness.join(DocumentationPageLiveView(page, renderer), trace)
          childId   = ExampleRegistry.instanceId(page.route, "counter")
          inspectorId = ExampleRegistry.inspectorInstanceId(page.route, "counter")
          child     <- parent.joinNested(childId)
          inspector <- parent.joinNested(inspectorId)
           _         <- inspector.clickButton("Start tracing")
          _         <- child.clickButton("Increase")
           records   <- store.records(session, child.topic)
           inspectorText <- (ZIO.yieldNow *> inspector.html)
                              .repeatUntil(_.contains("count = 1"))
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
           inspectorText.contains("CounterExample.Msg"),
           inspectorText.contains("count = 1"),
           inspectorText.contains("data-xray-summary-order=\"2\""),
           inspectorText.contains("data-xray-summary-order=\"3\""),
           inspectorObserved.isEmpty,
          !childRemoved,
          !inspectorRemoved
        )
      }
    }
  )
end DocumentationNestedExampleSpec
