package scalive

import zio.*
import zio.http.{Request, Response, Status}

/** Bounded decoder for CSRF-protected, URL-encoded ordinary HTTP forms.
  *
  * Decoding proceeds in a fixed short-circuiting order: require an
  * `application/x-www-form-urlencoded` content type, reject a declared or observed body over the
  * configured bound, read and parse the URL-encoded data, validate the CSRF token against the
  * request's browser cookie, then run the application [[FormCodec]]. Earlier failures prevent every
  * later step, including application validation.
  *
  * The decoder preserves ordered duplicate fields in [[FormData]] and reports typed [[Error]]
  * values. It does not check the request method or route, authenticate a user, authorize an action,
  * sanitize decoded strings, enforce application rate limits, or make unsafe form targets secure.
  * Those controls remain the route handler's responsibility.
  *
  * Create instances with [[HttpFormDecoder.urlEncoded]].
  *
  * @tparam A
  *   the application value produced by the form codec
  */
final class HttpFormDecoder[A] private (
  codec: FormCodec[A],
  maxBytes: Long,
  csrf: CsrfProtection):

  /** Reads, verifies, and decodes one HTTP request.
    *
    * Content type is checked before reading. A known oversized body is rejected immediately;
    * otherwise at most `maxBytes + 1` bytes are consumed before URL decoding. CSRF validation and
    * application decoding follow, and the pipeline stops at the first failing stage.
    *
    * @return
    *   an effect failing with the stage-specific [[HttpFormDecoder.Error]] or succeeding with the
    *   decoded application value
    */
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

  /** Decodes a request and maps both success and rejection to an HTTP response.
    *
    * `onDecoded` runs only after body parsing, CSRF validation, and application decoding all
    * succeed. On failure, `onRejected` runs first for observation or logging, then
    * [[HttpFormDecoder.Error.toResponse]] maps the same error to a response. `onValidation` is
    * called only for [[HttpFormDecoder.Error.Validation]]; transport, representation, and CSRF
    * errors use the decoder's fixed status mapping.
    *
    * `onRejected` cannot replace the response and its failure channel is `Nothing`. Defects in any
    * callback are not caught. Avoid logging raw bodies, CSRF tokens, cookies, or sensitive field
    * values from rejection details.
    *
    * @param request
    *   the request to decode
    * @param onValidation
    *   maps application validation errors to an application-specific response
    * @param onRejected
    *   observes every rejection before its response is returned
    * @param onDecoded
    *   handles a successfully decoded value
    */
  def respond[R, E](
    request: Request,
    onValidation: FormErrors => Response,
    onRejected: HttpFormDecoder.Error => URIO[R, Unit] = _ => ZIO.unit
  )(
    onDecoded: A => ZIO[R, E, Response]
  ): ZIO[R, E, Response] =
    decode(request).foldZIO(
      error => onRejected(error).as(error.toResponse(onValidation)),
      onDecoded
    )
end HttpFormDecoder

/** Constructs URL-encoded form decoders and defines their typed failures. */
object HttpFormDecoder:
  /** A failure from one stage of the bounded form-decoding pipeline. */
  enum Error:
    /** The request body exceeded the bound or could not be read. */
    case Body(error: FormData.BodyError)

    /** The body was not a supported URL-encoded representation. */
    case Representation(error: FormData.RepresentationError)

    /** The submitted CSRF token did not validate against the request context. */
    case Csrf(error: CsrfProtection.ValidationError)

    /** The application form codec rejected the parsed form data. */
    case Validation(errors: FormErrors)

    /** Returns a stable, low-cardinality code for logging and metrics.
      *
      * Codes distinguish body size/read failures, invalid content type or URL encoding, unsupported
      * binary field forms, CSRF rejection, and application validation. They are identifiers, not
      * localized client messages, and may collapse more detailed nested errors.
      */
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

    /** Maps this failure to an HTTP response.
      *
      * Oversized bodies map to 413, invalid content types to 415, other body or representation
      * failures to 400, and CSRF failures to 403. Application validation is delegated to
      * `onValidation`, which is invoked only for [[Error.Validation]]. Responses intentionally do
      * not expose low-level failure details by default.
      */
    def toResponse(onValidation: FormErrors => Response): Response = this match
      case Error.Body(FormData.BodyError.TooLarge(_)) => Status.RequestEntityTooLarge.toResponse
      case Error.Representation(FormData.RepresentationError.InvalidContentType(_)) =>
        Status.UnsupportedMediaType.toResponse
      case Error.Body(_) | Error.Representation(_) => Status.BadRequest.toResponse
      case Error.Csrf(_)                           => Response.forbidden
      case Error.Validation(errors)                => onValidation(errors)
  end Error

  /** Creates a bounded, CSRF-validating decoder for URL-encoded form requests.
    *
    * The request must use `application/x-www-form-urlencoded`; its declared charset is honored and
    * UTF-8 is the default. At most `maxBytes + 1` bytes are consumed to detect overflow without
    * buffering an unbounded body. Parsed key/value pairs and duplicates retain wire order. CSRF is
    * validated before `codec` runs.
    *
    * The bound applies only to the encoded body. Choose it according to route-specific resource
    * limits; this decoder does not provide rate limiting, authentication, authorization, request
    * method checks, or general input sanitization.
    *
    * @param codec
    *   the application decoder run after bounded parsing and CSRF validation
    * @param maxBytes
    *   the inclusive maximum encoded body size
    * @param csrf
    *   the protection instance used to validate the submitted token and request cookie
    * @throws IllegalArgumentException
    *   if `maxBytes` is negative or equals `Long.MaxValue`
    */
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
