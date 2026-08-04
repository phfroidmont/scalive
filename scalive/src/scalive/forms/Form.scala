package scalive

import scalive.codecs.StringAsIsEncoder

final case class Form[A](root: FormPath, state: FormState[A], codec: FormCodec[A]):
  def http[Msg](
    target: FormAction
  )(
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] = Form.http(target)(mods*)

  def onChange[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.change.form(codec)(f)

  def onSubmit[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.submit.form(codec)(f)

  def onRecover[Msg](f: FormEvent[A] => Msg): Mod.Attr[Msg] =
    on.recover.form(codec)(f)

  def disableRecovery: Mod.Attr[Nothing] =
    phx.autoRecover := "ignore"

  def triggerHttpSubmitWhen(condition: Boolean): Mod.Attr[Nothing] =
    phx.triggerAction := condition

  def field(path: String): FormFieldView[Vector[String]] =
    field(FormPath.parse(path))

  def field(path: FormPath): FormFieldView[Vector[String]] =
    FormFieldView(this, FormField.strings(fullPath(path)), Some(path))

  def field[B](definition: FormField[B]): FormFieldView[B] =
    require(
      root.isEmpty || definition.path.startsWith(root),
      s"field ${definition.name} is outside form root ${root.name}"
    )
    FormFieldView(this, definition, None)

  def name(path: String): String =
    name(FormPath.parse(path))

  def name(path: FormPath): String =
    fullPath(path).name

  def id(path: String): String =
    id(FormPath.parse(path))

  def id(path: FormPath): String =
    fullPath(path).id

  def value(path: String): String =
    value(FormPath.parse(path))

  def value(path: FormPath): String =
    state.raw.string(fullPath(path)).orElse(state.raw.string(path)).getOrElse("")

  def text[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).text(mods*)

  def text[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).text(mods*)

  def text[Msg](path: String, explicitId: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).text(explicitId, mods*)

  def text[Msg](path: FormPath, explicitId: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).text(explicitId, mods*)

  def email[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).email(mods*)

  def email[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).email(mods*)

  def password[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).password(mods*)

  def password[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).password(mods*)

  def hidden[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).hidden(mods*)

  def hidden[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).hidden(mods*)

  def checkbox[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).checkbox(mods*)

  def checkbox[Msg](path: String, checkedValue: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).checkbox(checkedValue, mods*)

  def checkbox[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).checkbox(mods*)

  def checkbox[Msg](path: FormPath, checkedValue: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).checkbox(checkedValue, mods*)

  def textarea[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).textarea(mods*)

  def textarea[Msg](path: FormPath, mods: Mod[Msg]*): HtmlElement[Msg] =
    field(path).textarea(mods*)

  def select[Msg](
    path: String,
    options: Iterable[(String, String)],
    mods: Mod[Msg]*
  ): HtmlElement[Msg] =
    field(path).select(options, mods*)

  def select[Msg](
    path: FormPath,
    options: Iterable[(String, String)],
    mods: Mod[Msg]*
  ): HtmlElement[Msg] =
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
  private[scalive] val feedbackFor = htmlAttr("phx-feedback-for", StringAsIsEncoder)
  private[scalive] val textareaTag = HtmlTag("textarea")
  private val httpAttributes       = Set("action", "method")
  private val csrfMarker           = htmlAttr(CsrfProtection.MarkerName, StringAsIsEncoder)

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
      case Mod.Attr.Binding(name, _)               => Some(name)
      case Mod.Attr.FormBinding(name, _)           => Some(name)
      case Mod.Attr.FormEventBinding(name, _, _)   => Some(name)
      case Mod.Attr.JsBinding(name, _)             => Some(name)
      case Mod.Attr.RoutedBinding(name, _)         => Some(name)
      case Mod.Attr.Group(attrs)                   => attrs.flatMap(attributeName).headOption
      case _: Mod.Content[?]                       => None

  def of[A](name: String, state: FormState[A], codec: FormCodec[A]): Form[A] =
    Form(FormPath.parse(name), state, codec)

  def of[A](name: String, event: FormEvent[A], codec: FormCodec[A]): Form[A] =
    of(name, event.state, codec)

end Form

final class FormFieldView[A] private[scalive] (
  private val form: Form[?],
  private val definition: FormField[A],
  private val legacyPath: Option[FormPath]):

  def path: FormPath  = definition.path
  def name: String    = definition.name
  def id: String      = definition.id
  def errorId: String = s"${id}_errors"

  def rawValues: Vector[String] =
    legacyPath match
      case Some(relative) =>
        val fullValues = form.state.raw.values(form.fullPath(relative))
        if fullValues.nonEmpty then fullValues else form.state.raw.values(relative)
      case None => form.state.raw.values(path)

  def fieldValue: String             = rawValues.lastOption.getOrElse("")
  def decoded: Either[FormErrors, A] = definition.codec.decode(form.state.raw)

  def errors: Vector[FormError] =
    legacyPath.fold(form.state.errors.forPath(path))(form.errorsFor)

  def isUsed: Boolean =
    legacyPath.fold(form.state.isUsed(path))(form.isUsed)

  def visibleErrors: Vector[FormError] =
    if isUsed then errors else Vector.empty

  def hasVisibleErrors: Boolean = visibleErrors.nonEmpty

  def validationAttributes: Vector[Mod.Attr[Nothing]] =
    Vector(aria.describedby := errorId) ++
      Option.when(hasVisibleErrors)(aria.invalid := "true").toVector

  def text[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    input(typ := "text", idAttr := id, nameAttr := name, value := fieldValue, flatten(mods))

  def text[Msg](
    explicitId: String,
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] =
    input(typ := "text", idAttr := explicitId, nameAttr := name, value := fieldValue, flatten(mods))

  def email[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    input(typ := "email", idAttr := id, nameAttr := name, value := fieldValue, flatten(mods))

  def password[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    input(typ := "password", idAttr := id, nameAttr := name, value := fieldValue, flatten(mods))

  def hidden[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    input(typ := "hidden", idAttr := id, nameAttr := name, value := fieldValue, flatten(mods))

  def checkbox[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    checkbox("true", flatten(mods))

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

  def textarea[Msg](mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*): HtmlElement[Msg] =
    Form.textareaTag(idAttr := id, nameAttr := name, flatten(mods), fieldValue)

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

  def errorFeedback(
    mods: (Mod[Nothing] | IterableOnce[Mod[Nothing]])*
  ): HtmlElement[Nothing] =
    val messages = visibleErrors.map { error =>
      Mod.Content.Tag(span(cls := "form-error", error.message))
    }
    div(
      idAttr           := errorId,
      Form.feedbackFor := name,
      aria.live        := "polite",
      cls              := "form-errors",
      flatten(mods),
      messages
    )

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
