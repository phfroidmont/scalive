package scalive.docs.pipeline

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.quoted.*
import scala.tasty.inspector.*
import scala.util.Using

final case class TastyPosition(
  path: String,
  startOffset: Int,
  pointOffset: Int,
  endOffset: Int)

final case class TastyDocumentationRecord(
  name: String,
  position: TastyPosition,
  exported: Boolean,
  comment: Option[String])

object TastyDocumentation:
  def inspect(
    roots: Seq[Path],
    dependencyClasspath: Seq[Path]
  ): Either[String, Vector[TastyDocumentationRecord]] =
    val inspector = DocumentationInspector()
    val succeeded = TastyInspector.inspectAllTastyFiles(
      tastyFiles(roots),
      Nil,
      (roots ++ dependencyClasspath).map(_.toString).distinct.toList
    )(inspector)

    Either.cond(
      succeeded,
      inspector.records.toVector.sortBy(record =>
        (
          record.position.path,
          record.position.startOffset,
          record.name
        )
      ),
      "TASTy Inspector failed to load the requested compilation output."
    )

  private def tastyFiles(roots: Seq[Path]): List[String] =
    roots
      .flatMap { root =>
        if Files.isDirectory(root) then
          Using.resource(Files.walk(root)) { stream =>
            stream
              .iterator().asScala
              .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".tasty"))
              .map(_.toString)
              .toVector
          }
        else Vector.empty
      }.distinct.sorted.toList

  final private class DocumentationInspector extends Inspector:
    val records = mutable.ArrayBuffer.empty[TastyDocumentationRecord]

    override def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
      import quotes.reflect.*

      val seen = mutable.HashSet.empty[Symbol]

      val traverser = new TreeTraverser:
        override def traverseTree(tree: Tree)(owner: Symbol): Unit =
          tree match
            case definition: Definition if seen.add(definition.symbol) =>
              val symbol   = definition.symbol
              val position = definition.pos
              records += TastyDocumentationRecord(
                name = symbol.name,
                position = TastyPosition(
                  path = position.sourceFile.path.replace('\\', '/'),
                  startOffset = position.start,
                  pointOffset = symbol.pos.map(_.start).getOrElse(position.start),
                  endOffset = position.end
                ),
                exported = symbol.flags.is(Flags.Exported),
                comment = symbol.docstring
              )
            case _ => ()

          super.traverseTree(tree)(owner)

      tastys.foreach(tasty => traverser.traverseTree(tasty.ast)(Symbol.spliceOwner))
  end DocumentationInspector
end TastyDocumentation
