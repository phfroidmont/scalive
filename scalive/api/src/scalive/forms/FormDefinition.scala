package scalive

/** Defines one form root and owns the fields, codecs, initial values, and forms created from it.
  *
  * Keep a root in a stable `val`, for example `val Profile = FormRoot("profile")`. The aliases on
  * that value capture its singleton type, so Scala rejects fields, codecs, and initial values from
  * every other root, including a different [[FormRoot]] with the same runtime path.
  *
  * Field paths passed to this root are relative. Their rendered names and IDs include [[path]].
  *
  * @param path
  *   the non-empty path prepended to every field owned by this root
  */
final class FormRoot private (val path: FormPath):
  self =>

  /** A field whose owner is this exact root value. */
  type Field[A] = RootedFormField[self.type, A]

  /** A codec composed exclusively from fields owned by this exact root value. */
  type Codec[A] = RootedFormCodec[self.type, A]

  /** An initial field value owned by this exact root value. */
  type InitialValue = FormInitialValue[self.type]

  /** Defines a field decoded from all submitted values at a relative path.
    *
    * `decode` receives an empty vector when the field is absent and preserves duplicate values in
    * submission order. Validation errors returned by it should normally use the resulting
    * [[RootedFormField.path field path]].
    *
    * @param path
    *   a non-empty path relative to this root
    * @param decode
    *   the field decoder
    * @throws IllegalArgumentException
    *   if `path` parses to an empty form path
    */
  def field[A](
    path: String
  )(
    decode: Vector[String] => Either[FormErrors, A]
  ): Field[A] =
    RootedFormField(FormField(fullPath(path))(decode))

  /** Defines a scalar string field at a relative path.
    *
    * An absent field decodes to the empty string. Exactly one submitted value is accepted; two or
    * more produce `duplicateMessage`.
    *
    * @param path
    *   a non-empty path relative to this root
    * @param duplicateMessage
    *   the error message used for duplicate values
    * @throws IllegalArgumentException
    *   if `path` parses to an empty form path
    */
  def string(
    path: String,
    duplicateMessage: String = "must be submitted at most once"
  ): Field[String] =
    RootedFormField(FormField.string(fullPath(path), duplicateMessage))

  /** Defines a non-empty scalar string field at a relative path.
    *
    * Missing and empty values produce `blankMessage`. Duplicate values produce `duplicateMessage`.
    *
    * @param path
    *   a non-empty path relative to this root
    * @param blankMessage
    *   the error message used for a missing or empty value
    * @param duplicateMessage
    *   the error message used for duplicate values
    * @throws IllegalArgumentException
    *   if `path` parses to an empty form path
    */
  def requiredString(
    path: String,
    blankMessage: String = "can't be blank",
    duplicateMessage: String = "must be submitted exactly once"
  ): Field[String] =
    RootedFormField(FormField.requiredString(fullPath(path), blankMessage, duplicateMessage))

  /** Defines an optional scalar string field at a relative path.
    *
    * Missing and empty values decode to `None`; one non-empty value decodes to `Some(value)`.
    * Duplicate values produce `duplicateMessage`.
    *
    * @param path
    *   a non-empty path relative to this root
    * @param duplicateMessage
    *   the error message used for duplicate values
    * @throws IllegalArgumentException
    *   if `path` parses to an empty form path
    */
  def optionalString(
    path: String,
    duplicateMessage: String = "must be submitted at most once"
  ): Field[Option[String]] =
    RootedFormField(FormField.optionalString(fullPath(path), duplicateMessage))

  /** Defines a repeated string field that preserves every value in submission order.
    *
    * An absent field decodes to an empty vector.
    *
    * @param path
    *   a non-empty path relative to this root
    * @throws IllegalArgumentException
    *   if `path` parses to an empty form path
    */
  def strings(path: String): Field[Vector[String]] =
    RootedFormField(FormField.strings(fullPath(path)))

  /** Builds a form definition from a codec owned by this root.
    *
    * The owner type prevents combining this root with a codec from another [[FormRoot]].
    *
    * @param codec
    *   the codec that decodes the complete form value
    */
  def form[A](codec: Codec[A]): FormDefinition[self.type, A] =
    FormDefinition(path, codec.underlying)

  /** Builds a form definition from one owned field and a result constructor.
    *
    * @param construct
    *   creates the form value from the decoded field
    * @param field1
    *   the first field, owned by this root
    */
  def form[A1, Result](construct: A1 => Result)(field1: Field[A1])
    : FormDefinition[self.type, Result] =
    form(field1.codec.map(construct))

  /** Builds a form definition from two owned fields and a result constructor.
    *
    * All field decoders run, and errors from both fields are accumulated in field order.
    *
    * @param construct
    *   creates the form value from the decoded fields
    * @param field1
    *   the first field, owned by this root
    * @param field2
    *   the second field, owned by this root
    */
  def form[A1, A2, Result](
    construct: (A1, A2) => Result
  )(
    field1: Field[A1],
    field2: Field[A2]
  ): FormDefinition[self.type, Result] =
    form(field1.codec.zip(field2.codec).map(construct.tupled))

  /** Builds a form definition from three owned fields and a result constructor.
    *
    * All field decoders run, and their errors are accumulated in field order.
    *
    * @param construct
    *   creates the form value from the decoded fields
    * @param field1
    *   the first field, owned by this root
    * @param field2
    *   the second field, owned by this root
    * @param field3
    *   the third field, owned by this root
    */
  def form[A1, A2, A3, Result](
    construct: (A1, A2, A3) => Result
  )(
    field1: Field[A1],
    field2: Field[A2],
    field3: Field[A3]
  ): FormDefinition[self.type, Result] =
    form(
      field1.codec.zip(field2.codec).zip(field3.codec).map { case ((value1, value2), value3) =>
        construct(value1, value2, value3)
      }
    )

  /** Builds a form definition from four owned fields and a result constructor.
    *
    * All field decoders run, and their errors are accumulated in field order.
    *
    * @param construct
    *   creates the form value from the decoded fields
    * @param field1
    *   the first field, owned by this root
    * @param field2
    *   the second field, owned by this root
    * @param field3
    *   the third field, owned by this root
    * @param field4
    *   the fourth field, owned by this root
    */
  def form[A1, A2, A3, A4, Result](
    construct: (A1, A2, A3, A4) => Result
  )(
    field1: Field[A1],
    field2: Field[A2],
    field3: Field[A3],
    field4: Field[A4]
  ): FormDefinition[self.type, Result] =
    form(
      field1.codec.zip(field2.codec).zip(field3.codec).zip(field4.codec).map {
        case (((value1, value2), value3), value4) => construct(value1, value2, value3, value4)
      }
    )

  /** Builds a form definition from five owned fields and a result constructor.
    *
    * All field decoders run, and their errors are accumulated in field order.
    *
    * @param construct
    *   creates the form value from the decoded fields
    * @param field1
    *   the first field, owned by this root
    * @param field2
    *   the second field, owned by this root
    * @param field3
    *   the third field, owned by this root
    * @param field4
    *   the fourth field, owned by this root
    * @param field5
    *   the fifth field, owned by this root
    */
  def form[A1, A2, A3, A4, A5, Result](
    construct: (A1, A2, A3, A4, A5) => Result
  )(
    field1: Field[A1],
    field2: Field[A2],
    field3: Field[A3],
    field4: Field[A4],
    field5: Field[A5]
  ): FormDefinition[self.type, Result] =
    form(
      field1.codec.zip(field2.codec).zip(field3.codec).zip(field4.codec).zip(field5.codec).map {
        case ((((value1, value2), value3), value4), value5) =>
          construct(value1, value2, value3, value4, value5)
      }
    )

  private def fullPath(relative: String): FormPath =
    val parsed = FormPath.parse(relative)
    require(parsed.nonEmpty, "form field path must not be empty")
    FormPath(path.segments ++ parsed.segments)
