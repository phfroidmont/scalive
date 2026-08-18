package scalive

/** One validation error associated with a form path.
  *
  * @param path
  *   the exact structured path used when filtering errors
  * @param message
  *   human-readable validation feedback
  * @param code
  *   optional application-defined machine-readable classification
  */
final case class FormError(path: FormPath, message: String, code: Option[String] = None)

/** Name-based constructors for [[FormError]]. */
object FormError:
  /** Creates an uncoded error by permissively parsing `name` as a [[FormPath]]. */
  def apply(name: String, message: String): FormError =
    FormError(FormPath.parse(name), message)

  /** Creates a coded error by permissively parsing `name` as a [[FormPath]]. */
  def apply(name: String, message: String, code: String): FormError =
    FormError(FormPath.parse(name), message, Some(code))

/** An immutable, ordered collection of validation errors.
  *
  * Error order is retained when constructing, appending, concatenating, and filtering. Duplicate
  * errors are not removed.
  *
  * @param all
  *   all errors in reporting order
  */
final case class FormErrors private (all: Vector[FormError]):
  /** Whether there are no errors. */
  def isEmpty: Boolean = all.isEmpty

  /** Whether there is at least one error. */
  def nonEmpty: Boolean = all.nonEmpty

  /** Appends `error` after all existing errors. */
  def +(error: FormError): FormErrors =
    FormErrors(all :+ error)

  /** Concatenates errors with this collection's errors first. */
  def ++(other: FormErrors): FormErrors =
    FormErrors(all ++ other.all)

  /** Returns errors whose path is exactly equal to `path`, preserving order. */
  def forPath(path: FormPath): Vector[FormError] =
    all.filter(_.path == path)

  /** Permissively parses `name`, then returns exact-path matches in order. */
  def forName(name: String): Vector[FormError] =
    forPath(FormPath.parse(name))

  /** Returns messages for the exact `path`, preserving error order. */
  def messages(path: FormPath): Vector[String] =
    forPath(path).map(_.message)

  /** Permissively parses `name`, then returns matching messages in order. */
  def messages(name: String): Vector[String] =
    messages(FormPath.parse(name))
end FormErrors

/** Constructors for ordered [[FormErrors]] collections. */
object FormErrors:
  /** The empty error collection. */
  val empty: FormErrors = FormErrors(Vector.empty)

  /** Consumes `errors` once and retains its iteration order and duplicates. */
  def apply(errors: IterableOnce[FormError]): FormErrors =
    new FormErrors(errors.iterator.toVector)

  /** Creates a collection containing one error at `path`. */
  def one(path: FormPath, message: String, code: Option[String] = None): FormErrors =
    FormErrors(Vector(FormError(path, message, code)))

  /** Creates one uncoded error after permissively parsing `name`. */
  def one(name: String, message: String): FormErrors =
    one(FormPath.parse(name), message)

  /** Creates one coded error after permissively parsing `name`. */
  def one(name: String, message: String, code: String): FormErrors =
    one(FormPath.parse(name), message, Some(code))
