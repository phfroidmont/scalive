package scalive

import zio.json.*
import zio.json.ast.Json

import scalive.codecs.StringAsIsEncoder

/** Structured form field path parsed from browser names such as `user[address][city]`. */
final case class FormPath(segments: Vector[String]):
  def /(segment: String): FormPath =
    copy(segments = segments :+ segment)

  def array: FormPath =
    copy(segments = segments :+ "")

  def isEmpty: Boolean  = segments.isEmpty
  def nonEmpty: Boolean = segments.nonEmpty

  def name: String =
    segments.headOption.fold("") { first =>
      first + segments.tail.map(segment => s"[$segment]").mkString
    }

  override def toString: String = name

object FormPath:
  val empty: FormPath = FormPath(Vector.empty)

  def apply(first: String, rest: String*): FormPath =
    FormPath((first +: rest).filter(_.nonEmpty).toVector)

  def parse(name: String): FormPath =
    if name.isEmpty then empty
    else
      val segments = Vector.newBuilder[String]
      val current  = new StringBuilder()
      var index    = 0

      def pushCurrent(): Unit =
        if current.nonEmpty then
          segments += current.toString
          current.clear()

      while index < name.length do
        name.charAt(index) match
          case '[' =>
            pushCurrent()
            index = index + 1
            while index < name.length && name.charAt(index) != ']' do
              current.append(name.charAt(index))
              index = index + 1
            pushCurrent()
          case ']'  =>
          case char =>
            current.append(char)
        index = index + 1

      pushCurrent()
      FormPath(segments.result().filter(_.nonEmpty))
end FormPath

final case class FormError(path: FormPath, message: String, code: Option[String] = None)

object FormError:
  def apply(name: String, message: String): FormError =
    FormError(FormPath.parse(name), message)

  def apply(name: String, message: String, code: String): FormError =
    FormError(FormPath.parse(name), message, Some(code))

final case class FormErrors private (all: Vector[FormError]):
  def isEmpty: Boolean  = all.isEmpty
  def nonEmpty: Boolean = all.nonEmpty

  def +(error: FormError): FormErrors =
    FormErrors(all :+ error)

  def ++(other: FormErrors): FormErrors =
    FormErrors(all ++ other.all)

  def forPath(path: FormPath): Vector[FormError] =
    all.filter(_.path == path)

  def forName(name: String): Vector[FormError] =
    forPath(FormPath.parse(name))

  def messages(path: FormPath): Vector[String] =
    forPath(path).map(_.message)

  def messages(name: String): Vector[String] =
    messages(FormPath.parse(name))

object FormErrors:
  val empty: FormErrors = FormErrors(Vector.empty)

  def apply(errors: IterableOnce[FormError]): FormErrors =
    new FormErrors(errors.iterator.toVector)

  def one(path: FormPath, message: String, code: Option[String] = None): FormErrors =
    FormErrors(Vector(FormError(path, message, code)))

  def one(name: String, message: String): FormErrors =
    one(FormPath.parse(name), message)

  def one(name: String, message: String, code: String): FormErrors =
    one(FormPath.parse(name), message, Some(code))

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

final case class FormSubmitter(name: String, value: String)

final case class FormState[+A](
  raw: FormData,
  value: Either[FormErrors, A],
  used: Set[FormPath],
  submitted: Boolean):
  def isValid: Boolean       = value.isRight
  def errors: FormErrors     = value.left.getOrElse(FormErrors.empty)
  def valueOption: Option[A] = value.toOption

  def isUsed(path: FormPath): Boolean =
    submitted || used.contains(path)

  def isUsed(name: String): Boolean =
    isUsed(FormPath.parse(name))

  def errorsFor(path: FormPath): Vector[FormError] =
    errors.forPath(path)

  def errorsFor(name: String): Vector[FormError] =
    errors.forName(name)

