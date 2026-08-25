package scalive.docs

import zio.{Task, ZIO}

import scalive.*
import scalive.docs.examples.ExampleRegistry
import scalive.docs.model.*

final private[docs] case class HomePageContent(
  introduction: Block.Paragraph,
  actions: Block.Paragraph,
  preview: Block.Code,
  example: Block.ExampleRef,
  principles: Block.BulletList,
  howHeading: Block.Heading,
  howIntroduction: Block.Paragraph,
  workflow: Block.OrderedList,
  whyHeading: Block.Heading,
  projectStatement: Block.Paragraph,
  audience: Block.Paragraph,
  stack: Block.BulletList,
  startHeading: Block.Heading,
  startIntroduction: Block.Paragraph,
  alphaNote: Block.Callout)

private[docs] object HomePageContent:
  def from(page: Page): Either[String, HomePageContent] =
    for
      _ <- require(
             page,
             page.route == "/",
             s"homepage route must be '/', found '${page.route}'."
           )
      _ <- require(
             page,
             page.metadata.section == Section.Home,
             "homepage route '/' must use section Home."
           )
      _ <- require(
             page,
             page.metadata.title == "Live interfaces. Typed end to end.",
             "homepage title must be 'Live interfaces. Typed end to end.'."
           )
      _ <- page.source match
             case PageSource.Authored(location) =>
               require(
                 page,
                 location.path == "documentation/content/index.md",
                 s"homepage must be authored in documentation/content/index.md, found '${location.path}'."
               )
             case PageSource.GeneratedApi(_) =>
               Left(s"${source(page)}: homepage must be authored content.")
      _ <- require(
             page,
             page.content.size == 15,
             s"homepage must contain exactly 15 blocks, found ${page.content.size}."
           )
      introduction <- block(page, 0, "an introduction paragraph") { case value: Block.Paragraph =>
                        value
                      }
      actions <- block(page, 1, "a Learn and Examples action paragraph") {
                   case value: Block.Paragraph =>
                     value
                 }
      _       <- validateActions(page, actions)
      preview <- block(page, 2, "a Scala code preview") {
                   case value @ Block.Code(Some("scala"), text, _, None) if text.trim.nonEmpty =>
                     value
                 }
      example <- block(page, 3, "@:example(counter)") { case value @ Block.ExampleRef("counter") =>
                   value
                 }
      principles <-
        block(page, 4, "a three-item principle list") {
          case value @ Block.BulletList(items) if items.size == 3 && items.forall(_.content match
                case Vector(_: Block.Paragraph) => true
                case _ => false) =>
            value
        }
      howHeading <- block(page, 5, "the level-2 #how-it-works heading") {
                      case value @ Block.Heading(
                            2,
                            "how-it-works",
                            Vector(Inline.Text("How it works"))
                          ) =>
                        value
                    }
      howIntroduction <- block(page, 6, "the How it works introduction") {
                           case value: Block.Paragraph => value
                         }
      workflow <- block(page, 7, "a four-step workflow list") {
                    case value @ Block.OrderedList(1, items) if items.size == 4 => value
                  }
      whyHeading <- block(page, 8, "the level-2 #why-scalive heading") {
                      case value @ Block.Heading(
                            2,
                            "why-scalive",
                            Vector(Inline.Text("Why Scalive"))
                          ) =>
                        value
                    }
      projectStatement <- block(page, 9, "the Why Scalive paragraph") {
                            case value: Block.Paragraph => value
                          }
      audience <- block(page, 10, "the Scalive audience paragraph") { case value: Block.Paragraph =>
                    value
                  }
      stack <- block(page, 11, "a five-item technology list") {
                 case value @ Block.BulletList(items) if items.size == 5 => value
               }
      startHeading <- block(page, 12, "the level-2 #start-building heading") {
                        case value @ Block.Heading(
                              2,
                              "start-building",
                              Vector(Inline.Text("Start building"))
                            ) =>
                          value
                      }
      startIntroduction <- block(page, 13, "the Start building paragraph") {
                             case value: Block.Paragraph => value
                           }
      alphaNote <- block(page, 14, "the final alpha info callout") {
                     case value @ Block.Callout(CalloutKind.Info, None, content)
                         if content.nonEmpty =>
                       value
                   }
    yield HomePageContent(
      introduction,
      actions,
      preview,
      example,
      principles,
      howHeading,
      howIntroduction,
      workflow,
      whyHeading,
      projectStatement,
      audience,
      stack,
      startHeading,
      startIntroduction,
      alphaNote
    )

  private def validateActions(page: Page, actions: Block.Paragraph): Either[String, Unit] =
    val targets = actions.content.collect { case Inline.Link(_, target, _) =>
      target
    }
    require(
      page,
      targets == Vector(
        LinkTarget.Internal("/learn/quick-start", None),
        LinkTarget.Internal("/examples/counter", None)
      ),
      "homepage block 2 must link to /learn/quick-start and /examples/counter, in that order."
    )

  private def block[A](
    page: Page,
    index: Int,
    expected: String
  )(
    extract: PartialFunction[Block, A]
  ): Either[String, A] =
    extract
      .lift(page.content(index)).toRight(
        s"${source(page)}: homepage block ${index + 1} must be $expected."
      )

  private def require(page: Page, condition: Boolean, message: => String): Either[String, Unit] =
    Either.cond(condition, (), s"${source(page)}: $message")

  private def source(page: Page): String = page.source match
    case PageSource.Authored(location) => s"${location.path}:${location.line}"
    case PageSource.GeneratedApi(id)   => s"generated API page '$id'"
