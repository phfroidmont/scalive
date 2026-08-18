package scalive

/** A typed form field that owns its path and decoding rules.
  *
  * Field transformations retain the path. A field's codec reads all raw values for the exact
  * rendered name, allowing scalar constructors to reject duplicates rather than silently selecting
  * one.
  *
  * For example:
  * {{{
  * val Name = FormField
  *   .string(FormPath("profile", "name"))
  *   .map(_.trim)
  *   .required("Name is required", Some("required"))
  * }}}
  *
  * @param path
  *   the exact field path used for names, ids, raw values, and validation added by field methods
  * @param codec
  *   the codec used by bindings and validation
  * @tparam A
  *   the successfully decoded field value
  */
final class FormField[A] private (
  val path: FormPath,
  val codec: FormCodec[A]):

  /** The browser field name rendered from [[path]]. */
  def name: String = path.name

  /** The conventional, potentially non-unique DOM id rendered from [[path]]. */
  def id: String = path.id

  /** Creates a `phx-change` binding that decodes this field into a typed [[FormEvent]]. */
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.change.form(codec)(f)

  /** Creates a `phx-submit` binding that decodes this field into a submitted [[FormEvent]]. */
  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.submit.form(codec)(f)

  /** Creates a LiveView auto-recovery binding that decodes this field into a [[FormEvent]]. */
  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.recover.form(codec)(f)

  /** Transforms a successfully decoded value while retaining this field's path.
    *
    * If the current codec fails, `f` is not evaluated.
    */
  def map[B](f: A => B): FormField[B] =
    FormField(path, codec.map(f))

  /** Adds a predicate validation after all existing decoding and transformations.
    *
    * A false predicate returns one error at this field's path. If an earlier stage fails, the
    * predicate is not run and only the earlier errors are returned; chain separate fields with
    * [[FormCodec.zip]] when independent errors should accumulate.
    */
  def validate(
    message: String,
    code: Option[String] = None
  )(
    predicate: A => Boolean
  ): FormField[A] =
    FormField(
      path,
      codec.emap(value => Either.cond(predicate(value), value, FormErrors.one(path, message, code)))
    )

  /** Requires a decoded `String` to be non-empty.
    *
    * This validation runs after prior mappings, so normalization such as `map(_.trim)` can define
    * blankness. Without such a mapping, whitespace-only strings are accepted. Earlier decoding
    * failures, including duplicate errors from [[FormField.string]], short-circuit this validation.
    */
  def required(
    message: String = "can't be blank",
    code: Option[String] = None
  )(using ev: A =:= String
  ): FormField[String] =
    map(ev).validate(message, code)(_.nonEmpty)
end FormField

/** Constructors for typed [[FormField]] definitions. */
object FormField:
  /** Creates a field decoder over every raw value submitted under `path`.
    *
    * The vector is in encounter order and is empty when the name is absent. The supplied function
    * owns all cardinality and validation semantics.
    */
  def apply[A](
    path: FormPath
  )(
    decode: Vector[String] => Either[FormErrors, A]
  ): FormField[A] =
    FormField(path, FormCodec(data => decode(data.values(path))))

  /** Creates a scalar string field that rejects duplicates.
    *
    * A missing field decodes as `""`; exactly one value is returned unchanged, including an empty
    * value; two or more values produce one error at `path`. This intentionally differs from
    * [[FormCodec.requiredString]] and [[FormCodec.optionalString]], which select the last
    * duplicate.
    */
  def string(
    path: FormPath,
    duplicateMessage: String = "must be submitted at most once"
  ): FormField[String] =
    FormField(path) {
      case Vector()      => Right("")
      case Vector(value) => Right(value)
      case _             => Left(FormErrors.one(path, duplicateMessage))
    }

  /** Creates a scalar string field requiring exactly one non-empty value.
    *
    * Missing and singly submitted empty values use `blankMessage`. Multiple values fail first with
    * `duplicateMessage`. Values are not trimmed.
    */
  def requiredString(
    path: FormPath,
    blankMessage: String = "can't be blank",
    duplicateMessage: String = "must be submitted exactly once"
  ): FormField[String] =
    string(path, duplicateMessage).required(blankMessage)

  /** Creates an optional scalar string field that rejects duplicates.
    *
    * Missing and singly submitted empty values decode as `None`; one non-empty value becomes
    * `Some(value)`; multiple values produce one error at `path`.
    */
  def optionalString(
    path: FormPath,
    duplicateMessage: String = "must be submitted at most once"
  ): FormField[Option[String]] =
    FormField(path) {
      case Vector()      => Right(None)
      case Vector(value) => Right(Option.when(value.nonEmpty)(value))
      case _             => Left(FormErrors.one(path, duplicateMessage))
    }

  /** Creates a field that always succeeds with all submitted values in encounter order. */
  def strings(path: FormPath): FormField[Vector[String]] =
    FormField(path)(Right(_))

  private def apply[A](path: FormPath, codec: FormCodec[A]): FormField[A] =
    new FormField(path, codec)
end FormField
