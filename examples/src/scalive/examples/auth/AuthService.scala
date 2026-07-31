package scalive.examples.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

import zio.*
import zio.json.*

final case class SessionCookieToken(value: String)
final case class PublicSessionId(value: String) derives JsonCodec

final case class AuthUser(email: String, name: String)

final case class CurrentSession(
  user: AuthUser,
  publicSessionId: PublicSessionId)

final case class AuthClaims(publicSessionId: PublicSessionId) derives JsonCodec

final case class LoginResult(cookieToken: SessionCookieToken, currentSession: CurrentSession)

final case class AuthServiceConfig(sessionTtl: Duration, maxSessions: Int):
  require(sessionTtl.toMillis > 0, "sessionTtl must be positive")
  require(maxSessions > 0, "maxSessions must be positive")

object AuthServiceConfig:
  val DefaultSessionTtl  = 30.minutes
  val DefaultMaxSessions = 1024

  val default = AuthServiceConfig(
    sessionTtl = DefaultSessionTtl,
    maxSessions = DefaultMaxSessions
  )

trait AuthService:
  def login(credentials: LoginCredentials): UIO[Option[LoginResult]]
  def authenticate(cookieToken: SessionCookieToken): UIO[Option[CurrentSession]]
  def resume(publicSessionId: PublicSessionId): UIO[Option[CurrentSession]]
  def logout(cookieToken: SessionCookieToken): UIO[Unit]

object AuthService:
  private val DemoEmail    = "alice@example.com"
  private val DemoPassword = "scalive"
  private val DemoUser     = AuthUser(DemoEmail, "Alice")

  private val RandomBytes  = 32
  private val secureRandom = new SecureRandom()

  final private case class SessionRecord(
    currentSession: CurrentSession,
    expiresAt: Instant,
    order: Long)

  final private case class State(
    sessionsByCookieHash: Map[String, SessionRecord],
    nextOrder: Long)

  private object State:
    val empty = State(Map.empty, 0L)

  val live: ULayer[AuthService] = layer(AuthServiceConfig.default)

  def live(config: AuthServiceConfig): ULayer[AuthService] = layer(config)

  private def layer(config: AuthServiceConfig): ULayer[AuthService] =
    ZLayer.fromZIO(
      Ref.make(State.empty).map { stateRef =>
        new AuthService:
          def login(credentials: LoginCredentials): UIO[Option[LoginResult]] =
            if validCredentials(credentials) then createSession(stateRef, config)
            else ZIO.none

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

          def logout(cookieToken: SessionCookieToken): UIO[Unit] =
            Clock.instant.flatMap { now =>
              stateRef.update { state =>
                val pruned     = prune(state, now)
                val cookieHash = hash(cookieToken.value)
                pruned.copy(
                  sessionsByCookieHash = pruned.sessionsByCookieHash - cookieHash
                )
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
      now             <- Clock.instant
      currentSession = CurrentSession(DemoUser, publicSessionId)
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
      sessionsByCookieHash = state.sessionsByCookieHash.filter { case (_, record) =>
        record.expiresAt.isAfter(now)
      }
    )

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

  private def validCredentials(credentials: LoginCredentials): Boolean =
    constantTimeEquals(credentials.email, DemoEmail) &
      constantTimeEquals(credentials.password, DemoPassword)

  private def hash(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding().encodeToString(digest)

  private def constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.getBytes(StandardCharsets.UTF_8),
      right.getBytes(StandardCharsets.UTF_8)
    )
end AuthService
