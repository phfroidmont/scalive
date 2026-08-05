package scalive.docs

import java.net.URI

final private[docs] case class PublicOrigin private (value: String):
  def absolute(path: String): String =
    require(path.startsWith("/"), s"Public path must start with '/': $path")
    s"$value$path"

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
  publicOrigin: PublicOrigin)

private[docs] object DocumentationConfig:
  val ServerPortVariable   = "SCALIVE_SERVER_PORT"
  val PublicOriginVariable = "SCALIVE_PUBLIC_ORIGIN"
  val DefaultPort          = 8080

  def fromEnvironment(environment: Map[String, String]): Either[String, DocumentationConfig] =
    for
      port   <- serverPort(environment.get(ServerPortVariable))
      origin <- environment
                  .get(PublicOriginVariable)
                  .fold(PublicOrigin.from(s"http://localhost:$port"))(PublicOrigin.from)
    yield DocumentationConfig(port, origin)

  private def serverPort(value: Option[String]): Either[String, Int] = value match
    case None      => Right(DefaultPort)
    case Some(raw) =>
      raw.trim.toIntOption
        .filter(port => port >= 1 && port <= 65535).toRight(
          s"$ServerPortVariable must be an integer between 1 and 65535."
        )
