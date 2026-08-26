package scalive.docs

import zio.*
import zio.test.*

import scalive.*
import scalive.docs.examples.ExampleRegistry
import scalive.docs.model.*
import scalive.testing.ConnectedRender

object DocumentationHomeSpec extends ZIOSpecDefault:
  private def loadApplication: Task[DocumentationApplication] =
    for
      bundle <- ZIO
                  .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                  .mapError(new IllegalArgumentException(_))
      application <- ZIO
                       .fromEither(DocumentationApplication.from(bundle))
                       .mapError(new IllegalArgumentException(_))
    yield application

  override def spec = suite("DocumentationHomeSpec")(
    test("renders a decorative two-plane mark without clipping") {
      ZIO.scoped {
        val view = new LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          override def view(model: Signal[Unit]) = DocumentationBrand.mark("mark")
        for
          connected <- ConnectedRender.join(view)
          rendered  <- connected.html
        yield assertTrue(
          rendered.contains("viewBox=\"0 0 96 96\""),
          rendered.contains("aria-hidden=\"true\""),
          rendered.sliding(5).count(_ == "<path") == 2,
          !rendered.contains("mask"),
          !rendered.contains("clip-path")
        )
      }
    },
    test("extracts the complete authored homepage contract") {
      val result = for
        bundle <- GeneratedDocumentation.load(getClass.getClassLoader)
        page   <- bundle.pages.find(_.route == "/").toRight("missing homepage")
        home   <- HomePageContent.from(page)
      yield home

      assertTrue(
        result.exists(_.principles.items.size == 3),
        result.exists(_.example.id == "counter"),
        result.exists(_.howHeading.id == "how-it-works"),
        result.exists(_.workflow.items.size == 4),
        result.exists(_.whyHeading.id == "why-scalive"),
        result.exists(_.stack.items.size == 5),
        result.exists(_.startHeading.id == "start-building"),
        result.exists(_.alphaNote.kind == CalloutKind.Info)
      )
    },
    test("rejects missing, reordered, and wrong-kind homepage blocks") {
      val result = for
        bundle <- GeneratedDocumentation.load(getClass.getClassLoader)
        page   <- bundle.pages.find(_.route == "/").toRight("missing homepage")
      yield
        val exampleIndex    = 3
        val howHeadingIndex = 5
        val whyHeadingIndex = 8
        val missingSection = HomePageContent.from(
          page.copy(content = page.content.patch(howHeadingIndex, Nil, 1))
        )
        val reordered = HomePageContent.from(
          page.copy(
            content = page.content
              .updated(howHeadingIndex, page.content(whyHeadingIndex))
              .updated(whyHeadingIndex, page.content(howHeadingIndex))
          )
        )
        val wrongKind = HomePageContent.from(
          page.copy(content = page.content.updated(exampleIndex, Block.Rule))
        )
        val duplicated = HomePageContent.from(
          page.copy(
            content = page.content.patch(
              exampleIndex,
              Vector(page.content(exampleIndex), page.content(exampleIndex)),
              1
            )
          )
        )
        assertTrue(
          missingSection.isLeft,
          reordered.isLeft,
          wrongKind.isLeft,
          duplicated.isLeft
        )

      result.fold(error => assertTrue(error.isEmpty), identity)
    },
    test("mounts an isolated compact counter and terminates it with the homepage") {
      ZIO.scoped {
        for
          application <- loadApplication
          page <- ZIO
                    .fromOption(application.page("/"))
                    .orElseFail(new NoSuchElementException("/"))
          renderer = DocumentationRenderer(application)
          parent <- ConnectedRender.join(
                      DocumentationHomeLiveView(page, application.homeContent, application, renderer)
                    )
          childId = ExampleRegistry.instanceId("/", "counter")
          child  <- parent.joinNested(childId)
          before <- child.text("[role=status] strong")
          _      <- child.clickButton("Increase")
          after  <- child.text("[role=status] strong")
          html   <- parent.html
          _      <- parent.leave
          alive  <- child.isJoined
        yield assertTrue(
          before == "0",
          after == "1",
          html.contains("docs-home-hero"),
          html.contains("docs-home-workflow"),
          html.contains("docs-home-start"),
          html.contains("id=\"example-counter\""),
          html.contains("data-example=\"counter\""),
          !html.contains("data-live-trace-viewer"),
          !alive,
          childId != ExampleRegistry.instanceId("/examples/counter", "counter")
        )
      }
    }
  )
end DocumentationHomeSpec
