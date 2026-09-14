package scalive

/** A read-only value sampled by the render engine. */
sealed trait Signal[+A]:
  private[scalive] def expression: Signal.Expression[A]

  /** Derives a signal with a pure transformation. */
  final def map[B](f: A => B): Signal[B] = Signal.Expression.Mapped(this, f)

object Signal:
  /** Type-directed shallow tuple conversion used by [[combineWith]].
    *
    * Shape follows the static value type: `Any` and unconstrained type parameters are atomic, even
    * when their runtime value happens to be a tuple.
    */
  sealed trait TupleShape[A]:
    type Out <: Tuple
    private[Signal] def apply(value: A): Out

  object TupleShape:
    type Aux[A, O <: Tuple] = TupleShape[A] { type Out = O }

    given scalar[A](using scala.util.NotGiven[A <:< Tuple]): Aux[A, Tuple1[A]] =
      new TupleShape[A]:
        type Out = Tuple1[A]
        private[Signal] def apply(value: A): Tuple1[A] = Tuple1(value)

    given tuple[A <: Tuple]: Aux[A, A] =
      new TupleShape[A]:
        type Out = A
        private[Signal] def apply(value: A): A = value

  /** Combines a non-empty tuple of signals, preserving one result element per signal.
    *
    * The render engine validates scope compatibility and samples inputs from left to right.
    */
  def combine[Signals <: NonEmptyTuple](
    signals: Signals
  )(using
    signalsOnly: Tuple.Union[Signals] <:< Signal[?]
  ): Signal[Tuple.InverseMap[Signals, Signal]] =
    // The union proof makes every erased input member a Signal; InverseMap fixes the output
    // element types while this loop appends exactly one sampled value per input, in order.
    val first                   = signals.productElement(0).asInstanceOf[Signal[Any]]
    var combined: Signal[Tuple] = first.map(Tuple1(_))
    var index                   = 1
    while index < signals.productArity do
      val next = signals.productElement(index).asInstanceOf[Signal[Any]]
      combined = Expression.Zipped(combined, next).map { case (values, value) => values :* value }
      index += 1
    combined.asInstanceOf[Signal[Tuple.InverseMap[Signals, Signal]]]

  /** Combines signals and derives a value when the render engine samples the result. Scope
    * validation and left-to-right input sampling are the same as [[combine]].
    *
    * Pass `.tupled` for a pre-existing FunctionN. Tuples containing `Nothing` also require a typed
    * function's `.tupled` form because Scala cannot untuple an untyped lambda over them.
    */
  def combineWithFn[Signals <: NonEmptyTuple, Result](
    signals: Signals
  )(using signalsOnly: Tuple.Union[Signals] <:< Signal[?]
  )(
    f: Tuple.InverseMap[Signals, Signal] => Result
  ): Signal[Result] = combine(signals).map(f)

  /** Opaque source identity installed by the render engine. */
  private[scalive] enum Expression[+A] extends Signal[A]:
    case Source[A](identity: Object)                     extends Expression[A]
    case Mapped[A, B](parent: Signal[A], f: A => B)      extends Expression[B]
    case Zipped[A, B](left: Signal[A], right: Signal[B]) extends Expression[(A, B)]

    final private[scalive] def expression: Expression[A] = this

  private[scalive] def source[A](identity: Object): Signal[A] = Expression.Source(identity)

  extension [A](left: Signal[A])
    /** Combines two signals and derives a value when sampled, without flattening either input. The
      * render engine validates scope compatibility and samples the left input first.
      */
    def combineWithFn[B, Result](right: Signal[B])(f: (A, B) => Result): Signal[Result] =
      Expression.Zipped(left, right).map { case (a, b) => f(a, b) }

    /** Combines two signals by shallowly concatenating statically tuple-valued operands.
      *
      * The render engine validates scope compatibility and samples the left input first.
      */
    def combineWith[B](
      right: Signal[B]
    )(using
      leftShape: TupleShape[A],
      rightShape: TupleShape[B]
    ): Signal[Tuple.Concat[leftShape.Out, rightShape.Out]] =
      Expression.Zipped(left, right).map { case (a, b) =>
        leftShape(a) ++ rightShape(b)
      }

  extension (condition: Signal[Boolean])
    def when[Msg](content: => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> content))

    def choose[Msg](whenTrue: => HtmlElement[Msg], whenFalse: => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> whenTrue, false -> whenFalse))

    def chooseMod[Msg](whenTrue: => Mod[Msg], whenFalse: => Mod[Msg]): Mod[Msg] =
      Mod.Content.SignalModChoice(condition, Vector(true -> whenTrue, false -> whenFalse))

  extension [A](value: Signal[Option[A]])
    def option[Msg](project: Signal[A] => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalOption(value, project)

  extension [A](value: Signal[A])
    def choose[Msg](branches: (A, HtmlElement[Msg])*): Mod[Msg] =
      Mod.Content.SignalChoice(value, branches.toVector)

    def chooseMod[Msg](branches: (A, Mod[Msg])*): Mod[Msg] =
      Mod.Content.SignalModChoice(value, branches.toVector)
end Signal
