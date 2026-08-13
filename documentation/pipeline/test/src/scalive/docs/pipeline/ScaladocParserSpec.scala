package scalive.docs.pipeline

import java.nio.file.Files
import java.nio.file.Path

import zio.test.*

import scalive.docs.model.*

object ScaladocParserSpec extends ZIOSpecDefault:
  private val comment =
    """/** Defines a [[LiveView]] with `typed` state.
      |  *
      |  * It preserves every body paragraph and a [[LiveView.mount labeled mount]] link.
      |  *
      |  * - first lifecycle
      |  * - second lifecycle
      |  *
      |  * @tparam Msg
      |  *   the accepted messages
      |  * @param model
      |  *   the current model with `inline code`
      |  * @return
      |  *   the next model
      |  */""".stripMargin

  private val links = Map(
    "LiveView"       -> LinkTarget.Internal("/api/scalive/live-view", None),
    "LiveView.mount" -> LinkTarget.Internal("/api/scalive/live-view", Some("mount"))
  )

  override def spec = suite("ScaladocParserSpec")(
    test("parses complete comments, markup, symbol links, and tags") {
      val result = ScaladocParser.parse(comment, "live-view", links.get)

      val assertions = result match
        case Left(errors) => assertTrue(errors.isEmpty)
        case Right(documentation) =>
          val links = documentation.body.flatMap {
            case Block.Paragraph(content) => content.collect { case link: Inline.Link => link }
            case _                        => Vector.empty
          }
          assertTrue(
            documentation.body.count(_.isInstanceOf[Block.Paragraph]) == 2,
            documentation.body.exists(_.isInstanceOf[Block.BulletList]),
            links.map(_.target) == Vector(
              LinkTarget.Internal("/api/scalive/live-view", None),
              LinkTarget.Internal("/api/scalive/live-view", Some("mount"))
            ),
            links.map(_.content) == Vector(
              Vector(Inline.Text("LiveView")),
              Vector(Inline.Text("labeled mount"))
            ),
            documentation.tags.map(tag => tag.name -> tag.subject) == Vector(
              "tparam" -> Some("Msg"),
              "param"  -> Some("model"),
              "return" -> None
            ),
            documentation.tags(1).content.exists {
              case Block.Paragraph(content) => content.contains(Inline.Code("inline code"))
              case _                        => false
            },
            ScaladocParser.summary(documentation).contains("Defines a LiveView with typed state.")
          )

      assertions
    },
    test("keeps unresolved symbols readable without leaking Scaladoc delimiters") {
      val result = ScaladocParser.parse("/** See [[Missing.Type]]. */", "missing", _ => None)
      assertTrue(
        result.exists(_.body == Vector(
          Block.Paragraph(Vector(Inline.Text("See "), Inline.Code("Type"), Inline.Text(".")))
        ))
      )
    },
    test("renders raw HTML as text and rejects unsafe external links") {
      val rawHtml = ScaladocParser.parse("/** <script>alert(1)</script> */", "html", _ => None)
      val unsafeLink = ScaladocParser.parse(
        "/** [unsafe](javascript:alert(1)) */",
        "link",
        _ => None
      )
      assertTrue(
        rawHtml.exists(_.body == Vector(
          Block.Paragraph(Vector(Inline.Text("<script>alert(1)</script>")))
        )),
        unsafeLink.isLeft
      )
    },
    test("restores HTML tags in inline and fenced code") {
      val inline = ScaladocParser.parse("/** Renders a `<link>` and `<script>`. */", "html", _ => None)
      val fenced = ScaladocParser.parse(
        "/**\n  * ```html\n  * <script>alert(1)</script>\n  * ```\n  */",
        "html",
        _ => None
      )
      val inlineBody = inline.toOption.toVector.flatMap(_.body)
      val fencedCode = fenced.toOption.toVector.flatMap(_.body).collectFirst {
        case code: Block.Code => code
      }

      assertTrue(
        inlineBody == Vector(
          Block.Paragraph(Vector(
            Inline.Text("Renders a "),
            Inline.Code("<link>"),
            Inline.Text(" and "),
            Inline.Code("<script>"),
            Inline.Text(".")
          ))
        ),
        fencedCode.exists(_.text == "<script>alert(1)</script>"),
        fencedCode.exists(_.tokens.map(_.text).mkString == "<script>alert(1)</script>")
      )
    },
    test("preserves HTML tags in StaticAssets Scaladoc extracted from TASTy") {
      val classes = Path.of(
        classOf[scalive.StaticAssets].getProtectionDomain.getCodeSource.getLocation.toURI
      )
      val classpath = System.getProperty("java.class.path")
        .split(java.io.File.pathSeparator)
        .toVector
        .map(Path.of(_))
        .filter(Files.exists(_))
      val comments = TastyDocumentation
        .inspect(Seq(classes), classpath)
        .toOption
        .toVector
        .flatten
        .filter(record => Set("trackedStylesheet", "trackedScript")(record.name))
        .flatMap(_.comment)
      val codeSpans = comments
        .flatMap(ScaladocParser.parse(_, "static-assets", _ => None).toOption)
        .flatMap(_.body)
        .flatMap {
          case Block.Paragraph(content) => content.collect { case Inline.Code(value) => value }
          case _                        => Vector.empty
        }.toSet

      assertTrue(Set("<link>", "<script>").subsetOf(codeSpans))
    }
  )
end ScaladocParserSpec
