package scalive

/** Untrusted successful-control metadata reported by a client protocol. */
final case class RawFormSubmitter(name: String, value: String)

enum ErrorVisibility derives CanEqual:
  case UsedOnly
  case All

/** Policy controlling when interaction makes field errors visible. */
enum FormFeedback derives CanEqual:
  case WhenUsed
  case AfterBlur

/** Owner-scoped validation visibility, independent of editable form values. */
final class FormInteraction[Owner] private[scalive] (
  val used: Set[FormAddress[Owner]],
  val visibility: ErrorVisibility,
  val feedback: FormFeedback,
  val blurred: Set[FormAddress[Owner]])
    derives CanEqual:

  def isUsed(address: FormAddress[Owner]): Boolean =
    visibility == ErrorVisibility.All || used.contains(address)

  def isBlurred(address: FormAddress[Owner]): Boolean = blurred.contains(address)

  def isVisible(address: FormAddress[Owner]): Boolean =
    visibility == ErrorVisibility.All ||
      (feedback match
        case FormFeedback.WhenUsed  => used.contains(address)
        case FormFeedback.AfterBlur => blurred.contains(address))

  private[scalive] def without(prefix: FormAddress[Owner]): FormInteraction[Owner] =
    FormInteraction(
      used.filterNot(_.startsWith(prefix)),
      visibility,
      feedback,
      blurred.filterNot(_.startsWith(prefix))
    )

  override def equals(other: Any): Boolean = other match
    case that: FormInteraction[?] =>
      used == that.used && visibility == that.visibility && feedback == that.feedback &&
      blurred == that.blurred
    case _ => false

  override def hashCode(): Int =
    ((used.hashCode() * 31 + visibility.hashCode()) * 31 + feedback.hashCode()) * 31 +
      blurred.hashCode()
end FormInteraction

object FormInteraction:
  def pristine[Owner]: FormInteraction[Owner] =
    new FormInteraction(Set.empty, ErrorVisibility.UsedOnly, FormFeedback.WhenUsed, Set.empty)

  private[scalive] def apply[Owner](
    used: Set[FormAddress[Owner]],
    visibility: ErrorVisibility,
    feedback: FormFeedback = FormFeedback.WhenUsed,
    blurred: Set[FormAddress[Owner]] = Set.empty[FormAddress[Owner]]
  ): FormInteraction[Owner] = new FormInteraction(used, visibility, feedback, blurred)

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
