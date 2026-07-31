package scalive

import zio.*
import zio.http.Request

final class HttpFormDecoder[A] private (
  codec: FormCodec[A],
  maxBytes: Long,
  csrf: CsrfProtection):

  def decode(request: Request): IO[HttpFormDecoder.Error, A] =
    for
      data <- FormData.fromUrlEncodedBody(request.body, maxBytes).mapError {
                case FormData.DecodeError.Body(error) =>
                  HttpFormDecoder.Error.Body(error)
                case FormData.DecodeError.Representation(error) =>
                  HttpFormDecoder.Error.Representation(error)
              }
      _ <- ZIO
             .fromEither(csrf.validate(request, data))
             .mapError(HttpFormDecoder.Error.Csrf(_))
      value <- ZIO
                 .fromEither(codec.decode(data))
                 .mapError(HttpFormDecoder.Error.Validation(_))
    yield value

object HttpFormDecoder:
  enum Error:
    case Body(error: FormData.BodyError)
    case Representation(error: FormData.RepresentationError)
    case Csrf(error: CsrfProtection.ValidationError)
    case Validation(errors: FormErrors)

  def urlEncoded[A](
    codec: FormCodec[A],
    maxBytes: Long,
    csrf: CsrfProtection
  ): HttpFormDecoder[A] =
    require(
      maxBytes >= 0 && maxBytes < Long.MaxValue,
      "maxBytes must be between 0 and Long.MaxValue - 1"
    )
    new HttpFormDecoder(codec, maxBytes, csrf)
