package scalive

/** Structural decoding and encoding for a field's editable input type.
  *
  * Decoding receives every submitted value, in encounter order. It should report malformed control
  * shape here; semantic refinement belongs in [[FormField.emap]].
  */
trait FieldInput[Input]:
  /** Decodes the complete raw value vector without discarding duplicate values. */
  def decode(raw: Vector[String]): Either[FieldIssues, Input]

  /** Encodes an editable value to the raw representation retained by a [[Form]]. */
  def encode(input: Input): Vector[String]

/** Constructors for common editable-input shapes and custom structural codecs. */
object FieldInput:
  /** Builds a bidirectional editable-input codec. */
  def apply[Input](
    decoder: Vector[String] => Either[FieldIssues, Input],
    encoder: Input => Vector[String]
  ): FieldInput[Input] = new FieldInput[Input]:
    def decode(raw: Vector[String]): Either[FieldIssues, Input] = decoder(raw)
    def encode(input: Input): Vector[String]                    = encoder(input)

  /** A single-value text codec; absence becomes `default` and duplicates are rejected. */
  def text(
    default: String = "",
    duplicateIssue: FieldIssue = FieldIssue(
      "must be submitted at most once",
      Some("duplicate_value")
    )
  ): FieldInput[String] = FieldInput(
    {
      case Vector()      => Right(default)
      case Vector(value) => Right(value)
      case _             => Left(FieldIssues.one(duplicateIssue))
    },
    value => Vector(value)
  )

  /** A single-value codec where absence and the configured `empty` value decode to `None`. */
  def optionalText(
    empty: String = "",
    duplicateIssue: FieldIssue = FieldIssue(
      "must be submitted at most once",
      Some("duplicate_value")
    )
  ): FieldInput[Option[String]] = FieldInput(
    {
      case Vector()                        => Right(None)
      case Vector(value) if value == empty => Right(None)
      case Vector(value)                   => Right(Some(value))
      case _                               => Left(FieldIssues.one(duplicateIssue))
    },
    _.fold(Vector.empty[String])(Vector(_))
  )

  /** Preserves all submitted values verbatim and in encounter order. */
  val texts: FieldInput[Vector[String]] = FieldInput(Right(_), identity)
end FieldInput
