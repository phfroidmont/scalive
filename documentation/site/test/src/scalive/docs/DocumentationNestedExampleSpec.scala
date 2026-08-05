package scalive.docs

import zio.*
import zio.test.*

import scalive.docs.examples.ExampleRegistry

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
    }
  )
end DocumentationNestedExampleSpec
