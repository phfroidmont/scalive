package scalive

import zio.http.{Method as HttpMethod, RoutePattern, URL}

/** Encoded action and browser method for an ordinary HTML form.
  *
  * Prefer [[FormAction.from]] or [[FormAction.fromEither]]: route-derived actions accept only GET
  * and POST, encode typed path parameters, and mark POST forms for Scalive CSRF-token injection.
  * [[FormAction.unsafe]] is an explicit escape hatch for already encoded or external targets and
  * never requests CSRF protection, even for POST.
  *
  * A checked action is input to [[Form.http]]; it does not by itself authenticate, authorize, or
  * validate the eventual request.
  *
  * @param method
  *   the HTML form method
  * @param href
  *   the encoded action URL rendered in the `action` attribute
  */
final class FormAction private (
  /** The method rendered by [[Form.http]]. */
  val method: FormAction.Method,
  /** The encoded URL rendered by [[Form.http]]. */
  val href: String,
  private[scalive] val protectFromCsrf: Boolean)

/** Creates checked route actions and explicit unsafe actions for ordinary HTML forms. */
object FormAction:
  /** Methods natively supported by ordinary HTML forms.
    *
    * @param attributeValue
    *   the lowercase value rendered in the form's `method` attribute
    */
  enum Method(val attributeValue: String):
    /** An ordinary GET form method. */
    case Get extends Method("get")

    /** An ordinary POST form method. */
    case Post extends Method("post")

  /** A typed failure to convert a route pattern and parameters into a [[FormAction]]. */
  enum EncodeError:
    /** The route uses a method other than GET or POST. */
    case UnsupportedMethod(method: HttpMethod)

    /** The route pattern rejected its path parameters. */
    case Path(details: String)

    /** Returns a human-readable description of this encoding failure. */
    def message: String = this match
      case UnsupportedMethod(method) =>
        s"HTML forms support only GET and POST, got ${method.name}"
      case Path(details) => s"Could not encode form action path: $details"

  /** Exception thrown by [[FormAction.from]] when action encoding fails.
    *
    * Use [[FormAction.fromEither]] when the failure should remain in the typed error channel.
    *
    * @param error
    *   the typed encoding failure
    */
  final class EncodingException(val error: EncodeError)
      extends IllegalArgumentException(error.message)

  /** Encodes a checked action from a parameterized route pattern.
    *
    * Only GET and POST patterns are accepted. Typed path parameters are formatted by `pattern`, a
    * leading slash is added, and the resulting URL is encoded. Checked POST actions request CSRF
    * token injection when rendered with [[Form.http]].
    *
    * @param pattern
    *   the typed route pattern that owns the method and path shape
    * @param params
    *   values used to format the route path
    * @throws FormAction.EncodingException
    *   if the method is unsupported or the path parameters cannot be encoded
    */
  def from[A](pattern: RoutePattern[A], params: A): FormAction =
    fromEither(pattern, params).fold(error => throw new EncodingException(error), identity)

  /** Encodes a checked action from a route pattern without parameters.
    *
    * @throws FormAction.EncodingException
    *   if the method is unsupported or the path cannot be encoded
    */
  def from(pattern: RoutePattern[Unit]): FormAction =
    from(pattern, ())

  /** Encodes a checked action from a parameterized route pattern without throwing.
    *
    * Only GET and POST patterns are accepted. Typed path parameters are formatted by `pattern`, a
    * leading slash is added, and the resulting URL is encoded. Checked POST actions request CSRF
    * token injection when rendered with [[Form.http]].
    *
    * @return
    *   an [[EncodeError]] or the encoded action
    */
  def fromEither[A](
    pattern: RoutePattern[A],
    params: A
  ): Either[EncodeError, FormAction] =
    for
      method <- formMethod(pattern.method)
      path   <- pattern.format(params).left.map(EncodeError.Path.apply)
    yield new FormAction(
      method,
      URL(path.addLeadingSlash).encode,
      protectFromCsrf = method == Method.Post
    )

  /** Encodes a checked action from a route pattern without parameters and without throwing. */
  def fromEither(pattern: RoutePattern[Unit]): Either[EncodeError, FormAction] =
    fromEither(pattern, ())

  /** Creates an unchecked action for an already encoded or external target.
    *
    * This method performs no URL parsing, route formatting, same-origin check, or method-to-target
    * validation. It also explicitly disables Scalive CSRF-token injection, including when `method`
    * is [[Method.Post]]. The caller must establish any required request integrity and target trust.
    * Use a checked route action whenever the target is owned by the application.
    */
  def unsafe(method: Method, href: String): FormAction =
    new FormAction(method, href, protectFromCsrf = false)

  private def formMethod(method: HttpMethod): Either[EncodeError, Method] =
    method match
      case HttpMethod.GET  => Right(Method.Get)
      case HttpMethod.POST => Right(Method.Post)
      case other           => Left(EncodeError.UnsupportedMethod(other))
end FormAction
