package scalive

/** Decodes and validates [[FormData]] as a value of type `A`.
  *
  * Codecs are reusable descriptions and do not catch exceptions thrown by user functions. [[zip]]
  * evaluates both codecs against the same input and accumulates their ordered errors, while [[map]]
  * and [[emap]] form a dependent chain that stops at the first failed stage.
  *
  * For example:
  * {{{
  * val name  = FormField.requiredString(FormPath("profile", "name")).codec
  * val email = FormField.requiredString(FormPath("profile", "email")).codec
  * val profile = name.zip(email)
  * }}}
  *
  * @tparam A
  *   the successfully decoded value
  */
trait FormCodec[A]:
  self =>

  /** Decodes `data`, returning all errors produced by this codec. */
  def decode(data: FormData): Either[FormErrors, A]

  /** Transforms a successful value; existing decoding errors short-circuit the function. */
  def map[B](f: A => B): FormCodec[B] =
    FormCodec(data => self.decode(data).map(f))

  /** Runs an error-producing transformation after successful decoding.
    *
    * If this codec fails, `f` is not evaluated and its potential errors cannot be accumulated.
    */
  def emap[B](f: A => Either[FormErrors, B]): FormCodec[B] =
    FormCodec(data => self.decode(data).flatMap(f))

  /** Decodes both codecs against the same data and returns their pair.
    *
    * Both sides are evaluated, left before right. If both fail, the left errors are followed by the
    * right errors; if only one fails, that side's errors are returned.
    */
  def zip[B](that: FormCodec[B]): FormCodec[(A, B)] =
    FormCodec { data =>
      (self.decode(data), that.decode(data)) match
        case (Right(left), Right(right)) => Right(left -> right)
        case (Left(left), Left(right))   => Left(left ++ right)
        case (Left(errors), _)           => Left(errors)
        case (_, Left(errors))           => Left(errors)
    }
end FormCodec

/** Constructors and common codecs for [[FormCodec]]. */
object FormCodec:
  /** The identity codec, which always returns its input. */
  val formData: FormCodec[FormData] =
    FormCodec(data => Right(data))

  /** Creates a codec from a decoding function. */
  def apply[A](f: FormData => Either[FormErrors, A]): FormCodec[A] =
    new FormCodec[A]:
      override def decode(data: FormData): Either[FormErrors, A] = f(data)

  /** Decodes the last value for `name` and rejects a missing or empty last value.
    *
    * Duplicate names are accepted and earlier values are ignored. This intentionally differs from
    * [[FormField.requiredString]], whose scalar field codec rejects duplicates. Whitespace is not
    * trimmed. Failure produces one error at `FormPath.parse(name)`.
    */
  def requiredString(
    name: String,
    message: String = "can't be blank"
  ): FormCodec[String] =
    FormCodec { data =>
      data.string(name).filter(_.nonEmpty) match
        case Some(value) => Right(value)
        case None        => Left(FormErrors.one(name, message))
    }

  /** Decodes a required last string value under `path` with the default message.
    *
    * The rendered path name is used for lookup and is reparsed when creating an error.
    * Consequently, empty array segments in `path` are absent from the error path.
    */
  def requiredString(path: FormPath): FormCodec[String] =
    requiredString(path.name)

  /** Decodes a required last string value under `path` with `message` on failure.
    *
    * The rendered path name is used for lookup and is reparsed when creating an error.
    * Consequently, empty array segments in `path` are absent from the error path.
    */
  def requiredString(
    path: FormPath,
    message: String
  ): FormCodec[String] =
    requiredString(path.name, message)

  /** Decodes the last value for `name`, returning `None` when it is missing or empty.
    *
    * Duplicate names are accepted and earlier values are ignored. This intentionally differs from
    * [[FormField.optionalString]], which rejects duplicate scalar submissions. Whitespace is
    * retained.
    */
  def optionalString(name: String): FormCodec[Option[String]] =
    FormCodec(data => Right(data.string(name).filter(_.nonEmpty)))

  /** Decodes the optional last string value under the rendered `path` name. */
  def optionalString(path: FormPath): FormCodec[Option[String]] =
    optionalString(path.name)
end FormCodec
