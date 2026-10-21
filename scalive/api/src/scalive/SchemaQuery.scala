package scalive

import scala.util.control.NonFatal

import zio.*
import zio.http.QueryParams
import zio.schema.Schema
import zio.schema.annotation.fieldDefaultValue

/** Query parameters derived from a schema, read the way a bookmarked URL needs.
  *
  * A field takes its fallback (its default value, `None`, or an empty collection) when its value is
  * missing or malformed, and a malformed list element is dropped. Only a field without a fallback
  * fails the decode. Encoding leaves out values equal to their fallback, so decoding the encoded
  * query gives the value back. Lists are repeated names, a singleton enum is its case name, and a
  * nested record is encoded under its field name, as in `back.days=3`.
  */
final private[scalive] class SchemaQuery[A] private (root: SchemaQuery.Param):
  import SchemaQuery.*

  def decode(params: QueryParams): IO[LiveParamsCodec.DecodeError, A] =
    root.decode(params) match
      case Decoded.Value(value, fallbacks) =>
        ZIO
          .foreachDiscard(fallbacks)(fallback =>
            ZIO.logDebug(s"Query parameter fell back to its default: $fallback")
          )
          .as(value.asInstanceOf[A])
      case Decoded.Missing(name) =>
        ZIO.fail(LiveParamsCodec.DecodeError(s"Missing query parameter $name"))
      case Decoded.Malformed(name, reason) =>
        ZIO.fail(LiveParamsCodec.DecodeError(s"Malformed query parameter $name: $reason"))

  def encode(value: A): QueryParams =
    root.encode(value).foldLeft(QueryParams.empty) { case (params, (name, encoded)) =>
      params.addQueryParam(name, encoded)
    }

