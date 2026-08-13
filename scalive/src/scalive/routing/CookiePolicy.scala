package scalive

import zio.Duration
import zio.http.{Cookie, Path}

/** Attributes shared by framework and application cookies.
  *
  * Cookies made by this policy are host-only (no `Domain` attribute), scoped to `/`, `HttpOnly`,
  * and `SameSite=Lax`. Their `Secure` attribute is exactly `secure`; Scalive does not infer TLS
  * from the request or proxy headers. `SameSite=Lax` and `HttpOnly` are useful hardening, but they
  * do not by themselves provide encryption, complete CSRF protection, or protection from
  * same-origin script injection.
  *
  * @param secure
  *   whether browsers may send the cookie only over secure transports; use `true` for every
  *   browser-facing HTTPS deployment
  */
final case class CookiePolicy(secure: Boolean):
  /** Creates a response cookie with this policy's fixed attributes.
    *
    * `maxAge = None` creates a browser-session cookie. Supplying a duration writes that `Max-Age`
    * without changing the policy's host-only, root-path, `HttpOnly`, `SameSite=Lax`, or configured
    * `Secure` guarantees. This method creates a value; the caller must add it to a response.
    *
    * @param name
    *   the cookie name
    * @param content
    *   the cookie value; this method does not sign or encrypt it
    * @param maxAge
    *   optional browser retention duration
    */
  def make(
    name: String,
    content: String,
    maxAge: Option[Duration] = None
  ): Cookie.Response =
    Cookie.Response(
      name,
      content,
      path = Some(Path.root),
      isSecure = secure,
      isHttpOnly = true,
      maxAge = maxAge,
      sameSite = Some(Cookie.SameSite.Lax)
    )

  /** Creates a matching root-path, host-only cookie deletion with empty content and zero max age.
    *
    * The returned cookie retains this policy's `HttpOnly`, `SameSite`, and `Secure` attributes and
    * must be added to a response for the browser to process it.
    */
  def expire(name: String): Cookie.Response =
    make(name, "", maxAge = Some(Duration.Zero))
end CookiePolicy
