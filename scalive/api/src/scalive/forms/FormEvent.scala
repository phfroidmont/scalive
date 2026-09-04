package scalive

/** The lifecycle operation represented by a form payload. */
enum FormEventKind derives CanEqual:
  /** An ordinary interactive validation change. */
  case Changed

  /** A submission, for which all errors become visible. */
  case Submitted

  /** State recovered after reconnect or remount. */
  case Recovered

/** Protocol context resolved into the owning form definition's logical address space.
  *
  * [[target]] uses stable [[FormAddress]] identity when resolution succeeds; [[browserTarget]]
  * preserves the protocol-level [[FormPath]], including compatibility-layer spelling.
  */
final case class FormEventMeta[Owner](
  target: Option[FormAddress[Owner]] = None,
  submitter: Option[FormSubmitter] = None,
  metadata: Map[String, String] = Map.empty,
  browserTarget: Option[FormPath] = None,
  diagnostics: Vector[String] = Vector.empty)

/** A definition-backed event containing an already rebuilt, internally consistent form.
  *
  * Prefer this over [[RawFormEvent]] for schema-defined forms. [[data]] is the original untrusted
  * payload; [[form]] is the bounded, canonical projection and validation result.
  */
final class FormEvent[Owner, Schema, Domain] private[scalive] (
  val form: Form[Owner, Schema, Domain],
  val data: FormData,
  val kind: FormEventKind,
  val meta: FormEventMeta[Owner]):

  /** Whether the rebuilt form decoded to its domain value. */
  def isValid: Boolean = form.isValid

  /** The decoded domain value when [[isValid]]. */
  def valueOption: Option[Domain] = form.valueOption

  /** All errors on the rebuilt form, whether currently visible or not. */
  def errors: FormErrors[Owner] = form.errors

  /** Resolved logical target, absent when the browser target is unknown or malformed. */
  def target: Option[FormAddress[Owner]] = meta.target

  /** Submit control metadata, when supplied by the client protocol. */
  def submitter: Option[FormSubmitter] = meta.submitter

/** A codec-backed event retained as an explicit low-level escape hatch.
  *
  * Unlike [[FormEvent]], this has no definition identity, typed address ownership, projection
  * limits, or interaction-aware error visibility. Its [[raw]] payload is preserved verbatim.
  */
final class RawFormEvent[+A] private[scalive] (
  val raw: FormData,
  val value: Either[FormErrors[Any], A],
  val target: Option[FormPath],
  val submitter: Option[FormSubmitter],
  val recovery: Boolean,
  val submitted: Boolean,
  val metadata: Map[String, String]):

  /** Alias for [[raw]]. */
  def data: FormData = raw

  /** Low-level state suitable for APIs that consume [[RawFormState]]. */
  def state: RawFormState[A] = RawFormState(raw, value, submitted)

  /** Whether codec decoding succeeded. */
  def isValid: Boolean = value.isRight

  /** Codec errors, or an empty collection on success. */
  def errors: FormErrors[Any] = value.left.getOrElse(FormErrors.empty)

  /** Decoded value on success. */
  def valueOption: Option[A] = value.toOption

/** Constructors and internal protocol metadata for [[RawFormEvent]]. */
object RawFormEvent:
  final private[scalive] case class Meta(
    target: Option[FormPath] = None,
    submitter: Option[FormSubmitter] = None,
    recovery: Boolean = false,
    metadata: Map[String, String] = Map.empty,
    diagnostics: Vector[String] = Vector.empty,
    originalTarget: Option[FormPath] = None):

    def params: Map[String, String] =
      val withTarget = target match
        case Some(path) if !metadata.contains("_target") => metadata.updated("_target", path.name)
        case _                                           => metadata
      if recovery && !withTarget.contains("_recover") && !withTarget.contains("_recovery") then
        withTarget.updated("_recovery", "true")
      else withTarget

  private[scalive] object Meta:
    val empty: Meta = Meta()

  private[scalive] def decode[A](
    raw: FormData,
    codec: FormCodec[A],
    kind: FormEventKind,
    meta: Meta
  ): RawFormEvent[A] =
    new RawFormEvent(
      raw,
      codec.decode(raw),
      meta.target,
      meta.submitter,
      recovery = kind == FormEventKind.Recovered || meta.recovery,
      submitted = kind == FormEventKind.Submitted,
      meta.metadata
    )

  /** Creates a successful event with no raw payload or protocol metadata. */
  def empty[A](value: A): RawFormEvent[A] =
    new RawFormEvent(FormData.empty, Right(value), None, None, false, false, Map.empty)
end RawFormEvent
