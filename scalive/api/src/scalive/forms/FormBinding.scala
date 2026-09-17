package scalive

/** Rendering and event wiring for one form instance. Feedback updates are applied in the handler,
  * against the current model, rather than against the form sampled during rendering.
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

  /** Binds a declared field without imposing a particular control renderer. */
  def field[Input, Value](field: FormField[Owner, Input, Value])
    : FormControl[Owner, Input, Value, Msg] =
    new FormControl(
      current.field(field),
      ref,
      trigger,
      feedback,
      onUpdate(FormUpdate.blurred[Owner, Schema, Domain, Input, Value](field, feedback)),
      None
    )

  /** Binds an existing schema- and key-owned repeated field. Render only while its row exists; use
    * [[optionalField]] for a control outside keyed row rendering.
    */
  def field[Group, Input, Value](field: BoundFormField[Owner, Schema, Group, Input, Value])
    : FormControl[Owner, Input, Value, Msg] =
    new FormControl(
      current.map(_.field(field)),
      ref,
      trigger,
      feedback,
      onUpdate(FormUpdate.blurred[Owner, Schema, Domain, Group, Input, Value](field, feedback)),
      None
    )

  /** Binds a stable row key inside a signal's keyed rendering callback. The row must exist whenever
    * this control is evaluated; use [[optionalField]] outside keyed rendering.
    */
  def field[Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    field: FormField[Group, Input, Value]
  ): FormControl[Owner, Input, Value, Msg] =
    new FormControl(
      current.field(rows, key, field),
      ref,
      trigger,
      feedback,
      onUpdate(
        FormUpdate.blurred[Owner, Schema, Domain, Group, Row, Input, Value](
          rows,
          key,
          field,
          feedback
        )
      ),
      None
    )

  /** Renders a captured repeated field only while its row exists. This installs an optional signal
    * scope so a later row removal never evaluates a stale control.
    */
  def optionalField[Group, Input, Value](
    field: BoundFormField[Owner, Schema, Group, Input, Value]
  )(
    render: FormControl[Owner, Input, Value, Msg] => HtmlElement[Msg]
  ): Mod[Msg] =
    current.map(_.fieldOption(field)).option { view =>
      render(
        new FormControl(
          view,
          ref,
          trigger,
          feedback,
          onUpdate(FormUpdate.blurred[Owner, Schema, Domain, Group, Input, Value](field, feedback)),
          None
        )
      )
    }

  /** Renders one keyed field outside a keyed row loop, omitting it when the row is absent. */
  def optionalField[Group, Row, Input, Value](
    rows: RepeatedRows[Owner, Group, Row],
    key: FormRowKey[Group],
    field: FormField[Group, Input, Value]
  )(
    render: FormControl[Owner, Input, Value, Msg] => HtmlElement[Msg]
  ): Mod[Msg] =
    current.fieldOption(rows, key, field).option { view =>
      render(
        new FormControl(
          view,
          ref,
          trigger,
          feedback,
          onUpdate(
            FormUpdate.blurred[Owner, Schema, Domain, Group, Row, Input, Value](
              rows,
              key,
              field,
              feedback
            )
          ),
          None
        )
      )
    }

  /** Row views for keyed rendering, including the existing row presence helpers. */
  def rows[Group, Row](rows: RepeatedRows[Owner, Group, Row])
    : Signal[Vector[FormRowView[Owner, Schema, Group, Row]]] = current.rows(rows)
end FormBinding

private[scalive] object FormBinding:
  private[scalive] def checked[Msg](mods: Vector[Mod[Msg]], owned: Set[String]): Vector[Mod[Msg]] =
    val overrides = mods
      .flatMap {
        case attr: Mod.Attr[Msg] => attr.flattened.flatMap(Form.attributeName)
        case _                   => Vector.empty
      }.filter(name => owned.exists(_.equalsIgnoreCase(name)))
    require(
      overrides.isEmpty,
      s"form binding owns attributes: ${overrides.distinct.mkString(", ")}"
    )
    mods

/** A reusable field rendering handle. Its ids are scoped to the owning form instance. [[view]]
  * exposes read-only field data; use this handle's ids and ARIA attributes when rendering feedback
  * so multiple instances of the same definition do not share DOM identities.
  */
