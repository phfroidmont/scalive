package scalive

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

/** A read-only value evaluated by a signal-backed LiveView graph.
  *
  * Signals are created by the runtime and may only be transformed with pure functions. They do not
  * expose sampling, mutation, subscriptions, or effects. A transformation can be skipped when its
  * dependencies have not changed, so observable side effects inside `map` or `zip` are invalid.
  */
sealed trait Signal[+A]:
  private[scalive] def scope: SignalScope

  /** Derives a signal by applying `f` when this signal's revision changes. */
  final def map[B](f: A => B): Signal[B] =
    new Signal.Mapped(this, f)

  /** Combines two signals whose render scopes are related by ancestry. */
  final def zip[B](that: Signal[B]): Signal[(A, B)] =
    SignalScope
      .narrowest(scope, that.scope)
      .fold[Signal[(A, B)]](
        throw new IllegalArgumentException("signals from sibling render scopes cannot be combined")
      )(combinedScope => new Signal.Zipped(this, that, combinedScope))

object Signal:
  final private[scalive] class Source[A](val scope: SignalScope) extends Signal[A]

  final private[scalive] class Mapped[A, B](
    val parent: Signal[A],
    val f: A => B)
      extends Signal[B]:
    val scope: SignalScope = parent.scope

  final private[scalive] class Zipped[A, B](
    val left: Signal[A],
    val right: Signal[B],
    val scope: SignalScope)
      extends Signal[(A, B)]

  private[scalive] def source[A](scope: SignalScope): Source[A] =
    new Source(scope)

  private[scalive] def evaluate[A](
    signal: Signal[A],
    transaction: SignalEvaluation.Transaction
  ): SignalSample[A] =
    signal match
      case source: Source[?] =>
        transaction.evaluateSource(source).asInstanceOf[SignalSample[A]]
      case mapped: Mapped[?, ?] =>
        transaction.evaluateMapped(mapped).asInstanceOf[SignalSample[A]]
      case zipped: Zipped[?, ?] =>
        transaction.evaluateZipped(zipped).asInstanceOf[SignalSample[A]]

  extension (condition: Signal[Boolean])
    /** Activates `content` while this signal is true. */
    def when[Msg](content: => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> content))

    /** Selects between two signal-backed branches. Both branches are constructed exactly once. */
    def choose[Msg](
      whenTrue: => HtmlElement[Msg],
      whenFalse: => HtmlElement[Msg]
    ): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> whenTrue, false -> whenFalse))

    /** Selects between two signal-backed wrapper-free modifiers. */
    def chooseMod[Msg](whenTrue: => Mod[Msg], whenFalse: => Mod[Msg]): Mod[Msg] =
      Mod.Content.SignalModChoice(condition, Vector(true -> whenTrue, false -> whenFalse))

  extension [A](value: Signal[Option[A]])
    /** Projects the present value into a signal-backed child scope, or emits no content for `None`.
      */
    def option[Msg](project: Signal[A] => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalOption(value, project)

  extension [A](value: Signal[A])
    /** Selects one preconstructed branch by Scala equality, or emits no content when unmatched. */
    def choose[Msg](branches: (A, HtmlElement[Msg])*): Mod[Msg] =
      Mod.Content.SignalChoice(value, branches.toVector)

    /** Selects one preconstructed wrapper-free modifier by Scala equality. */
    def chooseMod[Msg](branches: (A, Mod[Msg])*): Mod[Msg] =
      Mod.Content.SignalModChoice(value, branches.toVector)
end Signal

/** Identifies one view-graph scope and its ownership relationship to nested scopes. */
final private[scalive] class SignalScope private (
  val id: Long,
  val parent: Option[SignalScope]):

  @volatile private var disposed = false

  def child(): SignalScope =
    requireActive()
    SignalScope.create(Some(this))

  def dispose(): Unit = disposed = true

  def isDisposed: Boolean = disposed || parent.exists(_.isDisposed)

  /** Validates that `signal` is owned by this scope or one of its ancestors. */
  def validate(signal: Signal[?]): Either[String, Unit] =
    if isDisposed then Left(s"render scope $id has been disposed")
    else if signal.scope.isDisposed then Left(s"signal scope ${signal.scope.id} has been disposed")
    else
      Either.cond(
        signal.scope.isAncestorOf(this),
        (),
        s"signal scope ${signal.scope.id} is not visible from render scope $id"
      )

  private[scalive] def requireActive(): Unit =
    if isDisposed then throw new IllegalStateException(s"signal scope $id has been disposed")

  private[scalive] def isAncestorOf(descendant: SignalScope): Boolean =
    Iterator
      .iterate(Option(descendant))(_.flatMap(_.parent))
      .takeWhile(_.nonEmpty)
      .flatten
      .contains(this)
end SignalScope

private[scalive] object SignalScope:
  private val nextId = new AtomicLong(0L)

  def root(): SignalScope = create(None)

  private def create(parent: Option[SignalScope]): SignalScope =
    new SignalScope(nextId.incrementAndGet(), parent)

  private[scalive] def narrowest(
    left: SignalScope,
    right: SignalScope
  ): Option[SignalScope] =
    if left.isAncestorOf(right) then Some(right)
    else if right.isAncestorOf(left) then Some(left)
    else None

/** One cached signal value and the revision at which that value last changed. */
final private[scalive] case class SignalSample[+A](
  value: A,
  revision: Long,
  dependencyRevisions: Vector[Long])

/** Immutable signal samples retained by a successfully committed render operation. */
final private[scalive] case class SignalEvaluation private (
  revision: Long,
  samples: Map[Signal[?], SignalSample[Any]])

private[scalive] object SignalEvaluation:
  val empty: SignalEvaluation = SignalEvaluation(0L, Map.empty)

  def begin(
    previous: SignalEvaluation,
    revision: Long,
    sources: Map[Signal.Source[?], Any]
  ): Transaction =
    require(revision > previous.revision, "signal transaction revisions must increase")
    Transaction(previous, revision, sources)

  final class Transaction private[SignalEvaluation] (
    previous: SignalEvaluation,
    revision: Long,
    sources: Map[Signal.Source[?], Any]):

    private val evaluated       = mutable.HashMap.empty[Signal[?], SignalSample[Any]]
    private val sourceValues    = mutable.HashMap.from(sources)
    private val commitActions   = mutable.ArrayBuffer.empty[() => Unit]
    private val rollbackActions = mutable.ArrayBuffer.empty[() => Unit]
    private val discardedScopes = mutable.ArrayBuffer.empty[SignalScope]
    private var completed       = false

    def sample[A](signal: Signal[A]): SignalSample[A] =
      signal.scope.requireActive()
      evaluated
        .getOrElseUpdate(signal, Signal.evaluate(signal, this).asInstanceOf[SignalSample[Any]])
        .asInstanceOf[SignalSample[A]]

    def commit(): SignalEvaluation =
      require(!completed, "signal transaction has already completed")
      val result = candidateEvaluation
      completeCommit()
      result

    private[scalive] def deferCommitTo(parent: Transaction): SignalEvaluation =
      require(!completed, "signal transaction has already completed")
      val result = candidateEvaluation
      parent.onCommit(completeCommit())
      parent.onRollback(rollback())
      result

    private def candidateEvaluation: SignalEvaluation =
      SignalEvaluation(
        revision,
        (previous.samples ++ evaluated).filterNot { case (signal, _) =>
          signal.scope.isDisposed || discardedScopes.exists(_.isAncestorOf(signal.scope))
        }
      )

    private def completeCommit(): Unit =
      require(!completed, "signal transaction has already completed")
      completed = true
      commitActions.foreach(_())

    private[scalive] def onCommit(action: => Unit): Unit =
      commitActions += (() => action)

    private[scalive] def onRollback(action: => Unit): Unit =
      rollbackActions += (() => action)

    private[scalive] def discardScopeOnCommit(scope: SignalScope): Unit =
      discardedScopes += scope

    private[scalive] def rollback(): Unit =
      if !completed then
        completed = true
        rollbackActions.reverseIterator.foreach(_())

    private[scalive] def setSource[A](source: Signal.Source[A], value: A): Unit =
      sourceValues.update(source, value)

    private[scalive] def evaluateSource(source: Signal.Source[?]): SignalSample[Any] =
      val value = sourceValues.getOrElse(
        source,
        throw new IllegalStateException(s"signal source in scope ${source.scope.id} has no value")
      )
      changedSample(source, value, Vector.empty)

    private[scalive] def evaluateMapped(mapped: Signal.Mapped[?, ?]): SignalSample[Any] =
      val parentSample = sample(mapped.parent)
      val dependencies = Vector(parentSample.revision)
      previous.samples.get(mapped) match
        case Some(cached) if cached.dependencyRevisions == dependencies => cached
        case _                                                          =>
          val f     = mapped.f.asInstanceOf[Any => Any]
          val value = f(parentSample.value)
          changedSample(mapped, value, dependencies)

    private[scalive] def evaluateZipped(zipped: Signal.Zipped[?, ?]): SignalSample[Any] =
      val leftSample   = sample(zipped.left)
      val rightSample  = sample(zipped.right)
      val dependencies = Vector(leftSample.revision, rightSample.revision)
      previous.samples.get(zipped) match
        case Some(cached) if cached.dependencyRevisions == dependencies => cached
        case _                                                          =>
          changedSample(zipped, (leftSample.value, rightSample.value), dependencies)

    private def changedSample(
      signal: Signal[?],
      value: Any,
      dependencyRevisions: Vector[Long]
    ): SignalSample[Any] =
      previous.samples.get(signal) match
        case Some(cached) if cached.value == value =>
          cached.copy(dependencyRevisions = dependencyRevisions)
        case _ => SignalSample(value, revision, dependencyRevisions)
  end Transaction
end SignalEvaluation
