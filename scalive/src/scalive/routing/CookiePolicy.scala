package scalive

import zio.Duration
import zio.http.{Cookie, Path}

/** Hardened attributes shared by framework and application cookies. */
final case class CookiePolicy(secure: Boolean):
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

  def expire(name: String): Cookie.Response =
    make(name, "", maxAge = Some(Duration.Zero))
