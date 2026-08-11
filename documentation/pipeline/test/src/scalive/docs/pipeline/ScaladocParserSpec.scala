package scalive.docs.pipeline

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
    }
  )
end ScaladocParserSpec
