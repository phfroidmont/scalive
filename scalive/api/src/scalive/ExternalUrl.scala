package scalive

import zio.http.URL

/** An absolute `http` or `https` URL with a host, for redirects that leave the application.
  *
  * Use it for destinations outside the router, such as a payment page or an identity provider, and
  * pass it to `redirect`. Building one checks the URL's shape, not its host: when any part of it
  * comes from user input, check the host against the destinations the application allows first, or
  * the redirect is an open redirect.
  *
  * @param url
  *   the checked URL
  */
final class ExternalUrl private (val url: URL):
  /** Returns the encoded URL, suitable for an HTML `href` or a `Location` header. */
  def href: String = url.encode

  override def equals(other: Any): Boolean = other match
    case that: ExternalUrl => url == that.url
    case _                 => false

  override def hashCode: Int = url.hashCode

  override def toString: String = s"ExternalUrl($href)"

/** Constructors for [[ExternalUrl]]. */
object ExternalUrl:
  /** Parses an external URL.
    *
    * @param text
    *   an absolute `http` or `https` URL with a host, without control characters
    * @return
    *   the URL, or why it is not an external URL
    */
  def parse(text: String): Either[IllegalArgumentException, ExternalUrl] =
    for
      _   <- NavigationDestination.validateCharacters(text)
      url <- URL.decode(text).left.map(error => IllegalArgumentException(error.getMessage))
      ext <- from(url)
    yield ext

  /** Checks that `url` is an external URL.
    *
    * @param url
    *   an absolute `http` or `https` URL with a host, without control characters
    * @return
    *   the URL, or why it is not an external URL
    */
  def from(url: URL): Either[IllegalArgumentException, ExternalUrl] =
    // ZIO HTTP decodes an upper-case scheme such as `HTTPS` as a custom, lower-cased scheme.
    val web = url.scheme.exists(scheme => scheme.encode == "http" || scheme.encode == "https")
    if !web then Left(IllegalArgumentException(s"Not an absolute HTTP(S) URL: ${url.encode}"))
    else if !url.host.exists(_.nonEmpty) then
      Left(IllegalArgumentException(s"External URL has no host: ${url.encode}"))
    else NavigationDestination.validateCharacters(url.encode).map(_ => new ExternalUrl(url))
