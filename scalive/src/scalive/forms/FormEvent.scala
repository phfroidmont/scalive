package scalive

import zio.json.*
import zio.json.ast.Json

/** A typed LiveView form event and the protocol metadata received with it.
  *
  * Application code usually receives this value from a typed form binding. The public case-class
  * constructor is also available for tests and adapters, but it is low-level: it does not verify
  * that [[value]] was decoded from [[raw]] or that metadata fields agree with each other.
  *
  * [[metadata]], [[componentId]], and [[uploads]] expose protocol-facing data. Their current shapes
  * mirror the client event payload; undocumented metadata keys and raw upload JSON should not be
  * treated as a higher-level stable application schema.
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
  *   stringified client event metadata, excluding separately transported component and upload data
  * @param componentId
  *   the protocol component id (`cid`) associated with the event, when present
  * @param uploads
  *   raw protocol upload metadata, when present
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
  metadata: Map[String, String] = Map.empty,
  componentId: Option[Int] = None,
  uploads: Option[Json] = None):
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
    metadata: Map[String, String] = Map.empty,
    componentId: Option[Int] = None,
    uploads: Option[Json] = None):
    def params: Map[String, String] =
      val withTarget = target match
        case Some(path) if !metadata.contains("_target") => metadata.updated("_target", path.name)
        case _                                           => metadata

      val withRecovery =
        if recovery && !withTarget.contains("_recover") && !withTarget.contains("_recovery") then
          withTarget.updated("_recovery", "true")
        else withTarget

      val withCid = componentId match
        case Some(cid) => withRecovery.updated("__cid", cid.toString)
        case None      => withRecovery

      uploads match
        case Some(value) => withCid.updated("__uploads", value.toJson)
        case None        => withCid

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
      metadata = meta.metadata,
      componentId = meta.componentId,
      uploads = meta.uploads
    )
end FormEvent
