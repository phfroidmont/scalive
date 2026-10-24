package scalive.render

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ArrayDeque
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scalive.Signal

final private[render] class SignalScope private (
  val id: Long,
  val parent: Option[SignalScope]):
  private val closed = AtomicBoolean(false)

  /** How many collections enclose this scope: 0 for a program, 1 for a row of a top-level one. */
  val depth: Int = parent.fold(0)(_.depth + 1)

  def child(): Either[RenderError, SignalScope] =
    if isClosed then Left(RenderError.SignalScopeViolation(s"signal scope $id is closed"))
    else Right(SignalScope.create(Some(this)))

  def close(): Unit = closed.set(true)

  def isClosed: Boolean = closed.get() || parent.exists(_.isClosed)

  def validate(signal: Signal[?]): Either[RenderError, Unit] =
    SignalEvaluation.scopeOf(signal).flatMap { scope =>
      if isClosed then Left(RenderError.SignalScopeViolation(s"signal scope $id is closed"))
      else
        scope match
          // A signal built only from constants is visible from every scope.
          case None                          => Right(())
          case Some(owner) if owner.isClosed =>
            Left(RenderError.SignalScopeViolation(s"signal scope ${owner.id} is closed"))
          case Some(owner) if owner.isAncestorOf(this) => Right(())
          case Some(owner)                             =>
            Left(
              RenderError.SignalScopeViolation(
                s"signal scope ${owner.id} is not visible from render scope $id"
              )
            )
    }

  private def isAncestorOf(descendant: SignalScope): Boolean =
    Iterator
      .iterate(Option(descendant))(_.flatMap(_.parent))
      .takeWhile(_.nonEmpty)
      .flatten
      .contains(this)
end SignalScope

private[render] object SignalScope:
  private val nextId = AtomicLong(0L)

  def root(): SignalScope = create(None)

  private def create(parent: Option[SignalScope]): SignalScope =
    val id = nextId.incrementAndGet()
    if id <= 0L then throw RenderError.IdentityExhausted("signal scope identity")
    new SignalScope(id, parent)

  def narrowest(left: SignalScope, right: SignalScope): Option[SignalScope] =
    if left.isAncestorOf(right) then Some(right)
    else if right.isAncestorOf(left) then Some(left)
    else None

final private[render] class SignalSource[A](val scope: SignalScope)

/** One sampled signal value and the revision at which that exact value last changed. */
final case class SignalSample[+A] private[render] (
  value: A,
  revision: RenderRevision,
  private[render] val dependencyRevisions: Vector[RenderRevision])

/** Immutable signal samples retained by a successful render.
  *
  * Evaluation is identity-keyed and samples each transformation at most once per candidate
  * revision. A sample keeps its revision when exact Scala equality establishes that its value is
  * unchanged.
  */
final case class SignalEvaluation private[render] (
  revision: RenderRevision,
  private[render] val samples: SignalEvaluation.SignalCache)

