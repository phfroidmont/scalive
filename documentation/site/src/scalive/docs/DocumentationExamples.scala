package scalive.docs

import java.util.Locale

import zio.http.URL

import scalive.*
import scalive.codecs.StringAsIsEncoder
import scalive.docs.model.*

final private case class DocumentationExamplesModel(topic: Option[String])

final private[docs] class DocumentationExamplesLiveView(
  page: Page,
  application: DocumentationApplication,
  renderer: DocumentationRenderer)
    extends LiveView.Routed.Eventless[DocumentationExamplesModel, Option[String]]:
  private val ariaCurrent = htmlAttr("aria-current", StringAsIsEncoder)
  private val topics      = application.bundle.examples
    .flatMap(_.descriptor.topics)
    .groupBy(ExampleTopic.key)
    .toVector.collect {
      case (key, values) if key.nonEmpty => key -> ExampleTopic.label(values.head)
    }
    .sortBy(_._2.toLowerCase(Locale.ROOT))

  def mount(
    params: Option[String],
    ctx: MountContext
  ): LiveIO[DocumentationExamplesModel] =
    LiveIO.succeed(DocumentationExamplesModel(params.map(ExampleTopic.key).filter(_.nonEmpty)))

  override def handleParams(
    model: DocumentationExamplesModel,
    params: Option[String],
    url: URL,
    ctx: ParamsContext
  ): LiveIO[DocumentationExamplesModel] =
    LiveIO.succeed(DocumentationExamplesModel(params.map(ExampleTopic.key).filter(_.nonEmpty)))

  override def pageTitle(model: DocumentationExamplesModel): Option[String] =
    Some(s"${page.metadata.title} | Scalive")

  def render(model: DocumentationExamplesModel): HtmlElement[Nothing] =
    val examples = model.topic.fold(application.bundle.examples) { topic =>
      application.bundle.examples.filter(example =>
        example.descriptor.topics.exists(value => ExampleTopic.key(value) == topic)
      )
    }
    val listing =
      if examples.isEmpty then
        div(
          cls := "docs-example-catalog-empty",
          p("No examples match this topic."),
          link.pushPatch(
            DocumentationApplication.ExamplesCatalogRoute.location(None),
            "Show all examples"
          )
        )
      else
        div(
          cls := "docs-example-card-grid",
          examples.map(exampleCard)
        )
    articleTag(
      cls                         := "docs-content docs-prose docs-examples-catalog",
      dataAttr("example-catalog") := "",
      h1(page.metadata.title),
      page.content.map(renderer.renderBlock(page.route)),
      topicFilters(model.topic),
      p(
        cls  := "docs-example-catalog-status",
        role := "status",
        if examples.size == 1 then "1 example" else s"${examples.size} examples"
      ),
      listing,
      renderer.pageLinks(page)
    )
  end render

  private def topicFilters(active: Option[String]): HtmlElement[Nothing] =
    navTag(
      cls        := "docs-example-topic-filters",
      aria.label := "Filter examples by topic",
      topicLink(None, "All examples", active.isEmpty),
      topics.map { case (key, label) => topicLink(Some(key), label, active.contains(key)) }
    )

  private def topicLink(
    topic: Option[String],
    label: String,
    active: Boolean
  ): HtmlElement[Nothing] =
    val mods = Vector[Mod[Nothing]](
      dataAttr("example-topic-filter") := topic.getOrElse("all")
    ) ++ Option.when(active)((ariaCurrent := "page"): Mod[Nothing]).toVector ++
      Vector(Mod.Content.Text(label))
    link.pushPatch(
      DocumentationApplication.ExamplesCatalogRoute.location(topic),
      mods*
    )

  private def exampleCard(example: ExampleDefinition): HtmlElement[Nothing] =
    val descriptor = example.descriptor
    val route      = s"/examples/${descriptor.id}"
    val location   = application.location(route).getOrElse {
      throw new IllegalArgumentException(s"Missing validated example route: $route")
    }
    val source = application.bundle.apiReference.metadata.sourceLink(
      ApiSource.Repository(example.source.region)
    )
    articleTag(
      cls                      := "docs-example-card",
      dataAttr("example-card") := descriptor.id,
      h2(link.pushNavigate(location, descriptor.title)),
      p(descriptor.description),
      ul(
        cls := "docs-example-card-topics",
        descriptor.topics.map { topic =>
          li(
            link.pushPatch(
              DocumentationApplication.ExamplesCatalogRoute.location(Some(ExampleTopic.key(topic))),
              topic
            )
          )
        }
      ),
      footerTag(
        link.pushNavigate(location, "Open example"),
        a(href := source.url, "View source")
      )
    )
  end exampleCard
end DocumentationExamplesLiveView
