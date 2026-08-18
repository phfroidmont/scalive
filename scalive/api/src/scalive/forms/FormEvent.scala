package scalive

/** A typed LiveView form event and its semantic browser metadata.
  *
  * Application code usually receives this value from a typed form binding. The public case-class
  * constructor is also available for tests and adapters, but it is low-level: it does not verify
  * that [[value]] was decoded from [[raw]] or that metadata fields agree with each other.
  *
  * @param raw
  *   ordered, duplicate-preserving textual form fields
  * @param value
  *   the codec result for `raw`, including ordered validation errors
  * @param target
  *   the changed field reported by client `_target` metadata, when present; protocol decoding uses
  *   permissive [[FormPath]] semantics and can be lossy for array brackets
  * @param submitter
  *   the successful submit control reported by the client, when available
  * @param recovery
  *   whether client metadata identifies this as a form recovery event
  * @param submitted
  *   whether this was decoded through a typed `phx-submit` binding; this controls used-field
  *   visibility in [[state]] and is distinct from [[recovery]]
  * @param metadata
  *   stringified semantic client event metadata
  * @tparam A
  *   the successfully decoded form value
  */
final case class FormEvent[+A](
  raw: FormData,
  value: Either[FormErrors, A],
  target: Option[FormPath] = None,
  submitter: Option[FormSubmitter] = None,
  recovery: Boolean = false,
  submitted: Boolean = false,
  metadata: Map[String, String] = Map.empty):
  /** Builds form state, deriving used paths from [[raw]] and [[submitted]]. */
  def state: FormState[A] = FormState(raw, value, submitted)

  /** Alias for [[raw]]. */
  def data: FormData = raw

  /** Whether decoding and validation succeeded. */
  def isValid: Boolean = value.isRight

  /** The decoding errors, or [[FormErrors.empty]] when valid. */
  def errors: FormErrors = value.left.getOrElse(FormErrors.empty)

  /** The decoded value when valid. */
  def valueOption: Option[A] = value.toOption

/** Protocol integration for [[FormEvent]]. */
object FormEvent:
  final private[scalive] case class Meta(
    target: Option[FormPath] = None,
    submitter: Option[FormSubmitter] = None,
    recovery: Boolean = false,
    metadata: Map[String, String] = Map.empty):
    def params: Map[String, String] =
      val withTarget = target match
        case Some(path) if !metadata.contains("_target") => metadata.updated("_target", path.name)
        case _                                           => metadata

      val withRecovery =
        if recovery && !withTarget.contains("_recover") && !withTarget.contains("_recovery") then
          withTarget.updated("_recovery", "true")
        else withTarget

      withRecovery

  private[scalive] object Meta:
    val empty: Meta = Meta()

  private[scalive] def decode[A](
    raw: FormData,
    codec: FormCodec[A],
    submitted: Boolean,
    meta: Meta
  ): FormEvent[A] =
    FormEvent(
      raw = raw,
      value = codec.decode(raw),
      target = meta.target,
      submitter = meta.submitter,
      recovery = meta.recovery,
      submitted = submitted,
      metadata = meta.metadata
    )
end FormEvent
