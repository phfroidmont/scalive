package scalive

import zio.*
import zio.json.*

private[scalive] trait FlashRuntime:
  def put(kind: String, message: String): UIO[Unit]
  def clear(kind: String): UIO[Unit]
  def clearAll: UIO[Unit]
  def get(kind: String): UIO[Option[String]]
  def snapshot: UIO[Map[String, String]]

private[scalive] object FlashRuntime:
  object Disabled extends FlashRuntime:
    def put(kind: String, message: String): UIO[Unit] =
      ZIO.unit

    def clear(kind: String): UIO[Unit] =
      ZIO.unit

    def clearAll: UIO[Unit] = ZIO.unit

    def get(kind: String): UIO[Option[String]] =
      ZIO.none

    def snapshot: UIO[Map[String, String]] =
      ZIO.succeed(Map.empty)

private[scalive] object FlashToken:
  val CookieName = "__phoenix_flash__"

  def encode(config: TokenConfig, values: Map[String, String]): Option[String] =
    Option.when(values.nonEmpty)(Token.sign(config.secret, "flash", values))

  def decode(config: TokenConfig, token: String): Option[Map[String, String]] =
    Token
      .verify[Map[String, String]](config.secret, token, config.maxAge)
      .toOption
      .map { case (_, values) => values }
