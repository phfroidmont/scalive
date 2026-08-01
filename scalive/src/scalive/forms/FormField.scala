package scalive

final class FormField[A] private (
  val path: FormPath,
  val codec: FormCodec[A]):

  def name: String = path.name
  def id: String   = path.id

  def map[B](f: A => B): FormField[B] =
    FormField(path, codec.map(f))

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

  def required(
    message: String = "can't be blank",
    code: Option[String] = None
  )(using ev: A =:= String
  ): FormField[String] =
    map(ev).validate(message, code)(_.nonEmpty)

object FormField:
  def apply[A](
    path: FormPath
  )(
    decode: Vector[String] => Either[FormErrors, A]
  ): FormField[A] =
    FormField(path, FormCodec(data => decode(data.values(path))))

  def string(
    path: FormPath,
    duplicateMessage: String = "must be submitted at most once"
  ): FormField[String] =
    FormField(path) {
      case Vector()      => Right("")
      case Vector(value) => Right(value)
      case _             => Left(FormErrors.one(path, duplicateMessage))
    }

  def requiredString(
    path: FormPath,
    blankMessage: String = "can't be blank",
    duplicateMessage: String = "must be submitted exactly once"
  ): FormField[String] =
    string(path, duplicateMessage).required(blankMessage)

  def optionalString(
    path: FormPath,
    duplicateMessage: String = "must be submitted at most once"
  ): FormField[Option[String]] =
    FormField(path) {
      case Vector()      => Right(None)
      case Vector(value) => Right(Option.when(value.nonEmpty)(value))
      case _             => Left(FormErrors.one(path, duplicateMessage))
    }

  def strings(path: FormPath): FormField[Vector[String]] =
    FormField(path)(Right(_))

  private def apply[A](path: FormPath, codec: FormCodec[A]): FormField[A] =
    new FormField(path, codec)
end FormField
