package scalive.docs.pipeline

import zio.test.*

import scalive.docs.model.*

object ApiSnapshotSpec extends ZIOSpecDefault:
  private val metadata = ApiReferenceMetadata(
    "https://github.com/phfroidmont/scalive",
    "0123456789abcdef0123456789abcdef01234567",
    "18.1.0",
    "DomDefsGenerator.mill"
  )

  private def symbol(
    id: String,
    signature: String,
    summary: String = "Summary."
  ): ApiSymbol =
    ApiSymbol(
      id = id,
      ownerId = Some("package:scalive"),
      name = id.split('.').last,
      qualifiedName = id.split(':').last,
      kind = ApiSymbolKind.Def,
      summary = summary,
      signatures = Vector(
        ApiSignature(
          s"$id:signature",
          signature,
          CodeHighlighter.highlight(Some("scala"), signature),
          ApiOrigin(id.split(':').last, ApiExposure.Direct),
          ApiSource.Repository(SourceRegion("scalive/src/scalive/Sample.scala", 1, 2)),
          None
        )
      ),
      route = "/api/scalive",
      fragment = Some("sample")
    )

  override def spec = suite("ApiSnapshotSpec")(
    test("normalizes symbol and signature ordering") {
      val first = ApiReference(metadata, Vector(symbol("def:scalive.z", "def z: Int"), symbol("def:scalive.a", "def a: Int")))
      val second = first.copy(symbols = first.symbols.reverse)
      assertTrue(
        ApiSnapshot.from(first) == ApiSnapshot.from(second),
        ApiSnapshot.validate(first, ApiSnapshot.from(second)).isRight
      )
    },
    test("reports added, removed, and changed signatures") {
      val baseline = ApiReference(
        metadata,
        Vector(symbol("def:scalive.removed", "def removed: Int"), symbol("def:scalive.changed", "def changed: Int"))
      )
      val current = ApiReference(
        metadata,
        Vector(symbol("def:scalive.added", "def added: Int"), symbol("def:scalive.changed", "def changed: String"))
      )
      val errors = ApiSnapshot.validate(current, ApiSnapshot.from(baseline)).left.toOption.toVector
        .flatMap(_.messages)
      assertTrue(
        errors.exists(_.contains("added: def:scalive.added")),
        errors.exists(_.contains("removed: def:scalive.removed")),
        errors.exists(_.contains("changed: def:scalive.changed"))
      )
    },
    test("requires Scaladoc on every definition except packages") {
      val documentation = Some(ApiDocumentation(Vector(Block.Paragraph(Vector(Inline.Text("Docs.")))), Vector.empty))
      val documented    = symbol("def:scalive.documented", "def documented: Int")
      val overloaded    = symbol("def:scalive.overloaded", "def overloaded: Int", "")
      val reference = ApiReference(
        metadata,
        Vector(
          symbol("def:scalive.sample", "def sample: Int"),
          documented.copy(signatures = documented.signatures.map(_.copy(documentation = documentation))),
          overloaded.copy(signatures =
            Vector("a", "b").map(suffix =>
              overloaded.signatures.head.copy(id = s"${overloaded.id}:$suffix", documentation = documentation)
            )
          ),
          symbol("package:scalive", "package scalive").copy(kind = ApiSymbolKind.Package)
        )
      )
      val errors = ApiSnapshot.validateDocumentation(reference).left.toOption.toVector.flatMap(_.messages)
      assertTrue(
        errors == Vector(
          "missing Scaladoc: def:scalive.sample (scalive/src/scalive/Sample.scala:1)",
          "overloads of def:scalive.overloaded must share the first paragraph of their Scaladoc"
        )
      )
    }
  )
end ApiSnapshotSpec
