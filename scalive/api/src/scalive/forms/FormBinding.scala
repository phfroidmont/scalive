package scalive

import scalive.FormBinding.controlModifiers

/** Rendering and event wiring for one form instance. Feedback updates are applied in the handler,
  * against the current model, rather than against the form sampled during rendering.
  *
  * @param ref
  *   The form's DOM reference. It supplies the form's `id` and scopes the ids of bound controls.
  * @param feedback
  *   The policy that decides when field errors become visible.
  */
final class FormBinding[Owner, Schema, Domain, Msg] private[scalive] (
  source: Signal[Form[Owner, Schema, Domain]],
  val ref: DomRef,
  val feedback: FormFeedback,
  onUpdate: FormUpdate[Owner, Schema, Domain] => Msg,
  onSubmit: FormEvent[Owner, Schema, Domain] => Msg):

  private val current = source.map(_.withFeedback(feedback))
  private val trigger = DomRef(s"${ref.value}-scalive-blur-trigger")

  private def eventBinding(
    name: String,
    kind: FormEventKind,
    callback: FormEvent[Owner, Schema, Domain] => Msg
  ): Mod.Attr[Msg] =
    Mod.Attr.SignalBinding(
      name,
      current,
      (form, payload) =>
        val event = payload
          .typedFormEvent(
            (data, eventKind, meta) => form.owningDefinition.event(data, eventKind, meta),
            kind
          ).asInstanceOf[FormEvent[Owner, Schema, Domain]]
        val next =
          if event.kind == FormEventKind.Submitted then
            FormUpdate.fromEvent(event, feedback).applyTo(form).form
          else event.form.withFeedback(feedback)
        callback(new FormEvent(next, event.data, event.kind, event.meta))
    )

  private def control[Input, Value](
    view: Signal[FormFieldView[Owner, Input, Value]]
  ): FormControl[Owner, Input, Value, Nothing] =
    new FormControl(
      view,
      view.map(field => s"${ref.value}-${field.id}"),
      Option.when(feedback == FormFeedback.AfterBlur)(FormControl.BlurTransport(ref, trigger)),
      None
    )

  /** Form attributes and the internal blur trigger. Include exactly once on the owning form. Under
    * [[FormFeedback.AfterBlur]] these include a nameless hidden child, not just attributes.
    */
  def modifiers: Vector[Mod[Msg]] =
    val changed = (event: FormEvent[Owner, Schema, Domain]) =>
      onUpdate(FormUpdate.fromEvent(event, feedback))
    Vector(
      ref.attr,
      eventBinding("phx-change", FormEventKind.Changed, changed),
      eventBinding("phx-auto-recover", FormEventKind.Recovered, changed),
      eventBinding("phx-submit", FormEventKind.Submitted, onSubmit)
    ) ++ Option.when(feedback == FormFeedback.AfterBlur)(
      // Dispatch must be synchronous: the blur metadata is removed immediately afterward.
      Mod.Content.Tag(input(typ := "hidden", trigger.attr))
    )

  /** Renders a form with its bindings and internal trigger. Do not override the generated id or
    * change, recovery, and submit bindings. Use [[modifiers]] for an existing form renderer.
    */
  def render(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    _root_.scalive.form(
      modifiers,
      FormBinding.checked(
        "form binding",
        Mod.flatten(mods),
        Set(
          "id",
          "phx-change",
          "phx-auto-recover",
          "phx-submit",
          s"phx-value-${FormUpdate.BlurMetadataKey}"
        )
      )
    )

  /** Binds one field to a [[FormControl]] whose ids are scoped to this form instance.
    *
    * This form takes a field declared on the form root.
    */
  def field[Input, Value](field: FormField[Owner, Input, Value])
    : FormControl[Owner, Input, Value, Nothing] =
    control(current.map(_.fieldView(field)))

  /** Binds one field to a [[FormControl]] whose ids are scoped to this form instance.
    *
    * This form takes an existing schema- and key-owned repeated field. Render it only while its row
    * exists; use [[optionalField]] for a control outside keyed row rendering.
    */
  def field[Group, Input, Value](field: BoundFormField[Owner, Schema, Group, Input, Value])
    : FormControl[Owner, Input, Value, Nothing] =
    control(current.map(_.fieldView(field)))

  /** Binds one field to a [[FormControl]] whose ids are scoped to this form instance.
    *
    * This form takes a stable row key inside a signal's keyed rendering callback. The row must
    * exist whenever this control is evaluated; use [[optionalField]] outside keyed rendering.
    */
  def field[Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    field: FormField[Group, Input, Value]
  ): FormControl[Owner, Input, Value, Nothing] =
    control(current.rowFieldView(rows, key, field))

  /** Renders one repeated-row field with `render`, or nothing while its row is absent.
    *
    * This form takes a captured [[BoundFormField]]. It installs an optional signal scope so a later
    * row removal never evaluates a stale control.
    */
  def optionalField[Group, Input, Value](
    field: BoundFormField[Owner, Schema, Group, Input, Value]
  )(
    render: FormControl[Owner, Input, Value, Nothing] => HtmlElement[Msg]
  ): Mod[Msg] =
    current.map(_.fieldViewOption(field)).option(view => render(control(view)))

  /** Renders one repeated-row field with `render`, or nothing while its row is absent.
    *
    * This form takes the row group, a stable row key, and a row field, for use outside a keyed row
    * loop.
    */
  def optionalField[Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    field: FormField[Group, Input, Value]
  )(
    render: FormControl[Owner, Input, Value, Nothing] => HtmlElement[Msg]
  ): Mod[Msg] =
    current.rowFieldViewOption(rows, key, field).option(view => render(control(view)))

  /** The update message that marks a field as blurred.
    *
    * A composite widget sends it when focus leaves the whole widget; under
    * [[FormFeedback.AfterBlur]] it makes the field's errors visible. This form takes a field
    * declared on the form root.
    */
  def blurred[Input, Value](field: FormField[Owner, Input, Value]): Msg =
    onUpdate(FormUpdate.blurred[Owner, Schema, Domain, Input, Value](field, feedback))

  /** The update message that marks a field as blurred.
    *
    * This form takes an existing schema- and key-owned repeated field. A blur for a row that has
    * since been removed is ignored.
    */
  def blurred[Group, Input, Value](field: BoundFormField[Owner, Schema, Group, Input, Value]): Msg =
    onUpdate(FormUpdate.blurred[Owner, Schema, Domain, Group, Input, Value](field, feedback))

  /** The update message that marks a field as blurred.
    *
    * This form takes the row group, a stable row key, and a row field. A blur for a row that has
    * since been removed is ignored.
    */
  def blurred[Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    field: FormField[Group, Input, Value]
  ): Msg =
    onUpdate(
      FormUpdate.blurred[Owner, Schema, Domain, Group, Row, Input, Value](
        rows,
        key,
        field,
        feedback
      )
    )

  /** Renders the hidden control that keeps the row with `key` in `rows`, and its order, in the next
    * submission.
    *
    * Render it in every row, inside a signal's keyed rendering callback, even when all the row's
    * visible fields are empty or unchecked. The row must exist whenever the control is evaluated.
    * Its id is scoped to this form instance.
    *
    * The control owns its `id`, `name`, `value`, and `type` attributes. Setting one in `mods`, in
    * any letter case, throws `IllegalArgumentException`.
    */
  def presence[Group, Row](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    mods: Mod.Input[Msg]*
  ): HtmlElement[Msg] =
    Form.rowPresence(current, rows, key, id => s"${ref.value}-$id", mods)

  /** Row views for keyed rendering, with the stable keys that [[field]] and [[presence]] take. */
  def rows[Group, Row](rows: RepeatedRows[Owner, Group, Row])
    : Signal[Vector[FormRowView[Owner, Schema, Group, Row]]] = current.rows(rows)
end FormBinding

private[scalive] object FormBinding:
  private[scalive] def checked[Msg](
    owner: String,
    mods: Vector[Mod[Msg]],
    owned: Set[String]
  ): Vector[Mod[Msg]] =
    val overrides = Form.overriddenAttributes(mods, owned)
    require(overrides.isEmpty, s"$owner owns attributes: ${overrides.distinct.mkString(", ")}")
    mods

  private[scalive] def controlModifiers[Msg](mods: Seq[Mod.Input[Msg]]): Vector[Mod[Msg]] =
    checked(
      "form control",
      Mod.flatten(mods),
      Set("id", "name", "value", "type", "checked", "phx-change", "phx-blur")
    )

/** The handle that renders one field's controls and error feedback.
  *
  * Get one from `field` on a form value or a form signal, or from a [[FormBinding]]. A control from
  * a form value or a form signal uses the field's logical id and reports no blur feedback, so one
  * rendering helper serves live and static pages alike. A control from a binding scopes its ids to
  * the form instance, so several instances of one definition do not share DOM identities, and
  * reports blur under the binding's feedback policy.
  *
  * [[view]] is a signal for rendering; read field data in handlers with `fieldView` on the form
  * value.
  *
  * The control owns the `id`, `name`, `value`, `type`, `checked`, `phx-change`, and `phx-blur`
  * attributes of every control it renders, in any letter case. The control helpers throw
  * `IllegalArgumentException` when their `mods` set one, even one the control would not render. For
  * a custom control, use [[inputAttributes]] instead of a helper.
  *
  * @param view
  *   The field's current read-only data, for rendering. Its ids are not scoped to a form instance;
  *   render with this control's ids instead.
  */
final class FormControl[Owner, Input, Value, +Msg] private[scalive] (
  val view: Signal[FormFieldView[Owner, Input, Value]],
  controlId: Signal[String],
  blurTransport: Option[FormControl.BlurTransport],
  blurHandler: Option[String => Msg]):

  /** The control's DOM id, scoped to the form instance when bound. Use it for a label's `forId`. */
  def id: Signal[String] = controlId

  /** The browser field name, such as `profile[name]`. */
  def name: Signal[String] = view.map(_.name)

  /** The DOM id of this control's error feedback container. */
  def errorId: Signal[String] = id.map(_ + "_errors")

  /** The value a scalar control renders: the last retained raw value, or empty when absent. */
  def fieldValue: Signal[String] = view.map(_.fieldValue)

  /** Every raw value retained for this field, before decoding. */
  def rawValues: Signal[Vector[String]] = view.map(_.rawValues)

  /** The field's errors that the feedback policy currently makes visible. */
  def visibleErrors: Signal[Vector[FormError[Owner]]] = view.map(_.visibleErrors)

  /** Whether at least one field error is currently visible. */
  def hasVisibleErrors: Signal[Boolean] = view.map(_.hasVisibleErrors)

  /** Returns a copy of this control that also sends an application message when the field blurs.
    *
    * This form passes the browser value to `f`, as with `on.blur.withValue`. In a bound form, the
    * message follows feedback dispatch. Commands are ordered but do not wait for server
    * acknowledgements; this does not guarantee race-free normalization against later full-form
    * snapshots. Missing values (including unchecked checkboxes) become an empty string. A
    * multi-select supplies only its native scalar value here; use the form's change event for its
    * complete value vector.
    */
  def onBlur[M >: Msg](f: String => M): FormControl[Owner, Input, Value, M] =
    new FormControl(view, controlId, blurTransport, Some(f))

  /** Returns a copy of this control that also sends an application message when the field blurs.
    *
    * This form sends `message` and ignores the browser value.
    */
  def onBlur[M >: Msg](message: M): FormControl[Owner, Input, Value, M] =
    onBlur((_: String) => message)

  /** Interaction wiring only, for custom controls that own their id/name/value attributes.
    * Composite widgets should send [[FormBinding.blurred]] when leaving their logical focus
    * boundary instead.
    */
  def blurAttributes: Vector[Mod.Attr[Msg]] =
    if blurTransport.isEmpty && blurHandler.isEmpty then Vector.empty
    else
      val command = name.map { name =>
        val dispatch = blurTransport match
          case None            => JS
          case Some(transport) =>
            JS.setAttribute(
              s"phx-value-${FormUpdate.BlurMetadataKey}" -> name,
              to = transport.form.selector
            )
              .dispatch("input", to = transport.trigger.selector)
              .removeAttribute(
                s"phx-value-${FormUpdate.BlurMetadataKey}",
                to = transport.form.selector
              )
        blurHandler.fold[JSCommands.JSCommand[Msg]](dispatch)(dispatch.pushWithValue(_))
      }
      Vector(on.blur(command))

  /** Scalar input identity, retained value, and blur wiring. Does not include type or ARIA. */
  def inputAttributes: Vector[Mod.Attr[Msg]] =
    Vector(idAttr := id, nameAttr := name, value := fieldValue) ++ blurAttributes

  /** ARIA attributes linking the control to its error feedback and exposing visible invalidity.
    *
    * The control helpers do not add them; pass them explicitly, as in
    * `control.text(control.validationAttributes)`.
    */
  def validationAttributes: Vector[Mod.Attr[Nothing]] = Vector(
    aria.describedby := errorId,
    aria.invalid.optional(hasVisibleErrors.map(value => Option.when(value)("true")))
  )

  /** Renders a text input from the retained raw scalar value.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def text[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] =
    input(typ := "text", inputAttributes, controlModifiers(mods))

  /** Renders an email input from the retained raw scalar value.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def email[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] =
    input(typ := "email", inputAttributes, controlModifiers(mods))

  /** Renders a telephone input from the retained raw scalar value.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def tel[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] =
    input(typ := "tel", inputAttributes, controlModifiers(mods))

  /** Renders a date input without parsing the retained raw scalar value. Native browser
    * sanitization can make invalid raw dates appear empty.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def date[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] =
    input(typ := "date", inputAttributes, controlModifiers(mods))

  /** Renders a search input from the retained raw scalar value.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def search[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] =
    input(typ := "search", inputAttributes, controlModifiers(mods))

  /** Renders a password input from the retained raw scalar value.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def password[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] =
    input(typ := "password", inputAttributes, controlModifiers(mods))

  /** Renders a hidden input from the retained raw scalar value.
    *
    * Hidden values never install a native blur handler.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def hidden[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] =
    input(
      typ      := "hidden",
      idAttr   := id,
      nameAttr := name,
      value    := fieldValue,
      controlModifiers(mods)
    )

  /** Renders a checkbox, checked when the retained raw values contain its checked value.
    *
    * This form uses the checked value `"true"`, matching `FieldInput.checkbox()`. No hidden
    * unchecked input is rendered.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def checkbox[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] = checkbox("true", mods*)

  /** Renders a checkbox, checked when the retained raw values contain its checked value.
    *
    * This form uses `checkedValue`, which must match the token the field's codec expects, as in
    * `FieldInput.checkbox("yes")` with `control.checkbox("yes")`. No hidden unchecked input is
    * rendered.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def checkbox[M >: Msg](checkedValue: String, mods: Mod.Input[M]*): HtmlElement[M] =
    input(
      typ      := "checkbox",
      idAttr   := id,
      nameAttr := name,
      value    := checkedValue,
      checked  := rawValues.map(_.contains(checkedValue)),
      blurAttributes,
      controlModifiers(mods)
    )

  /** Binds one checkbox choice with its own field-scoped identity.
    *
    * The stable key is separate from the submitted value and must be unique among items rendered
    * for this control. Keys are encoded losslessly, including empty strings; null is rejected. Use
    * the returned id for labels, and this control's validation attributes and error feedback for
    * the shared field. Configure [[onBlur]] before creating items. Under `AfterBlur`, leaving any
    * checkbox blurs the field, even when focus moves to another item.
    */
  def item(key: String): FormControlItem[Msg] =
    require(key != null, "form control item key must not be null")
    // Encoding UTF-16 code units also distinguishes malformed surrogate keys without replacement.
    val encoded = key.iterator.map(char => f"${char.toInt}%04x").mkString
    new FormControlItem(id.map(_ + s"_item_$encoded"), name, rawValues, blurAttributes)

  /** Renders a textarea from the retained raw scalar value.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def textarea[M >: Msg](mods: Mod.Input[M]*): HtmlElement[M] =
    Form.textareaTag(
      idAttr   := id,
      nameAttr := name,
      blurAttributes,
      controlModifiers(mods),
      fieldValue
    )

  /** Renders a select from `(value, label)` options, selecting each one in the retained raw values.
    *
    * For a `multiple` select, use a repeated-value field such as `Root.texts`.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def select[M >: Msg](options: Iterable[(String, String)], mods: Mod.Input[M]*): HtmlElement[M] =
    _root_.scalive.select(
      idAttr   := id,
      nameAttr := name,
      blurAttributes,
      controlModifiers(mods),
      options.map { case (optionValue, label) =>
        option(value := optionValue, selected := rawValues.map(_.contains(optionValue)), label)
      }
    )

  /** Renders the visible errors in an ARIA live feedback container whose id is [[errorId]].
    *
    * `render` draws one error; the container and its `form-errors` and `form-error` wrappers are
    * supplied. Overriding `id`, `phx-feedback-for`, or `aria-live` through `mods` is rejected.
    */
  def errorFeedback(
    render: Signal[FormError[Owner]] => Mod[Nothing],
    mods: Mod.Input[Nothing]*
  ): HtmlElement[Nothing] =
    div(
      idAttr           := errorId,
      Form.feedbackFor := name,
      aria.live        := "polite",
      cls              := "form-errors",
      FormBinding.checked(
        "form control",
        Mod.flatten(mods),
        Set("id", "phx-feedback-for", "aria-live")
      ),
      visibleErrors.splitByIndex((_, error) => span(cls := "form-error", render(error)))
    )

end FormControl

private[scalive] object FormControl:
  /** The form and hidden trigger that carry a blur to the server under [[FormFeedback.AfterBlur]].
    */
  final case class BlurTransport(form: DomRef, trigger: DomRef)

  /** A control that uses the field's logical id and reports no blur feedback. */
  def unbound[Owner, Input, Value](
    view: Signal[FormFieldView[Owner, Input, Value]]
  ): FormControl[Owner, Input, Value, Nothing] =
    new FormControl(view, view.map(_.id), None, None)

/** One checkbox choice within a field, created by [[FormControl.item]].
  *
  * Items share raw values, validation, and interaction state with their parent field. They do not
  * add logical fields or hidden fallback inputs. Use a repeated-value codec such as `Root.texts`
  * for multiple selections; unchecked and disabled items follow native submission rules.
  */
final class FormControlItem[+Msg] private[scalive] (
  itemId: Signal[String],
  fieldName: Signal[String],
  rawValues: Signal[Vector[String]],
  blurAttributes: Vector[Mod.Attr[Msg]]):

  /** Field-scoped item identity, suitable for a label's `forId`. */
  def id: Signal[String] = itemId

  /** The parent's exact browser field name, shared by every item. */
  def name: Signal[String] = fieldName

  /** Renders this item's checkbox, checked when the parent's raw values contain `checkedValue`.
    *
    * This form takes a fixed token. ARIA remains opt-in through the parent control's validation
    * attributes.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def checkbox[M >: Msg](checkedValue: String, mods: Mod.Input[M]*): HtmlElement[M] =
    input(
      typ      := "checkbox",
      idAttr   := id,
      nameAttr := name,
      value    := checkedValue,
      checked  := rawValues.map(_.contains(checkedValue)),
      blurAttributes,
      controlModifiers(mods)
    )

  /** Renders this item's checkbox, checked when the parent's raw values contain `checkedValue`.
    *
    * This form takes a reactive token without changing item identity. Both token and raw-value
    * changes update checked state; changing the token never rewrites the field's retained values.
    *
    * Throws `IllegalArgumentException` when `mods` sets an attribute this control owns; see
    * [[FormControl]].
    */
  def checkbox[M >: Msg](checkedValue: Signal[String], mods: Mod.Input[M]*): HtmlElement[M] =
    input(
      typ      := "checkbox",
      idAttr   := id,
      nameAttr := name,
      value    := checkedValue,
      checked  := rawValues.combineWithFn(checkedValue)((values, token) => values.contains(token)),
      blurAttributes,
      controlModifiers(mods)
    )
end FormControlItem

extension [Owner, Schema, Domain](form: Signal[Form[Owner, Schema, Domain]])
  /** Connects one rendered form instance to state-preserving updates and typed submission. Apply
    * incoming updates to the current model with [[FormUpdate.applyTo]].
    */
  def bind[Msg](
    ref: DomRef,
    onUpdate: FormUpdate[Owner, Schema, Domain] => Msg,
    onSubmit: FormEvent[Owner, Schema, Domain] => Msg,
    feedback: FormFeedback = FormFeedback.WhenUsed
  ): FormBinding[Owner, Schema, Domain, Msg] =
    new FormBinding(form, ref, feedback, onUpdate, onSubmit)
