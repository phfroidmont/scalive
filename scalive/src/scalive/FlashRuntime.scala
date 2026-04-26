package scalive

import zio.*

trait FlashRuntime:
  def put(kind: String, message: String): UIO[Unit]
  def clear(kind: String): UIO[Unit]
  def clearAll: UIO[Unit]
  def get(kind: String): UIO[Option[String]]
  def snapshot: UIO[Map[String, String]]

object FlashRuntime:
  object Disabled extends FlashRuntime:
    def put(kind: String, message: String): UIO[Unit] =
      val _ = (kind, message)
      ZIO.unit

    def clear(kind: String): UIO[Unit] =
      val _ = kind
      ZIO.unit

    def clearAll: UIO[Unit] = ZIO.unit

    def get(kind: String): UIO[Option[String]] =
      val _ = kind
      ZIO.none

    def snapshot: UIO[Map[String, String]] =
      ZIO.succeed(Map.empty)
