package scalive

import scala.compiletime.{constValue, constValueTuple, erasedValue, error}
import scala.deriving.Mirror

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
    /** Renders `content` while the condition is `true` and nothing while it is `false`.
      *
      * `content` is anything an element accepts: text, elements, components, or attributes, as in
      * `active.when(cls := "active")`. It is built once.
      */
    def when[Msg](content: Mod.Input[Msg]*): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> Mod.flatten(content)))

    /** Renders the branch that matches the current value, switching as the value changes.
      *
      * This form renders `whenTrue` or `whenFalse`. Each branch is anything an element accepts,
      * such as text, an element, a `Vector` of siblings, or an attribute, and is built once.
      */
    def choose[Msg](whenTrue: Mod.Input[Msg], whenFalse: Mod.Input[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(
        condition,
        Vector(true -> Mod.flatten(Vector(whenTrue)), false -> Mod.flatten(Vector(whenFalse)))
      )

  extension [A](value: Signal[Option[A]])
    /** Renders `project` while the value is defined and nothing while it is empty.
      *
      * `project` receives a signal of the present value, so what it renders keeps following it. It
      * returns anything an element accepts and is called once.
      */
    def option[Msg](project: Signal[A] => Mod.Input[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(
        value.map(_.isDefined),
        Vector(true -> Mod.flatten(Vector(project(value.map(_.get)))))
      )

  extension [A](value: Signal[A])
    /** Renders the branch that matches the current value, switching as the value changes.
      *
      * This form renders the branch whose key equals the value, or nothing when none matches, as in
      * `tab.choose("details" -> details, "history" -> history)`. For an enum or sealed type, use
      * [[matchCases]] so that the compiler checks every case.
      */
    def choose[Msg](branches: (A, Mod.Input[Msg])*): Mod[Msg] =
      Mod.Content.SignalChoice(
        value,
        branches.toVector.map((key, branch) => key -> Mod.flatten(Vector(branch)))
      )

    /** Renders one branch per case of an enum or sealed type, switching as the case changes.
      *
      * `build` receives a builder with one member per case and returns a tuple with one handler per
      * case, in any order:
      *
      * {{{
      * enum Status:
      *   case Idle
      *   case Running(progress: Int)
      *   case Failed(reason: String)
      *
      * status.matchCases { is =>
      *   (
      *     is.Idle(_ => "Idle"),
      *     is.Running(running => span(running.map(_.progress.toString))),
      *     is.Failed(failed => p(failed.map(_.reason)))
      *   )
      * }
      * }}}
      *
      * Each handler receives a signal of its own case's value and returns anything an element
      * accepts. It is called once, and its signals are read only while that case is selected. A
      * missing case, or a case handled twice, does not compile.
      */
    inline def matchCases[Handlers <: Tuple](
      using mirror: Mirror.SumOf[A]
    )(
      build: CaseBuilder[mirror.MirroredElemLabels, mirror.MirroredElemTypes] => Handlers
    ): Mod[CaseMessages[Handlers]] =
      MatchCases.requireEachCaseOnce[mirror.MirroredElemLabels, mirror.MirroredElemTypes, Handlers]
      MatchCases.render[A, mirror.MirroredElemLabels, mirror.MirroredElemTypes, CaseMessages[
        Handlers
      ]](
        value,
        current => mirror.ordinal(current.asInstanceOf[mirror.MirroredMonoType]),
        constValueTuple[mirror.MirroredElemLabels].toList.map(_.toString).toVector,
        build
      )
  end extension

  /** The case builder passed to [[matchCases]]. It has one member per case, named after the case,
    * which wraps the function that renders that case.
    */
  final class CaseBuilder[Labels <: Tuple, Types <: Tuple] private[scalive] () extends Selectable:
    /** One [[Case]] per case, named after it. */
    type Fields = NamedTuple.NamedTuple[Labels, Tuple.Map[Types, Case]]

    /** Returns the [[Case]] named `name`. The compiler calls it for `is.Name`. */
    def selectDynamic(name: String): Any = new Case[Any](name)

  /** One case of a [[matchCases]] builder. Apply it to the function that renders the case. */
  final class Case[C] private[scalive] (label: String):
    /** Renders this case with `project`, which receives a signal of the case's value. */
    def apply[Msg](project: Signal[C] => Mod.Input[Msg]): CaseHandler[C, Msg] =
      new CaseHandler(label, project)

  /** The rendering of one case in [[matchCases]], made by applying a [[Case]]. */
  final class CaseHandler[C, +Msg] private[scalive] (
    private[scalive] val label: String,
    private[scalive] val project: Signal[C] => Mod.Input[Msg])

  /** The message type of a [[matchCases]] result: the union of its handlers' message types. */
  type CaseMessages[Handlers <: Tuple] = Handlers match
    case EmptyTuple                  => Nothing
    case CaseHandler[?, msg] *: rest => msg | CaseMessages[rest]

  private[scalive] object MatchCases:
    inline def requireEachCaseOnce[Labels <: Tuple, Types <: Tuple, Handlers <: Tuple]: Unit =
      inline erasedValue[(Labels, Types)] match
        case _: (EmptyTuple, EmptyTuple)            => ()
        case _: ((label *: labels), (tpe *: types)) =>
          inline if !handles[tpe, Handlers] then
            error("matchCases is missing case " + constValue[label])
          else inline if handlesTwice[tpe, Handlers] then
            error("matchCases handles case " + constValue[label] + " more than once")
          else requireEachCaseOnce[labels, types, Handlers]

    private inline def handles[C, Handlers <: Tuple]: Boolean =
      inline erasedValue[Handlers] match
        case _: EmptyTuple               => false
        case _: (CaseHandler[C, ?] *: ?) => true
        case _: (? *: rest)              => handles[C, rest]

    private inline def handlesTwice[C, Handlers <: Tuple]: Boolean =
      inline erasedValue[Handlers] match
        case _: EmptyTuple                  => false
        case _: (CaseHandler[C, ?] *: rest) => handles[C, rest]
        case _: (? *: rest)                 => handlesTwice[C, rest]

    def render[A, Labels <: Tuple, Types <: Tuple, Msg](
      value: Signal[A],
      ordinal: A => Int,
      labels: Vector[String],
      build: CaseBuilder[Labels, Types] => Tuple
    ): Mod[Msg] =
      val handlers = build(new CaseBuilder[Labels, Types]()).productIterator
        .map(_.asInstanceOf[CaseHandler[?, Msg]]).toVector
      val branches = handlers.map { handler =>
        val project = handler.project.asInstanceOf[Signal[A] => Mod.Input[Msg]]
        labels.indexOf(handler.label) -> Mod.flatten(Vector(project(value)))
      }
      Mod.Content.SignalChoice(value.map(ordinal), branches)
  end MatchCases
end Signal
