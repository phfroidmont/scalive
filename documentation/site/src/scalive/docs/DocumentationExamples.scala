package scalive.docs

import java.util.Locale

import zio.http.URL
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.codecs.StringAsIsEncoder
import scalive.docs.model.*

final private[docs] case class DocumentationExamplesParams(
  q: Option[String],
  topic: Option[String])
    derives Schema

final private case class DocumentationExamplesModel(params: DocumentationExamplesParams)

final private[docs] class DocumentationExamplesLiveView(
  page: Page,
  application: DocumentationApplication,
  renderer: DocumentationRenderer)
    extends LiveView.Routed.Eventless[DocumentationExamplesModel, DocumentationExamplesParams]:
  private val ariaCurrent = htmlAttr("aria-current", StringAsIsEncoder)
  private val categories  = ExampleCategory.values.toVector
  private val topics      = (application.bundle.examples.flatMap(_.descriptor.topics) ++
    LabCatalog.entries.flatMap(_.topics))
    .groupBy(ExampleTopic.key)
    .toVector.collect {
      case (key, values) if key.nonEmpty => key -> ExampleTopic.label(values.head)
    }
    .sortBy(_._2.toLowerCase(Locale.ROOT))

  def mount(
    params: DocumentationExamplesParams,
    ctx: MountContext
  ): LiveIO[DocumentationExamplesModel] =
    LiveIO.succeed(DocumentationExamplesModel(normalize(params)))

  override def handleParams(
    model: DocumentationExamplesModel,
    params: DocumentationExamplesParams,
    url: URL,
    ctx: ParamsContext
  ): LiveIO[DocumentationExamplesModel] =
    LiveIO.succeed(DocumentationExamplesModel(normalize(params)))

  override def pageTitle(model: DocumentationExamplesModel): Option[String] =
    Some(s"${page.metadata.title} | Scalive")

  def render(model: DocumentationExamplesModel): HtmlElement[Nothing] =
    val examples    = application.bundle.examples.filter(matches(_, model.params))
    val labs        = LabCatalog.entries.filter(matches(_, model.params))
    val resultCount = examples.size + labs.size
    articleTag(
      cls                         := "docs-content docs-prose docs-examples-catalog",
      dataAttr("example-catalog") := "",
      headerTag(
        cls := "docs-example-catalog-header",
        h1(page.metadata.title),
        page.content.map(renderer.renderBlock(page.route))
      ),
      discoveryControls(model.params),
      div(
        cls := "docs-example-catalog-toolbar",
        p(
          cls  := "docs-example-catalog-status",
          role := "status",
          resultLabel(resultCount, model.params)
        ),
        topicFilters(model.params)
      ),
      if resultCount == 0 then emptyState()
      else listing(examples, labs, model.params),
      renderer.pageLinks(page)
    )

  private def discoveryControls(params: DocumentationExamplesParams): HtmlElement[Nothing] =
    sectionTag(
      cls        := "docs-example-discovery",
      aria.label := "Find an example",
      form(
        cls    := "docs-example-search",
        action := DocumentationApplication.ExamplesRoute,
        method := "get",
        label(
          cls                                := "docs-visually-hidden",
          htmlAttr("for", StringAsIsEncoder) := "docs-example-search-input",
          "Search examples"
        ),
        params.topic.map(topic =>
          input(
            typ      := "hidden",
            nameAttr := DocumentationApplication.TopicParameter,
            value    := topic
          )
        ),
        div(
          cls := "docs-example-search-control",
          input(
            idAttr      := "docs-example-search-input",
            typ         := "search",
            nameAttr    := DocumentationApplication.ExamplesQueryParameter,
            value       := params.q.getOrElse(""),
            placeholder := "Search APIs and concepts, like LiveStream or ZLayer",
            htmlAttr("autocomplete", StringAsIsEncoder) := "off"
          ),
          button(typ := "submit", "Search")
        )
      ),
      Option.when(params.q.isEmpty && params.topic.isEmpty)(
        navTag(
          cls        := "docs-example-category-nav",
          aria.label := "Example categories",
          span("Explore:"),
          categories.map(category => a(href := s"#${categoryId(category)}", category.label)),
          a(href := "#complete-applications", "Complete applications")
        )
      )
    )

  private def topicFilters(params: DocumentationExamplesParams): HtmlElement[Nothing] =
    detailsTag(
      cls := "docs-example-topic-disclosure",
      summaryTag(
        span(activeTopicLabel(params.topic)),
        span(cls := "docs-example-topic-count", s"${topics.size}")
      ),
      navTag(
        cls        := "docs-example-topic-filters",
        aria.label := "Filter examples by topic",
        topicLink(None, "All topics", params),
        topics.map { case (key, label) => topicLink(Some(key), label, params) }
      )
    )

  private def topicLink(
    topic: Option[String],
    label: String,
    params: DocumentationExamplesParams
  ): HtmlElement[Nothing] =
    val active = params.topic == topic
    val mods   = Vector[Mod[Nothing]](
      dataAttr("example-topic-filter") := topic.getOrElse("all")
    ) ++ Option.when(active)((ariaCurrent := "page"): Mod[Nothing]).toVector ++
      Vector(Mod.Content.Text(label))
    link.pushPatch(
      DocumentationApplication.ExamplesCatalogRoute.location(params.copy(topic = topic)),
      mods*
    )

  private def listing(
    examples: Vector[ExampleDefinition],
    labs: Vector[LabDescriptor],
    params: DocumentationExamplesParams
  ): HtmlElement[Nothing] =
    val filtered = params.q.isDefined || params.topic.isDefined
    val sections =
      if filtered then
        Vector(
          sectionTag(
            cls := "docs-example-category docs-example-filtered-results",
            h2("Matching examples"),
            div(
              cls := "docs-example-card-grid",
              examples.map(exampleCard(_, showCategory = true)),
              labs.map(labCard(_, showCategory = true))
            )
          )
        )
      else
        categories.map { category =>
          val entries = examples.filter(_.descriptor.category == category)
          sectionTag(
            idAttr := categoryId(category),
            cls    := "docs-example-category",
            div(
              cls := "docs-example-category-heading",
              h2(category.label),
              p(category.description)
            ),
            div(
              cls := "docs-example-card-grid",
              entries.map(exampleCard(_, showCategory = false))
            )
          )
        }
    val labSection = Option
      .when(!filtered && labs.nonEmpty)(
        sectionTag(
          idAttr := "complete-applications",
          cls    := "docs-example-category docs-example-lab-category",
          div(
            cls := "docs-example-category-heading",
            h2("Complete applications"),
            p("Standalone routes that combine multiple Scalive capabilities.")
          ),
          div(
            cls := "docs-example-card-grid",
            labs.map(labCard(_, showCategory = false))
          )
        )
      ).toVector
    div(
      cls := "docs-example-listing",
      sections.map(section => Mod.Content.Tag(section): Mod[Nothing]),
      labSection.map(section => Mod.Content.Tag(section): Mod[Nothing])
    )
  end listing

  private def exampleCard(
    example: ExampleDefinition,
    showCategory: Boolean
  ): HtmlElement[Nothing] =
    val descriptor = example.descriptor
    val route      = s"/examples/${descriptor.id}"
    val location   = application.location(route).getOrElse {
      throw new IllegalArgumentException(s"Missing validated example route: $route")
    }
    val sources = example.sources.map(source =>
      source.label -> application.bundle.apiReference.metadata.sourceLink(
        ApiSource.Repository(source.region)
      )
    )
    articleTag(
      cls                      := "docs-example-card",
      dataAttr("example-card") := descriptor.id,
      Option.when(showCategory)(p(cls := "docs-example-card-kind", descriptor.category.label)),
      h3(link.pushNavigate(location, descriptor.title, span(aria.hidden := true, " →"))),
      p(descriptor.description),
      ul(
        cls := "docs-example-card-topics",
        descriptor.topics.take(2).map { topic =>
          li(
            link.pushPatch(
              DocumentationApplication.ExamplesCatalogRoute.location(
                DocumentationExamplesParams(None, Some(ExampleTopic.key(topic)))
              ),
              topic
            )
          )
        }
      ),
      footerTag(
        link.pushNavigate(location, "Open example"),
        div(
          cls := "docs-example-source-links",
          sources.map { case (label, source) =>
            a(
              href := source.url,
              if sources.size == 1 then "Source" else label
            )
          }
        )
      )
    )
  end exampleCard

  private def labCard(lab: LabDescriptor, showCategory: Boolean): HtmlElement[Nothing] =
    articleTag(
      cls                        := "docs-example-card docs-lab-card",
      dataAttr("example-card")   := lab.id,
      dataAttr("standalone-lab") := "",
      Option.when(showCategory)(p(cls := "docs-example-card-kind", "Complete application")),
      h3(a(href := lab.route, lab.title, span(aria.hidden := true, " →"))),
      p(lab.description),
      ul(
        cls := "docs-example-card-topics",
        lab.topics.take(2).map { topic =>
          li(
            link.pushPatch(
              DocumentationApplication.ExamplesCatalogRoute.location(
                DocumentationExamplesParams(None, Some(ExampleTopic.key(topic)))
              ),
              topic
            )
          )
        }
      ),
      footerTag(
        a(href := lab.route, lab.actionLabel),
        link.pushNavigate(
          application.location("/guides/authentication").getOrElse {
            throw new IllegalArgumentException("Missing authentication guide route")
          },
          "Read guide"
        )
      )
    )

  private def emptyState(): HtmlElement[Nothing] =
    div(
      cls := "docs-example-catalog-empty",
      h2("No matching examples"),
      p("Try another API, concept, or topic."),
      link.pushPatch(
        DocumentationApplication.ExamplesCatalogRoute.location(
          DocumentationExamplesParams(None, None)
        ),
        "Clear search and filters"
      )
    )

  private def matches(
    example: ExampleDefinition,
    params: DocumentationExamplesParams
  ): Boolean =
    val descriptor   = example.descriptor
    val matchesTopic = params.topic.forall(topic =>
      descriptor.topics.exists(value => ExampleTopic.key(value) == topic)
    )
    val searchable = Vector(
      descriptor.title,
      descriptor.description,
      descriptor.category.label
    ) ++ descriptor.topics ++ descriptor.aliases
    matchesTopic && matchesQuery(searchable, params.q)

  private def matches(lab: LabDescriptor, params: DocumentationExamplesParams): Boolean =
    val matchesTopic =
      params.topic.forall(topic => lab.topics.exists(value => ExampleTopic.key(value) == topic))
    matchesTopic && matchesQuery(Vector(lab.title, lab.description) ++ lab.topics, params.q)

  private def matchesQuery(values: Vector[String], query: Option[String]): Boolean =
    query.forall { value =>
      val searchable = values.mkString(" ").toLowerCase(Locale.ROOT)
      value.toLowerCase(Locale.ROOT).split("\\s+").forall(searchable.contains)
    }

  private def normalize(params: DocumentationExamplesParams): DocumentationExamplesParams =
    DocumentationExamplesParams(
      params.q.map(_.trim).filter(_.nonEmpty),
      params.topic.map(ExampleTopic.key).filter(_.nonEmpty)
    )

  private def resultLabel(count: Int, params: DocumentationExamplesParams): String =
    val countLabel = if count == 1 then "1 example" else s"$count examples"
    params.q match
      case Some(query) => s"$countLabel for '$query'"
      case None        => countLabel

  private def activeTopicLabel(active: Option[String]): String =
    active
      .flatMap(key => topics.find(_._1 == key).map(_._2))
      .getOrElse(if active.isDefined then "Unknown topic" else "Browse all topics")

  private def categoryId(category: ExampleCategory): String =
    ExampleTopic.key(category.label)
end DocumentationExamplesLiveView
