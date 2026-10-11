package scalive

/** One component of a browser form field name. */
enum FormPathSegment derives CanEqual:
  /** A literal, non-empty name component.
    *
    * @param value
    *   The component text. Path constructors reject an empty value or one containing `[` or `]`.
    */
  case Name(value: String)

  /** An empty bracket component (`[]`). */
  case Array

/** A structured form field path rendered with browser bracket notation.
  *
  * This is a wire/rendering path and may contain array segments. Use [[FormAddress]] for stable,
  * owner-scoped validation identity, especially inside repeated rows.
  *
  * @param segments
  *   The segments in order. A non-empty path always starts with a name segment.
  */
final case class FormPath private (segments: Vector[FormPathSegment]):
  import FormPathSegment.*

  /** Appends a trusted literal name segment.
    *
    * This constructor is intended for application-defined names. Invalid literal names fail fast;
    * use [[FormPath.parse]] for untrusted browser input.
    */
  def /(segment: String): FormPath =
    FormPath.create(segments :+ FormPath.trustedName(segment))

  /** Appends an explicit array segment. */
  def array: FormPath =
    if isEmpty then
      throw new IllegalArgumentException("a form path cannot begin with an array segment")
    else FormPath.create(segments :+ Array)

  /** Whether this path has no segments. */
  def isEmpty: Boolean = segments.isEmpty

  /** Whether this path has at least one segment. */
  def nonEmpty: Boolean = segments.nonEmpty

  /** Renders this path as a browser field name, such as `user[address][city]`. */
  def name: String =
    segments.headOption.fold("") {
      case Name(first) =>
        first + segments.tail.map {
          case Name(value) => s"[$value]"
          case Array       => "[]"
        }.mkString
      case Array =>
        throw new IllegalStateException("a form path cannot begin with an array segment")
    }

  /** Returns an injective, ASCII-only DOM id for this path.
    *
    * Name segments are encoded as UTF-16 code units and array segments have a distinct marker. The
    * `fp` prefix also ensures that non-empty ids begin with an ASCII letter.
    */
  def id: String =
    val result = new StringBuilder("fp")
    segments.foreach {
      case Array       => result.append("_a")
      case Name(value) =>
        result.append("_n")
        value.foreach { char =>
          val encoded = Integer.toHexString(char.toInt)
          result.append("0" * (4 - encoded.length))
          result.append(encoded)
        }
    }
    result.result()

  /** Whether this path begins with exactly the same segments as `prefix`. */
  def startsWith(prefix: FormPath): Boolean =
    segments.startsWith(prefix.segments)

  /** Returns [[name]]. */
  override def toString: String = name
end FormPath

