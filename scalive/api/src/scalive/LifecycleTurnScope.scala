package scalive

import zio.Exit
import zio.Scope
import zio.URIO
import zio.ZIO
import zio.json.ast.Json

/** Describes one connected turn of a LiveView lifecycle to a [[LifecycleTurnScope]].
  *
  * It deliberately excludes models, messages, browser event payloads, credentials, and session
  * claims. The one input from the browser is its connect parameters, which `connectParam` reads.
  *
  * @param lifecycle
  *   The lifecycle that runs the turn.
  * @param turnId
  *   The ID of the turn, as in [[LifecycleEvent.TurnSucceeded]] and [[LifecycleEvent.TurnFailed]].
  * @param commandId
  *   The ID of the command that started the turn, as in those events, or `None` for the bootstrap
  *   turn and turns that Scalive starts itself.
  * @param kind
  *   The source of the work the turn processes.
  * @param target
  *   Whether the LiveView is the root LiveView of a page or one nested inside another.
  * @param route
  *   The path pattern of the page's route, such as `/users/{id}`, with a placeholder for each path
  *   parameter and no parameter values, so it suits a span name or a metric label. A nested
  *   LiveView reports the route of the page it is rendered in.
  * @param connectParams
  *   Every parameter the browser sent when the LiveView joined. Read one with `connectParam`.
  */
final case class LifecycleTurn(
  lifecycle: ConnectedLifecycleContext,
  turnId: Long,
  commandId: Option[Long],
  kind: LifecycleTurnKind,
  target: LifecycleJoinTarget,
  route: String
)(
  connectParams: Map[String, Json]):

  /** The value the browser sent for `param` when the LiveView joined.
    *
    * `None` when the browser did not send it or it does not decode as `A`. The value is untrusted:
    * use it to correlate, for example with a browser telemetry session, never as a security signal.
    */
  def connectParam[A](param: ConnectParam[A]): Option[A] =
    connectParams.get(param.name).flatMap(param.decode)

/** Sets up what should be active while each connected turn of a LiveView runs.
  *
  * Before a turn, Scalive runs `open` in a `Scope` that it owns, then runs the turn on the same
  * fiber, then closes the scope. So whatever `open` sets up with scoped operators, such as
  * `ZIO.logAnnotateScoped`, `ZIO.logSpanScoped`, `FiberRef.locallyScoped`, or the scoped span of a
  * tracing library, is visible to the turn's connected-turn guards, hooks, handlers, rendering and
  * commit, and to the fibers they start, and is gone before the next turn. A turn is the bootstrap
  * turn, which mounts the LiveView, or a turn for a browser event, message, async result,
  * subscription value, upload step or live patch, including the turns that Scalive starts itself.
  * The disconnected HTTP render is not a turn; ZIO HTTP middleware covers it.
  *
  * A turn scope never receives the turn, so it cannot interrupt, skip, repeat or detach it. A
  * finalizer registered with `ZIO.addFinalizerExit` receives the turn's outcome: success when the
  * turn succeeds or a connected-turn guard halts it, a failure with the [[LifecycleError]] that
  * [[LifecycleEvent.TurnFailed]] reports when it fails, and an interruption when the lifecycle
  * closes during the turn. A command that Scalive rejects, such as an event from a binding the page
  * never rendered, also closes the scope with success.
  *
  * If `open` dies, Scalive logs a warning, closes what it acquired, and runs the turn without it.
  * If a finalizer dies, Scalive logs a warning and the turn's outcome stands. The lifecycle waits
  * for `open` and for the finalizers and cannot interrupt them, so keep them fast and hand slow
  * work such as network export to a bounded queue.
  *
  * Install a turn scope with the `turnScope` argument of `ZioHttp.routes`.
  */
trait LifecycleTurnScope:
  /** Sets up what should be active during `turn`, releasing it with finalizers of the `Scope`. */
  def open(turn: LifecycleTurn): URIO[Scope, Unit]

  /** Opens this turn scope and then `that` one, and closes them in the reverse order.
    *
    * A defect in one does not stop the other from opening or closing.
    */
  final def andThen(that: LifecycleTurnScope): LifecycleTurnScope =
    LifecycleTurnScope.fromFunction(turn => safely(turn) *> that.safely(turn))

  /** Opens this turn scope in a child of the current scope, so that a defect in `open` closes only
    * what this turn scope acquired.
    */
  final private[scalive] def safely(turn: LifecycleTurn): URIO[Scope, Unit] =
    ZIO.uninterruptible {
      for
        parent <- ZIO.scope
        child  <- parent.fork
        _      <-
          child.extend(ZIO.suspendSucceed(open(turn))).catchAllCause { cause =>
            ZIO.logWarningCause(
              "Turn scope died while opening, so the turn runs without it",
              cause
            ) *>
              child
                .close(Exit.failCause(cause)).catchAllCause(LifecycleTurnScope.logFinalizerDefect)
          }
      yield ()
    }
end LifecycleTurnScope

/** Constructors for [[LifecycleTurnScope]] values. */
object LifecycleTurnScope:
  /** A turn scope that sets up nothing. */
  val none: LifecycleTurnScope = fromFunction(_ => ZIO.unit)

  /** Creates a turn scope that runs `open` before each turn. */
  def fromFunction(open: LifecycleTurn => URIO[Scope, Unit]): LifecycleTurnScope =
    val setUp = open
    new LifecycleTurnScope:
      def open(turn: LifecycleTurn): URIO[Scope, Unit] = setUp(turn)

  private[scalive] def logFinalizerDefect(cause: zio.Cause[Nothing]): zio.UIO[Unit] =
    ZIO.logWarningCause("Turn scope finalizer died; the turn's outcome stands", cause)
