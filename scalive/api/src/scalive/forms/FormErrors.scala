package scalive

/** One pathless field decoding or validation issue.
  *
  * @param message
  *   The stable message or localization key to show the visitor.
  * @param code
  *   An optional machine-readable code, such as `required`.
  */
final case class FieldIssue(message: String, code: Option[String] = None)

/** A non-empty, ordered collection of field issues.
  *
  * @param all
  *   The issues in the order they were reported; never empty.
  */
final class FieldIssues private (val all: Vector[FieldIssue]) derives CanEqual:
  require(all.nonEmpty, "field issues must not be empty")

  /** Returns these issues followed by those of `other`. */
  def ++(other: FieldIssues): FieldIssues = FieldIssues(all ++ other.all)

  override def equals(other: Any): Boolean = other match
    case that: FieldIssues => all == that.all
    case _                 => false

  override def hashCode(): Int = all.hashCode()

/** Constructors for [[FieldIssues]]. */
object FieldIssues:
  /** Creates a collection from `issues` in order, throwing when `issues` is empty. */
  def apply(issues: IterableOnce[FieldIssue]): FieldIssues =
    new FieldIssues(issues.iterator.toVector)

  /** Creates a collection holding a single issue.
    *
    * This form takes an existing [[FieldIssue]].
    */
  def one(issue: FieldIssue): FieldIssues = new FieldIssues(Vector(issue))

  /** Creates a collection holding a single issue.
    *
    * This form builds the issue from `message` and an optional machine-readable `code`.
    */
  def one(message: String, code: Option[String] = None): FieldIssues =
    one(FieldIssue(message, code))

/** One issue attached to its stable logical form address.
  *
  * @param address
  *   The logical address of the field, row, group, or form the issue belongs to.
  * @param issue
  *   The issue reported at that address.
  */
final case class FormError[Owner](address: FormAddress[Owner], issue: FieldIssue):
  /** Returns the issue's message or localization key. */
  def message: String = issue.message

  /** Returns the issue's optional machine-readable code. */
  def code: Option[String] = issue.code

/** An immutable ordered collection of owner-scoped form errors.
  *
  * @param all
  *   The errors in the order they were reported.
  */
final class FormErrors[Owner] private (val all: Vector[FormError[Owner]]) derives CanEqual:
  /** Returns whether the collection holds no errors. */
  def isEmpty: Boolean = all.isEmpty

  /** Returns whether the collection holds at least one error. */
  def nonEmpty: Boolean = all.nonEmpty

  /** Returns these errors with `error` appended. */
  def +(error: FormError[Owner]): FormErrors[Owner] = FormErrors(all :+ error)

  /** Returns these errors followed by those of `other`. */
  def ++(other: FormErrors[Owner]): FormErrors[Owner] = FormErrors(all ++ other.all)

  /** Returns the errors attached exactly to `address`, excluding those below it. */
  def forAddress(address: FormAddress[Owner]): Vector[FormError[Owner]] =
    all.filter(_.address == address)

  /** Returns the errors at `address` or at any address below it, such as a row's field errors. */
  def below(address: FormAddress[Owner]): Vector[FormError[Owner]] =
    all.filter(_.address.startsWith(address))

  override def equals(other: Any): Boolean = other match
    case that: FormErrors[?] => all == that.all
    case _                   => false

  override def hashCode(): Int = all.hashCode()

/** Constructors for [[FormErrors]]. */
object FormErrors:
  /** Returns an empty collection. */
  def empty[Owner]: FormErrors[Owner] = new FormErrors(Vector.empty)

  /** Creates a collection from `errors`, keeping their order. */
  def apply[Owner](errors: IterableOnce[FormError[Owner]]): FormErrors[Owner] =
    new FormErrors(errors.iterator.toVector)

  /** Creates a collection with one issue at `address`.
    *
    * Prefer [[FormDefinition.errors]], which also checks that a field or group belongs to the
    * definition.
    */
  def one[Owner](address: FormAddress[Owner], issue: FieldIssue): FormErrors[Owner] =
    new FormErrors(Vector(FormError(address, issue)))
