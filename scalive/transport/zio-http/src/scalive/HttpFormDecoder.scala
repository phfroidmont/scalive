package scalive

import zio.*
import zio.http.{Request, Response, Status}

/** Bounded decoder for CSRF-protected, URL-encoded ordinary HTTP forms.
  *
  * Body bounds and representation checks run before CSRF validation, which runs before semantic
  * decoding. Rejections are typed as [[HttpFormDecoder.Error]] and map to conservative responses.
  */
final class HttpFormDecoder[A] private (
  decodeData: FormData => Either[FormErrors[?], A],
  maxBytes: Long,
  csrf: CsrfProtection):

  /** Enforces `maxBytes`, validates URL-encoded representation and CSRF, then decodes. */
  def decode(request: Request): IO[HttpFormDecoder.Error, A] =
    for
      data <- FormData.fromUrlEncodedBody(request.body, maxBytes).mapError {
                case FormData.DecodeError.Body(error) =>
                  HttpFormDecoder.Error.Body(error)
                case FormData.DecodeError.Representation(error) =>
                  HttpFormDecoder.Error.Representation(error)
              }
      _     <- csrf.validate(request, data).mapError(HttpFormDecoder.Error.Csrf(_))
      value <- ZIO.fromEither(decodeData(data)).mapError(HttpFormDecoder.Error.Validation(_))
    yield value

  /** Decodes and produces an HTTP response with centralized rejection handling.
    *
    * `onRejected` observes every decoder rejection but cannot replace its security-sensitive
    * status; only validation responses are application-defined through `onValidation`.
    */
  def respond[R, E](
    request: Request,
    onValidation: FormErrors[?] => Response,
    onRejected: HttpFormDecoder.Error => URIO[R, Unit] = _ => ZIO.unit
  )(
    onDecoded: A => ZIO[R, E, Response]
  ): ZIO[R, E, Response] =
    decode(request).foldZIO(
      error => onRejected(error).as(error.toResponse(onValidation)),
      onDecoded
    )
end HttpFormDecoder

/** Constructors and typed rejection categories for ordinary HTTP form decoders. */
object HttpFormDecoder:
  /** Failure category exposed without embedding sensitive details in default HTTP responses. */
  enum Error:
    case Body(error: FormData.BodyError)
    case Representation(error: FormData.RepresentationError)
    case Csrf(error: CsrfProtection.ValidationError)
    case Validation(errors: FormErrors[?])

    /** Stable machine-readable category code for logging or metrics. */
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

    /** Maps size to 413, media type to 415, malformed input to 400, CSRF to 403, and delegates
      * validation responses to `onValidation`.
      */
    def toResponse(onValidation: FormErrors[?] => Response): Response = this match
      case Error.Body(FormData.BodyError.TooLarge(_)) => Status.RequestEntityTooLarge.toResponse
      case Error.Representation(FormData.RepresentationError.InvalidContentType(_)) =>
        Status.UnsupportedMediaType.toResponse
      case Error.Body(_) | Error.Representation(_) => Status.BadRequest.toResponse
      case Error.Csrf(_)                           => Response.forbidden
      case Error.Validation(errors)                => onValidation(errors)
  end Error

  /** Builds a bounded, CSRF-protected decoder around the low-level [[FormCodec]] escape hatch. */
  def urlEncoded[A](
    codec: FormCodec[A],
    maxBytes: Long,
    csrf: CsrfProtection
  ): HttpFormDecoder[A] =
    bounded(maxBytes)
    new HttpFormDecoder(codec.decode, maxBytes, csrf)

  /** Returns a submitted typed form even when schema validation fails.
    *
    * Use this when validation errors must be rendered; submitted interaction makes all errors
    * visible. Transport, representation, and CSRF failures still reject decoding.
    */
  def urlEncoded[Owner, Domain](
    definition: FormDefinition[Owner, Domain],
    maxBytes: Long,
    csrf: CsrfProtection
  ): HttpFormDecoder[definition.Form] =
    bounded(maxBytes)
    new HttpFormDecoder(
      data => Right(definition.event(data, FormEventKind.Submitted).form),
      maxBytes,
      csrf
    )

  /** Convenience decoder requiring a valid submitted domain value.
    *
    * Schema errors become [[Error.Validation]] and are rendered by `respond`'s validation handler.
    */
  def urlEncodedValue[Owner, Domain](
    definition: FormDefinition[Owner, Domain],
    maxBytes: Long,
    csrf: CsrfProtection
  ): HttpFormDecoder[Domain] =
    bounded(maxBytes)
    new HttpFormDecoder(
      data => definition.event(data, FormEventKind.Submitted).form.result,
      maxBytes,
      csrf
    )

  private def bounded(maxBytes: Long): Unit =
    require(
      maxBytes >= 0 && maxBytes < Long.MaxValue,
      "maxBytes must be between 0 and Long.MaxValue - 1"
    )
end HttpFormDecoder
