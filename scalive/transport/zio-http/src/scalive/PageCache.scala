package scalive

import zio.NonEmptyChunk
import zio.http.Header

/** How browsers and caches may keep the responses [[ZioHttp.routes]] serves for pages.
  *
  * It applies to rendered pages, error pages, redirects, and HTTP route responses that set no
  * `Cache-Control` of their own. Scalive's browser files keep their own headers. A page carries its
  * CSRF token and signed session, so no case lets a shared cache keep it.
  */
enum PageCache derives CanEqual:
  /** `private, max-age=0, must-revalidate`, as Phoenix sends by default: only the browser may keep
    * the page, and it asks the server again before reusing it.
    */
  case Revalidate

  /** `no-store`: nothing keeps the page, for applications whose pages hold sensitive data. */
  case NoStore

  private[scalive] def header: Header.CacheControl = this match
    case Revalidate =>
      Header.CacheControl.Multiple(
        NonEmptyChunk(
          Header.CacheControl.Private,
          Header.CacheControl.MaxAge(0),
          Header.CacheControl.MustRevalidate
        )
      )
    case NoStore => Header.CacheControl.NoStore
