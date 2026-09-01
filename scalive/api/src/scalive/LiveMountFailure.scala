package scalive

import zio.http.URL

/** Stops connected mount admission before a LiveView lifecycle is installed.
  *
  * Redirect failures become socket redirect events. Unauthorized and stale failures become the
  * corresponding Phoenix-compatible join errors; their optional reasons are logged server-side and
  * are not sent to the client.
  */
enum LiveMountFailure:
  /** Redirects to a location produced by a typed Live route. */
  case Redirect(to: LiveLocation)

  /** Redirects to an unchecked URL.
    *
    * No same-origin or local-path validation is performed. Validate any untrusted input before
    * constructing this failure.
    */
  case RedirectUnsafe(to: URL)

  /** Rejects the socket join as unauthorized. */
  case Unauthorized(reason: Option[String])

  /** Rejects the socket join as stale, indicating that the client should reload. */
  case Stale(reason: Option[String])

/** Constructors for connected mount failures. */
object LiveMountFailure:
  /** Redirects connected mount to a typed Live route location. */
  def redirect(to: LiveLocation): LiveMountFailure =
    LiveMountFailure.Redirect(to)

  /** Redirects connected mount to an unchecked URL.
    *
    * This is an escape hatch for destinations that cannot be represented by [[LiveLocation]]. It
    * does not validate origin or locality and can create an open redirect when given untrusted
    * input.
    */
  def redirectUnsafe(to: URL): LiveMountFailure =
    LiveMountFailure.RedirectUnsafe(to)

  /** Rejects connected mount as unauthorized without logging an application reason. */
  def unauthorized: LiveMountFailure =
    LiveMountFailure.Unauthorized(None)

  /** Rejects connected mount as unauthorized and logs `reason` server-side.
    *
    * The reason is not included in the socket reply.
    */
  def unauthorized(reason: String): LiveMountFailure =
    LiveMountFailure.Unauthorized(Some(reason))

  /** Rejects connected mount as stale without logging an application reason. */
  def stale: LiveMountFailure =
    LiveMountFailure.Stale(None)

  /** Rejects connected mount as stale and logs `reason` server-side.
    *
    * The reason is not included in the socket reply.
    */
  def stale(reason: String): LiveMountFailure =
    LiveMountFailure.Stale(Some(reason))
end LiveMountFailure
