package scalive

import zio.json.*
import zio.json.ast.Json

final private[scalive] case class LiveSessionPayload(
  sessionName: String,
  flash: Option[String],
  mountClaims: Option[Json],
  hasRouteMountClaims: Boolean,
  rootLayoutKey: String)
    derives JsonCodec

final private[scalive] case class LegacyLiveSessionPayload(
  sessionName: String,
  flash: Option[String],
  mountClaims: Option[Json])
    derives JsonCodec

private[scalive] object LiveSessionPayload:
  private val LegacyRootLayoutKey = "scalive:legacy-root"

  def sign(
    config: TokenConfig,
    liveViewId: String,
    sessionName: String,
    flash: Option[String],
    mountClaims: Option[Json],
    hasRouteMountClaims: Boolean,
    rootLayoutKey: String
  ): String =
    Token.sign(
      config.secret,
      liveViewId,
      LiveSessionPayload(sessionName, flash, mountClaims, hasRouteMountClaims, rootLayoutKey)
    )

  def verify(config: TokenConfig, token: String): Either[String, (String, LiveSessionPayload)] =
    Token
      .verify[LiveSessionPayload](config.secret, token, config.maxAge)
      .map { case (liveViewId, session) => liveViewId -> session }
      .orElse(
        Token
          .verify[LegacyLiveSessionPayload](config.secret, token, config.maxAge)
          .map { case (liveViewId, session) =>
            liveViewId -> LiveSessionPayload(
              session.sessionName,
              session.flash,
              session.mountClaims,
              hasRouteMountClaims = true,
              rootLayoutKey = LegacyRootLayoutKey
            )
          }
      ).orElse(
        Token
          .verify[String](config.secret, token, config.maxAge)
          .map { case (liveViewId, sessionName) =>
            liveViewId -> LiveSessionPayload(
              sessionName,
              None,
              None,
              hasRouteMountClaims = false,
              rootLayoutKey = LegacyRootLayoutKey
            )
          }
      )
end LiveSessionPayload
