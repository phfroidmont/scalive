package scalive

/** One pathless field decoding or validation issue. */
final case class FieldIssue(message: String, code: Option[String] = None)

/** A non-empty, ordered collection of field issues. */
final class FieldIssues private (val all: Vector[FieldIssue]) derives CanEqual:
  require(all.nonEmpty, "field issues must not be empty")

  def ++(other: FieldIssues): FieldIssues = FieldIssues(all ++ other.all)

  override def equals(other: Any): Boolean = other match
    case that: FieldIssues => all == that.all
    case _                 => false

  override def hashCode(): Int = all.hashCode()

object FieldIssues:
  def apply(issues: IterableOnce[FieldIssue]): FieldIssues =
    new FieldIssues(issues.iterator.toVector)

  def one(issue: FieldIssue): FieldIssues = new FieldIssues(Vector(issue))

  def one(message: String, code: Option[String] = None): FieldIssues =
    one(FieldIssue(message, code))

/** One issue attached to its stable logical form address. */
final case class FormError[Owner](address: FormAddress[Owner], issue: FieldIssue):
  def message: String      = issue.message
  def code: Option[String] = issue.code

/** An immutable ordered collection of owner-scoped form errors. */
final class FormErrors[Owner] private (val all: Vector[FormError[Owner]]) derives CanEqual:
  def isEmpty: Boolean  = all.isEmpty
  def nonEmpty: Boolean = all.nonEmpty

  def +(error: FormError[Owner]): FormErrors[Owner] = FormErrors(all :+ error)

  def ++(other: FormErrors[Owner]): FormErrors[Owner] = FormErrors(all ++ other.all)

  def forAddress(address: FormAddress[Owner]): Vector[FormError[Owner]] =
    all.filter(_.address == address)

  def below(address: FormAddress[Owner]): Vector[FormError[Owner]] =
    all.filter(_.address.startsWith(address))

  override def equals(other: Any): Boolean = other match
    case that: FormErrors[?] => all == that.all
    case _                   => false

  override def hashCode(): Int = all.hashCode()

object FormErrors:
  def empty[Owner]: FormErrors[Owner] = new FormErrors(Vector.empty)

  def apply[Owner](errors: IterableOnce[FormError[Owner]]): FormErrors[Owner] =
    new FormErrors(errors.iterator.toVector)

  def one[Owner](address: FormAddress[Owner], issue: FieldIssue): FormErrors[Owner] =
    new FormErrors(Vector(FormError(address, issue)))
