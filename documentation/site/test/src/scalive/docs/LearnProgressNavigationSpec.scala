package scalive.docs

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import zio.*
import zio.test.*

import scalive.docs.model.{NavigationItem, PageSource, Section}
import scalive.testing.ConnectedRender

object LearnProgressNavigationSpec extends ZIOSpecDefault:
  private def loadApplication: Task[DocumentationApplication] =
    for
      bundle <- ZIO
                  .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                  .mapError(new IllegalArgumentException(_))
      application <- ZIO
                       .fromEither(DocumentationApplication.from(bundle))
                       .mapError(new IllegalArgumentException(_))
    yield application

  private def learnPages(application: DocumentationApplication): Vector[NavigationItem] =
    val root = application.bundle.navigation.items.find(_.section == Section.Learn).get
    root +: root.children

  private def render(
    application: DocumentationApplication,
    item: NavigationItem
  ): Task[Document] =
    ZIO.scoped {
      for
        page <- ZIO
                  .fromOption(application.page(item.route))
                  .orElseFail(new NoSuchElementException(item.route))
        _ <- ZIO
               .fail(new IllegalArgumentException(s"Expected authored Learn page: ${item.route}"))
               .unless(
                 page.metadata.section == Section.Learn && page.source
                   .isInstanceOf[PageSource.Authored]
               )
        connected <- ConnectedRender.join(
                       DocumentationPageLiveView(page, DocumentationRenderer(application))
                     )
        html <- connected.html
      yield Jsoup.parse(html)
    }

  private def isLiveLink(element: org.jsoup.nodes.Element, item: NavigationItem): Boolean =
    element.attr("href") == item.route &&
      element.attr("data-phx-link") == "redirect" &&
      element.attr("data-phx-link-state") == "push"

  override def spec = suite("LearnProgressNavigationSpec")(
    test("links each Learn boundary to its semantic neighbors") {
      for
        application <- loadApplication
        pages = learnPages(application)
        _ <- ZIO
               .fail(new AssertionError("Learn progress requires at least three pages")).unless(
                 pages.size >= 3
               )
        indices = Vector(0, pages.size / 2, pages.size - 1)
        _ <- ZIO.foreachDiscard(indices) { index =>
               for
                 document <- render(application, pages(index))
                 navigation       = document.selectFirst("nav[aria-label='Learn progress']")
                 previous         = Option(navigation.selectFirst("a.docs-learn-progress-previous"))
                 next             = Option(navigation.selectFirst("a.docs-learn-progress-next"))
                 expectedPrevious = pages.lift(index - 1)
                 expectedNext     = pages.lift(index + 1)
                 validPrevious    = previous.zip(expectedPrevious).forall(isLiveLink) &&
                                   previous.isDefined == expectedPrevious.isDefined
                 validNext = next.zip(expectedNext).forall(isLiveLink) &&
                               next.isDefined == expectedNext.isDefined
                 validCount = navigation.select(".docs-learn-progress-count").text() ==
                                s"${index + 1} of ${pages.size}"
                 _ <- ZIO
                        .fail(
                          new AssertionError(
                            s"Invalid Learn progress navigation: ${pages(index).route}"
                          )
                        )
                        .unless(validPrevious && validNext && validCount)
               yield ()
             }
      yield assertCompletes
    }
  )
end LearnProgressNavigationSpec
