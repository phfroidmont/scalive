package scalive

import zio.http.*
import zio.http.codec.PathCodec

final class LiveLocation private[scalive] (private[scalive] val url: URL):
  def href: String = url.encode

  def seeOther: Response = Response.seeOther(url)

  /** Returns a copy with `fragment` as its URI fragment.
    *
    * `fragment` must already use percent-encoded URI-fragment syntax. This method validates but
    * does not encode decoded text; callers must encode spaces, for example as `%20`, before passing
    * it.
    *
    * @throws LiveLocation.EncodingException
    *   if the fragment syntax is invalid
    */
  def withFragment(fragment: String): LiveLocation =
    withFragmentEither(fragment).fold(
      error => throw new LiveLocation.EncodingException(error),
      identity
    )

  /** Returns a checked copy with `fragment` as its URI fragment.
    *
    * `fragment` must already use percent-encoded URI-fragment syntax. This method validates but
    * does not encode decoded text; callers must encode spaces, for example as `%20`, before passing
    * it.
    */
  def withFragmentEither(
    fragment: String
  ): Either[LiveLocation.EncodeError, LiveLocation] =
    URL
      .decode(s"#$fragment")
      .left
      .map(error => LiveLocation.EncodeError.Fragment(error.getMessage))
      .map(parsed => new LiveLocation(url.copy(fragment = parsed.fragment)))
end LiveLocation

object LiveLocation:
  enum EncodeError:
    case Path(details: String)
    case Query(cause: Throwable)
    case Fragment(details: String)

    def message: String = this match
      case Path(details)     => s"Could not encode route path: $details"
      case Query(cause)      => s"Could not encode route query: ${cause.getMessage}"
      case Fragment(details) => s"Could not encode route fragment: $details"

  final class EncodingException(val error: EncodeError)
      extends IllegalArgumentException(error.message)

  private[scalive] def encode[A](
    pathCodec: PathCodec[A],
    encoded: LiveParamsCodec.Encoded[A]
  ): Either[EncodeError, LiveLocation] =
    pathCodec
      .encode(encoded.pathParams)
      .left
      .map(EncodeError.Path.apply)
      .map(path => new LiveLocation(URL(path, queryParams = encoded.queryParams)))
