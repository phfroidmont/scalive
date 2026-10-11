package scalive.docs.pipeline

import zio.json.*

import scalive.docs.model.*

final case class ApiSnapshot(formatVersion: Int, symbols: Vector[ApiSnapshotSymbol])
    derives JsonCodec

final case class ApiSnapshotSymbol(
  id: String,
  ownerId: Option[String],
  qualifiedName: String,
  kind: ApiSymbolKind,
  signatures: Vector[ApiSnapshotSignature])
    derives JsonCodec

final case class ApiSnapshotSignature(id: String, signature: String) derives JsonCodec

object ApiSnapshot:
  val FormatVersion = 1

  def from(reference: ApiReference): ApiSnapshot =
    ApiSnapshot(
      FormatVersion,
      reference.symbols.sortBy(_.id).map { symbol =>
        ApiSnapshotSymbol(
          symbol.id,
          symbol.ownerId,
          symbol.qualifiedName,
          symbol.kind,
          symbol.signatures
            .sortBy(_.id).map(signature => ApiSnapshotSignature(signature.id, signature.signature))
        )
      }
    )

  def validate(
    reference: ApiReference,
    expected: ApiSnapshot
  ): Either[ApiReferenceError, Unit] =
    val errors = Vector.newBuilder[String]
    if expected.formatVersion != FormatVersion then
      errors += s"unsupported API snapshot format: ${expected.formatVersion}"

    val currentById  = from(reference).symbols.map(symbol => symbol.id -> symbol).toMap
    val expectedById = expected.symbols.map(symbol => symbol.id -> symbol).toMap
    (currentById.keySet -- expectedById.keySet).toVector.sorted.foreach(id =>
      errors += s"public API added: $id"
    )
    (expectedById.keySet -- currentById.keySet).toVector.sorted.foreach(id =>
      errors += s"public API removed: $id"
    )
    (currentById.keySet intersect expectedById.keySet).toVector.sorted.foreach { id =>
      if currentById(id) != expectedById(id) then errors += s"public API changed: $id"
    }

    val result = errors.result()
    Either.cond(result.isEmpty, (), ApiReferenceError(result))

  /** Requires Scaladoc on every public definition. The first paragraph is the reference summary, so
    * overloads must share it; a field takes its owner's `@param` text. Packages have a generated
    * summary.
    */
  def validateDocumentation(reference: ApiReference): Either[ApiReferenceError, Unit] =
    val documented   = reference.symbols.filter(_.kind != ApiSymbolKind.Package)
    val undocumented = documented.flatMap { symbol =>
      symbol.signatures.collect {
        case signature if signature.documentation.isEmpty =>
          s"missing Scaladoc: ${symbol.id} (${location(signature.source)})"
      }
    }
    val unsummarized = documented.collect {
      case symbol
          if symbol.summary.trim.isEmpty && symbol.signatures.forall(_.documentation.nonEmpty) =>
        if symbol.signatures.sizeIs > 1 then
          s"overloads of ${symbol.id} must share the first paragraph of their Scaladoc"
        else s"the Scaladoc of ${symbol.id} needs a summary paragraph"
    }
    val errors = undocumented ++ unsummarized
    Either.cond(errors.isEmpty, (), ApiReferenceError(errors))

  private def location(source: ApiSource): String =
    source match
      case ApiSource.Repository(region) => s"${region.path}:${region.startLine}"
      case ApiSource.GeneratedDom       => "generated DOM definition"
end ApiSnapshot
