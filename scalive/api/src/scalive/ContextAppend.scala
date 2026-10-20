package scalive

import scala.NamedTuple.NamedTuple
import scala.compiletime.{constValue, error}

/** Controls how mount-aspect contexts accumulate.
  *
  * This is an advanced extension point used by aspect and route composition. The built-in behavior
  * discards the initial `Any` identity context and accumulates the rest. Named tuples merge into
  * one named tuple: `(user = currentUser)`, then `(user = currentUser, access = workspaceAccess)`,
  * read as `ctx.access`. Other values accumulate as a flat tuple: `CurrentUser`, then
  * `(CurrentUser, WorkspaceAccess)`, then `(CurrentUser, WorkspaceAccess, Locale)`. Custom
  * instances may choose another `Result`, but `left` must recover the original `In` from an
  * appended result so layouts installed before a later aspect continue to receive their original
  * context.
  */
trait ContextAppend[In, Out]:
  /** The accumulated context type. */
  type Result

  /** Combines the preceding and newly produced contexts. */
  def append(input: In, output: Out): Result

  /** Projects the preceding context from an accumulated result. */
  def left(result: Result): In

/** Built-in and refined forms of [[ContextAppend]]. */
object ContextAppend extends TupleContextAppend:
  /** Refines [[ContextAppend.Result]] to `Result0`. */
  type Aux[In, Out, Result0] = ContextAppend[In, Out] { type Result = Result0 }

  /** Drops the initial `Any` identity context, making the first aspect's output the whole context.
    */
  given empty[Out]: ContextAppend[Any, Out] with
    type Result = Out
    def append(input: Any, output: Out): Out = output
    def left(result: Out): Any               = ()

  /** Merges two named tuple contexts into one, failing to compile when they share a name. */
  inline given named[Names <: Tuple, Values <: Tuple, OutNames <: Tuple, OutValues <: Tuple]
    : ContextAppend.Aux[
      NamedTuple[Names, Values],
      NamedTuple[OutNames, OutValues],
      NamedTuple[Tuple.Concat[Names, OutNames], Tuple.Concat[Values, OutValues]]
    ] =
    inline if !constValue[Tuple.Disjoint[Names, OutNames]] then
      error("two mount aspects produce a context with the same name")
    NamedContextAppend(constValue[Tuple.Size[Names]])

final private class NamedContextAppend[
  Names <: Tuple,
  Values <: Tuple,
  OutNames <: Tuple,
  OutValues <: Tuple
](
  inputSize: Int)
    extends ContextAppend[NamedTuple[Names, Values], NamedTuple[OutNames, OutValues]]:
  type Result = NamedTuple[Tuple.Concat[Names, OutNames], Tuple.Concat[Values, OutValues]]

  def append(input: NamedTuple[Names, Values], output: NamedTuple[OutNames, OutValues]): Result =
    (input.toTuple ++ output.toTuple).asInstanceOf[Result]

  def left(result: Result): NamedTuple[Names, Values] =
    result.toTuple.take(inputSize).asInstanceOf[NamedTuple[Names, Values]]

private trait TupleContextAppend extends LowPriorityContextAppend:
  /** Appends an output to a tuple context, keeping the context flat. */
  given tuple[In <: NonEmptyTuple, Out]: ContextAppend[In, Out] with
    type Result = Tuple.Append[In, Out]
    def append(input: In, output: Out): Tuple.Append[In, Out] = input :* output
    def left(result: Tuple.Append[In, Out]): In               =
      result.asInstanceOf[NonEmptyTuple].init.asInstanceOf[In]

private trait LowPriorityContextAppend:
  /** Pairs a single context with the next output. */
  given pair[In, Out]: ContextAppend[In, Out] with
    type Result = (In, Out)
    def append(input: In, output: Out): (In, Out) = input -> output
    def left(result: (In, Out)): In               = result._1
