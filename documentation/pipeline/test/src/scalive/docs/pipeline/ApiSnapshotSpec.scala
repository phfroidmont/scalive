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
          ApiSource.Repository(SourceRegion("scalive/src/scalive/Sample.scala", 1, 2))
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
    test("requires summaries and rejects stale curated entries") {
      val reference = ApiReference(metadata, Vector(symbol("def:scalive.sample", "def sample: Int", "")))
      val errors = ApiSnapshot.validateSummaries(
        reference,
        Map("def:scalive.unknown" -> "Unknown summary")
      ).left.toOption.toVector.flatMap(_.messages)
      assertTrue(
        errors.exists(_.contains("missing summary: def:scalive.sample")),
        errors.exists(_.contains("unknown curated summary: def:scalive.unknown"))
      )
    }
  )
end ApiSnapshotSpec
