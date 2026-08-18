package scalive

import zio.http.Routes

/** ZIO HTTP assembly for a declarative [[LiveApplication]]. */
object ZioHttp:
  /** Interprets `application` as executable routes.
    *
    * Runtime interpretation is delivered by the first end-to-end runtime slice. Keeping the entry
    * point in the transport artifact establishes the public dependency boundary now.
    */
  def routes[R](_application: LiveApplication[R]): Routes[R, Nothing] =
    throw new UnsupportedOperationException(
      "ZIO HTTP runtime assembly is not implemented before runtime milestone 4"
    )
