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
    val paths = raw.raw.iterator
      .map(_._1)
      .filterNot(_.startsWith(unusedPrefix))
      .map(FormPath.parse)
      .filter(_.nonEmpty)
      .toSet

    if submitted then paths
    else paths -- unusedPaths(raw)

  private def unusedPaths(raw: FormData): Set[FormPath] =
    raw.raw.iterator.collect {
      case (name, _) if name.startsWith(unusedPrefix) =>
        FormPath.parse(name.stripPrefix(unusedPrefix))
    }.toSet

  private val unusedPrefix = "_unused_"
