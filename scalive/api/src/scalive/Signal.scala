package scalive

/** A value that changes over time, such as the model inside [[LiveView.view]].
  *
  * Scalive builds a view once per lifecycle and re-reads its signals whenever the model changes,
  * updating only the parts of the page whose values changed. Derive values with [[map]], combine
  * signals with [[Signal.combineWith]], and render conditional content with `when`, `option`, and
  * `choose` instead of reading the value directly. See
  * [rendering](https://scalive.dev/learn/rendering-and-dom-updates).
  */
sealed trait Signal[+A]:
  private[scalive] def expression: Signal.Expression[A]

  /** Derives a signal with a pure transformation, as in `model.map(_.count.toString)`. */
  final def map[B](f: A => B): Signal[B] = Signal.Expression.Mapped(this, f)

/** Combines signals and renders content that depends on them. */
object Signal:
  /** Type-directed shallow tuple conversion used by [[combineWith]].
    *
    * Shape follows the static value type: `Any` and unconstrained type parameters are atomic, even
    * when their runtime value happens to be a tuple.
    */
  sealed trait TupleShape[A]:
    /** The elements that a value of type `A` contributes to [[combineWith]]: `A` itself for a
      * tuple, otherwise `Tuple1[A]`.
      */
    type Out <: Tuple
    private[Signal] def apply(value: A): Out

  /** The two shapes: a tuple type contributes its elements, and any other type one element. */
  object TupleShape:
    /** A [[TupleShape]] for `A` whose [[TupleShape.Out]] is `O`. */
    type Aux[A, O <: Tuple] = TupleShape[A] { type Out = O }

    /** The shape of a type that is not statically a tuple: one element, `Tuple1[A]`. */
    given scalar[A](using scala.util.NotGiven[A <:< Tuple]): Aux[A, Tuple1[A]] =
      new TupleShape[A]:
        type Out = Tuple1[A]
        private[Signal] def apply(value: A): Tuple1[A] = Tuple1(value)

    /** The shape of a tuple type: its own elements, which join the combined tuple. */
    given tuple[A <: Tuple]: Aux[A, A] =
      new TupleShape[A]:
        type Out = A
        private[Signal] def apply(value: A): A = value

  /** Combines a non-empty tuple of signals, preserving one result element per signal.
    *
    * Tuple-valued inputs stay nested, unlike [[combineWith]]. For `position` and `size` of type
    * `Signal[(Int, Int)]`, `Signal.combine((position, size))` is a `Signal[((Int, Int), (Int,
    * Int))]`. Use `Signal.combine(Tuple1(signal))` for a single input; an empty tuple does not
    * compile.
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
    * {{{
    * val summary = Signal.combineWithFn((firstName, lastName, itemCount)) { (first, last, count) =>
    *   s"$first $last - $count items"
    * }
    * }}}
    *
    * The function receives each input value unflattened. Pass `.tupled` for a pre-existing
    * FunctionN, as in `Signal.combineWithFn((firstName, lastName))(formatName.tupled)`. Tuples
    * containing `Nothing` also require a typed function's `.tupled` form because Scala cannot
    * untuple an untyped lambda over them.
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
      *
      * {{{
      * val fullName = firstName.combineWithFn(lastName)((first, last) => s"$first $last")
      * }}}
      */
    def combineWithFn[B, Result](right: Signal[B])(f: (A, B) => Result): Signal[Result] =
      Expression.Zipped(left, right).map { case (a, b) => f(a, b) }

    /** Combines two signals by shallowly concatenating statically tuple-valued operands.
      *
      * `firstName.combineWith(lastName)` is a `Signal[(String, String)]`, and
      * `a.combineWith(b).combineWith(c)` is a flat triple when the three values are not tuples. For
      * `position` and `size` of type `Signal[(Int, Int)]`, `position.combineWith(size)` is a
      * `Signal[(Int, Int, Int, Int)]`; use [[Signal.combine]] to keep the pairs nested.
      *
      * Flattening follows the static value type, one level deep. Nested tuple elements and case
      * classes stay intact, and a value typed as `Any` or an unconstrained type parameter is one
      * element even when it holds a tuple at runtime.
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
  end extension

  extension (condition: Signal[Boolean])
    /** Renders `content` while the condition is `true` and nothing while it is `false`. */
    def when[Msg](content: => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> content))

    /** Renders the branch that matches the current value, switching as the value changes.
      *
      * This form renders `whenTrue` or `whenFalse`. Both branches are built once.
      */
    def choose[Msg](whenTrue: => HtmlElement[Msg], whenFalse: => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> whenTrue, false -> whenFalse))

    /** Applies the modifiers that match the current value, such as attributes.
      *
      * This form applies `whenTrue` or `whenFalse`.
      */
    def chooseMod[Msg](whenTrue: => Mod[Msg], whenFalse: => Mod[Msg]): Mod[Msg] =
      Mod.Content.SignalModChoice(condition, Vector(true -> whenTrue, false -> whenFalse))

  extension [A](value: Signal[Option[A]])
    /** Renders `project` while the value is defined and nothing while it is empty.
      *
      * `project` receives a signal of the present value, so the rendered element keeps following
      * it.
      */
    def option[Msg](project: Signal[A] => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalOption(value, project)

  extension [A](value: Signal[A])
    /** Renders the branch that matches the current value, switching as the value changes.
      *
      * This form renders the branch whose key equals the value, or nothing when none matches, as in
      * `tab.choose(Tab.Details -> details, Tab.History -> history)`.
      */
    def choose[Msg](branches: (A, HtmlElement[Msg])*): Mod[Msg] =
      Mod.Content.SignalChoice(value, branches.toVector)

    /** Applies the modifiers that match the current value, such as attributes.
      *
      * This form applies the modifiers whose key equals the value, or none when none matches.
      */
    def chooseMod[Msg](branches: (A, Mod[Msg])*): Mod[Msg] =
      Mod.Content.SignalModChoice(value, branches.toVector)
end Signal
