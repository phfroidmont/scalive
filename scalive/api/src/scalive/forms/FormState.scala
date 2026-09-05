package scalive

/** Untrusted successful-control metadata reported by a client protocol. */
final case class RawFormSubmitter(name: String, value: String)

enum ErrorVisibility derives CanEqual:
  case UsedOnly
  case All

/** Owner-scoped validation visibility, independent of editable form values. */
final class FormInteraction[Owner] private[scalive] (
  val used: Set[FormAddress[Owner]],
  val visibility: ErrorVisibility)
    derives CanEqual:

  def isUsed(address: FormAddress[Owner]): Boolean =
    visibility == ErrorVisibility.All || used.contains(address)

  private[scalive] def without(prefix: FormAddress[Owner]): FormInteraction[Owner] =
    FormInteraction(used.filterNot(_.startsWith(prefix)), visibility)

  override def equals(other: Any): Boolean = other match
    case that: FormInteraction[?] => used == that.used && visibility == that.visibility
    case _                        => false

  override def hashCode(): Int = used.hashCode() * 31 + visibility.hashCode()

object FormInteraction:
  def pristine[Owner]: FormInteraction[Owner] =
    new FormInteraction(Set.empty, ErrorVisibility.UsedOnly)

  private[scalive] def apply[Owner](
    used: Set[FormAddress[Owner]],
    visibility: ErrorVisibility
  ): FormInteraction[Owner] = new FormInteraction(used, visibility)

/** Interaction state for an explicitly low-level codec-backed event. */
final class RawFormState[+A] private[scalive] (
  val raw: FormData,
  val value: Either[FormErrors[Any], A],
  val used: Set[FormPath],
  val submitted: Boolean):

  def isValid: Boolean                = value.isRight
  def errors: FormErrors[Any]         = value.left.getOrElse(FormErrors.empty)
  def valueOption: Option[A]          = value.toOption
  def isUsed(path: FormPath): Boolean = submitted || used.contains(path)
  def isUsed(name: String): Boolean   = FormPath.parse(name).exists(isUsed)

private[scalive] object RawFormState:
  def apply[A](raw: FormData, value: Either[FormErrors[Any], A], submitted: Boolean)
    : RawFormState[A] =
    val parsed   = raw.raw.iterator.flatMap(pair => FormPath.parse(pair._1).toOption).toVector
    val ordinary = parsed.filter(path => RawFormState.unusedPath(path).isEmpty).toSet
    val used     =
      if submitted then ordinary
      else ordinary -- parsed.flatMap(RawFormState.unusedPath)
    new RawFormState(raw, value, used, submitted)

  private def unusedPath(path: FormPath): Option[FormPath] =
    path.segments.lastOption
      .collect {
        case FormPathSegment.Name(value) if value.startsWith("_unused_") =>
          value.stripPrefix("_unused_")
      }.filter(_.nonEmpty).map { field =>
        FormPath.fromSegments(path.segments.init :+ FormPathSegment.Name(field))
      }
