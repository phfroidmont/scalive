package scalive.docs.pipeline

import zio.test.*

object CodeHighlighterSpec extends ZIOSpecDefault:
  override def spec = suite("CodeHighlighterSpec")(
    test("highlights supported languages without changing source text") {
      val source = "// state\nenum Result:\n  case Count(value: Int)\nval count: Int = 1"
      val tokens = CodeHighlighter.highlight(Some("scala"), source)
      val text   = tokens.map(_.text).mkString

      assertTrue(
        text == source,
        tokens.exists(_.styles.contains("comment")),
        tokens.exists(token => token.text == "enum" && token.styles.contains("keyword")),
        tokens.exists(_.styles.contains("number-literal"))
      )
    },
    test("keeps unsupported and unspecified languages as plain text") {
      val source      = "some arbitrary source"
      val unsupported = CodeHighlighter.highlight(Some("unknown"), source)
      val unspecified = CodeHighlighter.highlight(None, source)
      val unsupportedText = unsupported.map(_.text).mkString
      val unspecifiedText = unspecified.map(_.text).mkString

      assertTrue(
        unsupportedText == source,
        unsupported.forall(_.styles.isEmpty),
        unspecifiedText == source,
        unspecified.forall(_.styles.isEmpty)
      )
    }
  )
