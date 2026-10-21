package scalive

/** Fails a root LiveView's `mount` or `handleParams` when what its URL names does not exist.
  *
  * A page request answers `404 Not Found` with the router's [[LiveErrorPage]], or with the bare
  * status without one. When the LiveView fails while joining, as after live navigation, the browser
  * reloads the page and that request answers the same 404. `reason` is logged at debug level and
  * never sent to the browser.
  *
  * {{{
  * patients.find(id).someOrFail(LiveNotFound(s"patient $id"))
  * }}}
  *
  * @param reason
  *   What was not found, for the log.
  */
final case class LiveNotFound(reason: String) extends RuntimeException(reason)

/** Creates [[LiveNotFound]] failures. */
object LiveNotFound:
  // The LiveNotFound a failure carries, directly or as the cause of a wrapping error.
  private[scalive] def in(cause: zio.Cause[Any]): Option[LiveNotFound] =
    (cause.failures ++ cause.defects)
      .collect { case error: Throwable => error }
      .flatMap(causeChain).collectFirst { case notFound: LiveNotFound => notFound }

  private def causeChain(error: Throwable): List[Throwable] =
    Iterator.iterate(error)(_.getCause).takeWhile(_ != null).take(16).toList
