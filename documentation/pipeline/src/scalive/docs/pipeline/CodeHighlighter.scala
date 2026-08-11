package scalive.docs.pipeline

import laika.ast.CodeSpan
import laika.ast.CodeSpanSequence
import laika.ast.Span
import laika.ast.Text
import laika.config.SyntaxHighlighting
import laika.parse.code.languages.ScalaSyntax

import scalive.docs.model.CodeToken

private[pipeline] object CodeHighlighter:
  private[pipeline] val syntaxHighlighting =
    SyntaxHighlighting.withSyntaxBinding("scala", ScalaSyntax.Scala3)

  private val highlighters = syntaxHighlighting.parsers.syntaxHighlighters
    .flatMap(highlighter => highlighter.language.toList.map(_.toLowerCase -> highlighter))
    .toMap

  def highlight(language: Option[String], text: String): Vector[CodeToken] =
    language
      .map(_.toLowerCase)
      .flatMap(highlighters.get)
      .flatMap(_.rootParser.parse(text).toOption)
      .map(spans => fromSpans(spans))
      .getOrElse(Vector(CodeToken(text, Vector.empty)))

  def fromSpans(spans: Seq[Span]): Vector[CodeToken] =
    spans.toVector.flatMap {
      case CodeSpan(content, categories, _) =>
        Vector(CodeToken(content, categories.toVector.map(_.name).sorted))
      case Text(content, _)           => Vector(CodeToken(content, Vector.empty))
      case sequence: CodeSpanSequence => fromSpans(sequence.content)
      case other                      => Vector(CodeToken(extractText(Seq(other)), Vector.empty))
    }

  private def extractText(spans: Seq[Span]): String = spans.map {
    case container: laika.ast.TextContainer => container.content
    case container: laika.ast.SpanContainer => extractText(container.content)
    case _                                  => ""
  }.mkString
end CodeHighlighter
