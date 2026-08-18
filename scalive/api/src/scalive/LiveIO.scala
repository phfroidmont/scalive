package scalive

import scala.language.implicitConversions

import zio.*

/** The effect type returned by LiveView and LiveComponent lifecycle callbacks.
  *
  * `LiveIO[A]` is a transparent alias for `zio.Task[A]`: it requires no environment, may fail with
  * a `Throwable`, and produces an `A`. Unhandled failures propagate to the active lifecycle.
  */
type LiveIO[+A] = Task[A]

/** Constructors and optional syntax for [[LiveIO]]. */
object LiveIO:
  /** Creates a successful [[LiveIO]] containing `value`. */
  def succeed[A](value: A): LiveIO[A] =
    ZIO.succeed(value)

  /** Creates a [[LiveIO]] that fails with `error`. */
  def fail[A](error: Throwable): LiveIO[A] =
    ZIO.fail(error)

  /** Converts a plain value to a successful [[LiveIO]].
    *
    * Import `scalive.LiveIO.given` to opt into returning plain model values from lifecycle methods.
    */
  given [A]: Conversion[A, LiveIO[A]] =
    ZIO.succeed(_)
