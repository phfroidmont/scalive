package scalive

/** Shared signing and cookie policy for Live routing and sibling HTTP handlers.
  *
  * Pass the same instance to `LiveRouter.withSecurity` and to ordinary handlers that validate
  * Scalive forms, issue flash redirects, or create application cookies. Framework values are signed
  * with the configured [[TokenConfig]] but are not encrypted. [[CookiePolicy]] controls browser
  * cookie attributes independently of token signing.
  *
  * @param cookies
  *   the policy used by CSRF, HTTP flash, and application cookies created through this instance
  */
final class LiveSecurity private[scalive] (
  private[scalive] val tokenConfig: TokenConfig,
  val cookies: CookiePolicy):

  /** Browser-bound double-submit CSRF protection using this signing and cookie policy. */
  val csrf: CsrfProtection = new CsrfProtection(tokenConfig, cookies)

  /** Signed, short-browser-lived flash messages for HTTP redirects into Live routes. */
  val flash: HttpFlash = new HttpFlash(tokenConfig, cookies)

  private[scalive] def withTokenConfig(config: TokenConfig): LiveSecurity =
    new LiveSecurity(config, cookies)

/** Creates shared Live and HTTP security capabilities. */
object LiveSecurity:
  /** Creates a security configuration.
    *
    * The default cookie policy has `Secure` disabled so local plain-HTTP development works. Scalive
    * does not infer browser-facing HTTPS from the request or forwarding headers; explicitly pass
    * `CookiePolicy(secure = true)` whenever the public endpoint uses HTTPS.
    *
    * @param tokenConfig
    *   signing secret and verification lifetime for framework values
    * @param cookies
    *   attributes for framework and application cookies
    */
  def apply(
    tokenConfig: TokenConfig,
    cookies: CookiePolicy = CookiePolicy(secure = false)
  ): LiveSecurity =
    new LiveSecurity(tokenConfig, cookies)
