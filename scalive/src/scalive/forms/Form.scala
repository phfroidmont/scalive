package scalive

import scalive.codecs.StringAsIsEncoder

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
end Form

object Form:
  private val feedbackFor = htmlAttr("phx-feedback-for", StringAsIsEncoder)
  private val textareaTag = HtmlTag("textarea")

  def of[A](name: String, state: FormState[A], codec: FormCodec[A]): Form[A] =
    Form(FormPath.parse(name), state, codec)

  def of[A](name: String, event: FormEvent[A], codec: FormCodec[A]): Form[A] =
    of(name, event.state, codec)

  final case class Field(form: Form[?], path: FormPath):
    def name: String       = form.name(path)
    def id: String         = form.id(path)
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
        typ      := "checkbox",
        idAttr   := id,
        nameAttr := name,
        value    := checkedValue,
        checked  := form.state.raw.values(form.fullPath(path)).contains(checkedValue),
        mods
      )

    def textarea(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      Form.textareaTag(idAttr := id, nameAttr := name, mods, fieldValue)

    def select(options: Iterable[(String, String)], mods: Mod[Nothing]*): HtmlElement[Nothing] =
      val selectedValues = form.state.raw.values(form.fullPath(path)).toSet
      _root_.scalive.select(
        idAttr   := id,
        nameAttr := name,
        mods,
        options.map { case (optionValue, label) =>
          _root_.scalive
            .option(value := optionValue, selected := selectedValues.contains(optionValue), label)
        }
      )

    def errors: HtmlElement[Nothing] =
      form.errors(path)

    def feedback(mods: Mod[Nothing]*): HtmlElement[Nothing] =
      form.feedback(path, mods*)
  end Field
end Form