end HomePageContent

final private[docs] class DocumentationHomeLiveView(
  page: Page,
  content: HomePageContent,
  application: DocumentationApplication,
  renderer: DocumentationRenderer)
    extends LiveView.Eventless[Unit]:

  def mount(ctx: MountContext): Task[Unit] = ZIO.succeed(())

  override def pageTitle(model: Unit): Option[String] = Some("Scalive")

  override def view(model: Signal[Unit]): HtmlElement[Nothing] =
    articleTag(
      cls := "docs-content docs-prose docs-home",
      sectionTag(
        cls := "docs-home-hero",
        DocumentationBrand.mark("docs-home-hero-mark"),
        div(
          cls := "docs-home-introduction",
          p(cls := "docs-home-eyebrow", "Scala 3", " · ", "Server rendered", " · ", "Fully typed"),
          h1(span("Live interfaces."), " ", span("Typed end to end.")),
          renderer.renderBlock(page.route)(content.introduction),
          p(cls := "docs-home-actions", content.actions.content.map(renderer.renderInline))
        ),
        div(
          cls := "docs-home-proof",
          div(
            cls := "docs-home-code",
            renderer.codeBlock(
              content.preview.language,
              content.preview.text,
              content.preview.tokens,
              content.preview.sourceRegion
            )
          ),
          compactExample
        )
      ),
      sectionTag(
        cls := "docs-home-principles",
        renderer.renderBlock(page.route)(content.principles)
      ),
      sectionTag(
        cls := "docs-home-workflow",
        div(
          cls := "docs-home-section-heading",
          renderer.renderBlock(page.route)(content.howHeading),
          renderer.renderBlock(page.route)(content.howIntroduction)
        ),
        renderer.renderBlock(page.route)(content.workflow)
      ),
      sectionTag(
        cls := "docs-home-statement",
        renderer.renderBlock(page.route)(content.whyHeading),
        div(
          cls := "docs-home-statement-content",
          renderer.renderBlock(page.route)(content.projectStatement),
          renderer.renderBlock(page.route)(content.audience),
          renderer.renderBlock(page.route)(content.stack)
        )
      ),
      sectionTag(
        cls := "docs-home-start",
        renderer.renderBlock(page.route)(content.startHeading),
        div(
          cls := "docs-home-start-content",
          renderer.renderBlock(page.route)(content.startIntroduction),
          renderer.renderBlock(page.route)(content.alphaNote)
        )
      ),
      renderer.pageLinks(page)
    )

  private def compactExample: HtmlElement[Nothing] =
    val id         = content.example.id
    val definition = application.example(id).getOrElse {
      throw new IllegalArgumentException(s"Unknown generated example: $id")
    }
    val registered = ExampleRegistry.get(id).getOrElse {
      throw new IllegalArgumentException(s"Unknown runtime example: $id")
    }
    val nestedId = ExampleRegistry.instanceId(page.route, id)

    sectionTag(
      idAttr                    := s"example-$id",
      cls                       := "docs-example docs-home-example",
      aria.label                := s"${definition.descriptor.title} live result",
      dataAttr("example")       := id,
      dataAttr("example-child") := nestedId,
      div(
        cls := "docs-home-example-heading",
        div(
          h2("Live result"),
          p("Click Increase to send ", code("Msg.Increment"), " to the server.")
        ),
        span(cls := "docs-home-live-label", "Live server")
      ),
      registered.render(nestedId),
      p(
        dataAttr("example-disconnected") := "",
        "This example is read-only while disconnected. Controls resume after reconnection."
      )
    )
  end compactExample
end DocumentationHomeLiveView
