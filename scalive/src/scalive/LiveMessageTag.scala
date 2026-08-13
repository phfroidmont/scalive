package scalive

import scala.reflect.ClassTag

/** Provides runtime message type evidence when mounting a [[LiveView]].
  *
  * Ordinary views derive this evidence from `ClassTag[Msg]`. Eventless views use `Nothing` as their
  * message type, for which Scala exposes `ClassTag.Nothing` but does not provide an implicit
  * `ClassTag[Nothing]`. This wrapper supplies that evidence without weakening the `ClassTag`
  * requirement for message-bearing views.
  *
  * @tparam Msg
  *   the LiveView message type represented by this evidence
  */
sealed trait LiveMessageTag[Msg]:
  private[scalive] def classTag: ClassTag[Msg]

/** Supplies [[LiveMessageTag]] evidence for eventless and message-bearing LiveViews. */
object LiveMessageTag:
  /** Provides the exact runtime `Nothing` tag required by eventless LiveViews. */
  given eventless: LiveMessageTag[Nothing] with
    private[scalive] val classTag = ClassTag.Nothing

  /** Derives LiveView message evidence from an available `ClassTag[Msg]`. */
  given [Msg](using tag: ClassTag[Msg]): LiveMessageTag[Msg] with
    private[scalive] val classTag = tag
