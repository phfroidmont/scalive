package scalive

/** Untrusted successful-control metadata reported by a client protocol.
  *
  * @param name
  *   The submit control's `name`.
  * @param value
  *   The submit control's `value`.
  */
final case class RawFormSubmitter(name: String, value: String)

/** Which of a form's validation errors are eligible for display. */
enum ErrorVisibility derives CanEqual:
  /** Errors appear field by field, as the form's [[FormFeedback]] policy allows. */
  case UsedOnly

  /** Every error is visible, as after a submission. */
  case All

/** Policy controlling when interaction makes field errors visible. */
enum FormFeedback derives CanEqual:
  /** A field's errors appear once the visitor has used it, following Phoenix's unused markers. */
  case WhenUsed

  /** A field's errors appear once focus has left it. */
  case AfterBlur

/** Owner-scoped validation visibility, independent of editable form values.
  *
  * @param used
  *   The addresses of fields the browser submitted without an `_unused_` marker.
  * @param visibility
  *   Whether every error is visible, as after a submission, or only as `feedback` allows.
  * @param feedback
  *   The policy that decides when a field's errors appear before a submission.
  * @param blurred
  *   The addresses of fields that focus has left, which [[FormFeedback.AfterBlur]] relies on.
  */
final class FormInteraction[Owner] private[scalive] (
  val used: Set[FormAddress[Owner]],
  val visibility: ErrorVisibility,
  val feedback: FormFeedback,
  val blurred: Set[FormAddress[Owner]])
    derives CanEqual:

  /** Returns whether the field at `address` is used or all errors are visible. */
  def isUsed(address: FormAddress[Owner]): Boolean =
    visibility == ErrorVisibility.All || used.contains(address)

  /** Returns whether focus has left the field at `address`. */
  def isBlurred(address: FormAddress[Owner]): Boolean = blurred.contains(address)

  /** Returns whether errors at `address` show under the current visibility and feedback policy. */
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

/** Creates [[FormInteraction]] values. */
object FormInteraction:
  /** Returns the state of a form nobody has interacted with yet.
    *
    * No field is used or blurred, errors appear field by field, and the feedback policy is
    * [[FormFeedback.WhenUsed]].
    */
  def pristine[Owner]: FormInteraction[Owner] =
    new FormInteraction(Set.empty, ErrorVisibility.UsedOnly, FormFeedback.WhenUsed, Set.empty)

  private[scalive] def apply[Owner](
    used: Set[FormAddress[Owner]],
    visibility: ErrorVisibility,
    feedback: FormFeedback = FormFeedback.WhenUsed,
    blurred: Set[FormAddress[Owner]] = Set.empty[FormAddress[Owner]]
  ): FormInteraction[Owner] = new FormInteraction(used, visibility, feedback, blurred)

/** Interaction state for an explicitly low-level codec-backed event.
  *
  * @param raw
  *   The untrusted payload the state was built from.
  * @param value
  *   The codec's result: the decoded value or its errors.
  * @param used
  *   The browser paths in the payload, minus fields marked `_unused_` unless it is a submission.
  * @param submitted
  *   Whether the payload is a submission, which makes every field count as used.
  */
final class RawFormState[+A] private[scalive] (
  val raw: FormData,
  val value: Either[FormErrors[Any], A],
  val used: Set[FormPath],
  val submitted: Boolean):

  /** Returns whether the codec decoded the payload. */
  def isValid: Boolean = value.isRight

  /** Returns the codec errors, or an empty collection on success. */
  def errors: FormErrors[Any] = value.left.getOrElse(FormErrors.empty)

  /** Returns the decoded value on success. */
  def valueOption: Option[A] = value.toOption

  /** Returns whether a field counts as used, which every field does after a submission.
    *
    * This form takes a parsed [[FormPath]].
    */
  def isUsed(path: FormPath): Boolean = submitted || used.contains(path)

  /** Returns whether a field counts as used, which every field does after a submission.
    *
    * This form parses the browser field `name` and returns false when it is malformed.
    */
  def isUsed(name: String): Boolean = FormPath.parse(name).exists(isUsed)

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