/** Constructors and strict parsing utilities for [[FormPath]]. */
object FormPath:
  import FormPathSegment.*

  /** Resource limits applied while parsing an untrusted browser name.
    *
    * Both limits must be positive; constructing other limits throws `IllegalArgumentException`.
    *
    * @param maxDepth
    *   The maximum number of segments, including the root name.
    * @param maxSegmentLength
    *   The maximum length of one segment, in UTF-16 code units.
    */
  final case class ParseLimits(maxDepth: Int = 32, maxSegmentLength: Int = 256):
    require(maxDepth > 0, "maxDepth must be positive")
    require(maxSegmentLength > 0, "maxSegmentLength must be positive")

  /** The companion of [[ParseLimits]], holding the defaults. */
  object ParseLimits:
    /** The limits [[FormPath.parse]] applies when none are given.
      *
      * They allow 32 segments of at most 256 UTF-16 code units each.
      */
    val default: ParseLimits = ParseLimits()

  /** A stable description of why a browser name has no `FormPath` representation. */
  enum RepresentationError derives CanEqual:
    /** The name is `null`. */
    case NullName

    /** The name is empty or starts with `[`, so it has no root name. */
    case MissingRootName

    /** A `[` appears inside a bracketed segment.
      *
      * @param offset
      *   The zero-based offset of the unexpected `[`.
      */
    case UnexpectedOpeningBracket(offset: Int)

    /** A `]` appears without a matching `[`.
      *
      * @param offset
      *   The zero-based offset of the unexpected `]`.
      */
    case UnexpectedClosingBracket(offset: Int)

    /** A character other than `[` follows a closing `]`.
      *
      * @param offset
      *   The zero-based offset of the unexpected character.
      */
    case ExpectedOpeningBracket(offset: Int)

    /** A `[` has no matching `]` before the end of the name.
      *
      * @param offset
      *   The zero-based offset of the unterminated `[`.
      */
    case UnterminatedBracket(offset: Int)

    /** The name has more segments than the depth limit allows.
      *
      * @param maxDepth
      *   The maximum number of segments that was exceeded.
      */
    case PathTooDeep(maxDepth: Int)

    /** A segment is longer than the segment length limit allows.
      *
      * @param segmentIndex
      *   The zero-based index of the segment that is too long, where 0 is the root name.
      * @param maxLength
      *   The maximum segment length that was exceeded.
      */
    case SegmentTooLong(segmentIndex: Int, maxLength: Int)

    /** Stable machine-readable error code. */
    def code: String = this match
      case NullName                    => "null_name"
      case MissingRootName             => "missing_root_name"
      case UnexpectedOpeningBracket(_) => "unexpected_opening_bracket"
      case UnexpectedClosingBracket(_) => "unexpected_closing_bracket"
      case ExpectedOpeningBracket(_)   => "expected_opening_bracket"
      case UnterminatedBracket(_)      => "unterminated_bracket"
      case PathTooDeep(_)              => "path_too_deep"
      case SegmentTooLong(_, _)        => "segment_too_long"
  end RepresentationError

  /** The path with no segments. */
  val empty: FormPath = create(Vector.empty)

  /** Creates a path from trusted segments, throwing `IllegalArgumentException` if one is invalid.
    *
    * This form takes literal names, as in `FormPath("user", "address")`. Each name must be
    * non-empty and contain no brackets. Use [[FormPath.parse]] for untrusted browser input.
    */
  def apply(first: String, rest: String*): FormPath =
    create((first +: rest).map(trustedName).toVector)

  /** Creates a path from trusted segments, throwing `IllegalArgumentException` if one is invalid.
    *
    * This form takes explicit segments, so it can include array segments, as in
    * `FormPath(Name("user"), Name("tags"), Array)`. The first segment must be a name. Use
    * [[FormPath.parse]] for untrusted browser input.
    */
  def apply(first: FormPathSegment, rest: FormPathSegment*): FormPath =
    val segments = (first +: rest).toVector
    validateTrustedSegments(segments)
    create(segments)

  /** Constructs a path from a trusted sequence of explicit segment values. */
  def fromSegments(segments: IterableOnce[FormPathSegment]): FormPath =
    val values = segments.iterator.toVector
    validateTrustedSegments(values)
    create(values)

  /** Parses an untrusted browser field name, such as `user[tags][]`, without normalizing it.
    *
    * This form applies [[ParseLimits.default]]. A malformed name or one over the limits returns a
    * [[RepresentationError]] rather than throwing.
    */
  def parse(name: String): Either[RepresentationError, FormPath] =
    parse(name, ParseLimits.default)

  /** Parses an untrusted browser field name, such as `user[tags][]`, without normalizing it.
    *
    * This form applies `limits`. A malformed name or one over the limits returns a
    * [[RepresentationError]] rather than throwing.
    */
  def parse(name: String, limits: ParseLimits): Either[RepresentationError, FormPath] =
    import RepresentationError.*

    if name == null then Left(NullName)
    else if name.isEmpty then Left(MissingRootName)
    else
      val parsed       = Vector.newBuilder[FormPathSegment]
      var segmentCount = 0

      def append(segment: FormPathSegment): Either[RepresentationError, Unit] =
        if segmentCount >= limits.maxDepth then Left(PathTooDeep(limits.maxDepth))
        else
          parsed += segment
          segmentCount += 1
          Right(())

      var index = 0
      while index < name.length && name.charAt(index) != '[' && name.charAt(index) != ']' do
        if index >= limits.maxSegmentLength then
          return Left(SegmentTooLong(0, limits.maxSegmentLength))
        index += 1

      if index == 0 then
        if name.charAt(0) == '[' then Left(MissingRootName)
        else Left(UnexpectedClosingBracket(0))
      else if index < name.length && name.charAt(index) == ']' then
        Left(UnexpectedClosingBracket(index))
      else
        append(Name(name.substring(0, index))) match
          case Left(error) => Left(error)
          case Right(_)    =>
            while index < name.length do
              if name.charAt(index) == ']' then return Left(UnexpectedClosingBracket(index))
              if name.charAt(index) != '[' then return Left(ExpectedOpeningBracket(index))

              val openingOffset = index
              index += 1
              if index == name.length then return Left(UnterminatedBracket(openingOffset))

              if name.charAt(index) == ']' then
                append(Array) match
                  case Left(error) => return Left(error)
                  case Right(_)    => index += 1
              else
                val segmentStart = index
                while index < name.length && name.charAt(index) != ']' do
                  if name.charAt(index) == '[' then return Left(UnexpectedOpeningBracket(index))
                  if index - segmentStart >= limits.maxSegmentLength then
                    return Left(SegmentTooLong(segmentCount, limits.maxSegmentLength))
                  index += 1

                if index == name.length then return Left(UnterminatedBracket(openingOffset))
                append(Name(name.substring(segmentStart, index))) match
                  case Left(error) => return Left(error)
                  case Right(_)    => index += 1

            Right(create(parsed.result()))
      end if
    end if
  end parse

  private def trustedName(value: String): FormPathSegment.Name =
    if value == null || value.isEmpty || value.exists(char => char == '[' || char == ']') then
      throw new IllegalArgumentException(
        "a form path name segment must be non-empty and contain no brackets"
      )
    Name(value)

  private def validateTrustedSegments(segments: Vector[FormPathSegment]): Unit =
    segments.headOption.foreach {
      case Array =>
        throw new IllegalArgumentException("a form path cannot begin with an array segment")
      case Name(_) => ()
    }
    segments.foreach {
      case Name(value) => trustedName(value)
      case Array       => ()
    }

  private def create(segments: Vector[FormPathSegment]): FormPath =
    new FormPath(segments)
end FormPath
