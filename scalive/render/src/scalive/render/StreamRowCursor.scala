package scalive.render

/** Finds the previous position of each row of a stream, asked for each of its rows once, in order.
  *
  * A stream's rows keep their order, and each one its operations did not insert was among the
  * previous rows, as were the few they deleted or pruned. A cursor over the previous rows then
  * finds each row, looking past those few, and a map by DOM id is built only when it does not.
  *
  * @param previous
  *   the previous rows
  * @param domId
  *   the DOM id of a row
  * @param inserted
  *   the DOM ids of the rows the operations inserted, which have no previous position
  * @param deleted
  *   how many rows the operations deleted
  */
final private[scalive] class StreamRowCursor[Row](
  previous: Vector[Row],
  domId: Row => String,
  inserted: Set[String],
  deleted: Int):
  private val reach          = deleted + inserted.size + 1
  private var cursor         = 0
  private lazy val positions = previous.iterator.map(domId).zipWithIndex.toMap

  /** The previous position of the row of DOM id `id`, or -1 when it has none. */
  def indexOf(id: String): Int =
    if inserted.contains(id) then -1
    else
      val end   = math.min(previous.length, cursor + reach)
      var index = cursor
      while index < end && domId(previous(index)) != id do index += 1
      if index < end then
        cursor = index + 1
        index
      else positions.getOrElse(id, -1)
