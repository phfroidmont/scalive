package scalive

import zio.*

trait TitleRuntime:
  def set(title: String): UIO[Unit]
  def drain: UIO[Option[String]]

object TitleRuntime:
  object Disabled extends TitleRuntime:
    def set(title: String): UIO[Unit] = ZIO.unit
    def drain: UIO[Option[String]]    = ZIO.none
