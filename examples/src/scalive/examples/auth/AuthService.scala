package scalive.examples.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

import zio.*
import zio.json.*

final case class LoginCsrfToken(value: String)
final case class LoginContextCookieToken(value: String)
final case class PublicLoginContextId(value: String) derives JsonCodec
final case class LoginBootstrap(cookieToken: LoginContextCookieToken)
final case class LoginContext(publicId: PublicLoginContextId, csrfToken: LoginCsrfToken)

final case class SessionCookieToken(value: String)
final case class PublicSessionId(value: String) derives JsonCodec
final case class LogoutCsrfToken(value: String)

final case class AuthUser(email: String, name: String)

final case class CurrentSession(
  user: AuthUser,
  publicSessionId: PublicSessionId,
  logoutCsrfToken: LogoutCsrfToken)

final case class LoginClaims(publicId: PublicLoginContextId) derives JsonCodec
final case class AuthClaims(publicSessionId: PublicSessionId) derives JsonCodec

final case class LoginResult(cookieToken: SessionCookieToken, currentSession: CurrentSession)

final case class AuthServiceConfig(
  loginContextTtl: Duration,
  sessionTtl: Duration,
  maxLoginContexts: Int,
  maxSessions: Int):
  require(loginContextTtl.toMillis > 0, "loginContextTtl must be positive")
  require(sessionTtl.toMillis > 0, "sessionTtl must be positive")
  require(maxLoginContexts > 0, "maxLoginContexts must be positive")
  require(maxSessions > 0, "maxSessions must be positive")

object AuthServiceConfig:
  val DefaultLoginContextTtl  = 5.minutes
  val DefaultSessionTtl       = 30.minutes
  val DefaultMaxLoginContexts = 256
  val DefaultMaxSessions      = 1024

  val default = AuthServiceConfig(
    loginContextTtl = DefaultLoginContextTtl,
    sessionTtl = DefaultSessionTtl,
    maxLoginContexts = DefaultMaxLoginContexts,
    maxSessions = DefaultMaxSessions
  )

trait AuthService:
  def beginLogin: UIO[LoginBootstrap]
  def prepareLogin(cookieToken: LoginContextCookieToken): UIO[Option[LoginContext]]
  def resumeLogin(publicId: PublicLoginContextId): UIO[Option[LoginContext]]
  def login(
    cookieToken: LoginContextCookieToken,
    csrfToken: LoginCsrfToken,
    email: String,
    password: String
  ): UIO[Option[LoginResult]]
  def authenticate(cookieToken: SessionCookieToken): UIO[Option[CurrentSession]]
  def resume(publicSessionId: PublicSessionId): UIO[Option[CurrentSession]]
  def logout(cookieToken: SessionCookieToken, csrfToken: LogoutCsrfToken): UIO[Boolean]

