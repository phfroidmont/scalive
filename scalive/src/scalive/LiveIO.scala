package scalive

import scala.language.implicitConversions

import zio.*

type LiveIO[+A] = Task[A]

object LiveIO:
  def succeed[A](value: A): LiveIO[A] =
    ZIO.succeed(value)

  def fail[A](error: Throwable): LiveIO[A] =
    ZIO.fail(error)

  given [A]: Conversion[A, LiveIO[A]] =
    ZIO.succeed(_)
