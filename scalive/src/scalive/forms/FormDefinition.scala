package scalive

final class FormRoot private (val path: FormPath):
  self =>

  type Field[A]     = RootedFormField[self.type, A]
  type Codec[A]     = RootedFormCodec[self.type, A]
  type InitialValue = FormInitialValue[self.type]

  def field[A](
    path: String
  )(
    decode: Vector[String] => Either[FormErrors, A]
  ): Field[A] =
    RootedFormField(FormField(fullPath(path))(decode))

  def string(
    path: String,
    duplicateMessage: String = "must be submitted at most once"
  ): Field[String] =
    RootedFormField(FormField.string(fullPath(path), duplicateMessage))

  def requiredString(
    path: String,
    blankMessage: String = "can't be blank",
    duplicateMessage: String = "must be submitted exactly once"
  ): Field[String] =
    RootedFormField(FormField.requiredString(fullPath(path), blankMessage, duplicateMessage))

  def optionalString(
    path: String,
    duplicateMessage: String = "must be submitted at most once"
  ): Field[Option[String]] =
    RootedFormField(FormField.optionalString(fullPath(path), duplicateMessage))

  def strings(path: String): Field[Vector[String]] =
    RootedFormField(FormField.strings(fullPath(path)))

  def form[A](codec: Codec[A]): FormDefinition[self.type, A] =
    FormDefinition(path, codec.underlying)

  def form[A1, Result](construct: A1 => Result)(field1: Field[A1])
    : FormDefinition[self.type, Result] =
    form(field1.codec.map(construct))

  def form[A1, A2, Result](
    construct: (A1, A2) => Result
  )(
    field1: Field[A1],
    field2: Field[A2]
  ): FormDefinition[self.type, Result] =
    form(field1.codec.zip(field2.codec).map(construct.tupled))

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

object FormRoot:
  def apply(name: String): FormRoot =
    val path = FormPath.parse(name)
    require(path.nonEmpty, "form root must not be empty")
    new FormRoot(path)

final class RootedFormCodec[Owner, A] private[scalive] (
  private[scalive] val underlying: FormCodec[A]):
  self =>

  def map[B](f: A => B): RootedFormCodec[Owner, B] =
    RootedFormCodec(self.underlying.map(f))

  def emap[B](f: A => Either[FormErrors, B]): RootedFormCodec[Owner, B] =
    RootedFormCodec(self.underlying.emap(f))

  def zip[B](that: RootedFormCodec[Owner, B]): RootedFormCodec[Owner, (A, B)] =
    RootedFormCodec(self.underlying.zip(that.underlying))

private[scalive] object RootedFormCodec:
  def apply[Owner, A](codec: FormCodec[A]): RootedFormCodec[Owner, A] =
    new RootedFormCodec(codec)

final class RootedFormField[Owner, A] private[scalive] (
  private[scalive] val underlying: FormField[A]):

  def path: FormPath = underlying.path
  def name: String   = underlying.name
  def id: String     = underlying.id

  def codec: RootedFormCodec[Owner, A] = RootedFormCodec(underlying.codec)

  def map[B](f: A => B): RootedFormField[Owner, B] =
    RootedFormField(underlying.map(f))

  def validate(
    message: String,
    code: Option[String] = None
  )(
    predicate: A => Boolean
  ): RootedFormField[Owner, A] =
    RootedFormField(underlying.validate(message, code)(predicate))

  def required(
    message: String = "can't be blank",
    code: Option[String] = None
  )(using A =:= String
  ): RootedFormField[Owner, String] =
    RootedFormField(underlying.required(message, code))

  def initial(values: String*): FormInitialValue[Owner] =
    FormInitialValue(path, values.toVector)

private[scalive] object RootedFormField:
  def apply[Owner, A](field: FormField[A]): RootedFormField[Owner, A] =
    new RootedFormField(field)

final case class FormInitialValue[Owner] private[scalive] (
  path: FormPath,
  values: Vector[String])

final class FormDefinition[Owner, A] private[scalive] (
  val root: FormPath,
  val codec: FormCodec[A]):

  type Form         = RootedForm[Owner, A]
  type Field[B]     = RootedFormField[Owner, B]
  type InitialValue = FormInitialValue[Owner]

  def initial(values: InitialValue*): Form =
    val raw   = FormData(values.flatMap(value => value.values.map(value.path.name -> _)))
    val state = FormState(
      raw = raw,
      value = codec.decode(raw),
      used = Set.empty,
      submitted = false
    )
    from(state)

  def from(state: FormState[A]): Form =
    RootedForm(Form(root, state, codec))

  def from(event: FormEvent[A]): Form =
    from(event.state)

private[scalive] object FormDefinition:
  def apply[Owner, A](root: FormPath, codec: FormCodec[A]): FormDefinition[Owner, A] =
    new FormDefinition(root, codec)

final class RootedForm[Owner, A] private[scalive] (
  private val underlying: Form[A]):

  def state: FormState[A] = underlying.state

  def http[Msg](
    target: FormAction
  )(
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] = underlying.http(target)(mods*)

  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    underlying.onChange(f)

  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    underlying.onSubmit(f)

  def field[B](definition: RootedFormField[Owner, B]): FormFieldView[B] =
    underlying.field(definition.underlying)

private[scalive] object RootedForm:
  def apply[Owner, A](form: Form[A]): RootedForm[Owner, A] =
    new RootedForm(form)
