package scalive

final class LiveSecurity private[scalive] (
  private[scalive] val tokenConfig: TokenConfig,
  val cookies: CookiePolicy):

  val csrf: CsrfProtection = new CsrfProtection(tokenConfig, cookies)
  val flash: HttpFlash     = new HttpFlash(tokenConfig, cookies)

  private[scalive] def withTokenConfig(config: TokenConfig): LiveSecurity =
    new LiveSecurity(config, cookies)

object LiveSecurity:
  def apply(
    tokenConfig: TokenConfig,
    cookies: CookiePolicy = CookiePolicy(secure = false)
  ): LiveSecurity =
    new LiveSecurity(tokenConfig, cookies)
