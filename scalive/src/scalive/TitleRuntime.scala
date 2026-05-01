package scalive

import zio.*

private[scalive] trait TitleRuntime:
  def set(title: String): UIO[Unit]
  def drain: UIO[Option[String]]

private[scalive] object TitleRuntime:
  object Disabled extends TitleRuntime:
    def set(title: String): UIO[Unit] = ZIO.unit
    def drain: UIO[Option[String]]    = ZIO.none
