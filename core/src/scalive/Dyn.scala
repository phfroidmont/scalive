package scalive

import scala.collection.mutable

/** Represents a mutable dynamic value that keeps track of its own state and whether it was changed
  * or not. It doesn't observe its parent, therefore sync() must be called manually for updates to
  * propagate. Likewise, setUnchanged must be called to reset the change tracking once the latest
  * diff has been sent to the client.
  *
  * Observables are not used on purpose to avoid the complexity of managing their cleanup. The
  * tradeoff of micro managing the updates is acceptable as it is only done internally.
  */
sealed trait Dyn[T]:
  private[scalive] def currentValue: T
  private[scalive] def changed: Boolean

  def apply[T2](f: T => T2): Dyn[T2]

  def when(zoom: T => Boolean)(el: HtmlElement): Mod =
    this match
      case v: Var[T] => Mod.Content.DynOptionElement(v.apply(i => Option.when(zoom(i))(el)))
      case v: DerivedVar[?, T] =>
        Mod.Content.DynOptionElement(v.apply(i => Option.when(zoom(i))(el)))

  inline def whenNot(f: T => Boolean)(el: HtmlElement): Mod =
    when(f.andThen(!_))(el)

  private[scalive] def render(trackUpdates: Boolean): Option[T]

  /** Dynamic values do not observe their parent state, sync() must be called manually to update the
    * currentValue.
    */
  private[scalive] def sync(): Unit

  private[scalive] def setUnchanged(): Unit

  private[scalive] def callOnEveryChild(f: T => Unit): Unit

extension [T](parent: Dyn[List[T]])
  def splitByIndex(project: (Int, Dyn[T]) => HtmlElement): Mod =
    Mod.Content.DynSplit(
      new SplitVar(
        parent.apply(_.zipWithIndex),
        key = _._2,
        project = (index, v) => project(index, v(_._1))
      )
    )

class Var[T] private (initial: T) extends Dyn[T]:
  private[scalive] var currentValue: T  = initial
  private[scalive] var changed: Boolean = true
  def set(value: T): Unit               =
    if value != currentValue then
      changed = true
      currentValue = value
  def update(f: T => T): Unit                                   = set(f(currentValue))
  def apply[T2](f: T => T2): DerivedVar[T, T2]                  = new DerivedVar(this, f)
  private[scalive] def render(trackUpdates: Boolean): Option[T] =
    if !trackUpdates || changed then Some(currentValue)
    else None
  private[scalive] def setUnchanged(): Unit                 = changed = false
  private[scalive] inline def sync(): Unit                  = ()
  private[scalive] def callOnEveryChild(f: T => Unit): Unit = f(currentValue)
object Var:
  def apply[T](initial: T): Var[T] = new Var(initial)

class DerivedVar[I, O] private[scalive] (parent: Var[I], f: I => O) extends Dyn[O]:
  private[scalive] var currentValue: O  = f(parent.currentValue)
  private[scalive] var changed: Boolean = true

  def apply[O2](zoom: O => O2): DerivedVar[I, O2] =
    new DerivedVar(parent, f.andThen(zoom))

  private[scalive] def sync(): Unit =
    if parent.changed then
      val value = f(parent.currentValue)
      if value != currentValue then
        changed = true
        currentValue = value

  private[scalive] def render(trackUpdates: Boolean): Option[O] =
    if !trackUpdates || changed then Some(currentValue)
    else None

  private[scalive] def setUnchanged(): Unit =
    changed = false
    parent.setUnchanged()

  private[scalive] def callOnEveryChild(f: O => Unit): Unit = f(currentValue)

class SplitVar[I, O, Key](
  parent: Dyn[List[I]],
  key: I => Key,
  project: (Key, Dyn[I]) => O):

  // Deleted elements have value none
  private val memoized: mutable.Map[Key, Option[(Var[I], O)]] =
    mutable.Map.empty

  private[scalive] def sync(): Unit =
    parent.sync()
    if parent.changed then
      // We keep track of the key to set deleted ones to None
      val nextKeys = mutable.HashSet.empty[Key]
      parent.currentValue.foreach(input =>
        val entryKey = key(input)
        nextKeys += entryKey
        memoized.updateWith(entryKey) {
          // Update matching key
          case varAndOutput @ Some(Some((entryVar, _))) =>
            entryVar.set(input)
            varAndOutput
          // Create new item
          case Some(None) | None =>
            val newVar = Var(input)
            Some(Some(newVar, project(entryKey, newVar)))
        }
      )
      memoized.keys.foreach(k => if !nextKeys.contains(k) then memoized.update(k, None))

  private[scalive] def render(trackUpdates: Boolean): List[(Key, Option[O])] =
    memoized.collect {
      case (k, Some(entryVar, output)) if !trackUpdates || entryVar.changed => (k, Some(output))
      case (k, None)                                                        => (k, None)
    }.toList

  private[scalive] def setUnchanged(): Unit =
    parent.setUnchanged()
    // Remove previously deleted
    memoized.filterInPlace((_, v) => v.nonEmpty)

  // Usefull to call setUnchanged when the output is an HtmlElement as only the caller can know the type
  private[scalive] def callOnEveryChild(f: O => Unit): Unit =
    memoized.values.foreach(_.foreach((_, output) => f(output)))

end SplitVar
