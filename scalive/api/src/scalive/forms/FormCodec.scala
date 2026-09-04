package scalive

/** Explicit low-level decoding for custom controls and transport adapters.
  *
  * This escape hatch has no schema ownership, bounded projection, raw-input form state, or
  * interaction-aware error visibility. Prefer [[FormDefinition]] for ordinary typed forms.
  */
trait FormCodec[A]:
  self =>

  /** Decodes the complete ordered form payload. */
  def decode(data: FormData): Either[FormErrors[Any], A]

  /** Maps a successfully decoded value. */
  def map[B](f: A => B): FormCodec[B] =
    FormCodec(data => self.decode(data).map(f))

  /** Adds error-producing refinement after decoding. */
  def emap[B](f: A => Either[FormErrors[Any], B]): FormCodec[B] =
    FormCodec(data => self.decode(data).flatMap(f))

  /** Decodes both codecs from the same payload and accumulates errors when both fail. */
  def zip[B](that: FormCodec[B]): FormCodec[(A, B)] = FormCodec { data =>
    (self.decode(data), that.decode(data)) match
      case (Right(left), Right(right)) => Right(left -> right)
      case (Left(left), Left(right))   => Left(left ++ right)
      case (Left(errors), _)           => Left(errors)
      case (_, Left(errors))           => Left(errors)
  }

/** Constructors and combinators for the low-level raw form escape hatch. */
object FormCodec:
  /** Identity codec preserving the complete raw payload. */
  val formData: FormCodec[FormData] = FormCodec(data => Right(data))

  /** Creates a low-level codec from a decoding function. */
  def apply[A](f: FormData => Either[FormErrors[Any], A]): FormCodec[A] =
    new FormCodec[A]:
      def decode(data: FormData): Either[FormErrors[Any], A] = f(data)

  /** Reads the last value for `name` when it is non-empty, or reports `issue`. */
  def requiredString(name: String, issue: FieldIssue = FieldIssue("can't be blank"))
    : FormCodec[String] =
    val address = lowLevelAddress(name)
    FormCodec { data =>
      data.string(name).filter(_.nonEmpty).toRight(FormErrors.one(address, issue))
    }

  /** Reads the last value for `name`, treating absence and empty text as `None`. */
  def optionalString(name: String): FormCodec[Option[String]] =
    FormCodec(data => Right(data.string(name).filter(_.nonEmpty)))

  private def lowLevelAddress(name: String): FormAddress[Any] =
    FormPath.parse(name).toOption.fold(FormAddress.names[Any](Vector(name)))(FormAddress.fromPath)