object FormState:
  def apply[A](raw: FormData, value: Either[FormErrors, A], submitted: Boolean): FormState[A] =
    FormState(raw, value, usedPaths(raw, submitted), submitted)

  private def usedPaths(raw: FormData, submitted: Boolean): Set[FormPath] =
    val paths = raw.raw.iterator
      .map(_._1)
      .filterNot(_.startsWith(unusedPrefix))
      .map(FormPath.parse)
      .filter(_.nonEmpty)
      .toSet

    if submitted then paths
    else paths -- unusedPaths(raw)

  private def unusedPaths(raw: FormData): Set[FormPath] =
    raw.raw.iterator.collect {
      case (name, _) if name.startsWith(unusedPrefix) =>
        FormPath.parse(name.stripPrefix(unusedPrefix))
    }.toSet

  private val unusedPrefix = "_unused_"

final case class FormEvent[+A](
  raw: FormData,
  value: Either[FormErrors, A],
  target: Option[FormPath] = None,
  submitter: Option[FormSubmitter] = None,
  recovery: Boolean = false,
  submitted: Boolean = false,
  metadata: Map[String, String] = Map.empty,
  componentId: Option[Int] = None,
  uploads: Option[Json] = None):
  def state: FormState[A]    = FormState(raw, value, submitted)
  def data: FormData         = raw
  def isValid: Boolean       = value.isRight
  def errors: FormErrors     = value.left.getOrElse(FormErrors.empty)
  def valueOption: Option[A] = value.toOption

object FormEvent:
  final private[scalive] case class Meta(
    target: Option[FormPath] = None,
    submitter: Option[FormSubmitter] = None,
    recovery: Boolean = false,
    metadata: Map[String, String] = Map.empty,
    componentId: Option[Int] = None,
    uploads: Option[Json] = None):
    def params: Map[String, String] =
      val withTarget = target match
        case Some(path) if !metadata.contains("_target") => metadata.updated("_target", path.name)
        case _                                           => metadata

      val withRecovery =
        if recovery && !withTarget.contains("_recover") && !withTarget.contains("_recovery") then
          withTarget.updated("_recovery", "true")
        else withTarget

      val withCid = componentId match
        case Some(cid) => withRecovery.updated("__cid", cid.toString)
        case None      => withRecovery

      uploads match
        case Some(value) => withCid.updated("__uploads", value.toJson)
        case None        => withCid

  private[scalive] object Meta:
    val empty: Meta = Meta()

  private[scalive] def decode[A](
    raw: FormData,
    codec: FormCodec[A],
    submitted: Boolean,
    meta: Meta
  ): FormEvent[A] =
    FormEvent(
      raw = raw,
      value = codec.decode(raw),
      target = meta.target,
      submitter = meta.submitter,
      recovery = meta.recovery,
      submitted = submitted,
      metadata = meta.metadata,
      componentId = meta.componentId,
      uploads = meta.uploads
    )
end FormEvent