final class FormControl[Owner, Input, Value, Msg] private[scalive] (
  val view: Signal[FormFieldView[Owner, Input, Value]],
  formRef: DomRef,
  trigger: DomRef,
  feedback: FormFeedback,
  val blurred: Msg,
  blurHandler: Option[String => Msg]):

  def id: Signal[String]                              = view.id.map(id => s"${formRef.value}-$id")
  def name: Signal[String]                            = view.name
  def errorId: Signal[String]                         = id.map(_ + "_errors")
  def fieldValue: Signal[String]                      = view.map(_.fieldValue)
  def rawValues: Signal[Vector[String]]               = view.map(_.rawValues)
  def visibleErrors: Signal[Vector[FormError[Owner]]] = view.map(_.visibleErrors)
  def hasVisibleErrors: Signal[Boolean]               = view.hasVisibleErrors

  /** Adds an application scalar blur handler after feedback dispatch. The browser value is passed
    * as with `on.blur.withValue`. Commands are ordered but do not wait for server acknowledgements;
    * this does not guarantee race-free normalization against later full-form snapshots. Missing
    * values (including unchecked checkboxes) become an empty string. A multi-select supplies only
    * its native scalar value here; use the form update for its complete value vector.
    */
  def onBlur(f: String => Msg): FormControl[Owner, Input, Value, Msg] =
    new FormControl(view, formRef, trigger, feedback, blurred, Some(f))

  /** Adds a message-only application blur handler after feedback dispatch. */
  def onBlur(message: Msg): FormControl[Owner, Input, Value, Msg] =
    onBlur((_: String) => message)

  /** Interaction wiring only, for custom controls that own their id/name/value attributes.
    * Composite widgets should report [[blurred]] when leaving their logical focus boundary instead.
    */
  def blurAttributes: Vector[Mod.Attr[Msg]] =
    if feedback == FormFeedback.WhenUsed && blurHandler.isEmpty then Vector.empty
    else
      val command = name.map { name =>
        val dispatch = feedback match
          case FormFeedback.WhenUsed  => JS
          case FormFeedback.AfterBlur =>
            JS.setAttribute(
              s"phx-value-${FormUpdate.BlurMetadataKey}" -> name,
              to = formRef.selector
            )
              .dispatch("input", to = trigger.selector)
              .removeAttribute(s"phx-value-${FormUpdate.BlurMetadataKey}", to = formRef.selector)
        blurHandler.fold[JSCommands.JSCommand[Msg]](dispatch)(dispatch.pushWithValue(_))
      }
      Vector(on.blur(command))

  /** Scalar input identity, retained value, and blur wiring. Does not include type or ARIA. */
  def inputAttributes: Vector[Mod.Attr[Msg]] =
    Vector(idAttr := id, nameAttr := name, value := fieldValue) ++ blurAttributes

  def validationAttributes: Vector[Mod.Attr[Nothing]] = Vector(
    aria.describedby := errorId,
    aria.invalid.optional(hasVisibleErrors.map(value => Option.when(value)("true")))
  )

  def text(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    input(typ := "text", inputAttributes, controlModifiers(mods))

  def email(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    input(typ := "email", inputAttributes, controlModifiers(mods))

  /** Renders a telephone input from the retained raw scalar value. */
  def tel(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    input(typ := "tel", inputAttributes, controlModifiers(mods))

  /** Renders a date input without parsing the retained raw scalar value. Native browser
    * sanitization can make invalid raw dates appear empty.
    */
  def date(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    input(typ := "date", inputAttributes, controlModifiers(mods))

  /** Renders a search input from the retained raw scalar value. */
  def search(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    input(typ := "search", inputAttributes, controlModifiers(mods))

  def password(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    input(typ := "password", inputAttributes, controlModifiers(mods))

  /** Hidden values never install a native blur handler. */
  def hidden(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    input(
      typ      := "hidden",
      idAttr   := id,
      nameAttr := name,
      value    := fieldValue,
      controlModifiers(mods)
    )

  def checkbox(mods: Mod.Input[Msg]*): HtmlElement[Msg] = checkbox("true", mods*)

  def checkbox(checkedValue: String, mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    input(
      typ      := "checkbox",
      idAttr   := id,
      nameAttr := name,
      value    := checkedValue,
      checked  := rawValues.map(_.contains(checkedValue)),
      blurAttributes,
      controlModifiers(mods)
    )

  def textarea(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    Form.textareaTag(
      idAttr   := id,
      nameAttr := name,
      blurAttributes,
      controlModifiers(mods),
      fieldValue
    )

  def select(options: Iterable[(String, String)], mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    _root_.scalive.select(
      idAttr   := id,
      nameAttr := name,
      blurAttributes,
      controlModifiers(mods),
      options.map { case (optionValue, label) =>
        option(value := optionValue, selected := rawValues.map(_.contains(optionValue)), label)
      }
    )

  def errorFeedback(
    render: Signal[FormError[Owner]] => Mod[Nothing],
    mods: Mod.Input[Nothing]*
  ): HtmlElement[Nothing] =
    div(
      idAttr           := errorId,
      Form.feedbackFor := name,
      aria.live        := "polite",
      cls              := "form-errors",
      FormBinding.checked(Mod.flatten(mods), Set("id", "phx-feedback-for", "aria-live")),
      visibleErrors.splitByIndex((_, error) => span(cls := "form-error", render(error)))
    )

  private def controlModifiers(mods: Seq[Mod.Input[Msg]]): Vector[Mod[Msg]] =
    FormBinding.checked(
      Mod.flatten(mods),
      Set("id", "name", "value", "type", "checked", "phx-change", "phx-blur")
    )
end FormControl

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
