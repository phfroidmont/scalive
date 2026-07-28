package scalive

final case class FormSubmitter(name: String, value: String)

final case class FormState[+A](
  raw: FormData,
  value: Either[FormErrors, A],
  used: Set[FormPath],
  submitted: Boolean):
  def isValid: Boolean       = value.isRight
  def errors: FormErrors     = value.left.getOrElse(FormErrors.empty)
  def valueOption: Option[A] = value.toOption

  def isUsed(path: FormPath): Boolean =
    submitted || used.contains(path)

  def isUsed(name: String): Boolean =
    isUsed(FormPath.parse(name))

  def errorsFor(path: FormPath): Vector[FormError] =
    errors.forPath(path)

  def errorsFor(name: String): Vector[FormError] =
    errors.forName(name)

object FormState:
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