end FormRoot

/** Creates stable form-root values.
  *
  * Assign the result to a stable `val` before declaring fields so its singleton type can enforce
  * ownership throughout the rooted form API.
  */
object FormRoot:
  /** Creates a form root from a non-empty browser field name.
    *
    * Nested syntax such as `profile[address]` is parsed as a [[FormPath]].
    *
    * @param name
    *   the root browser name
    * @throws IllegalArgumentException
    *   if `name` parses to an empty form path
    */
  def apply(name: String): FormRoot =
    val path = FormPath.parse(name)
    require(path.nonEmpty, "form root must not be empty")
    new FormRoot(path)

/** A form codec carrying the phantom type of the [[FormRoot]] that owns it.
  *
  * `Owner` has no runtime representation. It prevents codecs from different root values from being
  * combined accidentally while delegating decoding to an underlying [[FormCodec]].
  *
  * @tparam Owner
  *   the singleton type of the owning root
  * @tparam A
  *   the decoded value type
  */
final class RootedFormCodec[Owner, A] private[scalive] (
  private[scalive] val underlying: FormCodec[A]):
  self =>

  /** Maps successful decoded values without changing ownership or validation errors. */
  def map[B](f: A => B): RootedFormCodec[Owner, B] =
    RootedFormCodec(self.underlying.map(f))

  /** Validates or transforms a successful value while preserving this codec's owner.
    *
    * `f` is not run when the underlying decoder fails.
    */
  def emap[B](f: A => Either[FormErrors, B]): RootedFormCodec[Owner, B] =
    RootedFormCodec(self.underlying.emap(f))

  /** Combines this codec with another codec owned by the same root.
    *
    * Both decoders run against the same [[FormData]]. When both fail, the left errors precede the
    * right errors.
    */
  def zip[B](that: RootedFormCodec[Owner, B]): RootedFormCodec[Owner, (A, B)] =
    RootedFormCodec(self.underlying.zip(that.underlying))

