package scalive.docs

import java.net.URI
import java.nio.charset.StandardCharsets

final private[docs] case class PublicOrigin private (value: String):
  private val uri = URI.create(value)

  def absolute(path: String): String =
    require(path.startsWith("/"), s"Public path must start with '/': $path")
    s"$value$path"

  def isHttps: Boolean = uri.getScheme.equalsIgnoreCase("https")

  def isLoopback: Boolean =
    Set("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost.toLowerCase)

private[docs] object PublicOrigin:
  def from(value: String): Either[String, PublicOrigin] =
    val raw = value.trim
    try
      val uri       = URI.create(raw)
      val scheme    = Option(uri.getScheme).map(_.toLowerCase)
      val validPath = Option(uri.getRawPath).forall(path => path.isEmpty || path == "/")
      if !scheme.exists(Set("http", "https")) then
        Left("SCALIVE_PUBLIC_ORIGIN must use http or https.")
      else if Option(uri.getRawAuthority).forall(_.isEmpty) || Option(uri.getHost).forall(_.isEmpty)
      then Left("SCALIVE_PUBLIC_ORIGIN must include a host.")
      else if uri.getRawUserInfo != null then
        Left("SCALIVE_PUBLIC_ORIGIN must not include user information.")
      else if !validPath || uri.getRawQuery != null || uri.getRawFragment != null then
        Left("SCALIVE_PUBLIC_ORIGIN must be an origin without a path, query, or fragment.")
      else Right(PublicOrigin(raw.stripSuffix("/")))
    catch case _: IllegalArgumentException => Left("SCALIVE_PUBLIC_ORIGIN must be a valid URI.")

final private[docs] case class DocumentationConfig(
  serverPort: Int,
  publicOrigin: PublicOrigin,
  signingSecret: String):
  def secureCookie: Boolean = publicOrigin.isHttps

  override def toString: String =
    s"DocumentationConfig(serverPort=$serverPort, publicOrigin=$publicOrigin, signingSecret=<redacted>)"

private[docs] object DocumentationConfig:
  val ServerPortVariable    = "SCALIVE_SERVER_PORT"
  val PublicOriginVariable  = "SCALIVE_PUBLIC_ORIGIN"
  val SigningSecretVariable = "SCALIVE_TOKEN_SECRET"
  val DefaultPort           = 8080

  private val LocalDevelopmentSecret = "local-development-secret-change-me"

  def fromEnvironment(environment: Map[String, String]): Either[String, DocumentationConfig] =
    for
      port   <- serverPort(environment.get(ServerPortVariable))
      origin <- environment
                  .get(PublicOriginVariable)
                  .fold(PublicOrigin.from(s"http://localhost:$port"))(PublicOrigin.from)
      secret <- signingSecret(environment.get(SigningSecretVariable), origin)
    yield DocumentationConfig(port, origin, secret)

  private def serverPort(value: Option[String]): Either[String, Int] = value match
    case None      => Right(DefaultPort)
    case Some(raw) =>
      raw.trim.toIntOption
        .filter(port => port >= 1 && port <= 65535).toRight(
          s"$ServerPortVariable must be an integer between 1 and 65535."
        )

  private def signingSecret(
    value: Option[String],
    origin: PublicOrigin
  ): Either[String, String] =
    value match
      case Some(secret) if secret.getBytes(StandardCharsets.UTF_8).length >= 32 => Right(secret)
      case Some(secret)                                                         =>
        Left(
          s"$SigningSecretVariable must contain at least 32 UTF-8 bytes; got ${secret.getBytes(StandardCharsets.UTF_8).length}."
        )
      case None if origin.isLoopback => Right(LocalDevelopmentSecret)
      case None => Left(s"$SigningSecretVariable is required for a public origin.")
end DocumentationConfig
