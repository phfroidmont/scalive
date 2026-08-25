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
               .unless(page.metadata.section == Section.Learn && page.source.isInstanceOf[PageSource.Authored])
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
    test("renders the first Learn page with progress and only a next link") {
      for
        application <- loadApplication
        pages        = learnPages(application)
        document    <- render(application, pages.head)
        navigation  = document.selectFirst("nav.docs-learn-progress[aria-label='Learn progress']")
        next        = navigation.selectFirst("a.docs-learn-progress-next")
      yield assertTrue(
        navigation.select(".docs-learn-progress-count").text() == s"1 of ${pages.size}",
        navigation.select("a.docs-learn-progress-previous").isEmpty,
        navigation.select(".docs-learn-progress-separator").isEmpty,
        isLiveLink(next, pages(1)),
        next.select(".docs-learn-progress-direction").text() == "Next",
        next.select("strong").text() == pages(1).title,
        navigation.nextElementSibling().hasClass("docs-page-links")
      )
    },
    test("renders a middle Learn page with previous and next links") {
      for
        application <- loadApplication
        pages        = learnPages(application)
        index        = pages.size / 2
        document    <- render(application, pages(index))
        navigation  = document.selectFirst("nav.docs-learn-progress")
        previous    = navigation.selectFirst("a.docs-learn-progress-previous")
        next        = navigation.selectFirst("a.docs-learn-progress-next")
      yield assertTrue(
        pages.size >= 3,
        navigation.select(".docs-learn-progress-count").text() == s"${index + 1} of ${pages.size}",
        isLiveLink(previous, pages(index - 1)),
        previous.attr("aria-label") == s"Previous: ${pages(index - 1).title}",
        previous.select(".docs-learn-progress-direction").text() == "Previous",
        previous.select("strong").text() == pages(index - 1).title,
        navigation.select(".docs-learn-progress-separator[aria-hidden=true]").size() == 1,
        isLiveLink(next, pages(index + 1)),
        next.attr("aria-label") == s"Next: ${pages(index + 1).title}",
        next.select(".docs-learn-progress-direction").text() == "Next",
        next.select("strong").text() == pages(index + 1).title
      )
    },
    test("renders the last Learn page with only a previous link") {
      for
        application <- loadApplication
        pages        = learnPages(application)
        index        = pages.size - 1
        document    <- render(application, pages(index))
        navigation  = document.selectFirst("nav.docs-learn-progress")
        previous    = navigation.selectFirst("a.docs-learn-progress-previous")
      yield assertTrue(
        navigation.select(".docs-learn-progress-count").text() == s"${pages.size} of ${pages.size}",
        isLiveLink(previous, pages(index - 1)),
        previous.select(".docs-learn-progress-direction").text() == "Previous",
        previous.select("strong").text() == pages(index - 1).title,
        navigation.select(".docs-learn-progress-separator").isEmpty,
        navigation.select("a.docs-learn-progress-next").isEmpty
      )
    }
  )
end LearnProgressNavigationSpec
