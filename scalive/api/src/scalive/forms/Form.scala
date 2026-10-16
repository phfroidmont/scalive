package scalive

import scalive.codecs.StringAsIsEncoder

/** Immutable current semantic values, validation result, and interaction state.
  *
  * Obtain instances from [[FormDefinition.initial]] or [[FormDefinition.event]]. Raw values are
  * retained in [[values]] so invalid user input can be rendered without lossy round trips.
  *
  * @param values
  *   The current editable values, kept as raw strings so invalid input survives.
  * @param result
  *   The decoded domain value, or every validation error, including hidden ones.
  * @param interaction
  *   The used fields, blurred fields, and feedback policy that decide which errors are visible.
  */
final class Form[Owner, Schema, Domain] private[scalive] (
  private[scalive] val owningDefinition: FormDefinition[Owner, Domain],
  val values: FormValues[Owner, Schema],
  val result: Either[FormErrors[Owner], Domain],
  val interaction: FormInteraction[Owner]):

  /** Whether all structural, field, row, and product validation succeeded. */
  def isValid: Boolean = result.isRight

  /** The decoded domain value, available only when [[isValid]]. */
  def valueOption: Option[Domain] = result.toOption

  /** All validation errors, including errors currently hidden by interaction state. */
  def errors: FormErrors[Owner] = result.left.getOrElse(FormErrors.empty)

  /** Returns the current data of a field declared by this form's definition.
    *
    * This form takes a static field.
    */
  def field[Input, Value](
    definition: FormField[Owner, Input, Value]
  ): FormFieldView[Owner, Input, Value] =
    require(owningDefinition.owns(definition), "field is not declared by this form definition")
    FormFieldView(this, definition, definition.address, definition.path)

  /** Returns the current data of a field declared by this form's definition.
    *
    * This form takes a row-bound field from [[FormRowView.bind]]. The bound row must still exist in
    * this form's current values; use [[fieldOption]] when it may have been removed.
    */
  def field[Group, Input, Value](
    definition: BoundFormField[Owner, Schema, Group, Input, Value]
  ): FormFieldView[Owner, Input, Value] =
    fieldOption(definition).getOrElse(
      throw new IllegalArgumentException("bound row no longer exists")
    )

  /** Returns the current view of a schema-bound field when its row still exists. */
  def fieldOption[Group, Input, Value](
    definition: BoundFormField[Owner, Schema, Group, Input, Value]
  ): Option[FormFieldView[Owner, Input, Value]] =
    require(
      definition.schemaIdentity eq values.schemaIdentity,
      "bound field belongs to another form definition"
    )
    require(
      owningDefinition.owns(definition.rows),
      "repeated group is not declared by this form definition"
    )
    require(
      definition.rows.typedFields.exists(_ eq definition.field),
      "field is not declared by this row schema"
    )
    Option.when(resolveFieldPath(definition.path).contains(definition.address)) {
      FormFieldView(this, definition.field, definition.address, definition.path)
    }

  /** Returns repeated rows in submitted or programmatically mutated order.
    *
    * Each view carries its stable [[FormRowView.key]]; vector position is not row identity.
    */
  def rows[Group, Row](
    definition: RepeatedRows[Owner, Group, Row]
  ): Vector[FormRowView[Owner, Schema, Group, Row]] =
    require(
      owningDefinition.owns(definition),
      "repeated group is not declared by this form definition"
    )
    values.groups.getOrElse(definition.address, CanonicalFormGroup(Vector.empty)).rows.map { row =>
      new FormRowView(this, definition, row)
    }

  /** Replaces one field's value with typed editable input and revalidates the form.
    *
    * This form updates a static field declared by this form's definition.
    */
  def updated[Input, Value](
    field: FormField[Owner, Input, Value],
    input: Input
  ): Form[Owner, Schema, Domain] =
    updatedRaw(field, field.inputCodec.encode(input))

  /** Replaces one field's raw values and revalidates the form, preserving invalid input.
    *
    * This form updates a static field declared by this form's definition. `raw` may hold at most
    * [[FormLimits.maxValuesPerField]] values.
    */
  def updatedRaw[Input, Value](
    field: FormField[Owner, Input, Value],
    raw: Vector[String]
  ): Form[Owner, Schema, Domain] =
    require(owningDefinition.owns(field), "field is not declared by this form definition")
    require(
      raw.size <= owningDefinition.limits.maxValuesPerField,
      "raw field update exceeds maxValuesPerField"
    )
    val static =
      if raw.isEmpty then values.static.removed(field.address)
      else values.static.updated(field.address, raw)
    rebuild(static = static)

  /** Appends one raw value to a multi-value static field. */
  def appended[Value](
    field: FormField[Owner, Vector[String], Value],
    value: String
  ): Form[Owner, Schema, Domain] =
    updatedRaw(field, values.static.getOrElse(field.address, Vector.empty) :+ value)

  /** Removes a raw multi-value entry by position, failing when the index is out of bounds. */
  def removedAt[Value](
    field: FormField[Owner, Vector[String], Value],
    index: Int
  ): Form[Owner, Schema, Domain] =
    val raw = values.static.getOrElse(field.address, Vector.empty)
    if index < 0 || index >= raw.length then
      throw new IndexOutOfBoundsException(
        s"field value index $index out of bounds for ${raw.length} values"
      )
    updatedRaw(field, raw.patch(index, Nil, 1))

  /** Replaces one field's value with typed editable input and revalidates the form.
    *
    * This form updates one field of the keyed row bound by `field`, which must still exist.
    */
  def updated[Group, Input, Value](
    field: BoundFormField[Owner, Schema, Group, Input, Value],
    input: Input
  ): Form[Owner, Schema, Domain] =
    updatedRaw(field, field.field.inputCodec.encode(input))

  /** Replaces one field's raw values and revalidates the form, preserving invalid input.
    *
    * This form updates one field of the keyed row bound by `field`. The handle must come from this
    * exact definition and its keyed row must still exist. `raw` may hold at most
    * [[FormLimits.maxValuesPerField]] values.
    */
  def updatedRaw[Group, Input, Value](
    field: BoundFormField[Owner, Schema, Group, Input, Value],
    raw: Vector[String]
  ): Form[Owner, Schema, Domain] =
    require(
      field.schemaIdentity eq values.schemaIdentity,
      "bound field belongs to another form definition"
    )
    require(
      raw.size <= owningDefinition.limits.maxValuesPerField,
      "raw row field update exceeds maxValuesPerField"
    )
    val group = values.groups.getOrElse(field.rows.address, CanonicalFormGroup(Vector.empty))
    val index = group.rows.indexWhere(_.key == field.key.value)
    require(index >= 0, s"row '${field.key.value}' no longer exists")
    val current  = group.rows(index)
    val relative = FormDefinition.names(field.field.relativePath)
    val fields   = if raw.isEmpty then current.fields.removed(relative)
    else current.fields.updated(relative, raw)
    val next = group.copy(rows = group.rows.updated(index, current.copy(fields = fields)))
    rebuild(groups = values.groups.updated(field.rows.address, next))

  /** Appends a new row with a group-scoped stable key and owner-checked assignments. */
  def added[Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group]
  )(
    assignments: FormInitial[Group]*
  ): Form[Owner, Schema, Domain] =
    require(owningDefinition.owns(rows), "repeated group is not declared by this form definition")
    val group = values.groups.getOrElse(rows.address, CanonicalFormGroup(Vector.empty))
    require(!group.rows.exists(_.key == key.value), s"duplicate row key '${key.value}'")
    require(
      group.rows.size < owningDefinition.limits.maxRowsPerGroup,
      "repeated group exceeds maxRowsPerGroup"
    )
    val initial = rows.row(key)(assignments*)
    require(
      initial.assignments.forall(_.values.size <= owningDefinition.limits.maxValuesPerField),
      "new row field exceeds maxValuesPerField"
    )
    val fields = initial.assignments.iterator
      .filter(_.values.nonEmpty).map { value =>
        FormDefinition.names(value.field.relativePath) -> value.values
      }.toMap
    val next = group.copy(rows = group.rows :+ CanonicalFormRow(key.value, fields))
    rebuild(
      groups = values.groups.updated(rows.address, next),
      interaction = interaction.without(FormAddress.row(rows.address, key.value))
    )

  /** Removes the row with `key` and clears interaction state below its address. */
  def removed[Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group]
  ): Form[Owner, Schema, Domain] =
    require(owningDefinition.owns(rows), "repeated group is not declared by this form definition")
    val group = values.groups.getOrElse(rows.address, CanonicalFormGroup(Vector.empty))
    require(group.rows.exists(_.key == key.value), s"missing row key '${key.value}'")
    val next           = group.copy(rows = group.rows.filterNot(_.key == key.value))
    val removedAddress = FormAddress.row(rows.address, key.value)
    rebuild(
      groups = values.groups.updated(rows.address, next),
      interaction = interaction.without(removedAddress)
    )

  /** Moves a keyed row immediately before another keyed row without changing either identity. */
  def movedBefore[Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    target: FormRowKey[Group]
  ): Form[Owner, Schema, Domain] =
    move(rows, key, target, after = false)

  /** Moves a keyed row immediately after another keyed row without changing either identity. */
  def movedAfter[Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    target: FormRowKey[Group]
  ): Form[Owner, Schema, Domain] =
    move(rows, key, target, after = true)

  /** Returns this value state with every existing validation error visible, as on submission. */
  def withAllErrorsVisible: Form[Owner, Schema, Domain] =
    withInteraction(
      FormInteraction(
        interaction.used,
        ErrorVisibility.All,
        interaction.feedback,
        interaction.blurred
      )
    )

  /** Returns this value state with interaction history reset and its feedback policy retained. */
  def pristine: Form[Owner, Schema, Domain] =
    withInteraction(
      FormInteraction(Set.empty, ErrorVisibility.UsedOnly, interaction.feedback, Set.empty)
    )

  /** Changes the field-feedback policy without changing values, validation, or interaction history.
    */
  private[scalive] def withFeedback(feedback: FormFeedback): Form[Owner, Schema, Domain] =
    withInteraction(
      FormInteraction(interaction.used, interaction.visibility, feedback, interaction.blurred)
    )

  /** Captures definition-owned values and domain value only when validation succeeds. */
  def validSnapshot: Option[ValidFormSnapshot[Owner, Schema, Domain]] =
    result.toOption.map(value => new ValidFormSnapshot(values, value))

  private[scalive] def withInteraction(
    interaction: FormInteraction[Owner]
  ): Form[Owner, Schema, Domain] =
    new Form(owningDefinition, values, result, interaction)

  private[scalive] def resolveFieldPath(path: FormPath): Option[FormAddress[Owner]] =
    owningDefinition.addressForBrowserPath(
      path,
      values.asInstanceOf[owningDefinition.Values]
    )

  private def move[Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    target: FormRowKey[Group],
    after: Boolean
  ): Form[Owner, Schema, Domain] =
    require(owningDefinition.owns(rows), "repeated group is not declared by this form definition")
    require(key != target, "a row cannot be moved relative to itself")
    val group = values.groups.getOrElse(rows.address, CanonicalFormGroup(Vector.empty))
    val moved = group.rows
      .find(_.key == key.value).getOrElse(
        throw new IllegalArgumentException(s"missing row key '${key.value}'")
      )
    val without     = group.rows.filterNot(_.key == key.value)
    val targetIndex = without.indexWhere(_.key == target.value)
    require(targetIndex >= 0, s"missing target row key '${target.value}'")
    val insertion = targetIndex + (if after then 1 else 0)
    val next      = group.copy(rows = without.patch(insertion, Vector(moved), 0))
    rebuild(groups = values.groups.updated(rows.address, next))

  private def rebuild(
    static: Map[FormAddress[Owner], Vector[String]] = this.values.static,
    groups: Map[FormAddress[Owner], CanonicalFormGroup] = this.values.groups,
    interaction: FormInteraction[Owner] = this.interaction
  ): Form[Owner, Schema, Domain] =
    val next = new FormValues[Owner, Schema](values.schemaIdentity, static, groups)
    owningDefinition
      .rebuild(
        next.asInstanceOf[owningDefinition.Values],
        interaction,
        Vector.empty
      ).asInstanceOf[Form[Owner, Schema, Domain]]