private[scalive] object RootedFormCodec:
  def apply[Owner, A](codec: FormCodec[A]): RootedFormCodec[Owner, A] =
    new RootedFormCodec(codec)

/** A form field carrying the phantom type of the [[FormRoot]] that owns it.
  *
  * Its path is already rooted. The owner type restricts codec composition, initial values, and
  * [[RootedForm.field]] access to values created by the same stable root.
  *
  * @tparam Owner
  *   the singleton type of the owning root
  * @tparam A
  *   the decoded field type
  */
final class RootedFormField[Owner, A] private[scalive] (
  private[scalive] val underlying: FormField[A]):

  /** The complete field path, including its root. */
  def path: FormPath = underlying.path

  /** The browser field name derived from [[path]]. */
  def name: String = underlying.name

  /** The default DOM ID derived from [[path]]. */
  def id: String = underlying.id

  /** Returns this field's decoder with the same root owner. */
  def codec: RootedFormCodec[Owner, A] = RootedFormCodec(underlying.codec)

  /** Binds a LiveView `phx-change` event decoded with this field's codec.
    *
    * The callback receives a [[FormEvent]] for every change event; decoding failures remain in
    * `event.value`. The binding does not mutate any existing form state.
    */
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    underlying.onChange(f)

  /** Binds a LiveView `phx-submit` event decoded with this field's codec.
    *
    * The resulting event is marked submitted, so a form rebuilt from it treats all fields as used.
    * Decoding failures remain in `event.value` and still invoke the callback.
    */
  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    underlying.onSubmit(f)

  /** Binds a LiveView `phx-auto-recover` event decoded with this field's codec.
    *
    * Recovery metadata is exposed through [[FormEvent.recovery]]. Decoding failures remain in
    * `event.value` and still invoke the callback.
    */
  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    underlying.onRecover(f)

  /** Maps successful decoded values while preserving this field's path and owner. */
  def map[B](f: A => B): RootedFormField[Owner, B] =
    RootedFormField(underlying.map(f))

  /** Adds a validation that runs after this field has decoded successfully.
    *
    * A false predicate produces one [[FormError]] at [[path]], with `message` and optional `code`.
    */
  def validate(
    message: String,
    code: Option[String] = None
  )(
    predicate: A => Boolean
  ): RootedFormField[Owner, A] =
    RootedFormField(underlying.validate(message, code)(predicate))

  /** Requires the successfully decoded string to be non-empty.
    *
    * This can follow [[map]], allowing normalization such as trimming to happen before the required
    * check. After preceding decoding and mapping succeed, an empty decoded string produces one
    * error at [[path]]. Missing-field behavior is determined by the preceding decoder.
    */
  def required(
    message: String = "can't be blank",
    code: Option[String] = None
  )(using A =:= String
  ): RootedFormField[Owner, String] =
    RootedFormField(underlying.required(message, code))

  /** Creates an owner-checked raw initial value for this field.
    *
    * Values are retained in argument order, including duplicates. They are decoded when passed to
    * [[FormDefinition.initial]].
    */
  def initial(values: String*): FormInitialValue[Owner] =
    FormInitialValue(path, values.toVector)
end RootedFormField

private[scalive] object RootedFormField:
  def apply[Owner, A](field: FormField[A]): RootedFormField[Owner, A] =
    new RootedFormField(field)

/** Opaque raw initial field data carrying the phantom type of its owning [[FormRoot]].
  *
  * Instances are created with [[RootedFormField.initial]] and accepted only by a [[FormDefinition]]
  * with the same owner.
  */
final case class FormInitialValue[Owner] private[scalive] (
  private[scalive] val path: FormPath,
  private[scalive] val values: Vector[String])

/** A reusable, owner-checked definition of a rooted form.
  *
  * A definition combines a root and whole-form codec. Its `Owner` parameter prevents forms, fields,
  * and initial values from different stable [[FormRoot]] values from being mixed.
  *
  * @param root
  *   the path prepended to fields in this definition
  * @param codec
  *   the decoder used for initial values and LiveView form events
  * @tparam Owner
  *   the singleton type of the owning root
  * @tparam A
  *   the decoded form value type
  */