private[scalive] object SchemaQuery:
  /** The fields of a record schema, each a query parameter named after the field. */
  def fields[A](schema: Schema[A]): SchemaQuery[A] =
    node(schema) match
      case record: (Node.Record | Node.Mapped) =>
        SchemaQuery(Param("", record, fallback = None))
      case _ =>
        throw IllegalArgumentException(
          s"A query needs a case class; use a named query parameter for $schema"
        )

  /** One parameter called `name`. It fails when missing or malformed unless it is optional. */
  def param[A](name: String, schema: Schema[A]): SchemaQuery[A] =
    SchemaQuery(Param(name, node(schema), fallback = None))

  private enum Decoded:
    case Value(value: Any, fallbacks: Chunk[String])
    case Missing(name: String)
    case Malformed(name: String, reason: String)

  /** A value at a name. `fallback` is the field's declared default. */
  final private case class Param(name: String, node: Node, fallback: Option[Any]):
    def decode(params: QueryParams): Decoded =
      val orFallback = fallback.orElse(node.empty)
      node.decode(name, params) match
        case value: Decoded.Value     => value
        case missing: Decoded.Missing =>
          orFallback.fold(missing)(Decoded.Value(_, Chunk.empty))
        case malformed: Decoded.Malformed =>
          orFallback.fold(malformed)(
            Decoded.Value(_, Chunk(s"${malformed.name}: ${malformed.reason}"))
          )

    def encode(value: Any): Chunk[(String, String)] =
      if fallback.contains(value) then Chunk.empty else node.encode(name, value)

  private enum Node:
    /** A primitive, or a type that transforms one, read with ZIO HTTP's own value codec. */
    case Scalar(schema: Schema[Any])

    /** An enum whose cases carry no data, read by case name. */
    case Singletons(schema: Schema.Enum[Any])

    case Optional(value: Node)
    case Many(element: Node, schema: Schema.Collection[Any, Any])
    case Record(schema: Schema.Record[Any], fields: Chunk[Param])
    case Mapped(record: Node.Record, schema: Schema.Transform[Any, Any, ?])

    /** The value of an absent parameter that needs no declared default. */
    def empty: Option[Any] = this match
      case Optional(_)     => Some(None)
      case Many(_, schema) => Some(schema.empty)
      case Scalar(_)       => None
      case Singletons(_)   => None
      case Record(_, _)    => None
      case Mapped(_, _)    => None

    def decode(name: String, params: QueryParams): Decoded = this match
      case Scalar(schema) =>
        single(name, params)(raw =>
          try
            QueryParams(Map(ValueName -> Chunk(raw)))
              .query[Any](ValueName)(using schema).left.map(_.getMessage)
          catch case NonFatal(error) => Left(error.getMessage)
        )
      case Singletons(schema) =>
        single(name, params)(raw =>
          schema.caseOf(raw).map(singleton).toRight(s"no case is named '$raw'")
        )
      case Optional(value) =>
        value.decode(name, params) match
          case Decoded.Value(decoded, fallbacks) => Decoded.Value(Some(decoded), fallbacks)
          case Decoded.Missing(_)                => Decoded.Value(None, Chunk.empty)
          case malformed: Decoded.Malformed      => malformed
      case Many(element, schema) =>
        val decoded =
          params.getAll(name).map(raw => element.decode(name, QueryParams(Map(name -> Chunk(raw)))))
        Decoded.Value(
          schema.fromChunk(decoded.collect { case Decoded.Value(value, _) => value }),
          decoded.collect { case Decoded.Malformed(name, reason) => s"$name: $reason" }
        )
      case Record(schema, fields) =>
        decodeRecord(name, params, schema, fields)
      case Mapped(record, schema) =>
        record.decode(name, params) match
          case Decoded.Value(value, fallbacks) =>
            schema.f(value) match
              case Right(mapped) => Decoded.Value(mapped, fallbacks)
              case Left(reason)  => Decoded.Malformed(name, reason)
          case other => other

    def encode(name: String, value: Any): Chunk[(String, String)] = this match
      case Scalar(schema) =>
        QueryParams.empty
          .addQueryParam(ValueName, value)(using schema).getAll(ValueName).map(name -> _)
      case Singletons(schema) =>
        Chunk
          .fromIterable(schema.caseOf(value)).map(singletonCase => name -> singletonCase.caseName)
      case Optional(node) =>
        value match
          case Some(present) => node.encode(name, present)
          case _             => Chunk.empty
      case Many(element, schema) =>
        schema.toChunk(value).flatMap(element.encode(name, _))
      case Record(schema, fields) =>
        val values = Unsafe.unsafe(schema.deconstruct(value)(using _))
        fields.zip(values).flatMap { case (field, fieldValue) =>
          fieldValue.fold(Chunk.empty)(field.copy(name = qualified(name, field.name)).encode)
        }
      case Mapped(record, schema) =>
        schema.g(value) match
          case Right(unmapped) => record.encode(name, unmapped)
          case Left(reason)    => throw IllegalArgumentException(reason)
  end Node

  private val ValueName = "value"

  private def node(schema: Schema[?]): Node =
    schema match
      case lazySchema: Schema.Lazy[?]         => node(lazySchema.schema)
      case optional: Schema.Optional[?]       => Node.Optional(value(optional.schema))
      case sequence: Schema.Sequence[?, ?, ?] =>
        Node.Many(value(sequence.elementSchema), sequence.asInstanceOf[Schema.Collection[Any, Any]])
      case set: Schema.Set[?] =>
        Node.Many(value(set.elementSchema), set.asInstanceOf[Schema.Collection[Any, Any]])
      case record: Schema.Record[?] =>
        Node.Record(
          record.asInstanceOf[Schema.Record[Any]],
          record.fields.map(field =>
            Param(
              field.fieldName,
              fieldNode(field),
              field.annotations.collectFirst { case default: fieldDefaultValue[?] => default.value }
            )
          )
        )
      case transform: Schema.Transform[?, ?, ?] =>
        val erased = transform.asInstanceOf[Schema.Transform[Any, Any, ?]]
        node(transform.schema) match
          case record: Node.Record => Node.Mapped(record, erased)
          case _                   => scalar(erased)
      case enumeration: Schema.Enum[?] if enumeration.cases.forall(isSingleton) =>
        Node.Singletons(enumeration.asInstanceOf[Schema.Enum[Any]])
      case other => scalar(other.asInstanceOf[Schema[Any]])

  private def fieldNode(field: Schema.Field[?, ?]): Node =
    try node(field.schema)
    catch
      case error: IllegalArgumentException =>
        throw IllegalArgumentException(
          s"Query field ${field.fieldName}: ${error.getMessage}",
          error
        )

  /** A node holding one value per name, as a list element or an option needs. */
  private def value(schema: Schema[?]): Node =
    node(schema) match
      case single: (Node.Scalar | Node.Singletons) => single
      case _ => throw IllegalArgumentException(s"A query parameter cannot hold $schema")

  /** Checks once, when the codec is built, that ZIO HTTP can read the value. */
  private def scalar(schema: Schema[Any]): Node =
    val _ = QueryParams.empty.query[Any](ValueName)(using schema)
    Node.Scalar(schema)

  private def decodeRecord(
    name: String,
    params: QueryParams,
    schema: Schema.Record[Any],
    fields: Chunk[Param]
  ): Decoded =
    val decoded = fields.map(field => field.copy(name = qualified(name, field.name)).decode(params))
    decoded
      .collectFirst {
        case missing: Decoded.Missing     => missing
        case malformed: Decoded.Malformed => malformed
      }.getOrElse {
        val values    = decoded.collect { case Decoded.Value(value, _) => value }
        val fallbacks = decoded.flatMap {
          case Decoded.Value(_, fallbacks) => fallbacks
          case _                           => Chunk.empty
        }
        Unsafe.unsafe(schema.construct(values)(using _)) match
          case Right(value) => Decoded.Value(value, fallbacks)
          case Left(reason) => Decoded.Malformed(name, reason)
      }

  private def single(name: String, params: QueryParams)(read: String => Either[String, Any])
    : Decoded =
    params.getAll(name) match
      case Chunk()    => Decoded.Missing(name)
      case Chunk(raw) =>
        read(raw) match
          case Right(value) => Decoded.Value(value, Chunk.empty)
          case Left(reason) => Decoded.Malformed(name, s"'$raw': $reason")
      case values => Decoded.Malformed(name, s"expected one value, found ${values.size}")

  private def qualified(prefix: String, name: String): String =
    if prefix.isEmpty then name else s"$prefix.$name"

  private def isSingleton(enumCase: Schema.Case[?, ?]): Boolean =
    unlazy(enumCase.schema) match
      case record: Schema.Record[?] => record.fields.isEmpty
      case _                        => false

  private def singleton(enumCase: Schema.Case[Any, ?]): Any =
    val record = unlazy(enumCase.schema).asInstanceOf[Schema.Record[Any]]
    Unsafe.unsafe(record.construct(Chunk.empty)(using _)) match
      case Right(value) => enumCase.asInstanceOf[Schema.Case[Any, Any]].construct(value)
      case Left(reason) => throw IllegalStateException(reason)

  private def unlazy(schema: Schema[?]): Schema[?] = schema match
    case lazySchema: Schema.Lazy[?] => unlazy(lazySchema.schema)
    case other                      => other
end SchemaQuery
