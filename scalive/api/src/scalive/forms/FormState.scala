package scalive

/** The successful control used to submit a form, when the client reports one.
  *
  * @param name
  *   the submit control's browser field name
  * @param value
  *   the submit control's value, which may be empty
  */
final case class FormSubmitter(name: String, value: String)

/** Decoded form data together with validation visibility state.
  *
  * The four-argument case-class constructor is intentionally low-level: it does not verify that
  * [[value]] was decoded from [[raw]] or that [[used]] agrees with the payload. The three-argument
  * The three-argument [[FormState.apply apply]] derives used paths from LiveView's `_unused_`
  * markers and is the usual constructor for received form data.
  *
  * @param raw
  *   the ordered form payload used for rendering raw field values
  * @param value
  *   either ordered validation errors or the decoded value
  * @param used
  *   exact paths marked used; inspect through [[isUsed]] when rendering validation feedback
  * @param submitted
  *   whether the state came from a typed `phx-submit` binding; when true, [[isUsed]] returns true
  *   for every path, including paths absent from [[used]]
  * @tparam A
  *   the successfully decoded form value
  */
final case class FormState[+A](
  raw: FormData,
  value: Either[FormErrors, A],
  used: Set[FormPath],
  submitted: Boolean):
  /** Whether decoding and validation succeeded. */
  def isValid: Boolean = value.isRight

  /** The decoding errors, or [[FormErrors.empty]] when valid. */
  def errors: FormErrors = value.left.getOrElse(FormErrors.empty)

  /** The decoded value when valid. */
  def valueOption: Option[A] = value.toOption

  /** Whether validation feedback for `path` should be visible.
    *
    * Every path is considered used after submission; otherwise membership in [[used]] is exact.
    */
  def isUsed(path: FormPath): Boolean =
    submitted || used.contains(path)

  /** Permissively parses `name`, then checks whether that path is used. */
  def isUsed(name: String): Boolean =
    isUsed(FormPath.parse(name))

  /** Returns validation errors at exactly `path`, preserving order. */
  def errorsFor(path: FormPath): Vector[FormError] =
    errors.forPath(path)

  /** Permissively parses `name`, then returns matching validation errors in order. */
  def errorsFor(name: String): Vector[FormError] =
    errors.forName(name)
end FormState

/** Constructors for [[FormState]]. */
object FormState:
  /** Creates state and derives its used-path set from the raw payload.
    *
    * When `submitted` is false, a final segment such as `_unused_email` marks sibling `email` as
    * unused and is itself excluded. Other non-empty parsed names are marked used. When `submitted`
    * is true, marker paths are excluded but no sibling paths are removed; independently,
    * [[FormState.isUsed]] treats every path as used. Path parsing inherits [[FormPath.parse]]'s
    * permissive and lossy rules.
    */
  def apply[A](raw: FormData, value: Either[FormErrors, A], submitted: Boolean): FormState[A] =
    FormState(raw, value, usedPaths(raw, submitted), submitted)

  private def usedPaths(raw: FormData, submitted: Boolean): Set[FormPath] =
    val parsedPaths = raw.raw.iterator
      .map(_._1)
      .map(FormPath.parse)
      .filter(_.nonEmpty)
      .toVector
    val paths = parsedPaths.iterator
      .filter(path => unusedPath(path).isEmpty)
      .toSet

    if submitted then paths
    else paths -- parsedPaths.flatMap(unusedPath)

  private def unusedPath(path: FormPath): Option[FormPath] =
    path.segments.lastOption
      .filter(_.startsWith(unusedPrefix))
      .map(_.stripPrefix(unusedPrefix))
      .filter(_.nonEmpty)
      .map(field => FormPath(path.segments.init :+ field))

  private val unusedPrefix = "_unused_"
end FormState