final class FormDefinition[Owner, A] private[scalive] (
  /** The path prepended to fields in this definition. */
  val root: FormPath,
  /** The decoder used for initial values and LiveView form events. */
  val codec: FormCodec[A]):

  /** Creates a typed change binding using this definition's stable codec. */
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.change.form(codec)(f)

  /** Creates a typed submit binding using this definition's stable codec. */
  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.submit.form(codec)(f)

  /** Creates a typed recovery binding using this definition's stable codec. */
  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.recover.form(codec)(f)

  /** The rooted form type produced by this definition. */
  type Form = RootedForm[Owner, A]

  /** A field type accepted by forms from this definition. */
  type Field[B] = RootedFormField[Owner, B]

  /** An initial-value type accepted by this definition. */
  type InitialValue = FormInitialValue[Owner]

  /** Creates an unsubmitted form from owner-checked raw initial values.
    *
    * Values and duplicates retain call order, and [[codec]] runs immediately. Decode errors are
    * therefore present in [[RootedForm.state]] and [[FormFieldView.errors]] from the first render.
    * The new state is not submitted and has no used fields, so [[FormFieldView.visibleErrors]]
    * stays empty until the corresponding field is used or the form is submitted.
    */
  def initial(values: InitialValue*): Form =
    val raw   = FormData(values.flatMap(value => value.values.map(value.path.name -> _)))
    val state = FormState(
      raw = raw,
      value = codec.decode(raw),
      used = Set.empty,
      submitted = false
    )
    from(state)

  /** Wraps an existing decoded state without running [[codec]] again.
    *
    * The caller is responsible for ensuring the state's raw data and decoded value belong to this
    * definition.
    */
  def from(state: FormState[A]): Form =
    RootedForm(Form(root, state, codec))

  /** Rebuilds this form from a typed event without running [[codec]] again.
    *
    * Raw data, the already decoded value, and submission state are retained. Used-field state is
    * derived from the event payload according to [[FormEvent.state]].
    */
  def from(event: FormEvent[A]): Form =
    from(event.state)
end FormDefinition

private[scalive] object FormDefinition:
  def apply[Owner, A](root: FormPath, codec: FormCodec[A]): FormDefinition[Owner, A] =
    new FormDefinition(root, codec)

/** A form whose field access is restricted to definitions owned by the same [[FormRoot]].
  *
  * This is a type-safe facade over [[scalive.Form]]. Rendering, event bindings, recovery settings,
  * ordinary HTTP submission, and field views delegate without changing their behavior.
  *
  * @tparam Owner
  *   the singleton type of the owning root
  * @tparam A
  *   the decoded whole-form value type
  */
final class RootedForm[Owner, A] private[scalive] (
  private val underlying: Form[A]):

  /** The current raw, decoded, used-field, and submission state. */
  def state: FormState[A] = underlying.state

  /** Renders an ordinary browser form targeting `target`.
    *
    * This delegates to [[scalive.Form.http]]; it does not add LiveView change or submit bindings
    * unless they are supplied in `mods`.
    */
  def http[Msg](
    target: FormAction
  )(
    mods: Mod.Input[Msg]*
  ): HtmlElement[Msg] = underlying.http(target)(mods*)

  /** Binds `phx-change` and decodes each event with this form's codec.
    *
    * The callback runs for successful and failed decodes. Rebuild state explicitly with the owning
    * [[FormDefinition.from]] method when handling the resulting message.
    */
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    underlying.onChange(f)

  /** Binds `phx-submit` and decodes each event with this form's codec.
    *
    * The event is marked submitted and the callback runs even when decoding fails.
    */
  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    underlying.onSubmit(f)

  /** Binds `phx-auto-recover` and decodes recovery events with this form's codec. */
  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    underlying.onRecover(f)

  /** Disables Phoenix's automatic form recovery by rendering `phx-auto-recover="ignore"`. */
  def disableRecovery: Mod.Attr[Nothing] =
    underlying.disableRecovery

  /** Renders `phx-trigger-action` when `condition` is true.
    *
    * On a form with an ordinary `action` and `method`, Phoenix uses this attribute to hand a Live
    * submit back to the browser for normal HTTP submission.
    */
  def triggerHttpSubmitWhen(condition: Boolean): Mod.Attr[Nothing] =
    underlying.triggerHttpSubmitWhen(condition)

  /** Returns the current view of a field owned by this form's root.
    *
    * Ownership is checked at compile time; the underlying field is already fully rooted.
    */
  def field[B](definition: RootedFormField[Owner, B]): FormFieldView[B] =
    underlying.field(definition.underlying)
end RootedForm

private[scalive] object RootedForm:
  def apply[Owner, A](form: Form[A]): RootedForm[Owner, A] =
    new RootedForm(form)

extension [Owner, A](form: Signal[RootedForm[Owner, A]])
  /** Returns a signal-backed value for a field owned by this rooted form. */
  def field[B](definition: RootedFormField[Owner, B]): Signal[FormFieldView[B]] =
    form.map(_.field(definition))
