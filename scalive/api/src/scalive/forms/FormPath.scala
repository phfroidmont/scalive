package scalive

/** A structured form field path rendered with browser bracket notation.
  *
  * Empty segments represent array brackets when a path is constructed explicitly with [[array]].
  * Parsing is deliberately permissive and lossy: [[FormPath.parse parse]] ignores stray closing
  * brackets, accepts an unterminated opening bracket, and drops empty segments, including `[]`. It
  * is intended to normalize browser-provided names rather than validate their grammar.
  *
  * For example:
  * {{{
  * val city = FormPath("user", "address", "city")
  * city.name                         // "user[address][city]"
  * FormPath("tags").array.name      // "tags[]"
  * FormPath.parse("tags[]")         // FormPath(Vector("tags"))
  * }}}
  *
  * @param segments
  *   path segments in order; an empty segment renders as `[]` after the first segment
  */
final case class FormPath(segments: Vector[String]):
  /** Appends `segment` without parsing or filtering it. An empty segment renders as array brackets.
    */
  def /(segment: String): FormPath =
    copy(segments = segments :+ segment)

  /** Appends an empty segment so that a non-empty path's browser name ends in `[]`. */
  def array: FormPath =
    copy(segments = segments :+ "")

  /** Whether this path has no segments. */
  def isEmpty: Boolean = segments.isEmpty

  /** Whether this path has at least one segment. */
  def nonEmpty: Boolean = segments.nonEmpty

  /** Renders the path as a browser field name, such as `user[address][city]`. */
  def name: String =
    segments.headOption.fold("") { first =>
      first + segments.tail.map(segment => s"[$segment]").mkString
    }

  /** Returns a conventional DOM id by dropping empty segments and joining the rest with `_`.
    *
    * This transformation is not injective, so callers that need a unique id must handle collisions.
    */
  def id: String =
    segments.filter(_.nonEmpty).mkString("_")

  /** Whether this path begins with exactly the same segments as `prefix`. */
  def startsWith(prefix: FormPath): Boolean =
    segments.startsWith(prefix.segments)

  /** Returns [[name]]. */
  override def toString: String = name
end FormPath

/** Constructors and parsing utilities for [[FormPath]]. */
object FormPath:
  /** The path with no segments; its [[FormPath.name name]] and [[FormPath.id id]] are empty. */
  val empty: FormPath = FormPath(Vector.empty)

  /** Constructs a path from non-empty arguments, silently dropping every empty argument.
    *
    * Use [[FormPath.array array]] when an empty array segment must be retained.
    */
  def apply(first: String, rest: String*): FormPath =
    FormPath((first +: rest).filter(_.nonEmpty).toVector)

  /** Permissively parses bracket notation into non-empty segments.
    *
    * Plain text and bracket contents become segments. Empty bracket contents are discarded, stray
    * `]` characters are ignored, and an unterminated `[` consumes the remaining text. Consequently,
    * parsing and rendering do not round-trip array brackets or malformed input.
    */
  def parse(name: String): FormPath =
    if name.isEmpty then empty
    else
      val segments = Vector.newBuilder[String]
      val current  = new StringBuilder()
      var index    = 0

      def pushCurrent(): Unit =
        if current.nonEmpty then
          segments += current.toString
          current.clear()

      while index < name.length do
        name.charAt(index) match
          case '[' =>
            pushCurrent()
            index = index + 1
            while index < name.length && name.charAt(index) != ']' do
              current.append(name.charAt(index))
              index = index + 1
            pushCurrent()
          case ']'  =>
          case char =>
            current.append(char)
        index = index + 1

      pushCurrent()
      FormPath(segments.result().filter(_.nonEmpty))
end FormPath
