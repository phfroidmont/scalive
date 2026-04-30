package scalive

import zio.json.*
import zio.json.ast.Json

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
  def state: FormState[A]    = FormState(raw, value, submitted)
  def data: FormData         = raw
  def isValid: Boolean       = value.isRight
  def errors: FormErrors     = value.left.getOrElse(FormErrors.empty)
  def valueOption: Option[A] = value.toOption

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
