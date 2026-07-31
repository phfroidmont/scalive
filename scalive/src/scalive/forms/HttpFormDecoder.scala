package scalive

import zio.*
import zio.http.{Request, Response, Status}

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

    def code: String = this match
      case Error.Body(FormData.BodyError.TooLarge(_)) => "body_too_large"
      case Error.Body(FormData.BodyError.Read(_))     => "body_read"
      case Error.Representation(FormData.RepresentationError.InvalidContentType(_)) =>
        "invalid_content_type"
      case Error.Representation(FormData.RepresentationError.InvalidUrlEncoding(_)) =>
        "invalid_url_encoding"
      case Error.Representation(
            FormData.RepresentationError.UnsupportedField(
              _,
              FormData.UnsupportedFieldKind.Binary
            )
          ) =>
        "unsupported_binary"
      case Error.Representation(
            FormData.RepresentationError.UnsupportedField(
              _,
              FormData.UnsupportedFieldKind.StreamingBinary
            )
          ) =>
        "unsupported_streaming_binary"
      case Error.Csrf(_)       => "csrf"
      case Error.Validation(_) => "validation"

    def toResponse(onValidation: FormErrors => Response): Response = this match
      case Error.Body(FormData.BodyError.TooLarge(_)) => Status.RequestEntityTooLarge.toResponse
      case Error.Representation(FormData.RepresentationError.InvalidContentType(_)) =>
        Status.UnsupportedMediaType.toResponse
      case Error.Body(_) | Error.Representation(_) => Status.BadRequest.toResponse
      case Error.Csrf(_)                           => Response.forbidden
      case Error.Validation(errors)                => onValidation(errors)
  end Error

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
end HttpFormDecoder
