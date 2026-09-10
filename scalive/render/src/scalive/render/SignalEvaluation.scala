package scalive.render

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ArrayDeque
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scalive.Signal

final private[render] class SignalScope private (
  val id: Long,
  val parent: Option[SignalScope]):
  private val closed = AtomicBoolean(false)

  def child(): Either[RenderError, SignalScope] =
    if isClosed then Left(RenderError.SignalScopeViolation(s"signal scope $id is closed"))
    else Right(SignalScope.create(Some(this)))

  def close(): Unit = closed.set(true)

  def isClosed: Boolean = closed.get() || parent.exists(_.isClosed)

  def validate(signal: Signal[?]): Either[RenderError, Unit] =
    SignalEvaluation.scopeOf(signal).flatMap { owner =>
      if isClosed then Left(RenderError.SignalScopeViolation(s"signal scope $id is closed"))
      else if owner.isClosed then
        Left(RenderError.SignalScopeViolation(s"signal scope ${owner.id} is closed"))
      else if owner.isAncestorOf(this) then Right(())
      else
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

  private[render] def scopeOf(signal: Signal[?]): Either[RenderError, SignalScope] =
    signal.expression match
      case Signal.Expression.Source(source: SignalSource[?]) => return Right(source.scope)
      case _                                                 => ()

    val scopes = IdentityHashMap[Signal[?], SignalScope]()
    val stack  = ArrayDeque[ScopeFrame](ScopeFrame.Visit(signal))

    while stack.nonEmpty do
      stack.removeLast() match
        case ScopeFrame.Visit(current) if !scopes.containsKey(current) =>
          current.expression match
            case Signal.Expression.Source(identity) =>
              identity match
                case source: SignalSource[?] => scopes.put(current, source.scope): Unit
                case _ => return Left(RenderError.SignalScopeViolation("unknown signal source"))
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
          val leftScope  = scopes.get(left)
          val rightScope = scopes.get(right)
          SignalScope.narrowest(leftScope, rightScope) match
            case Some(scope) => scopes.put(current, scope): Unit
            case None        =>
              return Left(
                RenderError.SignalScopeViolation(
                  s"signals from sibling scopes ${leftScope.id} and ${rightScope.id} cannot be combined"
                )
              )

    Right(scopes.get(signal))
  end scopeOf

  sealed private trait ScopeFrame

  private object ScopeFrame:
    final case class Visit(signal: Signal[?])                             extends ScopeFrame
    final case class CompleteMapped(signal: Signal[?], parent: Signal[?]) extends ScopeFrame
    final case class CompleteZipped(signal: Signal[?], left: Signal[?], right: Signal[?])
        extends ScopeFrame

  final private[render] class Transaction private (
    previous: SignalEvaluation,
    revision: RenderRevision,
    source: PackedSignalValue[[Value] =>> Value]):
    private val evaluated = IdentityHashMap[Signal[?], PackedSignalValue[SignalSample]]()
    private val sources   = IdentityHashMap[Signal[?], PackedSignalValue[[Value] =>> Value]]()
    sources.put(source.signal, source)

    /** Supplies one child-scope source for this candidate only. */
    def bindSource[A](signal: Signal[A], value: A): Either[RenderError, Unit] =
      signal.expression match
        case Signal.Expression.Source(_) =>
          scopeOf(signal).flatMap { owner =>
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

    def sample[A](signal: Signal[A]): Either[RenderError, SignalSample[A]] =
      evaluatedSample(signal) match
        case Some(sample) => Right(sample)
        case None         =>
          evaluate(signal).flatMap(_ =>
            evaluatedSample(signal).toRight(RenderError.MissingSignalSource())
          )

    def result: SignalEvaluation =
      SignalEvaluation(revision, SignalCache.empty.appended(evaluated.values().asScala))

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
      Option(sources.get(signal))
        .flatMap(_.get(signal))
        .toRight(RenderError.MissingSignalSource())
        .map(value => putEvaluated(signal, changedSample(signal, value, Vector.empty)))

    private def completeMapped[A, B](
      frame: EvaluationFrame.CompleteMapped[A, B]
    ): Either[RenderError, Unit] =
      val parentSample = evaluatedSample(frame.parent).get
      val dependencies = Vector(parentSample.revision)
      val sampled      = previousSample(frame.signal) match
        case Some(cached) if cached.dependencyRevisions == dependencies => Right(cached)
        case _                                                          =>
          try Right(changedSample(frame.signal, frame.f(parentSample.value), dependencies))
          catch case NonFatal(error) => Left(RenderError.EvaluationFailed(error))
      sampled.map(sample => putEvaluated(frame.signal, sample))

    private def completeZipped[A, B](frame: EvaluationFrame.CompleteZipped[A, B]): Unit =
      val leftSample   = evaluatedSample(frame.left).get
      val rightSample  = evaluatedSample(frame.right).get
      val dependencies = Vector(leftSample.revision, rightSample.revision)
      val sampled      = previousSample(frame.signal) match
        case Some(cached) if cached.dependencyRevisions == dependencies => cached
        case _                                                          =>
          changedSample(
            frame.signal,
            (leftSample.value, rightSample.value),
            dependencies
          )
      putEvaluated(frame.signal, sampled)

    private def evaluatedSample[A](signal: Signal[A]): Option[SignalSample[A]] =
      Option(evaluated.get(signal)).flatMap(_.get(signal))

    private def putEvaluated[A](signal: Signal[A], sample: SignalSample[A]): Unit =
      evaluated.put(signal, PackedSignalValue[SignalSample, A](signal, sample)): Unit

    private def changedSample[A](
      signal: Signal[A],
      value: A,
      dependencies: Vector[RenderRevision]
    ): SignalSample[A] =
      previousSample(signal) match
        case Some(cached) if cached.value == value =>
          cached.copy(dependencyRevisions = dependencies)
        case _ => SignalSample(value, revision, dependencies)

    private def previousSample[A](signal: Signal[A]): Option[SignalSample[A]] =
      previous.samples.get(signal)
  end Transaction

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