object SignalEvaluation:
  val empty: SignalEvaluation = SignalEvaluation(RenderRevision.initial, SignalCache.empty)

  private[render] def begin[A](
    previous: SignalEvaluation,
    revision: RenderRevision,
    source: Signal[A],
    value: A
  ): Transaction =
    Transaction(previous, revision, PackedSignalValue[[Value] =>> Value, A](source, value))

  /** The scope that owns `signal`, or `None` when it is built only from constants. */
  private[render] def scopeOf(signal: Signal[?]): Either[RenderError, Option[SignalScope]] =
    signal.expression match
      case Signal.Expression.Source(source: SignalSource[?]) => return Right(Some(source.scope))
      case _                                                 => ()

    val scopes = IdentityHashMap[Signal[?], Option[SignalScope]]()
    val stack  = ArrayDeque[ScopeFrame](ScopeFrame.Visit(signal))

    while stack.nonEmpty do
      stack.removeLast() match
        case ScopeFrame.Visit(current) if !scopes.containsKey(current) =>
          current.expression match
            case Signal.Expression.Source(identity) =>
              identity match
                case source: SignalSource[?] => scopes.put(current, Some(source.scope)): Unit
                case _ => return Left(RenderError.SignalScopeViolation("unknown signal source"))
            case Signal.Expression.Constant(_)       => scopes.put(current, None): Unit
            case Signal.Expression.Mapped(parent, _) =>
              stack.append(ScopeFrame.CompleteMapped(current, parent))
              stack.append(ScopeFrame.Visit(parent))
            case Signal.Expression.Zipped(left, right) =>
              stack.append(ScopeFrame.CompleteZipped(current, left, right))
              stack.append(ScopeFrame.Visit(right))
              stack.append(ScopeFrame.Visit(left))
        case ScopeFrame.Visit(_)                        => ()
        case ScopeFrame.CompleteMapped(current, parent) =>
          scopes.put(current, scopes.get(parent)): Unit
        case ScopeFrame.CompleteZipped(current, left, right) =>
          (scopes.get(left), scopes.get(right)) match
            case (None, rightScope)                  => scopes.put(current, rightScope): Unit
            case (leftScope, None)                   => scopes.put(current, leftScope): Unit
            case (Some(leftScope), Some(rightScope)) =>
              SignalScope.narrowest(leftScope, rightScope) match
                case Some(scope) => scopes.put(current, Some(scope)): Unit
                case None        =>
                  return Left(
                    RenderError.SignalScopeViolation(
                      s"signals from sibling scopes ${leftScope.id} and ${rightScope.id} cannot be combined"
                    )
                  )
    end while

    Right(scopes.get(signal))
  end scopeOf

  sealed private trait ScopeFrame

  private object ScopeFrame:
    final case class Visit(signal: Signal[?])                             extends ScopeFrame
    final case class CompleteMapped(signal: Signal[?], parent: Signal[?]) extends ScopeFrame
    final case class CompleteZipped(signal: Signal[?], left: Signal[?], right: Signal[?])
        extends ScopeFrame

  /** What evaluating the rows of one collection read, kept so that the collection can be reused
    * without evaluating its rows again while none of it changes.
    *
    * @param samples
    *   the samples of the signals of the rows themselves
    * @param outside
    *   the signals from outside the rows that the rows read, such as a selection the rows compare
    *   their item with
    * @param untracked
    *   whether the rows render something no signal tracks: a component, a nested LiveView, a
    *   stream, or a flash
    */
  final private[render] case class RowsEvaluation(
    samples: SignalCache,
    outside: Vector[Signal[?]],
    untracked: Boolean)

  private[render] object RowsEvaluation:
    val empty: RowsEvaluation = RowsEvaluation(SignalCache.empty, Vector.empty, untracked = false)

  final private[render] class Transaction private (
    previous: SignalEvaluation,
    revision: RenderRevision,
    source: PackedSignalValue[[Value] =>> Value]):
    private val evaluated = IdentityHashMap[Signal[?], Evaluated]()
    private val sources   = IdentityHashMap[Signal[?], PackedSignalValue[[Value] =>> Value]]()
    sources.put(source.signal, source)

    // The program, then the rows of each collection being evaluated, outermost first. The signals
    // of a row scope of depth n belong to level n.
    private val levels = ArrayBuffer(Level(previous.samples))

    /** Supplies one child-scope source for this candidate only. */
    def bindSource[A](signal: Signal[A], value: A): Either[RenderError, Unit] =
      signal.expression match
        case Signal.Expression.Source(_) =>
          scopeOf(signal).map(_.get).flatMap { owner =>
            if owner.isClosed then
              Left(RenderError.SignalScopeViolation(s"signal scope ${owner.id} is closed"))
            else if sources.containsKey(signal) then
              Left(RenderError.SignalScopeViolation("signal source was bound more than once"))
            else
              sources.put(signal, PackedSignalValue[[Value] =>> Value, A](signal, value))
              Right(())
          }
        case _ =>
          Left(RenderError.SignalScopeViolation("only source signals can be candidate-bound"))

    /** Samples `signal` for the template being evaluated at the current level. */
    def sample[A](signal: Signal[A]): Either[RenderError, SignalSample[A]] =
      val sampled = evaluatedSample(signal) match
        case Some(sample) => Right(sample)
        case None         =>
          evaluate(signal).flatMap(_ =>
            evaluatedSample(signal).toRight(RenderError.MissingSignalSource())
          )
      sampled.map { sample =>
        recordOutside(signal, evaluated.get(signal).depth, levels.length - 1)
        sample
      }

    /** Whether `sample` holds the value it held in the previous evaluation. */
    def unchanged(sample: SignalSample[?]): Boolean = sample.revision != revision

    /** Starts evaluating the rows of a collection, which read their previous samples from
      * `previous`.
      */
    def enterRows(previous: RowsEvaluation): Unit = levels += Level(previous.samples)

    /** Ends evaluating the rows that the last `enterRows` started. */
    def exitRows(): RowsEvaluation =
      val level = levels.remove(levels.length - 1)
      RowsEvaluation(
        SignalCache.empty.appended(level.placed),
        level.outside.values().asScala.toVector,
        level.untracked
      )

    /** Records that the rows being evaluated render something no signal tracks. */
    def markUntracked(): Unit = levels.iterator.drop(1).foreach(_.untracked = true)

    def result: SignalEvaluation =
      SignalEvaluation(revision, SignalCache.empty.appended(levels.head.placed))

    private def evaluate[A](signal: Signal[A]): Either[RenderError, Unit] =
      val stack = ArrayDeque[EvaluationFrame](EvaluationFrame.Visit(signal))

      while stack.nonEmpty do
        stack.removeLast() match
          case EvaluationFrame.Visit(current) if !evaluated.containsKey(current) =>
            current.expression match
              case Signal.Expression.Source(_) =>
                evaluateSource(current) match
                  case Left(error) => return Left(error)
                  case Right(_)    => ()
              case Signal.Expression.Constant(value) =>
                putEvaluated(current, changedSample(current, value, Vector.empty, -1), -1)
              case mapped @ Signal.Expression.Mapped(parent, f) =>
                stack.append(EvaluationFrame.CompleteMapped(mapped, parent, f))
                stack.append(EvaluationFrame.Visit(parent))
              case zipped @ Signal.Expression.Zipped(left, right) =>
                stack.append(EvaluationFrame.CompleteZipped(zipped, left, right))
                stack.append(EvaluationFrame.Visit(right))
                stack.append(EvaluationFrame.Visit(left))
          case EvaluationFrame.Visit(_)                    => ()
          case frame: EvaluationFrame.CompleteMapped[?, ?] =>
            completeMapped(frame) match
              case Left(error) => return Left(error)
              case Right(_)    => ()
          case frame: EvaluationFrame.CompleteZipped[?, ?] => completeZipped(frame)

      Right(())

    private def evaluateSource[A](signal: Signal[A]): Either[RenderError, Unit] =
      val depth = signal.expression match
        case Signal.Expression.Source(source: SignalSource[?]) => source.scope.depth
        case _                                                 => 0
      Option(sources.get(signal))
        .flatMap(_.get(signal))
        .toRight(RenderError.MissingSignalSource())
        .map(value =>
          putEvaluated(signal, changedSample(signal, value, Vector.empty, depth), depth)
        )

    private def completeMapped[A, B](
      frame: EvaluationFrame.CompleteMapped[A, B]
    ): Either[RenderError, Unit] =
      val parentSample = evaluatedSample(frame.parent).get
      val depth        = evaluated.get(frame.parent).depth
      val dependencies = Vector(parentSample.revision)
      val sampled      = previousSample(frame.signal, depth) match
        case Some(cached) if cached.dependencyRevisions == dependencies => Right(cached)
        case _                                                          =>
          try Right(changedSample(frame.signal, frame.f(parentSample.value), dependencies, depth))
          catch case NonFatal(error) => Left(RenderError.EvaluationFailed(error))
      sampled.map(sample => putEvaluated(frame.signal, sample, depth))

    private def completeZipped[A, B](frame: EvaluationFrame.CompleteZipped[A, B]): Unit =
      val leftSample   = evaluatedSample(frame.left).get
      val rightSample  = evaluatedSample(frame.right).get
      val leftDepth    = evaluated.get(frame.left).depth
      val rightDepth   = evaluated.get(frame.right).depth
      val depth        = math.max(leftDepth, rightDepth)
      val dependencies = Vector(leftSample.revision, rightSample.revision)
      val sampled      = previousSample(frame.signal, depth) match
        case Some(cached) if cached.dependencyRevisions == dependencies => cached
        case _                                                          =>
          changedSample(
            frame.signal,
            (leftSample.value, rightSample.value),
            dependencies,
            depth
          )
      recordOutside(frame.left, leftDepth, depth)
      recordOutside(frame.right, rightDepth, depth)
      putEvaluated(frame.signal, sampled, depth)

    // A signal of depth `depth` read by something of depth `readerDepth` comes from outside the
    // rows of every level in between, unless it is a constant.
    private def recordOutside(signal: Signal[?], depth: Int, readerDepth: Int): Unit =
      if depth >= 0 then
        val last  = math.min(readerDepth, levels.length - 1)
        var level = depth + 1
        while level <= last do
          levels(level).outside.putIfAbsent(SignalKey(signal), signal): Unit
          level += 1

    private def evaluatedSample[A](signal: Signal[A]): Option[SignalSample[A]] =
      Option(evaluated.get(signal)).flatMap(_.value.get(signal))

    private def putEvaluated[A](signal: Signal[A], sample: SignalSample[A], depth: Int): Unit =
      val packed = PackedSignalValue[SignalSample, A](signal, sample)
      evaluated.put(signal, Evaluated(packed, depth))
      levels(levelOf(depth)).placed += packed

    private def changedSample[A](
      signal: Signal[A],
      value: A,
      dependencies: Vector[RenderRevision],
      depth: Int
    ): SignalSample[A] =
      previousSample(signal, depth) match
        case Some(cached) if cached.value == value =>
          cached.copy(dependencyRevisions = dependencies)
        case _ => SignalSample(value, revision, dependencies)

    private def previousSample[A](signal: Signal[A], depth: Int): Option[SignalSample[A]] =
      levels(levelOf(depth)).previous.get(signal)

    // Constants have depth -1 and belong to the program.
    private def levelOf(depth: Int): Int = math.min(math.max(depth, 0), levels.length - 1)
  end Transaction

  final private class Evaluated(
    val value: PackedSignalValue[SignalSample],
    val depth: Int)

  final private class Level(val previous: SignalCache):
    val placed    = ArrayBuffer.empty[PackedSignalValue[SignalSample]]
    val outside   = java.util.LinkedHashMap[SignalKey, Signal[?]]()
    var untracked = false

  private object Transaction:
    def apply(
      previous: SignalEvaluation,
      revision: RenderRevision,
      source: PackedSignalValue[[Value] =>> Value]
    ): Transaction = new Transaction(previous, revision, source)

  sealed private trait EvaluationFrame

  private object EvaluationFrame:
    final case class Visit[A](signal: Signal[A]) extends EvaluationFrame
    final case class CompleteMapped[A, B](signal: Signal[B], parent: Signal[A], f: A => B)
        extends EvaluationFrame
    final case class CompleteZipped[A, B](
      signal: Signal[(A, B)],
      left: Signal[A],
      right: Signal[B])
        extends EvaluationFrame

  final private class SignalKey private (val signal: Signal[?]):
    override def hashCode(): Int = System.identityHashCode(signal)

    override def equals(other: Any): Boolean = other match
      case key: SignalKey => signal eq key.signal
      case _              => false

  private object SignalKey:
    def apply(signal: Signal[?]): SignalKey = new SignalKey(signal)

  /** Concentrates the signal cache's unavoidable erased type recovery in one identity check. */
  sealed private[render] trait PackedSignalValue[F[_]]:
    type Value
    def signal: Signal[Value]
    def value: F[Value]

    final def get[A](requested: Signal[A]): Option[F[A]] =
      Option.when(signal eq requested)(value.asInstanceOf[F[A]])

  private[render] object PackedSignalValue:
    def apply[F[_], A](signalValue: Signal[A], packedValue: F[A]): PackedSignalValue[F] =
      new PackedSignalValue[F]:
        type Value = A
        val signal = signalValue
        val value  = packedValue

  final private[render] class SignalCache private (
    private val entries: Map[SignalKey, PackedSignalValue[SignalSample]]):
    def get[A](signal: Signal[A]): Option[SignalSample[A]] =
      entries.get(SignalKey(signal)).flatMap(_.get(signal))

    def appended(samples: Iterable[PackedSignalValue[SignalSample]]): SignalCache =
      SignalCache(samples.foldLeft(entries) { (all, sample) =>
        all + (SignalKey(sample.signal) -> sample)
      })

  private[render] object SignalCache:
    val empty: SignalCache = new SignalCache(Map.empty)

    private def apply(
      entries: Map[SignalKey, PackedSignalValue[SignalSample]]
    ): SignalCache =
      new SignalCache(entries)
end SignalEvaluation
