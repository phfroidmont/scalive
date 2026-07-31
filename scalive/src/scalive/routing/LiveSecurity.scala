package scalive

final class LiveSecurity private[scalive] (
  private[scalive] val tokenConfig: TokenConfig,
  private[scalive] val secureCookies: Boolean):

  val csrf: CsrfProtection = new CsrfProtection(tokenConfig, secureCookies)
  val flash: HttpFlash     = new HttpFlash(tokenConfig, secureCookies)

  private[scalive] def withTokenConfig(config: TokenConfig): LiveSecurity =
    new LiveSecurity(config, secureCookies)

object LiveSecurity:
  def apply(
    tokenConfig: TokenConfig,
    secureCookies: Boolean = false
  ): LiveSecurity =
    new LiveSecurity(tokenConfig, secureCookies)
