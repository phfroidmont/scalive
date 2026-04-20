package scalive

import zio.*

opaque type LiveIO[-R, +A] = RIO[R, A]

object LiveIO:
  def pure[A](value: A): LiveIO[Any, A] =
    ZIO.succeed(value)

  given pureConversion[R, A]: Conversion[A, LiveIO[R, A]] =
    value => ZIO.succeed(value)

  given effectConversion[R, A]: Conversion[RIO[R, A], LiveIO[R, A]] =
    effect => effect

  private[scalive] def toZIO[R, A](value: LiveIO[R, A]): RIO[R, A] = value
