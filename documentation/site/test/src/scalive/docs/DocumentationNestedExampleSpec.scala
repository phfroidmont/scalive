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
    test("renders a captured counter operation on a separate trace viewer topic") {
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
          store <- DocumentationTraceStore.make()
          renderer = DocumentationRenderer(application, Some(store))
          trace    = DocumentationRuntimeTrace(store, session, connectionEpoch = 1L)
          parent <- SiteLiveViewHarness.join(DocumentationPageLiveView(page, renderer), trace)
          childId     = ExampleRegistry.instanceId(page.route, "counter")
          viewerId = ExampleRegistry.traceViewerInstanceId(page.route, "counter")
          child   <- parent.joinNested(childId)
          viewer  <- parent.joinNested(viewerId)
          initial <- viewer.html
          _       <- viewer.clickButton("Start capture")
          _         <- child.clickButton("Increase")
          records   <- store.records(session, child.topic)
          viewerText <- (ZIO.yieldNow *> viewer.html)
                    .repeatUntil(_.contains("data-trace-evidence=\"Updated model\""))
          stages = records.filter(_.producer == TraceProducer.Server).map(_.stage)
          viewerObserved <- store.records(session, viewer.topic)
          _              <- parent.leave
          childRemoved   <- parent.socketExists(child.topic)
          viewerRemoved  <- parent.socketExists(viewer.topic)
        yield assertTrue(
          child.topic != viewer.topic,
          initial.contains("data-live-trace-viewer=\"counter\""),
          !initial.contains("docs-live-trace-catalog"),
          !initial.contains("data-trace-provenance=\"authored\""),
          stages.contains("DecodedEvent"),
          stages.contains("BindingResolution"),
          stages.contains("TypedMessage"),
          stages.contains("ModelProposed"),
          stages.contains("ModelRendered"),
          stages.contains("TreeDiff"),
          stages.contains("ModelCommitted"),
          viewerText.contains("data-trace-provenance=\"captured\""),
          !viewerText.contains("data-trace-evidence=\"Message fields\""),
          viewerText.contains("data-trace-evidence=\"Updated model\""),
          !viewerText.contains("data-trace-evidence=\"Tree diff\""),
          !viewerText.contains("data-trace-evidence=\"Handler started\""),
          !viewerText.contains("data-trace-evidence=\"Handler completed\""),
          viewerText.contains("count</dt><dd>1"),
          viewerObserved.isEmpty,
          !childRemoved,
          !viewerRemoved
        )
        end for
      }
    }
  )
end DocumentationNestedExampleSpec
