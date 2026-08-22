package scalive.docs

import java.util.Locale

import zio.http.URL
import zio.schema.Schema
import zio.schema.derived
import zio.{Task, ZIO}

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
  ): Task[DocumentationExamplesModel] =
    ZIO.succeed(DocumentationExamplesModel(normalize(params)))

  override def handleParams(
    model: DocumentationExamplesModel,
    params: DocumentationExamplesParams,
    url: URL,
    ctx: ParamsContext
  ): Task[DocumentationExamplesModel] =
    ZIO.succeed(DocumentationExamplesModel(normalize(params)))

  override def pageTitle(model: DocumentationExamplesModel): Option[String] =
    Some(s"${page.metadata.title} | Scalive")

  override def view(model: Signal[DocumentationExamplesModel]): HtmlElement[Nothing] =
    val params      = model.map(_.params)
    val examples    = params.map(value => application.bundle.examples.filter(matches(_, value)))
    val labs        = params.map(value => LabCatalog.entries.filter(matches(_, value)))
    val resultCount = examples.zip(labs).map { case (examples, labs) => examples.size + labs.size }
    articleTag(
      cls                         := "docs-content docs-prose docs-examples-catalog",
      dataAttr("example-catalog") := "",
      headerTag(
        cls := "docs-example-catalog-header",
        h1(page.metadata.title),
        page.content.map(renderer.renderBlock(page.route))
      ),
      discoveryControls(params),
      div(
        cls := "docs-example-catalog-toolbar",
        p(
          cls  := "docs-example-catalog-status",
          role := "status",
          resultCount.zip(params).map { case (count, params) => resultLabel(count, params) }
        ),
        topicFilters(params)
      ),
      resultCount.map(_ == 0).when(emptyState()),
      resultCount.map(_ != 0).when(listing(examples, labs, params)),
      renderer.pageLinks(page)
    )

  private def discoveryControls(params: Signal[DocumentationExamplesParams]): HtmlElement[Nothing] =
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
        params
          .map(_.topic).option(topic =>
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
            value       := params.map(_.q.getOrElse("")),
            placeholder := "Search APIs and concepts, like LiveStream or ZLayer",
            htmlAttr("autocomplete", StringAsIsEncoder) := "off"
          ),
          button(typ := "submit", "Search")
        )
      ),
      params
        .map(value => value.q.isEmpty && value.topic.isEmpty).when(
          navTag(
            cls        := "docs-example-category-nav",
            aria.label := "Example categories",
            span("Explore:"),
            categories.map(category => a(href := s"#${categoryId(category)}", category.label)),
            a(href := "#complete-applications", "Complete applications")
          )
        )
    )

  private def topicFilters(params: Signal[DocumentationExamplesParams]): HtmlElement[Nothing] =
    detailsTag(
      cls := "docs-example-topic-disclosure",
      summaryTag(
        span(params.map(value => activeTopicLabel(value.topic))),
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
    params: Signal[DocumentationExamplesParams]
  ): HtmlElement[Nothing] =
    link.pushPatch(
      params.map(value =>
        DocumentationApplication.ExamplesCatalogRoute.location(value.copy(topic = topic))
      ),
      dataAttr("example-topic-filter") := topic.getOrElse("all"),
      ariaCurrent.optional(params.map(value => Option.when(value.topic == topic)("page"))),
      label
    )

  private def listing(
    examples: Signal[Vector[ExampleDefinition]],
    labs: Signal[Vector[LabDescriptor]],
    params: Signal[DocumentationExamplesParams]
  ): HtmlElement[Nothing] =
    val filtered = params.map(value => value.q.isDefined || value.topic.isDefined)
    div(
      cls := "docs-example-listing",
      filtered.when(
        sectionTag(
          cls := "docs-example-category docs-example-filtered-results",
          h2("Matching examples"),
          div(
            cls := "docs-example-card-grid",
            examples.splitBy(_.descriptor.id) { (_, example) =>
              exampleCard(example, showCategory = true)
            },
            labs.splitBy(_.id)((_, lab) => labCard(lab, showCategory = true))
          )
        )
      ),
      categories.map { category =>
        filtered
          .map(!_).when(
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
                examples.map(_.filter(_.descriptor.category == category)).splitBy(_.descriptor.id) {
                  (_, example) => exampleCard(example, showCategory = false)
                }
              )
            )
          )
      },
      filtered
        .zip(labs).map { case (filtered, labs) => !filtered && labs.nonEmpty }.when(
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
              labs.splitBy(_.id)((_, lab) => labCard(lab, showCategory = false))
            )
          )
        )
    )
  end listing

  private def exampleCard(
    example: Signal[ExampleDefinition],
    showCategory: Boolean
  ): HtmlElement[Nothing] =
    val descriptor = example.map(_.descriptor)
    val location   = descriptor.map { descriptor =>
      val route = s"/examples/${descriptor.id}"
      application.location(route).getOrElse {
        throw new IllegalArgumentException(s"Missing validated example route: $route")
      }
    }
    val sources = example.map(
      _.sources.map(source =>
        source.label -> application.bundle.apiReference.metadata.sourceLink(
          ApiSource.Repository(source.region)
        )
      )
    )
    articleTag(
      cls                      := "docs-example-card",
      dataAttr("example-card") := descriptor.map(_.id),
      Option.when(showCategory)(
        p(cls := "docs-example-card-kind", descriptor.map(_.category.label))
      ),
      h3(link.pushNavigate(location, descriptor.map(_.title), span(aria.hidden := true, " →"))),
      p(descriptor.map(_.description)),
      ul(
        cls := "docs-example-card-topics",
        descriptor.map(_.topics.take(2)).splitBy(identity) { (_, topic) =>
          li(
            link.pushPatch(
              topic.map(value =>
                DocumentationApplication.ExamplesCatalogRoute.location(
                  DocumentationExamplesParams(None, Some(ExampleTopic.key(value)))
                )
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
          sources.splitBy(_._1) { (_, entry) =>
            a(
              href := entry.map(_._2.url),
              entry.zip(sources).map { case ((label, _), sources) =>
                if sources.size == 1 then "Source" else label
              }
            )
          }
        )
      )
    )
  end exampleCard

  private def labCard(lab: Signal[LabDescriptor], showCategory: Boolean): HtmlElement[Nothing] =
    articleTag(
      cls                        := "docs-example-card docs-lab-card",
      dataAttr("example-card")   := lab.map(_.id),
      dataAttr("standalone-lab") := "",
      Option.when(showCategory)(p(cls := "docs-example-card-kind", "Complete application")),
      h3(a(href := lab.map(_.route), lab.map(_.title), span(aria.hidden := true, " →"))),
      p(lab.map(_.description)),
      ul(
        cls := "docs-example-card-topics",
        lab.map(_.topics.take(2)).splitBy(identity) { (_, topic) =>
          li(
            link.pushPatch(
              topic.map(value =>
                DocumentationApplication.ExamplesCatalogRoute.location(
                  DocumentationExamplesParams(None, Some(ExampleTopic.key(value)))
                )
              ),
              topic
            )
          )
        }
      ),
      footerTag(
        a(href := lab.map(_.route), lab.map(_.actionLabel)),
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
