package scalive

import scalive.codecs.StringAsIsEncoder

/** Immutable current semantic values, validation result, and interaction state.
  *
  * Obtain instances from [[FormDefinition.initial]] or [[FormDefinition.event]]. Raw values are
  * retained in [[values]] so invalid user input can be rendered without lossy round trips.
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

  /** Renders an ordinary HTTP form; `action` and `method` are owned by `target`. */
  def http[Msg](target: FormAction)(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    Form.http(target)(mods*)

  /** Handles LiveView `phx-change` and recovery while preserving this form's schema type. */
  def onChange[Msg](f: FormEvent[Owner, Schema, Domain] => Msg): Mod.Attr[Msg] =
    owningDefinition.onChange(event => f(event.asInstanceOf[FormEvent[Owner, Schema, Domain]]))

  /** Handles LiveView `phx-change` and recovery with separate schema-safe callbacks. */
  def onChange[Msg](
    changed: FormEvent[Owner, Schema, Domain] => Msg,
    recovered: FormEvent[Owner, Schema, Domain] => Msg
  ): Mod.Attr[Msg] =
    owningDefinition.onChange(
      event => changed(event.asInstanceOf[FormEvent[Owner, Schema, Domain]]),
      event => recovered(event.asInstanceOf[FormEvent[Owner, Schema, Domain]])
    )

  /** Handles a typed submission event. */
  def onSubmit[Msg](f: FormEvent[Owner, Schema, Domain] => Msg): Mod.Attr[Msg] =
    owningDefinition.onSubmit(event => f(event.asInstanceOf[FormEvent[Owner, Schema, Domain]]))

  /** Handles a typed recovery event. */
  def onRecover[Msg](f: FormEvent[Owner, Schema, Domain] => Msg): Mod.Attr[Msg] =
    owningDefinition.onRecover(event => f(event.asInstanceOf[FormEvent[Owner, Schema, Domain]]))

  /** Disables Phoenix automatic form recovery for the rendered form. */
  def disableRecovery: Mod.Attr[Nothing] = phx.autoRecover := "ignore"

  /** Requests a conventional HTTP submission when `condition` becomes true. */
  def triggerHttpSubmitWhen(condition: Boolean): Mod.Attr[Nothing] =
    phx.triggerAction := condition

  /** Returns the renderable view of a static field declared by this form's definition. */
  def field[Input, Value](
    definition: FormField[Owner, Input, Value]
  ): FormFieldView[Owner, Input, Value] =
    require(owningDefinition.owns(definition), "field is not declared by this form definition")
    FormFieldView(this, definition, definition.address, definition.path)

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

  /** Replaces a static field from typed editable input and immediately revalidates the form. */
  def updated[Input, Value](
    field: FormField[Owner, Input, Value],
    input: Input
  ): Form[Owner, Schema, Domain] =
    updatedRaw(field, field.inputCodec.encode(input))

  /** Replaces a static field with explicit raw values, preserving invalid control input. */
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

  /** Updates the row identified by a schema-bound field handle from typed editable input. */
  def updated[Group, Input, Value](
    field: BoundFormField[Owner, Schema, Group, Input, Value],
    input: Input
  ): Form[Owner, Schema, Domain] =
    updatedRaw(field, field.field.inputCodec.encode(input))

  /** Replaces a bound row field's raw values.
    *
    * The handle must come from this exact definition and its keyed row must still exist.
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

  /** Returns this value state revalidated with every error visible, as on submission. */
  def withAllErrorsVisible: Form[Owner, Schema, Domain] =
    owningDefinition
      .rebuild(
        values.asInstanceOf[owningDefinition.Values],
        FormInteraction(interaction.used, ErrorVisibility.All),
        Vector.empty
      ).asInstanceOf[Form[Owner, Schema, Domain]]

  /** Returns this value state revalidated with no fields marked as used. */
  def pristine: Form[Owner, Schema, Domain] =
    owningDefinition
      .rebuild(
        values.asInstanceOf[owningDefinition.Values],
        FormInteraction.pristine,
        Vector.empty
      ).asInstanceOf[Form[Owner, Schema, Domain]]

  /** Captures definition-owned values and domain value only when validation succeeds. */
  def validSnapshot: Option[ValidFormSnapshot[Owner, Schema, Domain]] =
    result.toOption.map(value => new ValidFormSnapshot(values, value))

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
    * Caller-supplied `action` or `method` attributes are rejected to keep routing policy coherent.
    */
  def http[Msg](target: FormAction)(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    val flattened = Mod.flatten(mods)
    val overrides = flattened.flatMap(attributeName).filter { name =>
      httpAttributes.exists(_.equalsIgnoreCase(name))
    }
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

  private def attributeName(mod: Mod[?]): Option[String] = mod match
    case Mod.Attr.Static(name, _)                       => Some(name)
    case Mod.Attr.StaticValueAsPresence(name, _)        => Some(name)
    case Mod.Attr.SignalValue(name, _)                  => Some(name)
    case Mod.Attr.SignalOptionalValue(name, _)          => Some(name)
    case Mod.Attr.SignalValueAsPresence(name, _)        => Some(name)
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
    case Mod.Attr.ComponentBinding(name, _, _)          => Some(name)
    case Mod.Attr.ComponentTarget(_)                    => Some("phx-target")
    case Mod.Attr.Group(attrs)                          => attrs.flatMap(attributeName).headOption
    case _: Mod.Content[?]                              => None
end Form

/** A row-bound field handle safe for keyed programmatic updates.
  *
  * Create one with [[FormRowView.bind]]. Its hidden schema identity prevents applying it to an
  * unrelated definition, while [[key]] keeps updates independent of row order.
  */
final class BoundFormField[Owner, Schema, Group, Input, Value] private[scalive] (
  private[scalive] val schemaIdentity: AnyRef,
  private[scalive] val rows: RepeatedRows[Owner, Group, ?],
  val key: FormRowKey[Group],
  private[scalive] val field: FormField[Group, Input, Value],
  val address: FormAddress[Owner],
  val path: FormPath)

/** Current renderable view of one static or row-bound field.
  *
  * Rendering reads [[rawValues]], not the decoded result, so malformed user input remains visible.
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

  /** Conventional scalar control value: the last submitted value, or empty when absent. */
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
  def visibleErrors: Vector[FormError[Owner]] = if isUsed then errors else Vector.empty

  /** Whether at least one field error is currently visible. */
  def hasVisibleErrors: Boolean = visibleErrors.nonEmpty

  /** ARIA attributes linking the control to feedback and exposing visible invalidity. */
  def validationAttributes: Vector[Mod.Attr[Nothing]] =
    Vector(aria.describedby := errorId) ++
      Option.when(hasVisibleErrors)(aria.invalid := "true").toVector

  /** Renders a text input from the retained raw scalar value. */
  def text[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    _root_.scalive.input(
      typ      := "text",
      idAttr   := id,
      nameAttr := name,
      value    := fieldValue,
      Mod.flatten(mods)
    )

  /** Renders an email input from the retained raw scalar value. */
  def email[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    _root_.scalive.input(
      typ      := "email",
      idAttr   := id,
      nameAttr := name,
      value    := fieldValue,
      Mod.flatten(mods)
    )

  /** Renders a password input from the retained raw scalar value. */
  def password[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    _root_.scalive.input(
      typ      := "password",
      idAttr   := id,
      nameAttr := name,
      value    := fieldValue,
      Mod.flatten(mods)
    )

  /** Renders a hidden input from the retained raw scalar value. */
  def hidden[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    _root_.scalive.input(
      typ      := "hidden",
      idAttr   := id,
      nameAttr := name,
      value    := fieldValue,
      Mod.flatten(mods)
    )

  /** Renders a checkbox whose checked value defaults to `"true"`. */
  def checkbox[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] = checkbox("true", mods*)

  /** Renders a checkbox checked when raw values contain `checkedValue`. */
  def checkbox[Msg](checkedValue: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    _root_.scalive.input(
      typ      := "checkbox",
      idAttr   := id,
      nameAttr := name,
      value    := checkedValue,
      checked  := rawValues.contains(checkedValue),
      Mod.flatten(mods)
    )

  /** Renders a textarea from the retained raw scalar value. */
  def textarea[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    Form.textareaTag(idAttr := id, nameAttr := name, Mod.flatten(mods), fieldValue)

  /** Renders options and selects every option represented in the retained raw values. */
  def select[Msg](options: Iterable[(String, String)], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    val selectedValues = rawValues.toSet
    _root_.scalive.select(
      idAttr   := id,
      nameAttr := name,
      Mod.flatten(mods),
      options.map { case (optionValue, label) =>
        _root_.scalive.option(
          value    := optionValue,
          selected := selectedValues.contains(optionValue),
          label
        )
      }
    )

  /** Renders only [[visibleErrors]] in an ARIA live feedback container. */
  def errorFeedback(
    render: FormError[Owner] => Mod[Nothing],
    mods: Mod.Input[Nothing]*
  ): HtmlElement[Nothing] =
    val messages = visibleErrors.map { error =>
      Mod.Content.Tag(span(cls := "form-error", render(error)))
    }
    div(
      idAttr           := errorId,
      Form.feedbackFor := name,
      aria.live        := "polite",
      cls              := "form-errors",
      Mod.flatten(mods),
      messages
    )
end FormFieldView

/** Signal-backed rendering operations for [[FormFieldView]]. */
object FormFieldView:
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

  extension [Owner, Input, Value](field: Signal[FormFieldView[Owner, Input, Value]])
    /** Signal of the current stable logical DOM id. */
    def id: Signal[String] = field.map(_.id)

    /** Signal of the current browser field name. */
    def name: Signal[String] = field.map(_.name)

    /** Signal of the DOM id reserved for the current field's feedback container. */
    def errorId: Signal[String] = field.map(_.errorId)

    /** Signal indicating whether at least one field error is currently visible. */
    def hasVisibleErrors: Signal[Boolean] = field.map(_.hasVisibleErrors)

    /** Signal-backed ARIA attributes for the current error visibility. */
    def validationAttributes: Vector[Mod.Attr[Nothing]] = Vector(
      aria.describedby := field.errorId,
      aria.invalid.optional(field.hasVisibleErrors.map(value => Option.when(value)("true")))
    )

    /** Renders a signal-backed text input. */
    def text[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      input(
        typ      := "text",
        idAttr   := field.id,
        nameAttr := field.name,
        value    := field.map(_.fieldValue),
        Mod.flatten(mods)
      )

    /** Renders a signal-backed email input. */
    def email[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      input(
        typ      := "email",
        idAttr   := field.id,
        nameAttr := field.name,
        value    := field.map(_.fieldValue),
        Mod.flatten(mods)
      )

    /** Renders a signal-backed password input. */
    def password[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      input(
        typ      := "password",
        idAttr   := field.id,
        nameAttr := field.name,
        value    := field.map(_.fieldValue),
        Mod.flatten(mods)
      )

    /** Renders a signal-backed hidden input. */
    def hidden[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      input(
        typ      := "hidden",
        idAttr   := field.id,
        nameAttr := field.name,
        value    := field.map(_.fieldValue),
        Mod.flatten(mods)
      )

    /** Renders a signal-backed checkbox whose checked value defaults to `"true"`. */
    def checkbox[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] = checkbox("true", mods*)

    /** Renders a signal-backed checkbox checked when raw values contain `checkedValue`. */
    def checkbox[Msg](checkedValue: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      input(
        typ      := "checkbox",
        idAttr   := field.id,
        nameAttr := field.name,
        value    := checkedValue,
        checked  := field.map(_.rawValues.contains(checkedValue)),
        Mod.flatten(mods)
      )

    /** Renders a signal-backed textarea. */
    def textarea[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
      Form.textareaTag(
        idAttr   := field.id,
        nameAttr := field.name,
        Mod.flatten(mods),
        field.map(_.fieldValue)
      )

    /** Renders signal-backed options and selects every current raw value. */
    def select[Msg](
      options: Iterable[(String, String)],
      mods: Mod.Input[Msg]*
    ): HtmlElement[Msg] =
      _root_.scalive.select(
        idAttr   := field.id,
        nameAttr := field.name,
        Mod.flatten(mods),
        options.map { case (optionValue, label) =>
          _root_.scalive.option(
            value    := optionValue,
            selected := field.map(_.rawValues.contains(optionValue)),
            label
          )
        }
      )

    /** Renders signal-backed visible errors in an ARIA live feedback container. */
    def errorFeedback(
      render: Signal[FormError[Owner]] => Mod[Nothing],
      mods: Mod.Input[Nothing]*
    ): HtmlElement[Nothing] =
      div(
        idAttr           := field.errorId,
        Form.feedbackFor := field.name,
        aria.live        := "polite",
        cls              := "form-errors",
        Mod.flatten(mods),
        field.map(_.visibleErrors).splitByIndex { (_, error) =>
          span(cls := "form-error", render(error))
        }
      )
  end extension
end FormFieldView

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
    errors.filter(error => form.interaction.isUsed(error.address))

  /** Whether submission or field interaction has made this row relevant to feedback. */
  def isUsed: Boolean =
    form.interaction.visibility == ErrorVisibility.All ||
      form.interaction.used.exists(_.startsWith(address))

  /** Resolves a row-schema field to this keyed row's renderable path and address. */
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

  /** Hidden control that preserves row existence and encounter order in core payloads. */
  def presence[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    val path = FormPath.fromSegments(
      rows.path.segments ++ Vector(
        FormPathSegment.Name(current.key),
        FormPathSegment.Name(FormDefinition.RowPresenceName)
      )
    )
    input(
      typ      := "hidden",
      idAttr   := FormAddress.append(address, Vector(FormDefinition.RowPresenceName)).id,
      nameAttr := path.name,
      value    := FormDefinition.RowPresenceValue,
      Mod.flatten(mods)
    )
end FormRowView

/** Signal-backed rendering operations for [[FormRowView]]. */
object FormRowView:
  extension [Owner, Schema, Group, Row](row: Signal[FormRowView[Owner, Schema, Group, Row]])
    /** Signal-backed presence control for rows rendered with [[splitBy]]. */
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
  /** Resolves a declared static field for each current form value. */
  def field[Input, Value](
    definition: FormField[Owner, Input, Value]
  ): Signal[FormFieldView[Owner, Input, Value]] = form.map(_.field(definition))

  /** Resolves stable repeated row views for each current form value. */
  def rows[Group, Row](
    definition: RepeatedRows[Owner, Group, Row]
  ): Signal[Vector[FormRowView[Owner, Schema, Group, Row]]] = form.map(_.rows(definition))
