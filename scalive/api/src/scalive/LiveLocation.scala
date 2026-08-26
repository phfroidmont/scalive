package scalive

import zio.http.codec.PathCodec
import zio.http.{Response, URL}

/** An encoded destination produced by a typed Live route.
  *
  * Construction is kept behind route codecs so application navigation does not accidentally skip
  * path or query validation. Use a route's `locationEither` method at checked boundaries and its
  * `location` convenience method when invalid application values are programmer errors.
  */
final class LiveLocation private[scalive] (private[scalive] val url: URL):
  /** Returns the encoded path, query, and optional fragment suitable for an HTML `href`.
    *
    * @return
    *   the encoded destination
    */
  def href: String = url.encode

  /** Returns an HTTP 303 See Other response targeting this location. */
  def seeOther: Response = Response.seeOther(url)

  /** Returns a copy with `fragment` as its URI fragment.
    *
    * `fragment` must already use percent-encoded URI-fragment syntax. This method validates but
    * does not encode decoded text; callers must encode spaces, for example as `%20`, before passing
    * it.
    *
    * @param fragment
    *   a percent-encoded URI fragment without the leading `#`
    * @return
    *   the updated location
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
    *
    * @param fragment
    *   a percent-encoded URI fragment without the leading `#`
    * @return
    *   the updated location, or [[LiveLocation.EncodeError.Fragment]] for invalid syntax
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

/** Encoding failures and operations associated with [[LiveLocation]]. */
object LiveLocation:
  /** A checked failure while constructing or extending a typed location. */
  enum EncodeError:
    /** The route path codec rejected its path value.
      *
      * @param details
      *   the diagnostic returned by the path codec
      */
    case Path(details: String)

    /** The query codec failed or threw while encoding its value.
      *
      * @param cause
      *   the underlying query encoding failure
      */
    case Query(cause: Throwable)

    /** The supplied percent-encoded URI fragment had invalid syntax.
      *
      * @param details
      *   the URL parser diagnostic
      */
    case Fragment(details: String)

    /** Returns a contextual message for this encoding failure.
      *
      * @return
      *   the path-, query-, or fragment-specific diagnostic
      */
    def message: String = this match
      case Path(details)     => s"Could not encode route path: $details"
      case Query(cause)      => s"Could not encode route query: ${cause.getMessage}"
      case Fragment(details) => s"Could not encode route fragment: $details"
  end EncodeError

  /** The unchecked wrapper used by convenience location APIs.
    *
    * Prefer the corresponding `Either`-returning method when values can originate outside trusted
    * application code.
    *
    * @param error
    *   the checked encoding failure
    */
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
end LiveLocation

private[scalive] object NavigationDestination:
  def live(value: String): Either[IllegalArgumentException, String] =
    validateCharacters(value).flatMap { destination =>
      scheme(destination) match
        case None | Some("http") | Some("https") => Right(destination)
        case Some(value)                         =>
          Left(
            IllegalArgumentException(
              s"Live navigation requires a path or HTTP(S) URL, not scheme '$value'"
            )
          )
    }

  def redirect(value: String): Either[IllegalArgumentException, String] = live(value)

  private def validateCharacters(value: String): Either[IllegalArgumentException, String] =
    val literalControl = value.exists(character => character <= ' ' || character == '\u007f')
    val lower          = value.toLowerCase(java.util.Locale.ROOT)
    val encodedControl = lower.indices.exists(index =>
      index + 2 < lower.length && lower.charAt(index) == '%' &&
        isControlByte(lower.substring(index + 1, index + 3))
    )
    if literalControl || encodedControl then
      Left(IllegalArgumentException("Navigation destination contains unsafe characters"))
    else Right(value)

  private def isControlByte(value: String): Boolean =
    value.length == 2 && value.forall(isHexDigit) && {
      val byte = Integer.parseInt(value, 16)
      byte <= 0x1f || byte == 0x7f
    }

  private def isHexDigit(character: Char): Boolean =
    character >= '0' && character <= '9' ||
      character >= 'a' && character <= 'f'

  private def scheme(value: String): Option[String] =
    if value.startsWith("/") then None
    else
      val delimiter = value.indexWhere(character =>
        character == ':' || character == '/' ||
          character == '?' || character == '#'
      )
      Option.when(delimiter >= 0 && value.charAt(delimiter) == ':')(
        value.substring(0, delimiter).toLowerCase(java.util.Locale.ROOT)
      )
end NavigationDestination
