package scalive

import java.net.{Inet6Address, InetAddress, URI, URISyntaxException, UnknownHostException}
import java.util.Locale

/** An exact browser origin allowed to open a Scalive WebSocket.
  *
  * Origins contain an HTTP or HTTPS scheme, a canonical lowercase host, and an effective port.
  * Default ports are normalized, so `https://example.com` and `https://example.com:443` describe
  * the same origin.
  */
final class WebSocketOrigin private (
  /** The canonical `http` or `https` scheme. */
  val scheme: String,
  /** The canonical lowercase ASCII host, without IPv6 brackets. */
  val host: String,
  /** The effective port, including `80` or `443` when omitted from the serialized origin. */
  val port: Int):
  override def equals(other: Any): Boolean = other match
    case that: WebSocketOrigin =>
      scheme == that.scheme && host == that.host && port == that.port
    case _ => false

  override def hashCode(): Int =
    var result = scheme.hashCode
    result = 31 * result + host.hashCode
    31 * result + port

  override def toString: String =
    val authority = if host.contains(':') then s"[$host]" else host
    val default   = if scheme == "http" then 80 else 443
    if port == default then s"$scheme://$authority" else s"$scheme://$authority:$port"

/** Constructors and strict parsing for [[WebSocketOrigin]]. */
object WebSocketOrigin:
  /** A typed failure to construct or parse an exact WebSocket origin. */
  enum Error:
    /** The value is not a serialized origin. */
    case Malformed

    /** Only browser HTTP and HTTPS origins are supported. */
    case UnsupportedScheme(scheme: String)

    /** The host is absent or invalid. */
    case InvalidHost

    /** The port is outside the usable TCP port range. */
    case InvalidPort(port: Int)

    /** A human-readable description of the validation failure. */
    def message: String = this match
      case Malformed => "Expected an HTTP or HTTPS origin without a path, query, or fragment"
      case UnsupportedScheme(scheme) => s"WebSocket origins must use HTTP or HTTPS, got $scheme"
      case InvalidHost               => "WebSocket origins require a valid ASCII host"
      case InvalidPort(port) => s"WebSocket origin port must be between 1 and 65535, got $port"

  /** Exception thrown by the ergonomic HTTP and HTTPS constructors. */
  final class ValidationException(val error: Error) extends IllegalArgumentException(error.message)

  /** Creates an HTTP origin from trusted program input.
    *
    * @throws ValidationException
    *   when the host or port is invalid
    */
  def http(host: String, port: Int = 80): WebSocketOrigin =
    httpEither(host, port).fold(error => throw new ValidationException(error), identity)

  /** Creates an HTTPS origin from trusted program input.
    *
    * @throws ValidationException
    *   when the host or port is invalid
    */
  def https(host: String, port: Int = 443): WebSocketOrigin =
    httpsEither(host, port).fold(error => throw new ValidationException(error), identity)

  /** Creates an HTTP origin with dynamic host or port failures in the typed error channel. */
  def httpEither(host: String, port: Int = 80): Either[Error, WebSocketOrigin] =
    fromParts("http", host, port)

  /** Creates an HTTPS origin with dynamic host or port failures in the typed error channel. */
  def httpsEither(host: String, port: Int = 443): Either[Error, WebSocketOrigin] =
    fromParts("https", host, port)

  /** Strictly parses one serialized browser origin for deployment configuration or admission.
    *
    * User information, paths, queries, fragments, opaque origins, Unicode hosts, surrounding
    * whitespace, and non-HTTP schemes are rejected.
    */
  def parse(value: String): Either[Error, WebSocketOrigin] =
    if value == null || value.isEmpty || value != value.trim || !value.forall(_ <= 0x7f) then
      Left(Error.Malformed)
    else
      try
        val uri       = URI(value).parseServerAuthority()
        val schemeRaw = Option(uri.getScheme).getOrElse("")
        val scheme    = schemeRaw.toLowerCase(Locale.ROOT)
        val rawPath   = Option(uri.getRawPath).getOrElse("")
        val authority = Option(uri.getRawAuthority)
        val validForm =
          uri.isAbsolute && !uri.isOpaque &&
            authority.exists(_.nonEmpty) &&
            uri.getRawUserInfo == null && rawPath.isEmpty &&
            uri.getRawQuery == null && uri.getRawFragment == null

        if !validForm then Left(Error.Malformed)
        else if scheme != "http" && scheme != "https" then Left(Error.UnsupportedScheme(schemeRaw))
        else
          Option(uri.getHost) match
            case None          => Left(Error.InvalidHost)
            case Some(rawHost) =>
              canonicalHost(withoutIpv6Brackets(rawHost)) match
                case None       => Left(Error.InvalidHost)
                case Some(host) =>
                  val authorityHost     = if host.contains(':') then s"[$host]" else host
                  val expectedAuthority = uri.getPort match
                    case -1       => authorityHost
                    case explicit => s"$authorityHost:$explicit"
                  val port = uri.getPort match
                    case -1 if scheme == "http" => 80
                    case -1                     => 443
                    case explicit               => explicit
                  if !authority.exists(_.equalsIgnoreCase(expectedAuthority)) then
                    Left(Error.Malformed)
                  else if port < 1 || port > 65535 then Left(Error.InvalidPort(port))
                  else Right(new WebSocketOrigin(scheme, host, port))
      catch case _: IllegalArgumentException | _: URISyntaxException => Left(Error.Malformed)

  private def fromParts(
    scheme: String,
    host: String,
    port: Int
  ): Either[Error, WebSocketOrigin] =
    if port < 1 || port > 65535 then Left(Error.InvalidPort(port))
    else if host == null || host.isEmpty then Left(Error.InvalidHost)
    else
      canonicalHost(withoutIpv6Brackets(host)) match
        case None            => Left(Error.InvalidHost)
        case Some(canonical) =>
          val authority = if canonical.contains(':') then s"[$canonical]" else canonical
          parse(s"$scheme://$authority:$port").left.map {
            case Error.InvalidPort(invalid) => Error.InvalidPort(invalid)
            case _                          => Error.InvalidHost
          }

  private def canonicalHost(host: String): Option[String] =
    val lowercase = host.toLowerCase(Locale.ROOT)
    if lowercase.isEmpty || lowercase.contains('%') || !lowercase.forall(_ <= 0x7f) then None
    else if lowercase.contains(':') then canonicalIpv6(lowercase)
    else if lowercase.forall(character => character.isDigit || character == '.') then
      canonicalIpv4(lowercase)
    else Some(lowercase)

  private def canonicalIpv4(host: String): Option[String] =
    val parts = host.split("\\.", -1).toVector
    Option.when(
      parts.length == 4 &&
        parts.forall(part =>
          part.nonEmpty && part.forall(_.isDigit) &&
            (part == "0" || part.head != '0') &&
            part.toIntOption.exists(value => value >= 0 && value <= 255)
        )
    )(parts.map(_.toInt).mkString("."))

  private def canonicalIpv6(host: String): Option[String] =
    try
      val bytes = InetAddress.getByName(host) match
        case address: Inet6Address                   => Some(address.getAddress)
        case mapped if mapped.getAddress.length == 4 =>
          Some(Array.fill[Byte](10)(0) ++ Array(0xff.toByte, 0xff.toByte) ++ mapped.getAddress)
        case _ => None
      bytes.map(renderIpv6)
    catch case _: IllegalArgumentException | _: UnknownHostException => None

  private def renderIpv6(bytes: Array[Byte]): String =
    val groups = Vector.tabulate(8) { index =>
      ((bytes(index * 2) & 0xff) << 8) | (bytes(index * 2 + 1) & 0xff)
    }
    val (zeroStart, zeroLength) = longestZeroRun(groups)
    val rendered                = groups.map(Integer.toHexString)
    if zeroLength < 2 then rendered.mkString(":")
    else
      val left  = rendered.take(zeroStart).mkString(":")
      val right = rendered.drop(zeroStart + zeroLength).mkString(":")
      s"$left::$right"

  private def longestZeroRun(groups: Vector[Int]): (Int, Int) =
    var bestStart    = -1
    var bestLength   = 0
    var currentStart = -1
    var index        = 0
    while index <= groups.length do
      if index < groups.length && groups(index) == 0 then
        if currentStart == -1 then currentStart = index
      else if currentStart != -1 then
        val length = index - currentStart
        if length > bestLength then
          bestStart = currentStart
          bestLength = length
        currentStart = -1
      index += 1
    bestStart -> bestLength

  private def withoutIpv6Brackets(host: String): String =
    if host.length >= 2 && host.head == '[' && host.last == ']' then
      host.substring(1, host.length - 1)
    else host
end WebSocketOrigin
