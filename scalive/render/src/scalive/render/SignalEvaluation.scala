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
  private[render] val samples: SignalEvaluation.SignalCache,
  // Whether the render read anything no signal tracks: a nested LiveView, a stream, or a flash. A
  // render that did not is the same render again for the same input. Its components do not count:
  // signals track their declarations, and the runtime resolves them into it again on every turn.
  private[render] val untracked: Boolean)

object SignalEvaluation:
  val empty: SignalEvaluation =
    SignalEvaluation(RenderRevision.initial, SignalCache.empty, untracked = true)

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

  /** What evaluating one row read, kept so that the row can be reused without evaluating it again
    * while none of it changes.
    *
    * @param samples
    *   the samples of the signals of the row itself
    * @param outside
    *   the signals from outside the row that it read, such as a selection the row compares its item
    *   with
    * @param untracked
    *   whether the row renders something no signal tracks: a nested LiveView, a stream, or a flash
    */
  final private[render] case class RowSignals(
    samples: SignalCache,
    outside: Vector[Signal[?]],
    untracked: Boolean)

  /** What evaluating the rows of one collection read: each row's, and every signal from outside the
    * rows that any of them read.
    */
  final private[render] case class RowsEvaluation(
    rows: Map[RowId, RowSignals],
    outside: Vector[Signal[?]],
    untracked: Boolean)

  private[render] object RowsEvaluation:
    val empty: RowsEvaluation = RowsEvaluation(Map.empty, Vector.empty, untracked = false)

  final private[render] class Transaction private (
    previous: SignalEvaluation,
    revision: RenderRevision,
    source: PackedSignalValue[[Value] =>> Value]):
    private val evaluated = IdentityHashMap[Signal[?], Evaluated]()
    private val sources   = IdentityHashMap[Signal[?], PackedSignalValue[[Value] =>> Value]]()
    sources.put(source.signal, source)

    // The program, then the rows of each collection being evaluated, outermost first. The signals
    // of a row scope of depth n belong to level n, and to the row of that level being evaluated.
    private val levels = ArrayBuffer(Level(Map.empty, previous.samples))

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

    /** Whether every signal of `signals`, which a row or collection read from outside it in that
      * order, holds the value it held in the previous evaluation.
      *
      * Samples them in order and stops at the first that changed. A signal read inside a branch
      * comes after the branch's condition and may be valid only while the branch is shown, such as
      * the present value that `option` projects, so it is sampled only while the condition is
      * unchanged.
      */
    def allUnchanged(signals: Vector[Signal[?]]): Either[RenderError, Boolean] =
      var index = 0
      while index < signals.length do
        sample(signals(index)) match
          case Left(error)                           => return Left(error)
          case Right(sampled) if !unchanged(sampled) => return Right(false)
          case Right(_)                              => ()
        index += 1
      Right(true)

    /** Starts evaluating the rows of a collection, which read their previous samples from
      * `previous`.
      */
    def enterRows(previous: RowsEvaluation): Unit =
      levels += Level(previous.rows, SignalCache.empty)

    /** Ends evaluating the rows that the last `enterRows` started. A row keeps what it read in the
      * previous evaluation unless it was evaluated again or dropped.
      */
    def exitRows(): RowsEvaluation =
      val level = levels.remove(levels.length - 1)
      RowsEvaluation(level.rows, level.outside.values().asScala.toVector, level.untracked)

    /** Starts evaluating row `id` of the collection that the last `enterRows` started. */
    def enterRow(id: RowId): Unit =
      val level = levels.last
      level.current = Some(
        RowLevel(id, level.previousRows.get(id).fold(SignalCache.empty)(_.samples))
      )

    /** Ends evaluating the row that the last `enterRow` started, and returns what it read. */
    def exitRow(): RowSignals =
      val level = levels.last
      val row   = level.current.get
      val read  = RowSignals(
        SignalCache.empty.appended(row.placed),
        row.outside.values().asScala.toVector,
        row.untracked
      )
      level.updateRow(row.id, read)
      level.current = None
      read

    /** Forgets what `ids`, rows the collection being evaluated no longer renders, read. */
    def dropRows(ids: Iterable[RowId]): Unit = levels.last.dropRows(ids)

    /** What row `id` of the collection being evaluated read in the previous evaluation. */
    def previousRow(id: RowId): Option[RowSignals] = levels.last.previousRows.get(id)

    /** Records that what is being evaluated, and every row and collection around it, renders
      * something no signal tracks.
      */
    def markUntracked(): Unit = levels.foreach { level =>
      level.untracked = true
      level.current.foreach(_.untracked = true)
    }

    def result: SignalEvaluation =
      SignalEvaluation(
        revision,
        SignalCache.empty.appended(levels.head.placed),
        levels.head.untracked
      )

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
          levels(level).outside.putIfAbsent(SignalKey(signal), signal)
          levels(level).current.foreach(_.outside.putIfAbsent(SignalKey(signal), signal))
          level += 1

    private def evaluatedSample[A](signal: Signal[A]): Option[SignalSample[A]] =
      Option(evaluated.get(signal)).flatMap(_.value.get(signal))

    private def putEvaluated[A](signal: Signal[A], sample: SignalSample[A], depth: Int): Unit =
      val packed = PackedSignalValue[SignalSample, A](signal, sample)
      evaluated.put(signal, Evaluated(packed, depth))
      val level = levels(levelOf(depth))
      level.current.fold(level.placed)(_.placed) += packed

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
      val level = levels(levelOf(depth))
      level.current.fold(level.previous)(_.previous).get(signal)

    // Constants have depth -1 and belong to the program.
    private def levelOf(depth: Int): Int = math.min(math.max(depth, 0), levels.length - 1)
  end Transaction

  final private class Evaluated(
    val value: PackedSignalValue[SignalSample],
    val depth: Int)

  // The program has `previous` samples; the rows of a collection have `previousRows`.
  final private class Level(
    val previousRows: Map[RowId, RowSignals],
    val previous: SignalCache):
    val placed    = ArrayBuffer.empty[PackedSignalValue[SignalSample]]
    val outside   = java.util.LinkedHashMap[SignalKey, Signal[?]]()
    var untracked = false
    var current   = Option.empty[RowLevel]

    // What the rows read: those of the previous rows updated one by one, or, when there are none,
    // built at once, which allocates less.
    private var updated = previousRows
    private val built   = Option.when(previousRows.isEmpty)(Map.newBuilder[RowId, RowSignals])
    private var dropped = Vector.empty[RowId]

    def updateRow(id: RowId, read: RowSignals): Unit = built match
      case Some(rows) => rows += id -> read
      case None       => updated = updated.updated(id, read)

    def dropRows(ids: Iterable[RowId]): Unit = built match
      case Some(_) => dropped ++= ids
      case None    => updated = updated -- ids

    def rows: Map[RowId, RowSignals] = built.fold(updated)(_.result() -- dropped)

  final private class RowLevel(val id: RowId, val previous: SignalCache):
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
