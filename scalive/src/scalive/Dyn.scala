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
  def splitBy[Key](key: T => Key)(project: (Key, Dyn[T]) => HtmlElement): Mod =
    Mod.Content.DynSplit(
      new SplitVar(
        parent,
        key = key,
        project = (k, v) => project(k, v)
      )
    )
  def splitByIndex(project: (Int, Dyn[T]) => HtmlElement): Mod =
    parent(_.zipWithIndex).splitBy(_._2)((index, v) => project(index, v(_._1)))

extension [T](parent: Dyn[streams.LiveStream[T]])
  def stream(
    project: (Dyn[String], Dyn[T]) => HtmlElement
  ): Mod =
    Mod.Content.DynStream(new StreamSplitVar(parent, project))

private class Var[T] private (initial: T) extends Dyn[T]:
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
private object Var:
  def apply[T](initial: T): Var[T] = new Var(initial)

private class DerivedVar[I, O] private[scalive] (parent: Var[I], f: I => O) extends Dyn[O]:
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

private class SplitVar[I, O, Key](
  parent: Dyn[List[I]],
  key: I => Key,
  project: (Key, Dyn[I]) => O):

  private val memoized: mutable.Map[Key, (Var[I], O)] =
    mutable.Map.empty

  private var orderedKeys = List.empty[Key]

  private var previousKeysToIndex: Map[Key, Int] = Map.empty

  private var nonEmptySyncCount = 0

  private[scalive] def sync(): Unit =
    parent.sync()
    if parent.changed then
      previousKeysToIndex = orderedKeys.zipWithIndex.toMap
      // We keep track of the keys to remove deleted ones afterwards
      val nextKeys = mutable.HashSet.empty[Key]
      orderedKeys = parent.currentValue.map(key)
      parent.currentValue.foreach(input =>
        val entryKey = key(input)
        nextKeys += entryKey
        memoized.updateWith(entryKey) {
          // Update matching key
          case varAndOutput @ Some((entryVar, _)) =>
            entryVar.set(input)
            varAndOutput
          // Create new item
          case None =>
            val newVar = Var(input)
            Some(newVar, project(entryKey, newVar))
        }
      )
      memoized.keys.foreach(k =>
        if !nextKeys.contains(k) then
          val _ = memoized.remove(k)
      )
    if memoized.nonEmpty then nonEmptySyncCount += 1

  private[scalive] def render(trackUpdates: Boolean): Option[
    (
      changeList: List[(index: Int, previousIndex: Option[Int], value: O)],
      keysCount: Int,
      includeStatics: Boolean,
      stream: Option[Diff.Stream]
    )
  ] =
    if parent.changed || !trackUpdates then
      Some(
        (
          changeList = orderedKeys
            .map(k => (k, memoized(k))).zipWithIndex
            .map { case ((key, (entryVar, output)), index) =>
              (index, previousKeysToIndex.get(key).filterNot(_ == index), entryVar, output)
            }
            .collect {
              case (index, previousIndex, entryVar, output)
                  if !trackUpdates || entryVar.changed || previousIndex.isDefined =>
                (index, previousIndex, output)
            }.toList,
          keysCount = memoized.size,
          includeStatics = nonEmptySyncCount == 1,
          stream = None
        )
      )
    else None

  private[scalive] def setUnchanged(): Unit =
    parent.setUnchanged()

  // Usefull to call setUnchanged when the output is an HtmlElement as only the caller can know the type
  private[scalive] def callOnEveryChild(f: O => Unit): Unit =
    memoized.values.foreach((_, output) => f(output))

  private[scalive] def currentValues: Iterable[O] = memoized.values.map(_._2)

end SplitVar

private class StreamSplitVar[I, O](
  parent: Dyn[streams.LiveStream[I]],
  project: (Dyn[String], Dyn[I]) => O):

  final private case class Row(
    domId: String,
    domIdVar: Var[String],
    entryVar: Var[I],
    output: O)

  private var rows: Vector[Row] = Vector.empty

  private var renderRows: Vector[Row] = Vector.empty

  private var nonEmptySyncCount = 0

  private[scalive] def sync(): Unit =
    parent.sync()
    if parent.changed then
      val liveStream = parent.currentValue

      var nextRows =
        if liveStream.reset then Vector.empty
        else rows

      if liveStream.deleteIds.nonEmpty then
        val deleteIds = liveStream.deleteIds.toSet
        nextRows = nextRows.filterNot(row => deleteIds.contains(row.domId))

      val insertsByDomId = liveStream.inserts.iterator.map(insert => insert.domId -> insert).toMap

      liveStream.entries.foreach { entry =>
        nextRows.find(_.domId == entry.domId) match
          case Some(existingRow) =>
            existingRow.entryVar.set(entry.value)
          case None =>
            val insertMeta = insertsByDomId.get(entry.domId)
            val updateOnly = insertMeta.flatMap(_.updateOnly).contains(true)

            if !updateOnly then
              val newRow = createRow(entry.domId, entry.value)
              nextRows = insertAt(nextRows, newRow, insertMeta.map(_.at).getOrElse(-1))
      }

      rows = nextRows

      val rowsByDomId = rows.iterator.map(row => row.domId -> row).toMap
      renderRows = liveStream.entries.map(entry =>
        rowsByDomId.getOrElse(entry.domId, createRow(entry.domId, entry.value))
      )
    end if

    if rows.nonEmpty then nonEmptySyncCount += 1
  end sync

  private def createRow(domId: String, value: I): Row =
    val domIdVar = Var(domId)
    val entryVar = Var(value)
    Row(
      domId = domId,
      domIdVar = domIdVar,
      entryVar = entryVar,
      output = project(domIdVar, entryVar)
    )

  private def insertAt(rows: Vector[Row], row: Row, at: Int): Vector[Row] =
    if at == -1 || at >= rows.length then rows :+ row
    else if at <= 0 then row +: rows
    else rows.patch(at, Seq(row), 0)

  private[scalive] def render(trackUpdates: Boolean): Option[
    (
      changeList: List[(index: Int, previousIndex: Option[Int], value: O)],
      keysCount: Int,
      includeStatics: Boolean,
      stream: Option[Diff.Stream]
    )
  ] =
    if parent.changed || !trackUpdates then
      val liveStream = parent.currentValue
      val sourceRows =
        if trackUpdates then renderRows
        else rows
      val streamPatch =
        Option.when(
          liveStream.inserts.nonEmpty || liveStream.deleteIds.nonEmpty || liveStream.reset
        )(
          Diff.Stream(
            ref = liveStream.ref,
            inserts = liveStream.inserts.map(insert =>
              Diff.StreamInsert(
                domId = insert.domId,
                at = insert.at,
                limit = insert.limit,
                updateOnly = insert.updateOnly
              )
            ),
            deleteIds = liveStream.deleteIds,
            reset = liveStream.reset
          )
        )

      Some(
        (
          changeList = sourceRows.zipWithIndex.map { case (row, index) =>
            (index, None, row.output)
          }.toList,
          keysCount = sourceRows.length,
          includeStatics = nonEmptySyncCount == 1,
          stream = streamPatch
        )
      )
    else None

  private[scalive] def setUnchanged(): Unit =
    parent.setUnchanged()

  private[scalive] def callOnEveryChild(f: O => Unit): Unit =
    rows.foreach(row => f(row.output))
    renderRows.foreach(row => f(row.output))

  private[scalive] def currentValues: Iterable[O] =
    rows.map(_.output) ++ renderRows.map(_.output)

end StreamSplitVar
