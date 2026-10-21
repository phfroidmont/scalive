package scalive

import zio.http.URL

/** Stops connected mount admission before a LiveView lifecycle is installed.
  *
  * A [[LiveSessionMountAspect]] fails with it during the connected mount, and
  * [[LiveRouteMountFailure.custom]] uses it for a route's connected outcome. Redirect failures
  * become socket redirect events. Unauthorized and stale failures become the corresponding
  * Phoenix-compatible join errors; their optional reasons are logged server-side and are not sent
  * to the client.
  */
enum LiveSessionMountFailure:
  /** Redirects to a location produced by a typed Live route.
    *
    * @param to
    *   The location to redirect to.
    */
  case Redirect(to: LiveLocation)

  /** Redirects to an unchecked URL.
    *
    * No same-origin or local-path validation is performed. Validate any untrusted input before
    * constructing this failure.
    *
    * @param to
    *   The URL to redirect to, used as given.
    */
  case RedirectUnsafe(to: URL)

  /** Rejects the socket join as unauthorized.
    *
    * @param reason
    *   An optional reason, logged server-side and never sent to the client.
    */
  case Unauthorized(reason: Option[String])

  /** Rejects the socket join as stale, indicating that the client should reload.
    *
    * @param reason
    *   An optional reason, logged server-side and never sent to the client.
    */
  case Stale(reason: Option[String])
end LiveSessionMountFailure

/** Constructors for connected mount failures. */
object LiveSessionMountFailure:
  /** Redirects connected mount to `to`.
    *
    * This form targets a typed Live route location.
    */
  def redirect(to: LiveLocation): LiveSessionMountFailure =
    LiveSessionMountFailure.Redirect(to)

  /** Redirects connected mount to `to`.
    *
    * This form leaves the application, for example for an identity provider.
    */
  def redirect(to: ExternalUrl): LiveSessionMountFailure =
    LiveSessionMountFailure.RedirectUnsafe(to.url)

  /** Redirects connected mount to an unchecked URL.
    *
    * This is an escape hatch for destinations that neither [[LiveLocation]] nor [[ExternalUrl]] can
    * represent. It does not validate origin or locality and can create an open redirect when given
    * untrusted input.
    */
  def redirectUnsafe(to: URL): LiveSessionMountFailure =
    LiveSessionMountFailure.RedirectUnsafe(to)

  /** Rejects connected mount as unauthorized.
    *
    * This form logs no application reason.
    */
  def unauthorized: LiveSessionMountFailure =
    LiveSessionMountFailure.Unauthorized(None)

  /** Rejects connected mount as unauthorized.
    *
    * This form logs `reason` server-side; the socket reply does not include it.
    */
  def unauthorized(reason: String): LiveSessionMountFailure =
    LiveSessionMountFailure.Unauthorized(Some(reason))

  /** Rejects connected mount as stale, indicating that the client should reload.
    *
    * This form logs no application reason.
    */
  def stale: LiveSessionMountFailure =
    LiveSessionMountFailure.Stale(None)

  /** Rejects connected mount as stale, indicating that the client should reload.
    *
    * This form logs `reason` server-side; the socket reply does not include it.
    */
  def stale(reason: String): LiveSessionMountFailure =
    LiveSessionMountFailure.Stale(Some(reason))
end LiveSessionMountFailure
