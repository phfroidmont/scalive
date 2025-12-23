package scalive

import zio.*

private def normalize[A](value: A | RIO[LiveContext, A], ctx: LiveContext): Task[A] =
  value match
    case t: ZIO[LiveContext, Throwable, A] @unchecked => t.provide(ZLayer.succeed(ctx))
    case v                                            => ZIO.succeed(v.asInstanceOf[A])
