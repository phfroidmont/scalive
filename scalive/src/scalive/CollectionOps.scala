package scalive

extension [T](items: IterableOnce[T])
  def splitBy[Key](key: T => Key)(project: (Key, T) => HtmlElement): Mod =
    val entries = items.iterator
      .map(item => Mod.Content.Keyed.Entry(key(item), project(key(item), item))).toVector
    Mod.Content.Keyed(entries)

  def splitByIndex(project: (Int, T) => HtmlElement): Mod =
    val entries = items.iterator.zipWithIndex.map { case (item, index) =>
      Mod.Content.Keyed.Entry(index, project(index, item))
    }.toVector
    Mod.Content.Keyed(entries)

extension [T](stream: streams.LiveStream[T])
  def stream(project: (String, T) => HtmlElement): Mod =
    val renderedEntries =
      stream.allEntries.iterator
        .map(entry =>
          entry.domId -> Mod.Content.Keyed.Entry(entry.domId, project(entry.domId, entry.value))
        ).toVector

    val renderedByDomId = renderedEntries.toMap

    val allEntries = renderedEntries.map(_._2)

    val entries = stream.entries.iterator
      .map(entry =>
        renderedByDomId.getOrElse(
          entry.domId,
          Mod.Content.Keyed.Entry(entry.domId, project(entry.domId, entry.value))
        )
      ).toVector

    val streamPatch =
      Option.when(stream.inserts.nonEmpty || stream.deleteIds.nonEmpty || stream.reset)(
        Diff.Stream(
          ref = stream.ref,
          inserts = stream.inserts.map(insert =>
            Diff.StreamInsert(
              domId = insert.domId,
              at = insert.at,
              limit = insert.limit,
              updateOnly = insert.updateOnly
            )
          ),
          deleteIds = stream.deleteIds,
          reset = stream.reset
        )
      )

    Mod.Content.Keyed(entries, stream = streamPatch, allEntries = Some(allEntries))
end extension