end Form

/** Rendering helpers for ordinary HTTP forms. */
object Form:
  private[scalive] val feedbackFor = htmlAttr("phx-feedback-for", StringAsIsEncoder)
  private[scalive] val textareaTag = HtmlTag("textarea")
  private val httpAttributes       = Set("action", "method")
  private val csrfMarker           = htmlAttr("data-scalive-csrf", StringAsIsEncoder)

  /** Renders an ordinary HTTP form with action, method, and CSRF policy from `target`.
    *
    * `target` owns the `action` and `method` attributes. Setting either in `mods`, in any letter
    * case and even inside a conditional modifier, throws `IllegalArgumentException`.
    */
  def http[Msg](target: FormAction)(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    val flattened = Mod.flatten(mods)
    val overrides = overriddenAttributes(flattened, httpAttributes)
    require(
      overrides.isEmpty,
      s"ordinary HTTP forms own the ${overrides.distinct.mkString(" and ")} attribute"
    )
    _root_.scalive.form(
      _root_.scalive.action := target.href,
      _root_.scalive.method := target.method.attributeValue,
      Option.when(target.protectFromCsrf)(csrfMarker := "true"),
      flattened
    )

  /** Returns the names in `owned` that `mods` set, in any letter case, including inside attribute
    * groups and conditional modifiers.
    */
  private[scalive] def overriddenAttributes(
    mods: Vector[Mod[?]],
    owned: Set[String]
  ): Vector[String] =
    def attributeNames(mod: Mod[?]): Vector[String] = mod match
      case attr: Mod.Attr[?]                     => attr.flattened.flatMap(attributeName)
      case Mod.Content.SignalChoice(_, branches) =>
        branches.flatMap { case (_, branch) => branch.flatMap(attributeNames) }
      case _ => Vector.empty
    mods.flatMap(attributeNames).filter(name => owned.exists(_.equalsIgnoreCase(name)))

  private[scalive] def attributeName(mod: Mod[?]): Option[String] = mod match
    case Mod.Attr.Static(name, _)                       => Some(name)
    case Mod.Attr.StaticValueAsPresence(name, _)        => Some(name)
    case Mod.Attr.SignalValue(name, _)                  => Some(name)
    case Mod.Attr.SignalOptionalValue(name, _)          => Some(name)
    case Mod.Attr.SignalValueAsPresence(name, _)        => Some(name)
    case Mod.Attr.ManagedUploadDropTarget(_)            => Some(phx.dropTarget.name)
    case Mod.Attr.CompositeStatic(name, _)              => Some(name)
    case Mod.Attr.CompositeSignalValue(name, _)         => Some(name)
    case Mod.Attr.CompositeSignalOptionalValue(name, _) => Some(name)
    case Mod.Attr.Binding(name, _)                      => Some(name)
    case Mod.Attr.SignalBinding(name, _, _)             => Some(name)
    case Mod.Attr.FormBinding(name, _)                  => Some(name)
    case Mod.Attr.FormEventBinding(name, _, _)          => Some(name)
    case Mod.Attr.TypedFormEventBinding(name, _, _)     => Some(name)
    case Mod.Attr.JsBinding(name, _)                    => Some(name)
    case Mod.Attr.SignalJsBinding(name, _)              => Some(name)
    case Mod.Attr.RoutedBinding(name, _)                => Some(name)
    case Mod.Attr.Group(attrs)                          => attrs.flatMap(attributeName).headOption
    case _: Mod.Content[?]                              => None
end Form

/** A row-bound field handle safe for keyed programmatic updates.
  *
  * Create one with [[FormRowView.bind]]. Its hidden schema identity prevents applying it to an
  * unrelated definition, while [[key]] keeps updates independent of row order.
  *
  * @param key
  *   The stable key of the bound row.
  * @param address
  *   The stable logical address of the field in that row.
  * @param path
  *   The browser path of the field in that row.
  */
final class BoundFormField[Owner, Schema, Group, Input, Value] private[scalive] (
  private[scalive] val schemaIdentity: AnyRef,
  private[scalive] val rows: RepeatedRows[Owner, Group, ?],
  val key: FormRowKey[Group],
  private[scalive] val field: FormField[Group, Input, Value],
  val address: FormAddress[Owner],
  val path: FormPath)

/** The current data of one static or row-bound field: its identity, raw values, and errors.
  *
  * It is read-only; render the field with the [[FormControl]] from `field` on a form signal or from
  * a [[FormBinding]]. Controls render [[rawValues]], not the decoded result, preserving malformed
  * input where the native control permits it.
  *
  * @param address
  *   The field's stable logical address, which its DOM [[id]] and errors derive from.
  * @param path
  *   The field's browser path, which its [[name]] derives from.
  */
final class FormFieldView[Owner, Input, Value] private[scalive] (
  private val form: Form[Owner, ?, ?],
  private val definition: FormField[?, Input, Value],
  val address: FormAddress[Owner],
  val path: FormPath):

  /** Browser field name derived from [[path]]. */
  def name: String = path.name

  /** Stable logical DOM id derived from [[address]]. */
  def id: String = address.id

  /** DOM id reserved for this field's error feedback container. */
  def errorId: String = s"${id}_errors"

  /** Complete raw value vector retained for rendering and structural decoding. */
  def rawValues: Vector[String] = definition.scope match
    case FormFieldScope.Static(_, _) => form.values.static.getOrElse(address, Vector.empty)
    case FormFieldScope.Row(_, _, _, relative) =>
      val (groupAddress, key) = FormFieldView.rowLocation(address)
      form.values.groups
        .get(groupAddress).flatMap(_.rows.find(_.key == key)).flatMap { row =>
          row.fields.get(FormDefinition.names(relative))
        }.getOrElse(Vector.empty)

  /** Conventional scalar control value: the last retained raw value, or empty when absent. */
  def fieldValue: String = rawValues.lastOption.getOrElse("")

  /** Structurally decoded editable input before semantic refinement. */
  def input: Either[FieldIssues, Input] = definition.decodeInput(rawValues)

  /** Fully refined field result. */
  def result: Either[FieldIssues, Value] = definition.decode(rawValues)

  /** All errors exactly at this logical address, regardless of visibility. */
  def errors: Vector[FormError[Owner]] = form.errors.forAddress(address)

  /** Whether browser interaction marked this field as used. */
  def isUsed: Boolean = form.interaction.isUsed(address)

  /** Errors suitable for display under the current interaction policy. */
  def visibleErrors: Vector[FormError[Owner]] =
    if form.interaction.isVisible(address) then errors else Vector.empty

  /** Whether at least one field error is currently visible. */
  def hasVisibleErrors: Boolean = visibleErrors.nonEmpty
end FormFieldView

private[scalive] object FormFieldView:
  private[scalive] def apply[Owner, Input, Value](
    form: Form[Owner, ?, ?],
    definition: FormField[?, Input, Value],
    address: FormAddress[Owner],
    path: FormPath
  ): FormFieldView[Owner, Input, Value] = new FormFieldView(form, definition, address, path)

  private def rowLocation[Owner](address: FormAddress[Owner]): (FormAddress[Owner], String) =
    val rowIndex = address.segments.indexWhere(_.isInstanceOf[FormAddressSegment.Row])
    val key      = address.segments(rowIndex).asInstanceOf[FormAddressSegment.Row].key
    new FormAddress(address.segments.take(rowIndex)) -> key

/** Current values and validation state for one stable repeated row.
  *
  * [[key]] is stable across reordering and scopes field addresses, DOM ids, and mutations.
  */
final class FormRowView[Owner, Schema, Group, Row] private[scalive] (
  private val form: Form[Owner, Schema, ?],
  private val rows: RepeatedRows[Owner, Group, Row],
  private val current: CanonicalFormRow):

  /** Stable group-scoped key retained across reordering. */
  val key: FormRowKey[Group] = FormRowKey.from[Group](current.key).toOption.get

  /** Stable logical address of this keyed row. */
  val address: FormAddress[Owner] = FormAddress.row(rows.address, current.key)

  /** Decodes this row independently using the repeated-row schema. */
  def result: Either[FormErrors[Owner], Row] = form.owningDefinition.decodeRow(rows, current)

  /** All errors at or below this row's logical address. */
  def errors: Vector[FormError[Owner]] = form.errors.below(address)

  /** Row errors whose individual field addresses are currently visible. */
  def visibleErrors: Vector[FormError[Owner]] =
    errors.filter(error => form.interaction.isVisible(error.address))

  /** Whether submission or field interaction has made this row relevant to feedback. */
  def isUsed: Boolean =
    form.interaction.visibility == ErrorVisibility.All ||
      form.interaction.used.exists(_.startsWith(address))

  /** Returns the current data of a row-schema field in this keyed row. */
  def field[Input, Value](
    definition: FormField[Group, Input, Value]
  ): FormFieldView[Owner, Input, Value] =
    require(rows.typedFields.exists(_ eq definition), "field is not declared by this row schema")
    val fieldAddress = FormAddress.append(address, FormDefinition.names(definition.relativePath))
    val path         = FormPath.fromSegments(
      rows.path.segments ++ Vector(
        FormPathSegment.Name(current.key)
      ) ++ definition.relativePath.segments
    )
    FormFieldView(form, definition, fieldAddress, path)

  /** Creates a schema- and key-bound handle for [[Form.updated]] or [[Form.updatedRaw]]. */
  def bind[Input, Value](
    definition: FormField[Group, Input, Value]
  ): BoundFormField[Owner, Schema, Group, Input, Value] =
    val view = field(definition)
    new BoundFormField(form.values.schemaIdentity, rows, key, definition, view.address, view.path)
end FormRowView

/** Rendering operations for a [[FormRowView]] signal. */
object FormRowView:
  extension [Owner, Schema, Group, Row](row: Signal[FormRowView[Owner, Schema, Group, Row]])
    /** Renders the hidden control that keeps the row, and its order, in the next submission.
      *
      * Render it in every row, even when all its visible fields are empty or unchecked.
      */
    def presence[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      input(
        typ    := "hidden",
        idAttr := row.map { value =>
          FormAddress.append(value.address, Vector(FormDefinition.RowPresenceName)).id
        },
        nameAttr := row.map { value =>
          FormPath
            .fromSegments(
              value.rows.path.segments ++ Vector(
                FormPathSegment.Name(value.key.value),
                FormPathSegment.Name(FormDefinition.RowPresenceName)
              )
            ).name
        },
        value := FormDefinition.RowPresenceValue,
        Mod.flatten(mods)
      )

extension [Owner, Schema, Domain](form: Signal[Form[Owner, Schema, Domain]])
  /** Returns the control that renders a declared field from the current form value.
    *
    * The control uses the field's logical id and reports no blur feedback; use [[bind]] for
    * controls scoped to one form instance. This form takes a static field.
    */
  def field[Input, Value](
    definition: FormField[Owner, Input, Value]
  ): FormControl[Owner, Input, Value, Nothing] =
    FormControl.unbound(form.map(_.field(definition)))

  /** Returns the control that renders a declared field from the current form value.
    *
    * This form takes a field of the row with `key` in `rows`, inside a signal's keyed rendering
    * callback. The row must exist whenever the control is evaluated.
    */
  def field[Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    definition: FormField[Group, Input, Value]
  ): FormControl[Owner, Input, Value, Nothing] =
    FormControl.unbound(fieldView(rows, key, definition))

  private[scalive] def fieldView[Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    definition: FormField[Group, Input, Value]
  ): Signal[FormFieldView[Owner, Input, Value]] =
    fieldOption(rows, key, definition).map(
      _.getOrElse(throw new IllegalArgumentException("bound row no longer exists"))
    )

  private[scalive] def fieldOption[Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    definition: FormField[Group, Input, Value]
  ): Signal[Option[FormFieldView[Owner, Input, Value]]] =
    form.map { current =>
      require(
        current.owningDefinition.owns(rows),
        "repeated group is not declared by this form definition"
      )
      require(
        rows.typedFields.exists(_ eq definition),
        "field is not declared by this row schema"
      )
      current.rows(rows).find(_.key == key).map(_.field(definition))
    }

  /** Resolves stable repeated row views for each current form value. */
  def rows[Group, Row](
    definition: RepeatedRows[Owner, Group, Row]
  ): Signal[Vector[FormRowView[Owner, Schema, Group, Row]]] = form.map(_.rows(definition))
end extension
