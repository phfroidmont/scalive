package scalive

/** Controls how mount-aspect contexts accumulate.
  *
  * This is an advanced extension point used by aspect and route composition. The built-in behavior
  * discards the initial `Any` identity context and accumulates the rest as a flat tuple:
  * `CurrentUser`, then `(CurrentUser, WorkspaceAccess)`, then `(CurrentUser, WorkspaceAccess,
  * Locale)`. Custom instances may choose another `Result`, but `left` must recover the original
  * `In` from an appended result so layouts installed before a later aspect continue to receive
  * their original context.
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
