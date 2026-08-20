package scalive.runtime.contracts

import zio.*

private[scalive] object RuntimeCleanup:
  /** Attempts every cleanup in order, then restores all accumulated defects. */
  def all(cleanups: Iterable[UIO[Unit]]): UIO[Unit] =
    ZIO.uninterruptible {
      ZIO.foreach(cleanups)(_.exit).flatMap { exits =>
        val failures = exits.collect { case Exit.Failure(cause) => cause }
        failures.reduceOption(_ ++ _).fold[UIO[Unit]](ZIO.unit)(ZIO.failCause(_))
      }
    }
