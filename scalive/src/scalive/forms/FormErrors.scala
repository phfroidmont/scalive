package scalive

final case class FormError(path: FormPath, message: String, code: Option[String] = None)

object FormError:
  def apply(name: String, message: String): FormError =
    FormError(FormPath.parse(name), message)

  def apply(name: String, message: String, code: String): FormError =
    FormError(FormPath.parse(name), message, Some(code))

final case class FormErrors private (all: Vector[FormError]):
  def isEmpty: Boolean  = all.isEmpty
  def nonEmpty: Boolean = all.nonEmpty

  def +(error: FormError): FormErrors =
    FormErrors(all :+ error)

  def ++(other: FormErrors): FormErrors =
    FormErrors(all ++ other.all)

  def forPath(path: FormPath): Vector[FormError] =
    all.filter(_.path == path)

  def forName(name: String): Vector[FormError] =
    forPath(FormPath.parse(name))

  def messages(path: FormPath): Vector[String] =
    forPath(path).map(_.message)

  def messages(name: String): Vector[String] =
    messages(FormPath.parse(name))

object FormErrors:
  val empty: FormErrors = FormErrors(Vector.empty)

  def apply(errors: IterableOnce[FormError]): FormErrors =
    new FormErrors(errors.iterator.toVector)

  def one(path: FormPath, message: String, code: Option[String] = None): FormErrors =
    FormErrors(Vector(FormError(path, message, code)))

  def one(name: String, message: String): FormErrors =
    one(FormPath.parse(name), message)

  def one(name: String, message: String, code: String): FormErrors =
    one(FormPath.parse(name), message, Some(code))