final case class Form[A](root: FormPath, state: FormState[A], codec: FormCodec[A]):
  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    phx.onChangeForm(codec)(f)

  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    phx.onSubmitForm(codec)(f)

  def field(path: String): Form.Field =
    field(FormPath.parse(path))

  def field(path: FormPath): Form.Field =
    Form.Field(this, path)

  def name(path: String): String =
    name(FormPath.parse(path))

  def name(path: FormPath): String =
    fullPath(path).name

  def id(path: String): String =
    id(FormPath.parse(path))

  def id(path: FormPath): String =
    fullPath(path).segments.filter(_.nonEmpty).mkString("_")

  def value(path: String): String =
    value(FormPath.parse(path))

  def value(path: FormPath): String =
    state.raw.string(fullPath(path)).orElse(state.raw.string(path)).getOrElse("")

  def text(path: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).text(mods*)

  def text(path: FormPath, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).text(mods*)

  def text(path: String, explicitId: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).text(explicitId, mods*)

  def text(path: FormPath, explicitId: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).text(explicitId, mods*)

  def email(path: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).email(mods*)

  def email(path: FormPath, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).email(mods*)

  def password(path: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).password(mods*)

  def password(path: FormPath, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).password(mods*)

  def hidden(path: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).hidden(mods*)

  def hidden(path: FormPath, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).hidden(mods*)

  def checkbox(path: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).checkbox(mods*)

  def checkbox(path: String, checkedValue: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).checkbox(checkedValue, mods*)

  def checkbox(path: FormPath, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).checkbox(mods*)

  def checkbox(path: FormPath, checkedValue: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).checkbox(checkedValue, mods*)

  def textarea(path: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).textarea(mods*)

  def textarea(path: FormPath, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    field(path).textarea(mods*)

  def select(
    path: String,
    options: Iterable[(String, String)],
    mods: Mod[Nothing]*
  ): HtmlElement[Nothing] =
    field(path).select(options, mods*)

  def select(
    path: FormPath,
    options: Iterable[(String, String)],
    mods: Mod[Nothing]*
  ): HtmlElement[Nothing] =
    field(path).select(options, mods*)

  def errors(path: String): HtmlElement[Nothing] =
    errors(FormPath.parse(path))

  def errors(path: FormPath): HtmlElement[Nothing] =
    val messages = errorsFor(path).map { error =>
      Mod.Content.Tag(span(cls := "form-error", error.message))
    }
    div(cls := "form-errors", messages)

  def feedback(path: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    feedback(FormPath.parse(path), mods*)

  def feedback(path: FormPath, mods: Mod[Nothing]*): HtmlElement[Nothing] =
    div(Form.feedbackFor := name(path), mods)

  def errorsFor(path: String): Vector[FormError] =
    errorsFor(FormPath.parse(path))

  def errorsFor(path: FormPath): Vector[FormError] =
    val relativeErrors = state.errors.forPath(path)
    val fullErrors     = state.errors.forPath(fullPath(path))
    (relativeErrors ++ fullErrors).distinct

  def isUsed(path: String): Boolean =
    isUsed(FormPath.parse(path))

  def isUsed(path: FormPath): Boolean =
    state.isUsed(path) || state.isUsed(fullPath(path))

  private[scalive] def fullPath(path: FormPath): FormPath =
    if root.isEmpty then path
    else FormPath(root.segments ++ path.segments)

object Form:
  private val feedbackFor = htmlAttr("phx-feedback-for", StringAsIsEncoder)
  private val textareaTag = HtmlTag("textarea")

  def of[A](name: String, state: FormState[A], codec: FormCodec[A]): Form[A] =
    Form(FormPath.parse(name), state, codec)

  def of[A](name: String, event: FormEvent[A], codec: FormCodec[A]): Form[A] =
    of(name, event.state, codec)

  final case class Field(form: Form[?], path: FormPath):
    def name: String = form.name(path)
    def id: String   = form.id(path)
    def fieldValue: String = form.value(path)

    def text(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      input(typ := "text", idAttr := id, nameAttr := name, value := fieldValue, mods)

    def text(explicitId: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
      input(typ := "text", idAttr := explicitId, nameAttr := name, value := fieldValue, mods)

    def email(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      input(typ := "email", idAttr := id, nameAttr := name, value := fieldValue, mods)

    def password(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      input(typ := "password", idAttr := id, nameAttr := name, value := fieldValue, mods)

    def hidden(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      input(typ := "hidden", idAttr := id, nameAttr := name, value := fieldValue, mods)

    def checkbox(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      checkbox("true", mods*)

    def checkbox(checkedValue: String, mods: Mod[Nothing]*): HtmlElement[Nothing] =
      input(
        typ := "checkbox",
        idAttr := id,
        nameAttr := name,
        value := checkedValue,
        checked := form.state.raw.values(form.fullPath(path)).contains(checkedValue),
        mods
      )

    def textarea(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      Form.textareaTag(idAttr := id, nameAttr := name, mods, fieldValue)

    def select(options: Iterable[(String, String)], mods: Mod[Nothing]*): HtmlElement[Nothing] =
      val selectedValues = form.state.raw.values(form.fullPath(path)).toSet
      _root_.scalive.select(
        idAttr := id,
        nameAttr := name,
        mods,
        options.map { case (optionValue, label) =>
          _root_.scalive.option(value := optionValue, selected := selectedValues.contains(optionValue), label)
        }
      )

    def errors: HtmlElement[Nothing] =
      form.errors(path)

    def feedback(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      form.feedback(path, mods*)
  end Field
end Form
