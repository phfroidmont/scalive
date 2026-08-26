package scalive.docs

import zio.*
import zio.test.*

import scalive.docs.examples.ExampleRegistry
import scalive.testing.ConnectedRender

object DocumentationNestedExampleSpec extends ZIOSpecDefault:
  override def spec = suite("DocumentationNestedExampleSpec")(
    test("mounts every canonical nested example") {
      for
        bundle <- ZIO
                    .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                    .mapError(new IllegalArgumentException(_))
        application <- ZIO
                         .fromEither(DocumentationApplication.from(bundle))
                         .mapError(new IllegalArgumentException(_))
        _ <- ZIO.foreachDiscard(ExampleRegistry.entries) { entry =>
               ZIO
                 .scoped {
                   val route = s"/examples/${entry.descriptor.id}"
                   for
                     page <- ZIO
                               .fromOption(application.page(route))
                               .orElseFail(new NoSuchElementException(route))
                     renderer = DocumentationRenderer(application)
                     parent <- ConnectedRender.join(
                                 DocumentationPageLiveView(page, renderer)
                               )
                     childId <- ZIO.succeed(ExampleRegistry.instanceId(route, entry.descriptor.id))
                     child   <- parent.joinNested(childId)
                     joined  <- child.isJoined
                     _       <- ZIO
                            .fail(
                              new AssertionError(
                                s"Nested example did not join: ${entry.descriptor.id}"
                              )
                            )
                            .unless(joined)
                   yield ()
                 }.mapError(error =>
                   new RuntimeException(s"Failed example ${entry.descriptor.id}", error)
                 )
             }
      yield assertCompletes
    },
    test("leaving a documentation page tears down its nested example") {
      val entry = ExampleRegistry.entries.find(_.descriptor.id == "counter").get
      val route = s"/examples/${entry.descriptor.id}"
      ZIO.scoped {
        for
          bundle <- ZIO
                      .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                      .mapError(new IllegalArgumentException(_))
          application <- ZIO
                           .fromEither(DocumentationApplication.from(bundle))
                           .mapError(new IllegalArgumentException(_))
          page <- ZIO
                    .fromOption(application.page(route))
                    .orElseFail(new NoSuchElementException(route))
          parent <- ConnectedRender.join(
                      DocumentationPageLiveView(page, DocumentationRenderer(application))
                    )
          child  <- parent.joinNested(ExampleRegistry.instanceId(route, entry.descriptor.id))
          _      <- parent.leave
          joined <- child.isJoined
        yield assertTrue(!joined)
      }
    }
  )
end DocumentationNestedExampleSpec
