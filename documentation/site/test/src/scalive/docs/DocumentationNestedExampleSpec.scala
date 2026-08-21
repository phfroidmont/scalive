package scalive.docs

import zio.*
import zio.test.*

import scalive.docs.examples.ExampleRegistry
import scalive.testing.ConnectedRender

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
                          parent <- ConnectedRender.join(
                                      DocumentationPageLiveView(page, renderer)
                                    )
                         childId = ExampleRegistry.instanceId(route, entry.descriptor.id)
                         child   <- parent.joinNested(childId)
                          joined  <- child.isJoined
                          _       <- parent.leave
                          removed <- child.isJoined
                       yield joined && !removed
                     }
                   }
      yield assertTrue(results.forall(identity))
    }
  )
end DocumentationNestedExampleSpec
