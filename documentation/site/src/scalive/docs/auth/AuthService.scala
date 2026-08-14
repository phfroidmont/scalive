package scalive.docs.auth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Base64

import zio.*
import zio.json.*

final case class SessionCookieToken(value: String)
final case class VisitorToken(value: String)
final case class PublicSessionId(value: String) derives JsonCodec

final case class AuthUser(email: String, name: String)
final case class CurrentSession(user: AuthUser, publicSessionId: PublicSessionId)
final case class AuthClaims(publicSessionId: PublicSessionId) derives JsonCodec
final case class LoginResult(cookieToken: SessionCookieToken, currentSession: CurrentSession)
final case class AuthRecordCounts(sessions: Int, visitors: Int)

enum LoginDecision:
  case Successful(result: LoginResult)
  case Invalid
  case RateLimited

  def toOption: Option[LoginResult] = this match
    case Successful(result) => Some(result)
    case _                  => None

final case class AuthServiceConfig(
  sessionTtl: Duration,
  maxSessions: Int,
  attemptWindow: Duration,
  maxAttempts: Int,
  maxVisitors: Int):
  require(sessionTtl.toMillis > 0, "sessionTtl must be positive")
  require(maxSessions > 0, "maxSessions must be positive")
  require(attemptWindow.toMillis > 0, "attemptWindow must be positive")
  require(maxAttempts > 0, "maxAttempts must be positive")
  require(maxVisitors > 0, "maxVisitors must be positive")

object AuthServiceConfig:
  val default = AuthServiceConfig(
    sessionTtl = 30.minutes,
    maxSessions = 1024,
    attemptWindow = 1.minute,
    maxAttempts = 5,
    maxVisitors = 2048
  )

trait AuthService:
  def login(visitor: VisitorToken, credentials: LoginCredentials): UIO[LoginDecision]
  def authenticate(cookieToken: SessionCookieToken): UIO[Option[CurrentSession]]
  def resume(publicSessionId: PublicSessionId): UIO[Option[CurrentSession]]
  def reset(visitor: VisitorToken, cookieToken: Option[SessionCookieToken]): UIO[Unit]
  private[auth] def recordCounts: UIO[AuthRecordCounts]

object AuthService:
  val DemoEmail        = "alice@example.com"
  val DemoPassword     = "scalive"
  private val DemoUser = AuthUser(DemoEmail, "Alice")

  private val RandomBytes  = 32
  private val secureRandom = new SecureRandom()

  sealed private trait OrderedRecord:
    def order: Long

  final private case class SessionRecord(
    currentSession: CurrentSession,
    expiresAt: Instant,
    order: Long)
      extends OrderedRecord

  final private case class AttemptRecord(count: Int, expiresAt: Instant, order: Long)
      extends OrderedRecord

  final private case class State(
    sessionsByCookieHash: Map[String, SessionRecord],
    attemptsByVisitorHash: Map[String, AttemptRecord],
    nextOrder: Long)

  private object State:
    val empty = State(Map.empty, Map.empty, 0L)

  val live: ULayer[AuthService] = live(AuthServiceConfig.default)

  def inMemory(config: AuthServiceConfig = AuthServiceConfig.default): AuthService =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ZIO.service[AuthService].provide(live(config)))
        .getOrThrowFiberFailure()
    }

  def live(config: AuthServiceConfig): ULayer[AuthService] =
    ZLayer.fromZIO(
      Ref.Synchronized.make(State.empty).map { stateRef =>
        new AuthService:
          def login(visitor: VisitorToken, credentials: LoginCredentials): UIO[LoginDecision] =
            Clock.instant.flatMap { now =>
              stateRef.modifyZIO { state =>
                val pruned      = prune(state, now)
                val visitorHash = hash(visitor.value)
                pruned.attemptsByVisitorHash.get(visitorHash) match
                  case Some(record) if record.count >= config.maxAttempts =>
                    ZIO.succeed(LoginDecision.RateLimited -> pruned)
                  case _ if validCredentials(credentials) =>
                    createSession(pruned, visitorHash, now, config)
                  case current =>
                    val attempts = current.fold(1)(_.count + 1)
                    val record   = AttemptRecord(
                      attempts,
                      now.plusMillis(config.attemptWindow.toMillis),
                      pruned.nextOrder
                    )
                    val updated = pruned.copy(
                      attemptsByVisitorHash = insertBounded(
                        pruned.attemptsByVisitorHash,
                        visitorHash,
                        record,
                        config.maxVisitors
                      ),
                      nextOrder = pruned.nextOrder + 1
                    )
                    ZIO.succeed(LoginDecision.Invalid -> updated)
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

          def reset(
            visitor: VisitorToken,
            cookieToken: Option[SessionCookieToken]
          ): UIO[Unit] =
            Clock.instant.flatMap { now =>
              stateRef.update { state =>
                val pruned = prune(state, now)
                pruned.copy(
                  sessionsByCookieHash = cookieToken.fold(pruned.sessionsByCookieHash)(token =>
                    pruned.sessionsByCookieHash - hash(token.value)
                  ),
                  attemptsByVisitorHash = pruned.attemptsByVisitorHash - hash(visitor.value)
                )
              }
            }

          private[auth] def recordCounts: UIO[AuthRecordCounts] =
            Clock.instant.flatMap { now =>
              stateRef.modify { state =>
                val pruned = prune(state, now)
                AuthRecordCounts(
                  pruned.sessionsByCookieHash.size,
                  pruned.attemptsByVisitorHash.size
                ) -> pruned
              }
            }
      }
    )

  private def createSession(
    state: State,
    visitorHash: String,
    now: Instant,
    config: AuthServiceConfig
  ): UIO[(LoginDecision, State)] =
    for
      cookieToken     <- randomToken.map(SessionCookieToken(_))
      publicSessionId <- randomToken.map(PublicSessionId(_))
      currentSession = CurrentSession(DemoUser, publicSessionId)
      record         = SessionRecord(
                 currentSession,
                 now.plusMillis(config.sessionTtl.toMillis),
                 state.nextOrder
               )
      updated = state.copy(
                  sessionsByCookieHash = insertBounded(
                    state.sessionsByCookieHash,
                    hash(cookieToken.value),
                    record,
                    config.maxSessions
                  ),
                  attemptsByVisitorHash = state.attemptsByVisitorHash - visitorHash,
                  nextOrder = state.nextOrder + 1
                )
    yield LoginDecision.Successful(LoginResult(cookieToken, currentSession)) -> updated

  private def prune(state: State, now: Instant): State =
    state.copy(
      sessionsByCookieHash =
        state.sessionsByCookieHash.filter((_, record) => record.expiresAt.isAfter(now)),
      attemptsByVisitorHash =
        state.attemptsByVisitorHash.filter((_, record) => record.expiresAt.isAfter(now))
    )

  private def insertBounded[A <: OrderedRecord](
    records: Map[String, A],
    key: String,
    value: A,
    maxSize: Int
  ): Map[String, A] =
    val replacing    = records.contains(key)
    val withCapacity =
      if !replacing && records.size >= maxSize then records - records.minBy(_._2.order)._1
      else records
    withCapacity.updated(key, value)

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
