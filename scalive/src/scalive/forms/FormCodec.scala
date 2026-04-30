package scalive

trait FormCodec[A]:
  self =>

  def decode(data: FormData): Either[FormErrors, A]

  def map[B](f: A => B): FormCodec[B] =
    FormCodec(data => self.decode(data).map(f))

  def emap[B](f: A => Either[FormErrors, B]): FormCodec[B] =
    FormCodec(data => self.decode(data).flatMap(f))

object FormCodec:
  val formData: FormCodec[FormData] =
    FormCodec(data => Right(data))

  def apply[A](f: FormData => Either[FormErrors, A]): FormCodec[A] =
    new FormCodec[A]:
      override def decode(data: FormData): Either[FormErrors, A] = f(data)

  def requiredString(
    name: String,
    message: String = "can't be blank"
  ): FormCodec[String] =
    FormCodec { data =>
      data.string(name).filter(_.nonEmpty) match
        case Some(value) => Right(value)
        case None        => Left(FormErrors.one(name, message))
    }

  def requiredString(path: FormPath): FormCodec[String] =
    requiredString(path.name)

  def requiredString(
    path: FormPath,
    message: String
  ): FormCodec[String] =
    requiredString(path.name, message)

  def optionalString(name: String): FormCodec[Option[String]] =
    FormCodec(data => Right(data.string(name).filter(_.nonEmpty)))

  def optionalString(path: FormPath): FormCodec[Option[String]] =
    optionalString(path.name)
end FormCodec