object AuthService:
  private val DemoEmail    = "alice@example.com"
  private val DemoPassword = "scalive"
  private val DemoUser     = AuthUser(DemoEmail, "Alice")

  private val RandomBytes  = 32
  private val secureRandom = new SecureRandom()

  final private case class LoginContextRecord(
    context: LoginContext,
    expiresAt: Instant,
    order: Long)

  final private case class SessionRecord(
    currentSession: CurrentSession,
    expiresAt: Instant,
    order: Long)

  final private case class State(
    loginContextsByCookieHash: Map[String, LoginContextRecord],
    sessionsByCookieHash: Map[String, SessionRecord],
    nextOrder: Long)

  private object State:
    val empty = State(Map.empty, Map.empty, 0L)

  val live: ULayer[AuthService] = layer(AuthServiceConfig.default)

  def live(config: AuthServiceConfig): ULayer[AuthService] = layer(config)

  private def layer(config: AuthServiceConfig): ULayer[AuthService] =
    ZLayer.fromZIO(
      Ref.make(State.empty).map { stateRef =>
        new AuthService:
          def beginLogin: UIO[LoginBootstrap] =
            for
              cookieToken <- randomToken.map(LoginContextCookieToken(_))
              publicId    <- randomToken.map(PublicLoginContextId(_))
              csrfToken   <- randomToken.map(LoginCsrfToken(_))
              now         <- Clock.instant
              context = LoginContext(publicId, csrfToken)
              _ <- stateRef.update { state =>
                     val pruned = prune(state, now)
                     val record = LoginContextRecord(
                       context,
                       expiresAt = now.plusMillis(config.loginContextTtl.toMillis),
                       order = pruned.nextOrder
                     )
                     pruned.copy(
                       loginContextsByCookieHash = insertLoginContext(
                         pruned.loginContextsByCookieHash,
                         hash(cookieToken.value),
                         record,
                         config.maxLoginContexts
                       ),
                       nextOrder = pruned.nextOrder + 1
                     )
                   }
            yield LoginBootstrap(cookieToken)

          def prepareLogin(
            cookieToken: LoginContextCookieToken
          ): UIO[Option[LoginContext]] =
            Clock.instant.flatMap { now =>
              stateRef.modify { state =>
                val pruned = prune(state, now)
                pruned.loginContextsByCookieHash.get(hash(cookieToken.value)).map(_.context) ->
                  pruned
              }
            }

          def resumeLogin(publicId: PublicLoginContextId): UIO[Option[LoginContext]] =
            Clock.instant.flatMap { now =>
              stateRef.modify { state =>
                val pruned = prune(state, now)
                pruned.loginContextsByCookieHash.values
                  .find(_.context.publicId == publicId)
                  .map(_.context) -> pruned
              }
            }

          def login(
            cookieToken: LoginContextCookieToken,
            csrfToken: LoginCsrfToken,
            email: String,
            password: String
          ): UIO[Option[LoginResult]] =
            Clock.instant.flatMap { now =>
              stateRef
                .modify { state =>
                  val pruned     = prune(state, now)
                  val cookieHash = hash(cookieToken.value)
                  val validCsrf  = pruned.loginContextsByCookieHash
                    .get(cookieHash).exists(record =>
                      constantTimeEquals(record.context.csrfToken.value, csrfToken.value)
                    )
                  validCsrf -> pruned.copy(
                    loginContextsByCookieHash = pruned.loginContextsByCookieHash - cookieHash
                  )
                }.flatMap { csrfWasValid =>
                  if csrfWasValid && validCredentials(email, password) then
                    createSession(stateRef, config)
                  else ZIO.none
                }
            }

          def authenticate(cookieToken: SessionCookieToken): UIO[Option[CurrentSession]] =
            Clock.instant.flatMap { now =>
              stateRef.modify { state =>
                val pruned = prune(state, now)
                pruned.sessionsByCookieHash.get(hash(cookieToken.value)).map(_.currentSession) ->
                  pruned
              }
            }

          def resume(publicSessionId: PublicSessionId): UIO[Option[CurrentSession]] =
            Clock.instant.flatMap { now =>
              stateRef.modify { state =>
                val pruned = prune(state, now)
                pruned.sessionsByCookieHash.values
                  .find(_.currentSession.publicSessionId == publicSessionId)
                  .map(_.currentSession) -> pruned
              }
            }

          def logout(
            cookieToken: SessionCookieToken,
            csrfToken: LogoutCsrfToken
          ): UIO[Boolean] =
            Clock.instant.flatMap { now =>
              stateRef.modify { state =>
                val pruned       = prune(state, now)
                val cookieHash   = hash(cookieToken.value)
                val validSession = pruned.sessionsByCookieHash
                  .get(cookieHash).exists(record =>
                    constantTimeEquals(
                      record.currentSession.logoutCsrfToken.value,
                      csrfToken.value
                    )
                  )

                if validSession then
                  true -> pruned.copy(
                    sessionsByCookieHash = pruned.sessionsByCookieHash - cookieHash
                  )
                else false -> pruned
              }
            }
      }
    )

  private def createSession(
    stateRef: Ref[State],
    config: AuthServiceConfig
  ): UIO[Option[LoginResult]] =
    for
      cookieToken     <- randomToken.map(SessionCookieToken(_))
      publicSessionId <- randomToken.map(PublicSessionId(_))
      logoutCsrfToken <- randomToken.map(LogoutCsrfToken(_))
      now             <- Clock.instant
      currentSession = CurrentSession(DemoUser, publicSessionId, logoutCsrfToken)
      _ <- stateRef.update { state =>
             val pruned = prune(state, now)
             val record = SessionRecord(
               currentSession,
               expiresAt = now.plusMillis(config.sessionTtl.toMillis),
               order = pruned.nextOrder
             )
             pruned.copy(
               sessionsByCookieHash = insertSession(
                 pruned.sessionsByCookieHash,
                 hash(cookieToken.value),
                 record,
                 config.maxSessions
               ),
               nextOrder = pruned.nextOrder + 1
             )
           }
    yield Some(LoginResult(cookieToken, currentSession))

  private def prune(state: State, now: Instant): State =
    state.copy(
      loginContextsByCookieHash = state.loginContextsByCookieHash.filter { case (_, record) =>
        record.expiresAt.isAfter(now)
      },
      sessionsByCookieHash = state.sessionsByCookieHash.filter { case (_, record) =>
        record.expiresAt.isAfter(now)
      }
    )

  private def insertLoginContext(
    contexts: Map[String, LoginContextRecord],
    cookieHash: String,
    record: LoginContextRecord,
    maxSize: Int
  ): Map[String, LoginContextRecord] =
    val withCapacity =
      if contexts.size >= maxSize then contexts - contexts.minBy(_._2.order)._1
      else contexts
    withCapacity.updated(cookieHash, record)

  private def insertSession(
    sessions: Map[String, SessionRecord],
    cookieHash: String,
    record: SessionRecord,
    maxSize: Int
  ): Map[String, SessionRecord] =
    val withCapacity =
      if sessions.size >= maxSize then sessions - sessions.minBy(_._2.order)._1
      else sessions
    withCapacity.updated(cookieHash, record)

  private def randomToken: UIO[String] =
    ZIO.succeed {
      val bytes = Array.ofDim[Byte](RandomBytes)
      secureRandom.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }

  private def validCredentials(email: String, password: String): Boolean =
    constantTimeEquals(email, DemoEmail) & constantTimeEquals(password, DemoPassword)

  private def hash(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding().encodeToString(digest)

  private def constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.getBytes(StandardCharsets.UTF_8),
      right.getBytes(StandardCharsets.UTF_8)
    )
end AuthService
