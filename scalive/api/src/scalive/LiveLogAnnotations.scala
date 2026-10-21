package scalive

import zio.*

/** The log annotations that mount aspects declare with `logAnnotated`, for one lifecycle.
  *
  * An aspect records its annotations in a fiber-local value as it produces its context, so they
  * follow `map`, `++` and aspect chains. Admission collects them and applies them around the
  * lifecycle, the page render, or the HTTP handler that the context is for.
  */
final private[scalive] class LiveLogAnnotations private (
  aspects: Vector[LiveLogAnnotations.Aspect]):
  def apply[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A] =
    aspects.foldLeft(effect)(_ @@ _)

private[scalive] object LiveLogAnnotations:
  type Aspect = ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any]

  val none: LiveLogAnnotations = LiveLogAnnotations(Vector.empty)

  private val recorded: FiberRef[Vector[Aspect]] =
    Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make(Vector.empty[Aspect]))

  def record(aspect: Aspect): UIO[Unit] = recorded.update(_ :+ aspect)

  /** Runs admission and returns its result with the annotations its aspects recorded. */
  def collect[R, E, A](admission: ZIO[R, E, A]): ZIO[R, E, (A, LiveLogAnnotations)] =
    recorded.locally(Vector.empty)(
      admission.flatMap(result =>
        recorded.get.map(aspects => result -> LiveLogAnnotations(aspects))
      )
    )
