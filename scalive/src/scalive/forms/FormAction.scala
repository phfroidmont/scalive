package scalive

import zio.http.{Method as HttpMethod, RoutePattern, URL}

final class FormAction private (
  val method: FormAction.Method,
  val href: String,
  private[scalive] val protectFromCsrf: Boolean)

object FormAction:
  enum Method(val attributeValue: String):
    case Get  extends Method("get")
    case Post extends Method("post")

  enum EncodeError:
    case UnsupportedMethod(method: HttpMethod)
    case Path(details: String)

    def message: String = this match
      case UnsupportedMethod(method) =>
        s"HTML forms support only GET and POST, got ${method.name}"
      case Path(details) => s"Could not encode form action path: $details"

  final class EncodingException(val error: EncodeError)
      extends IllegalArgumentException(error.message)

  def from[A](pattern: RoutePattern[A], params: A): FormAction =
    fromEither(pattern, params).fold(error => throw new EncodingException(error), identity)

  def from(pattern: RoutePattern[Unit]): FormAction =
    from(pattern, ())

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

  def fromEither(pattern: RoutePattern[Unit]): Either[EncodeError, FormAction] =
    fromEither(pattern, ())

  def unsafe(method: Method, href: String): FormAction =
    new FormAction(method, href, protectFromCsrf = false)

  private def formMethod(method: HttpMethod): Either[EncodeError, Method] =
    method match
      case HttpMethod.GET  => Right(Method.Get)
      case HttpMethod.POST => Right(Method.Post)
      case other           => Left(EncodeError.UnsupportedMethod(other))
end FormAction
