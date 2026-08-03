package scalive

extension [T](items: IterableOnce[T])
  def splitBy[Key, Msg](key: T => Key)(project: (Key, T) => HtmlElement[Msg]): Mod[Msg] =
    val entries = items.iterator
      .map(item => Mod.Content.Keyed.Entry(key(item), project(key(item), item))).toVector
    Mod.Content.Keyed(entries)

  def splitByIndex[Msg](project: (Int, T) => HtmlElement[Msg]): Mod[Msg] =
    val entries = items.iterator.zipWithIndex.map { case (item, index) =>
      Mod.Content.Keyed.Entry(index, project(index, item))
    }.toVector
    Mod.Content.Keyed(entries)

extension [T](stream: streams.LiveStream[T])
  def stream[Msg](project: (String, T) => HtmlElement[Msg]): Mod[Msg] =
    val renderedEntries =
      stream.snapshotEntries.iterator
        .map(entry =>
          entry.domId -> Mod.Content.Keyed.Entry(entry.domId, project(entry.domId, entry.value))
        ).toVector

    val renderedByDomId = renderedEntries.toMap

    val snapshotEntries = renderedEntries.map(_._2)

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

    Mod.Content.Keyed(entries, stream = streamPatch, allEntries = Some(snapshotEntries))
  end stream

  def renderIn[Msg](
    container: HtmlTag,
    mods: Mod[Msg]*
  )(
    project: T => HtmlElement[Msg]
  ): HtmlElement[Msg] =
    val containerMods = mods.filterNot(mod => isAttr(mod, "id") || isAttr(mod, "phx-update"))
    val entries       = stream.stream { (domId, item) =>
      val element = project(item)
      HtmlElement(
        element.tag,
        element.mods.filterNot(isAttr(_, "id")).prepended(idAttr := domId)
      )
    }

    HtmlElement(
      container,
      Vector(idAttr := stream.name, phx.onUpdate := "stream") ++ containerMods :+ entries
    )
end extension

private def isAttr(mod: Mod[?], expectedName: String): Boolean =
  mod match
    case Mod.Attr.Static(name, _)                => name == expectedName
    case Mod.Attr.StaticValueAsPresence(name, _) => name == expectedName
    case Mod.Attr.Binding(name, _)               => name == expectedName
    case Mod.Attr.FormBinding(name, _)           => name == expectedName
    case Mod.Attr.FormEventBinding(name, _, _)   => name == expectedName
    case Mod.Attr.JsBinding(name, _)             => name == expectedName
    case Mod.Attr.RoutedBinding(name, _)         => name == expectedName
    case _: Mod.Content[?]                       => false
