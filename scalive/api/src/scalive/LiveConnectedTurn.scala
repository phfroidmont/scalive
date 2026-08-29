package scalive

import zio.http.URL
import zio.{IO, ZIO}

/** A controlled outcome that stops a connected LiveView turn before hooks, handlers, or rendering.
  *
  * A guard continues by succeeding with `Unit`. These failures leave the committed model unchanged.
  * Optional reasons are logged server-side and are not sent to the browser.
  */
enum LiveConnectedTurnFailure:
  /** Skips the turn without rendering or running after-render hooks. */
  case Halt

  /** Performs a full browser redirect to a typed Live route. */
  case Redirect(to: LiveLocation)

  /** Performs a full browser redirect to an unchecked URL.
    *
    * No same-origin or local-path validation is performed. Validate untrusted input before using
    * this escape hatch.
    */
  case RedirectUnsafe(to: URL)

  /** Closes the physical socket and every lifecycle sharing it. */
  case Disconnect(reason: Option[String])

  /** Performs a full HTTP reload of the URL governing the guarded turn.
    *
    * During patch acknowledgement this is the pending patch destination; otherwise it is the
    * lifecycle's current committed URL.
    */
  case Reload(reason: Option[String])

/** Constructors for controlled connected-turn outcomes. */
object LiveConnectedTurnFailure:
  /** Skips the turn without changing or rendering the model. */
  def halt: LiveConnectedTurnFailure = Halt

  /** Redirects to a typed Live route. */
  def redirect(to: LiveLocation): LiveConnectedTurnFailure = Redirect(to)

  /** Redirects to an unchecked URL without validating its origin or locality. */
  def redirectUnsafe(to: URL): LiveConnectedTurnFailure = RedirectUnsafe(to)

  /** Disconnects the physical socket without logging an application reason. */
  def disconnect: LiveConnectedTurnFailure = Disconnect(None)

  /** Disconnects the physical socket and logs `reason` server-side. */
  def disconnect(reason: String): LiveConnectedTurnFailure = Disconnect(Some(reason))

  /** Reloads the URL governing the guarded turn without logging an application reason. */
  def reload: LiveConnectedTurnFailure = Reload(None)

  /** Reloads the URL governing the guarded turn and logs `reason` server-side. */
  def reload(reason: String): LiveConnectedTurnFailure = Reload(Some(reason))

/** An ordered, model-independent connected-turn guard declaration. */
final private[scalive] case class LiveConnectedTurnGuard[-Ctx](
  run: Ctx => IO[LiveConnectedTurnFailure, Unit],
  isEmpty: Boolean):
  def andThen[Ctx1 <: Ctx](next: LiveConnectedTurnGuard[Ctx1]): LiveConnectedTurnGuard[Ctx1] =
    if isEmpty then next
    else if next.isEmpty then this
    else LiveConnectedTurnGuard(context => run(context) *> next.run(context))

  def contramap[Ctx1](project: Ctx1 => Ctx): LiveConnectedTurnGuard[Ctx1] =
    if isEmpty then LiveConnectedTurnGuard.empty
    else LiveConnectedTurnGuard(context => run(project(context)))

object LiveConnectedTurnGuard:
  def empty[Ctx]: LiveConnectedTurnGuard[Ctx] =
    new LiveConnectedTurnGuard(_ => ZIO.unit, isEmpty = true)

  def apply[Ctx](run: Ctx => IO[LiveConnectedTurnFailure, Unit]): LiveConnectedTurnGuard[Ctx] =
    new LiveConnectedTurnGuard(run, isEmpty = false)
