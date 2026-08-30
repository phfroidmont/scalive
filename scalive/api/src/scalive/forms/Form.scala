package scalive

import scalive.codecs.StringAsIsEncoder

/** Lower-level form model with a runtime root, current state, and event codec.
  *
  * Prefer [[FormRoot]] and [[FormDefinition]] for new forms: their [[RootedForm]] facade enforces
  * field ownership at compile time. This unowned API accepts relative paths and supports states
  * containing either rooted browser names or legacy relative names. Path-based value and raw-value
  * lookup tries the rooted path first, then the relative path; error lookup combines both, and used
  * state accepts either. A typed [[FormField]] is expected to contain its complete path and is
  * checked against [[root]] at runtime, unless the root is empty.
  *
  * @param root
  *   the path prepended to relative field paths; an empty path makes the form unrooted
  * @param state
  *   the current raw, decoded, used-field, and submission state
  * @param codec
  *   the codec used by typed LiveView event bindings
  * @tparam A
  *   the decoded whole-form value type
  */
final case class Form[A](root: FormPath, state: FormState[A], codec: FormCodec[A]):
  /** Renders an ordinary browser form targeting `target`.
    *
    * The model's root, state, and codec are not otherwise applied automatically; add controls and
    * LiveView bindings through `mods` as needed. This delegates to [[Form.http]].
    */
  def http[Msg](
    target: FormAction
  )(
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] = Form.http(target)(mods*)

  /** Binds `phx-change` and decodes each event with [[codec]].
    *
    * The callback runs for successful and failed decodes. It receives a new [[FormEvent]] but this
    * immutable form is not updated automatically.
    */
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.change.form(codec)(f)

  /** Binds `phx-submit` and decodes each event with [[codec]].
    *
    * The event is marked submitted, making every field used when converted to [[FormState]]. The
    * callback also runs when decoding fails.
    */
  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.submit.form(codec)(f)

  /** Binds `phx-auto-recover` and decodes recovery events with [[codec]]. */
  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.recover.form(codec)(f)

  /** Disables Phoenix's automatic form recovery by rendering `phx-auto-recover="ignore"`. */
  def disableRecovery: Mod.Attr[Nothing] =
    phx.autoRecover := "ignore"

  /** Renders `phx-trigger-action` when `condition` is true.
    *
    * On a form with an ordinary `action` and `method`, Phoenix uses this attribute to hand a Live
    * submit back to the browser for normal HTTP submission.
    */
  def triggerHttpSubmitWhen(condition: Boolean): Mod.Attr[Nothing] =
    phx.triggerAction := condition

  /** Returns an untyped view of a relative field path.
    *
    * The view reads values from the rooted path first and falls back to the exact relative path.
    */
  def field(path: String): FormFieldView[Vector[String]] =
    field(FormPath.parse(path))

  /** Returns an untyped view of a relative field path.
    *
    * Raw display values use the rooted path first and fall back to the exact relative path. The
    * view's [[FormFieldView.decoded decoded value]] is always decoded from the rooted field path.
    */
  def field(path: FormPath): FormFieldView[Vector[String]] =
    FormFieldView(this, FormField.strings(fullPath(path)), Some(path))

  /** Returns the current view of a typed, fully rooted field definition.
    *
    * Unlike the path overloads, this performs no relative fallback.
    *
    * @throws IllegalArgumentException
    *   if this form has a non-empty root and the field path is outside it
    */
  def field[B](definition: FormField[B]): FormFieldView[B] =
    require(
      root.isEmpty || definition.path.startsWith(root),
      s"field ${definition.name} is outside form root ${root.name}"
    )
    FormFieldView(this, definition, None)

  /** Returns the rooted browser name for a relative field path. */
  def name(path: String): String =
    name(FormPath.parse(path))

  /** Returns the rooted browser name for a relative field path. */
  def name(path: FormPath): String =
    fullPath(path).name

  /** Returns the default DOM ID for a relative field path. */
  def id(path: String): String =
    id(FormPath.parse(path))

  /** Returns the default DOM ID for a relative field path. */
  def id(path: FormPath): String =
    fullPath(path).id

  /** Returns the last raw value for a relative field path, or the empty string.
    *
    * The rooted path is tried first, followed by the exact relative path.
    */
  def value(path: String): String =
    value(FormPath.parse(path))

  /** Returns the last raw value for a relative field path, or the empty string.
    *
    * The rooted path is tried first, followed by the exact relative path.
    */
  def value(path: FormPath): String =
    state.raw.string(fullPath(path)).orElse(state.raw.string(path)).getOrElse("")

  /** Renders a text input for a relative field path. */
  def text[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).text(mods*)

  /** Renders a text input for a relative field path. */
  def text[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).text(mods*)

  /** Renders a text input with an explicit DOM ID for a relative field path. */
  def text[Msg](path: String, explicitId: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).text(explicitId, mods*)

  /** Renders a text input with an explicit DOM ID for a relative field path. */
  def text[Msg](path: FormPath, explicitId: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).text(explicitId, mods*)

  /** Renders an email input for a relative field path. */
  def email[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).email(mods*)

  /** Renders an email input for a relative field path. */
  def email[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).email(mods*)

  /** Renders a password input for a relative field path. */
  def password[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).password(mods*)

  /** Renders a password input for a relative field path. */
  def password[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).password(mods*)

  /** Renders a hidden input for a relative field path. */
  def hidden[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).hidden(mods*)

  /** Renders a hidden input for a relative field path. */
  def hidden[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).hidden(mods*)

  /** Renders a checkbox whose submitted value is `"true"`. */
  def checkbox[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).checkbox(mods*)

  /** Renders a checkbox with `checkedValue` as its submitted value. */
  def checkbox[Msg](path: String, checkedValue: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).checkbox(checkedValue, mods*)

  /** Renders a checkbox whose submitted value is `"true"`. */
  def checkbox[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).checkbox(mods*)

  /** Renders a checkbox with `checkedValue` as its submitted value. */
  def checkbox[Msg](path: FormPath, checkedValue: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).checkbox(checkedValue, mods*)

  /** Renders a textarea for a relative field path. */
  def textarea[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).textarea(mods*)

  /** Renders a textarea for a relative field path. */
  def textarea[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).textarea(mods*)

  /** Renders a select whose options are `(submittedValue, label)` pairs. */
  def select[Msg](
    path: String,
    options: Iterable[(String, String)],
    mods: Mod[Msg]*
  ): HtmlElement[Msg] =
    field(path).select(options, mods*)

  /** Renders a select whose options are `(submittedValue, label)` pairs. */
  def select[Msg](
    path: FormPath,
    options: Iterable[(String, String)],
    mods: Mod[Msg]*
  ): HtmlElement[Msg] =
    field(path).select(options, mods*)

  /** Renders the distinct current errors for a relative field path.
    *
    * This helper does not apply used-field visibility rules. Prefer [[FormFieldView.errorFeedback]]
    * when errors should appear only after interaction and be paired with control accessibility
    * attributes.
    */
  def errors(path: String): HtmlElement[Nothing] =
    errors(FormPath.parse(path))

  /** Renders the distinct current errors for a relative field path.
    *
    * Errors stored under the exact relative and rooted paths are combined and deduplicated. The
    * wrapper has class `form-errors`; each message is a `span.form-error`.
    */
  def errors(path: FormPath): HtmlElement[Nothing] =
    val messages = errorsFor(path).map { error =>
      Mod.Content.Tag(span(cls := "form-error", error.message))
    }
    div(cls := "form-errors", messages)

  /** Renders a Phoenix feedback container for a relative field path. */
  def feedback(path: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    feedback(FormPath.parse(path), mods*)

  /** Renders a `div` with `phx-feedback-for` set to the rooted field name.
    *
    * Caller modifiers, including content, are appended to the container.
    */
  def feedback(path: FormPath, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    div(Form.feedbackFor := name(path), mods)

  /** Returns the distinct current errors for a relative field path, regardless of visibility. */
  def errorsFor(path: String): Vector[FormError] =
    errorsFor(FormPath.parse(path))

  /** Returns the distinct current errors for a relative field path, regardless of visibility.
    *
    * Errors stored under the exact relative and rooted paths are combined and deduplicated while
    * preserving their first occurrence.
    */
  def errorsFor(path: FormPath): Vector[FormError] =
    val relativeErrors = state.errors.forPath(path)
    val fullErrors     = state.errors.forPath(fullPath(path))
    (relativeErrors ++ fullErrors).distinct

  /** Tests whether a relative field is eligible to show errors. */
  def isUsed(path: String): Boolean =
    isUsed(FormPath.parse(path))

  /** Tests whether a relative field is eligible to show errors.
    *
    * Either the exact relative or rooted path may mark it used; a submitted form treats every path
    * as used.
    */
  def isUsed(path: FormPath): Boolean =
    state.isUsed(path) || state.isUsed(fullPath(path))

  private[scalive] def fullPath(path: FormPath): FormPath =
    if root.isEmpty then path
    else FormPath(root.segments ++ path.segments)
end Form

/** Constructors and rendering helpers for lower-level [[Form]] values. */
object Form:
  private[scalive] val feedbackFor = htmlAttr("phx-feedback-for", StringAsIsEncoder)
  private[scalive] val textareaTag = HtmlTag("textarea")
  private val httpAttributes       = Set("action", "method")
  private val csrfMarker           = htmlAttr("data-scalive-csrf", StringAsIsEncoder)

  /** Renders an ordinary HTML form with the action and method owned by `target`.
    *
    * `mods` may contain individual modifiers or collections of modifiers. The target owns the
    * transport attributes; directly supplying an `action` or `method` attribute, with any letter
    * case, is rejected. LiveView bindings and `phx-trigger-action` are not added automatically.
    *
    * A checked POST action is marked for CSRF-token injection during Scalive rendering. Checked GET
    * actions and all [[FormAction.unsafe unsafe]] actions are not marked. Token injection also
    * requires rendering in a lifecycle with configured CSRF state; this helper alone does not make
    * a request secure.
    *
    * @throws IllegalArgumentException
    *   if a directly supplied modifier sets `action` or `method`
    */
  def http[Msg](
    target: FormAction
  )(
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] =
    val flattened = mods.toVector.flatMap {
      case mod: Mod[Msg]                => Some(mod)
      case mods: IterableOnce[Mod[Msg]] => mods
    }
    val overrides = flattened
      .flatMap(Form.attributeName)
      .filter(name => Form.httpAttributes.exists(_.equalsIgnoreCase(name)))
    require(
      overrides.isEmpty,
      s"ordinary HTTP forms own the ${overrides.distinct.mkString(" and ")} attribute"
    )

    _root_.scalive.form(
      _root_.scalive.action := target.href,
      _root_.scalive.method := target.method.attributeValue,
      Option.when(target.protectFromCsrf)(Form.csrfMarker := "true"),
      flattened
    )

  private def attributeName(mod: Mod[?]): Option[String] =
    mod match
      case Mod.Attr.Static(name, _)                => Some(name)
      case Mod.Attr.StaticValueAsPresence(name, _) => Some(name)
      case Mod.Attr.SignalValue(name, _)           => Some(name)
      case Mod.Attr.SignalOptionalValue(name, _)   => Some(name)
      case Mod.Attr.SignalValueAsPresence(name, _) => Some(name)
      case Mod.Attr.Binding(name, _)               => Some(name)
      case Mod.Attr.SignalBinding(name, _, _)      => Some(name)
      case Mod.Attr.FormBinding(name, _)           => Some(name)
      case Mod.Attr.FormEventBinding(name, _, _)   => Some(name)
      case Mod.Attr.JsBinding(name, _)             => Some(name)
      case Mod.Attr.SignalJsBinding(name, _)       => Some(name)
      case Mod.Attr.RoutedBinding(name, _)         => Some(name)
      case Mod.Attr.ComponentBinding(name, _, _)   => Some(name)
      case Mod.Attr.ComponentTarget(_)             => Some("phx-target")
      case Mod.Attr.Group(attrs)                   => attrs.flatMap(attributeName).headOption
      case _: Mod.Content[?]                       => None

  /** Creates a lower-level form from a parsed root name and existing state.
    *
    * No decoding or ownership validation occurs until a relevant operation is requested.
    */
  def of[A](name: String, state: FormState[A], codec: FormCodec[A]): Form[A] =
    Form(FormPath.parse(name), state, codec)

  /** Creates a lower-level form from a typed event.
    *
    * The event is converted to state without decoding it again.
    */
  def of[A](name: String, event: FormEvent[A], codec: FormCodec[A]): Form[A] =
    of(name, event.state, codec)

end Form

/** Current raw, decoded, validation, and rendering state for one field.
  *
  * Obtain a view from [[RootedForm.field]] or [[Form.field]]. A view does not mutate its form. Its
  * control helpers render the field's derived name, ID, and current raw value, followed by caller
  * modifiers. Validation display is split between all [[errors]] and interaction-gated
  * [[visibleErrors]].
  *
  * @tparam A
  *   the decoded field value type
  */
final class FormFieldView[A] private[scalive] (
  private val form: Form[?],
  private val definition: FormField[A],
  private val legacyPath: Option[FormPath]):

  /** The complete rooted field path. */
  def path: FormPath = definition.path

  /** The browser field name derived from [[path]]. */
  def name: String = definition.name

  /** The default DOM ID derived from [[path]]. */
  def id: String = definition.id

  /** The DOM ID used by [[errorFeedback]] and [[validationAttributes]]. */
  def errorId: String = s"${id}_errors"

  /** Returns all raw values for this field in submission order.
    *
    * Views created from a relative path try the rooted path first and fall back to the exact
    * relative path only when no rooted values exist. Typed field views read their exact path.
    */
  def rawValues: Vector[String] =
    legacyPath match
      case Some(relative) =>
        val fullValues = form.state.raw.values(form.fullPath(relative))
        if fullValues.nonEmpty then fullValues else form.state.raw.values(relative)
      case None => form.state.raw.values(path)

  /** Returns the last raw value, or the empty string when the field is absent. */
  def fieldValue: String = rawValues.lastOption.getOrElse("")

  /** Decodes this field afresh from the form's complete raw payload. */
  def decoded: Either[FormErrors, A] = definition.codec.decode(form.state.raw)

  /** Returns validation errors for this field, even when it has not been used.
    *
    * Typed views preserve every error at their exact path. Relative-path views combine rooted and
    * relative errors and remove duplicates. Use [[visibleErrors]] for interaction-gated
    * presentation.
    */
  def errors: Vector[FormError] =
    legacyPath.fold(form.state.errors.forPath(path))(form.errorsFor)

  /** Tests whether this field is eligible to show validation errors.
    *
    * A field is used when tracked by the current state; every field is used after submission.
    */
  def isUsed: Boolean =
    legacyPath.fold(form.state.isUsed(path))(form.isUsed)

  /** Returns [[errors]] only when [[isUsed]] is true. */
  def visibleErrors: Vector[FormError] =
    if isUsed then errors else Vector.empty

  /** Tests whether at least one interaction-visible error exists. */
  def hasVisibleErrors: Boolean = visibleErrors.nonEmpty

  /** Returns accessibility attributes paired with [[errorFeedback]].
    *
    * `aria-describedby` always references [[errorId]], allowing a stable relationship across
    * patches. `aria-invalid="true"` is included only when [[hasVisibleErrors]] is true. Pass these
    * modifiers to the corresponding control.
    */
  def validationAttributes: Vector[Mod.Attr[Nothing]] =
    Vector(aria.describedby := errorId) ++
      Option.when(hasVisibleErrors)(aria.invalid := "true").toVector

  /** Renders a text input with the default [[id]], [[name]], and [[fieldValue]]. */
  def text[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    input(typ := "text", idAttr := id, nameAttr := name, value := fieldValue, flatten(mods))

  /** Renders a text input with `explicitId`, [[name]], and [[fieldValue]].
    *
    * [[errorId]] remains derived from the field's default [[id]].
    */
  def text[Msg](
    explicitId: String,
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] =
    input(typ := "text", idAttr := explicitId, nameAttr := name, value := fieldValue, flatten(mods))

  /** Renders an email input with the default ID, name, and current raw value. */
  def email[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    input(typ := "email", idAttr := id, nameAttr := name, value := fieldValue, flatten(mods))

  /** Renders a password input with the default ID, name, and current raw value. */
  def password[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    input(typ := "password", idAttr := id, nameAttr := name, value := fieldValue, flatten(mods))

  /** Renders a hidden input with the default ID, name, and current raw value. */
  def hidden[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    input(typ := "hidden", idAttr := id, nameAttr := name, value := fieldValue, flatten(mods))

  /** Renders a checkbox whose submitted value is `"true"`. */
  def checkbox[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    checkbox("true", flatten(mods))

  /** Renders a checkbox with `checkedValue` as its submitted value.
    *
    * The checkbox is checked when [[rawValues]] contains `checkedValue`. No hidden unchecked value
    * is generated.
    */
  def checkbox[Msg](
    checkedValue: String,
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] =
    input(
      typ      := "checkbox",
      idAttr   := id,
      nameAttr := name,
      value    := checkedValue,
      checked  := rawValues.contains(checkedValue),
      flatten(mods)
    )

  /** Renders a textarea whose text content is [[fieldValue]]. */
  def textarea[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    Form.textareaTag(idAttr := id, nameAttr := name, flatten(mods), fieldValue)

  /** Renders a select from `(submittedValue, label)` options.
    *
    * Every option whose submitted value occurs in [[rawValues]] is marked selected. Supply ordinary
    * select modifiers, such as `multiple`, through `mods` when needed.
    */
  def select[Msg](
    options: Iterable[(String, String)],
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] =
    val selectedValues = rawValues.toSet
    _root_.scalive.select(
      idAttr   := id,
      nameAttr := name,
      flatten(mods),
      options.map { case (optionValue, label) =>
        _root_.scalive
          .option(value := optionValue, selected := selectedValues.contains(optionValue), label)
      }
    )

  /** Renders interaction-visible errors paired with [[validationAttributes]].
    *
    * The wrapper is a `div` with [[errorId]], `phx-feedback-for`, `aria-live="polite"`, and class
    * `form-errors`. Each visible error is passed to `render` inside a generated `span.form-error`.
    * Caller modifiers, including content, are appended before the generated error spans. The stable
    * wrapper remains present when no errors are visible, but caller content is still rendered.
    */
  def errorFeedback(
    render: FormError => Mod[Nothing],
    mods: (Mod[Nothing] | IterableOnce[Mod[Nothing]])*
  ): HtmlElement[Nothing] =
    val messages = visibleErrors.map { error =>
      Mod.Content.Tag(span(cls := "form-error", render(error)))
    }
    div(
      idAttr           := errorId,
      Form.feedbackFor := name,
      aria.live        := "polite",
      cls              := "form-errors",
      flatten(mods),
      messages
    )

  /** Renders a Phoenix feedback container for this field.
    *
    * This lower-level helper sets only `phx-feedback-for` before appending caller modifiers,
    * including content. It does not generate errors or the accessibility ID supplied by
    * [[errorFeedback]].
    */
  def feedback(mods: (Mod[Nothing] | IterableOnce[Mod[Nothing]])*): HtmlElement[Nothing] =
    div(Form.feedbackFor := name, flatten(mods))

  private def flatten[Msg](
    mods: Seq[Mod[Msg] | IterableOnce[Mod[Msg]]]
  ): Vector[Mod[Msg]] =
    mods.toVector.flatMap {
      case mod: Mod[Msg]                => Some(mod)
      case mods: IterableOnce[Mod[Msg]] => mods
    }
end FormFieldView

object FormFieldView:
  extension [A](field: Signal[FormFieldView[A]])
    /** The signal-backed field's complete rooted browser ID. */
    def id: Signal[String] = field.map(_.id)

    /** Attributes pairing a signal-backed control with its dynamic error feedback. */
    def validationAttributes: Vector[Mod.Attr[Nothing]] =
      Vector(
        aria.describedby := field.map(_.errorId),
        aria.invalid.optional(field.map(value => Option.when(value.hasVisibleErrors)("true")))
      )

    /** Renders a signal-backed text input. */
    def text[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
      input(
        typ      := "text",
        idAttr   := field.map(_.id),
        nameAttr := field.map(_.name),
        value    := field.map(_.fieldValue),
        flattenSignalMods(mods)
      )

    /** Renders a signal-backed email input. */
    def email[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
      input(
        typ      := "email",
        idAttr   := field.map(_.id),
        nameAttr := field.map(_.name),
        value    := field.map(_.fieldValue),
        flattenSignalMods(mods)
      )

    /** Renders a signal-backed textarea. */
    def textarea[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
      Form.textareaTag(
        idAttr   := field.map(_.id),
        nameAttr := field.map(_.name),
        flattenSignalMods(mods),
        field.map(_.fieldValue)
      )

    /** Renders signal-backed interaction-visible errors with stable accessibility relationships.
      *
      * Each retained error signal is passed to `render` inside a generated `span.form-error`.
      */
    def errorFeedback(
      render: Signal[FormError] => Mod[Nothing],
      mods: (Mod[Nothing] | IterableOnce[Mod[Nothing]])*
    ): HtmlElement[Nothing] =
      div(
        idAttr           := field.map(_.errorId),
        Form.feedbackFor := field.map(_.name),
        aria.live        := "polite",
        cls              := "form-errors",
        flattenSignalMods(mods),
        field.map(_.visibleErrors).splitByIndex { (_, error) =>
          span(cls := "form-error", render(error))
        }
      )
  end extension

  private def flattenSignalMods[Msg](
    mods: Seq[Mod[Msg] | IterableOnce[Mod[Msg]]]
  ): Vector[Mod[Msg]] =
    mods.toVector.flatMap {
      case mod: Mod[Msg]                  => Some(mod)
      case values: IterableOnce[Mod[Msg]] => values
    }
end FormFieldView
