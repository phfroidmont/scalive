package scalive

/** Semantic lifecycle classification after applying a form update. */
enum FormUpdateKind derives CanEqual:
  case Changed
  case Blurred
  case Submitted
  case Recovered

/** The form and metadata produced by applying a [[FormUpdate]]. A semantic blur for a removed row
  * is ignored: its kind remains [[FormUpdateKind.Blurred]], but [[target]] is empty and no field is
  * recorded as blurred. Check the target when counting accepted field interactions rather than
  * received messages.
  */
final case class AppliedFormUpdate[Owner, Schema, Domain](
  form: Form[Owner, Schema, Domain],
  valuesChanged: Boolean,
  kind: FormUpdateKind,
  target: Option[FormAddress[Owner]])

import FormUpdate.Source

/** A delayed incoming or semantic interaction update applied to the latest form state. */
final class FormUpdate[Owner, Schema, Domain] private (
  private val source: Source[Owner, Schema, Domain],
  private val feedback: FormFeedback):

  /** Applies this update to `current`, checking its definition identity at the boundary. */
  def applyTo(
    current: Form[Owner, Schema, Domain]
  ): AppliedFormUpdate[Owner, Schema, Domain] =
    source match
      case Source.Incoming(event) => applyIncoming(current, event)
      case _                      => applyBlur(current)

  private def applyIncoming(
    current: Form[Owner, Schema, Domain],
    event: FormEvent[Owner, Schema, Domain]
  ): AppliedFormUpdate[Owner, Schema, Domain] =
    require(
      current.values.schemaIdentity eq event.form.values.schemaIdentity,
      "form update belongs to another form definition"
    )

    val blurredTarget = event.kind match
      case FormEventKind.Changed => blurTarget(event)
      case _                     => None
    val target          = blurredTarget.orElse(event.meta.target)
    val existingBlurred =
      if current.interaction.blurred.isEmpty then Set.empty
      else
        val addresses = event.form.owningDefinition.existingFieldAddresses(
          event.form.values.asInstanceOf[event.form.owningDefinition.Values]
        )
        current.interaction.blurred.intersect(addresses)
    val blurred    = existingBlurred ++ blurredTarget
    val visibility =
      if event.kind == FormEventKind.Submitted ||
        current.interaction.visibility == ErrorVisibility.All
      then ErrorVisibility.All
      else ErrorVisibility.UsedOnly
    val interaction = FormInteraction(
      event.form.interaction.used,
      visibility,
      feedback,
      blurred
    )
    val kind = event.kind match
      case FormEventKind.Changed if blurredTarget.nonEmpty => FormUpdateKind.Blurred
      case FormEventKind.Changed                           => FormUpdateKind.Changed
      case FormEventKind.Submitted                         => FormUpdateKind.Submitted
      case FormEventKind.Recovered                         => FormUpdateKind.Recovered

    AppliedFormUpdate(
      event.form.withInteraction(interaction),
      current.values != event.form.values,
      kind,
      target
    )
  end applyIncoming

  private def blurTarget(
    event: FormEvent[Owner, Schema, Domain]
  ): Option[FormAddress[Owner]] =
    event.meta.metadata.get(FormUpdate.BlurMetadataKey).flatMap { name =>
      val limits = event.form.owningDefinition.limits
      FormPath
        .parse(name, FormPath.ParseLimits(limits.maxPathDepth, limits.maxSegmentLength))
        .toOption
        .filter(_.name == name)
        .flatMap(event.form.resolveFieldPath)
    }

  private def applyBlur(
    current: Form[Owner, Schema, Domain]
  ): AppliedFormUpdate[Owner, Schema, Domain] =
    val path = source match
      case Source.Static(field) =>
        require(
          current.owningDefinition.owns(field),
          "field is not declared by this form definition"
        )
        Some(field.path)
      case Source.Bound(schemaIdentity, path) =>
        require(
          schemaIdentity eq current.values.schemaIdentity,
          "bound field belongs to another form definition"
        )
        Some(path)
      case Source.Row(rows, key, field) =>
        require(
          current.owningDefinition.owns(rows),
          "row schema is not declared by this form definition"
        )
        require(
          rows.typedFields.exists(_ eq field),
          "field is not declared by this row schema"
        )
        Some(
          FormPath.fromSegments(
            rows.path.segments ++ Vector(FormPathSegment.Name(key.value)) ++
              field.relativePath.segments
          )
        )
      case Source.Incoming(_) => None

    val target      = path.flatMap(current.resolveFieldPath)
    val interaction = FormInteraction(
      current.interaction.used,
      current.interaction.visibility,
      feedback,
      current.interaction.blurred ++ target
    )
    AppliedFormUpdate(
      current.withInteraction(interaction),
      valuesChanged = false,
      FormUpdateKind.Blurred,
      target
    )
  end applyBlur
end FormUpdate

private[scalive] object FormUpdate:
  private enum Source[Owner, Schema, Domain]:
    case Incoming[Owner, Schema, Domain](event: FormEvent[Owner, Schema, Domain])
        extends Source[Owner, Schema, Domain]
    case Static[Owner, Schema, Domain](field: FormField[Owner, ?, ?])
        extends Source[Owner, Schema, Domain]
    case Bound[Owner, Schema, Domain](schemaIdentity: AnyRef, path: FormPath)
        extends Source[Owner, Schema, Domain]
    case Row[Owner, Schema, Domain, Group, Row, Input, Value](
      rows: RepeatedRows[Owner, Group, Row],
      key: FormRowKey[Group],
      field: FormField[Group, Input, Value]) extends Source[Owner, Schema, Domain]

  /** Event metadata key whose value is the canonical browser name of a blurred field. */
  private[scalive] val BlurMetadataKey: String = "scalive-blur"

  private[scalive] def fromEvent[Owner, Schema, Domain](
    event: FormEvent[Owner, Schema, Domain],
    feedback: FormFeedback
  ): FormUpdate[Owner, Schema, Domain] =
    new FormUpdate(Source.Incoming(event), feedback)

  private[scalive] def blurred[Owner, Schema, Domain, Input, Value](
    field: FormField[Owner, Input, Value],
    feedback: FormFeedback
  ): FormUpdate[Owner, Schema, Domain] =
    new FormUpdate(Source.Static(field), feedback)

  private[scalive] def blurred[Owner, Schema, Domain, Group, Input, Value](
    field: BoundFormField[Owner, Schema, Group, Input, Value],
    feedback: FormFeedback
  ): FormUpdate[Owner, Schema, Domain] =
    new FormUpdate(Source.Bound(field.schemaIdentity, field.path), feedback)

  private[scalive] def blurred[Owner, Schema, Domain, Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    field: FormField[Group, Input, Value],
    feedback: FormFeedback
  ): FormUpdate[Owner, Schema, Domain] =
    new FormUpdate(Source.Row(rows, key, field), feedback)
end FormUpdate
