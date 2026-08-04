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

  def validateSummaries(
    reference: ApiReference,
    curatedSummaries: Map[String, String]
  ): Either[ApiReferenceError, Unit] =
    val symbolIds = reference.symbols.map(_.id).toSet
    val missing   = reference.symbols.collect {
      case symbol if symbol.summary.trim.isEmpty => s"missing summary: ${symbol.id}"
    }
    val unknown = (curatedSummaries.keySet -- symbolIds).toVector.sorted.map(id =>
      s"unknown curated summary: $id"
    )
    val blank = curatedSummaries.toVector.collect {
      case (id, summary) if summary.trim.isEmpty => s"blank curated summary: $id"
    }.sorted
    val errors = missing ++ unknown ++ blank
    Either.cond(errors.isEmpty, (), ApiReferenceError(errors))
end ApiSnapshot
